package app.bumpbeeper.sync

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.Fix
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
import app.bumpbeeper.RoutePoint
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The live road speed limit while recording (opt-in): [LiveLimitPlanner] decides when, this sends the last few
 * hundred metres through our `speed-limits` function (same sign-in, region and format as [SpeedLimitSync]) on its
 * own thread, and puts the answer into [LiveState]. Created per trip on the engine thread; [onFix] runs there and
 * answers are posted back to [engine].
 *
 * The limit lives only in memory (planner and [LiveState]), never on disk, in the database or in recordings. Logs
 * carry status codes only, and nothing at all while [quiet] (a trip that started by itself and isn't confirmed yet).
 * Calls count against the daily quota shared with the after-trip lookup ([SpeedLimitQuota]), leaving it the last
 * [SpeedLimitQuota.KEEP_FOR_AFTER_TRIP].
 */
class LiveSpeedLimit(
    private val ctx: Context,
    private val engine: Handler,
    private val quiet: () -> Boolean,
    private val transport: Transport = HttpTransport(15_000),
    private val exec: Executor = Executors.newSingleThreadExecutor(),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val TAG = "BumpBeeper"

        /** Opted in, accepted the disclosure at least once, and the network allowed (shared-map choice answered). */
        fun allowed(ctx: Context): Boolean =
            Prefs.liveLimits(ctx) && Prefs.liveLimitsConsentVersion(ctx) >= 1 && Prefs.syncChoice(ctx) != Prefs.SYNC_UNSET
    }

    val planner = LiveLimitPlanner { wallMs -> SpeedLimitQuota.take(ctx, wallMs, reserve = SpeedLimitQuota.KEEP_FOR_AFTER_TRIP) }

    /** Engine thread, every GPS fix. */
    fun onFix(f: Fix) {
        if (!allowed(ctx)) { planner.forget(); LiveState.liveLimitsOn = false; publish(); return }
        LiveState.liveLimitsOn = true
        planner.onFix(f)
        val pts = planner.next(f.timeMs, wall())
        if (pts != null) {
            // Fix times are since boot; the server wants epoch ms.
            val offset = wall() - elapsed()
            val body = SpeedLimitSync.requestJson(pts.map { RoutePoint(it.lat, it.lon, it.timeMs, it.timeMs + offset) }).toString()
            exec.execute {
                val (code, kmh) = call(body, pts.size)
                // A 429 also ends the after-trip lookups for today: the server's quota is shared.
                if (code == 429) SpeedLimitQuota.exhaust(ctx, wall())
                engine.post { planner.onResult(elapsed(), wall(), code, kmh); publish() }
            }
        }
        publish()
    }

    /** Engine thread, now and then without fixes (tunnel): drops an answer that got too old. */
    fun tick() = publish()

    /** Trip ended: nothing kept. */
    fun close() {
        (exec as? ExecutorService)?.shutdown()
        planner.forget()
        LiveState.setSpeedLimit(null, 0L)
    }

    private fun publish() = LiveState.setSpeedLimit(planner.limit(elapsed()), planner.limitAtMs)

    /** Background thread. The HTTP status (or [LiveLimitPlanner.OFFLINE]) and the last point's limit. */
    private fun call(body: String, n: Int): Pair<Int, Int?> {
        // Switched off while this waited: nothing leaves the phone.
        if (!allowed(ctx)) return LiveLimitPlanner.OFFLINE to null
        return try {
            val auth = SupabaseAuth(ctx, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, transport)
            val url = "${BuildConfig.SUPABASE_URL}/functions/v1/speed-limits"
            // The sync jobs share the session: never refresh the token at the same time as they do.
            fun headers(refresh: Boolean): Map<String, String> {
                val token = synchronized(Sync.lock) { auth.accessToken(forceRefresh = refresh) }
                return mapOf("apikey" to BuildConfig.SUPABASE_KEY, "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json", SpeedLimitSync.REGION_HEADER to SpeedLimitSync.REGION)
            }
            var r = transport.post(url, headers(false), body)
            if (r.code == 401) r = transport.post(url, headers(true), body)
            if (r.code != 200 && !quiet()) Log.w(TAG, "live speed limit: HTTP ${r.code}")
            if (r.code != 200) r.code to null
            else 200 to SpeedLimitSync.parseLimits(r.body, n)?.lastOrNull()?.let { Math.round(it).toInt() }
        } catch (e: IOException) {
            if (!quiet()) Log.w(TAG, "live speed limit: ${e.javaClass.simpleName}")
            LiveLimitPlanner.OFFLINE to null
        } catch (_: ApiException) {
            LiveLimitPlanner.OFFLINE to null
        }
    }
}
