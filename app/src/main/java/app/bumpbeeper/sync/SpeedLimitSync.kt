package app.bumpbeeper.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.BumpDb
import app.bumpbeeper.Prefs
import app.bumpbeeper.auto.TripHold
import app.bumpbeeper.RoutePoint
import app.bumpbeeper.RouteSampler
import app.bumpbeeper.SpeedLimitScoring
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Road speed limits for the driving score, looked up after a trip through our `speed-limits` Edge Function
 * (TomTom Snap to Roads, see supabase/README.md).
 *
 * Only when the user switched it on ([Prefs.speedLimits]) and allowed the network on the first-run screen ("receive"
 * or "share"; "unset" never goes online). At trip end the fixes go to a file in app-private storage that backup and
 * device transfer never copy (noBackupFilesDir). A [SyncJob] (network required) plans the requests
 * ([RouteSampler.plan]: privacy zone trimmed, sampled), sends one per chunk, rescores the trip and deletes the file.
 * The file is also deleted after [MAX_AGE_MS], when the feature or the network is switched off, on forget me or
 * "clear all data", and unsent when the trip is gone or already looked up. Consent is checked again before each call.
 * Logs carry status codes only: never coordinates, URLs or tokens.
 */
object SpeedLimitSync {
    private const val TAG = "BumpBeeper"
    const val JOB_ID = 4104
    /** The same job, waiting for tomorrow's quota (a separate id, so scheduling it never stops a running job). */
    private const val JOB_TOMORROW = 4105
    const val EXTRA_JOB = "speed_limits"
    /** The server's per-user quota (take_speed_limit_quota): calls per UTC day. */
    const val MAX_CALLS_PER_DAY = 8
    const val MAX_AGE_MS = 48 * 60 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MAGIC = 0x534c5231
    private const val DAY_KEY = "speed_limit_day"
    private const val CALLS_KEY = "speed_limit_calls"
    /** A 5xx is tried again on the next run, [MAX_TRIES] runs in all (still within [MAX_AGE_MS]). */
    const val MAX_TRIES = 3
    /** A half-written file (`.tmp`) older than this is left over from a failed write. */
    private const val STALE_TMP_MS = 10 * 60_000L
    /**
     * Run the function in Frankfurt, next to the database, not in the edge region nearest the phone, so the route is
     * processed in the EU (Supabase "regional invocation"; a pinned region is not rerouted during an outage).
     */
    const val REGION_HEADER = "x-region"
    const val REGION = "eu-central-1"
    /** The function waits up to 20 s for TomTom. */
    private val longTransport = HttpTransport(30_000)

    /** Lookups are on: opted in, and the network allowed. */
    fun allowed(ctx: Context): Boolean = Prefs.speedLimits(ctx) && Prefs.syncChoice(ctx) != Prefs.SYNC_UNSET

    /** The settings switch. Off deletes routes still waiting (background thread). */
    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs.sp(ctx).edit().putBoolean(Prefs.SPEED_LIMITS, on).apply()
        if (!on) clearPendingAsync(ctx)
    }

    fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "speed_limits")

    fun pendingFiles(ctx: Context): List<File> =
        (dir(ctx).listFiles() ?: emptyArray()).filter { it.name.endsWith(".route") }
            .sortedBy { it.name.substringBefore('.').toLongOrNull() ?: 0L }

    fun clearPending(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Deletes stray half-written files ([STALE_TMP_MS]); `.route` files are checked by their age inside. */
    private fun sweepTmp(ctx: Context, now: Long) {
        dir(ctx).listFiles()?.forEach { if (!it.name.endsWith(".route") && now - it.lastModified() > STALE_TMP_MS) it.delete() }
    }

    fun clearPendingAsync(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        Thread({ try { clearPending(app) } catch (_: Exception) {} }, "speed-limit-clear").start()
    }

    /**
     * Trip end (engine thread): saves the route and schedules the lookup. Does nothing unless [allowed].
     * [epochOffsetMs] turns the fixes' since-boot times into epoch times ([RouteSampler.sample]).
     */
    fun afterTrip(
        ctx: Context, tripId: Long, route: TripRoute, now: Long = System.currentTimeMillis(),
        epochOffsetMs: Long = now - SystemClock.elapsedRealtime(),
    ) {
        if (!allowed(ctx) || route.size < 2) return
        val d = dir(ctx)
        d.mkdirs()
        val tmp = File(d, "$tripId.tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC); out.writeLong(tripId); out.writeLong(now); out.writeLong(epochOffsetMs)
                route.write(out)
            }
        } catch (e: IOException) {
            tmp.delete()   // never leave part of a route behind (e.g. disk full)
            Log.w(TAG, "speed limits: route not saved (${e.javaClass.simpleName})")
            return
        }
        if (!tmp.renameTo(File(d, "$tripId.route"))) tmp.delete()
        // A trip that started by itself waits for "Yes, I drove" ([release]); see TripHold.
        if (!TripHold.isHeld(ctx, tripId)) schedule(ctx, JOB_ID, 0L)
    }

    /** The held trip was a drive: its route may now be looked up. */
    fun release(ctx: Context, tripId: Long) {
        if (allowed(ctx) && File(dir(ctx), "$tripId.route").exists()) schedule(ctx, JOB_ID, 0L)
    }

    /** "No", or no answer in time: the held trip's route is deleted unsent. Throws if it couldn't be deleted. */
    fun drop(ctx: Context, tripId: Long) {
        val f = File(dir(ctx), "$tripId.route")
        if (f.exists() && !f.delete()) throw IOException("route of trip $tripId not deleted")
    }

    private fun heldFile(ctx: Context, f: File): Boolean =
        f.name.substringBefore('.').toLongOrNull()?.let { TripHold.isHeld(ctx, it) } == true

    /** App opened (background thread): drops old or unwanted routes, and makes sure waiting ones get a job. */
    fun onAppStart(ctx: Context, now: Long = System.currentTimeMillis()) {
        try {
            TripHold.expire(ctx, now)   // unanswered "Was this a drive?": held routes are deleted unsent
            if (!allowed(ctx)) { clearPending(ctx); return }
            sweepTmp(ctx, now)
            var waiting = false
            for (f in pendingFiles(ctx)) {
                if (load(f)?.let { fresh(it, now) } != true) f.delete() else if (!heldFile(ctx, f)) waiting = true
            }
            if (waiting) schedule(ctx, JOB_ID, 0L)
        } catch (e: Exception) {
            Log.w(TAG, "speed limits: ${e.javaClass.simpleName}")
        }
    }

    private class Pending(val tripId: Long, val createdAt: Long, val epochOffsetMs: Long, val route: TripRoute)

    private fun load(f: File): Pending? = try {
        DataInputStream(BufferedInputStream(FileInputStream(f))).use { inp ->
            if (inp.readInt() != MAGIC) null
            else Pending(inp.readLong(), inp.readLong(), inp.readLong(), TripRoute.read(inp))
        }
    } catch (_: IOException) {
        null
    }

    private fun fresh(p: Pending, now: Long) = now - p.createdAt <= MAX_AGE_MS && p.createdAt - now <= DAY_MS

    private enum class Step { DONE, TOMORROW, RETRY, STOP }

    /** One job run (background thread). Returns true to retry later (offline). [transport] is a fake in tests. */
    fun run(ctx: Context, transport: Transport = longTransport, now: Long = System.currentTimeMillis()): Boolean = synchronized(Sync.lock) {
        if (!allowed(ctx)) { clearPending(ctx); return false }
        sweepTmp(ctx, now)
        val files = pendingFiles(ctx)
        if (files.isEmpty()) return false
        Sync.withDb(ctx) { db ->
            val store = SyncStore(db)
            val auth = SupabaseAuth(ctx, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, transport)
            for (f in files) {
                if (heldFile(ctx, f)) continue   // waits for "Yes, I drove"
                val p = load(f)
                val tries = "speed_limit_tries_" + f.name.substringBefore('.')
                if (p == null || !fresh(p, now)) { f.delete(); store.put(tries, null); continue }
                when (lookUp(ctx, p, db, store, auth, transport, now, tries)) {
                    Step.DONE -> { f.delete(); store.put(tries, null) }
                    Step.STOP -> { clearPending(ctx); return@withDb false }   // consent withdrawn mid-run
                    Step.TOMORROW -> { schedule(ctx, JOB_TOMORROW, (now / DAY_MS + 1) * DAY_MS - now + 60_000L); return@withDb false }
                    Step.RETRY -> return@withDb true
                }
            }
            false
        }
    }

    private fun lookUp(
        ctx: Context, p: Pending, db: BumpDb, store: SyncStore, auth: SupabaseAuth, transport: Transport, now: Long, triesKey: String,
    ): Step {
        // Trip deleted ("clear all data") or already looked up (the process died before the file was deleted): no calls.
        fun gone() = db.trip(p.tripId).let { it == null || it.limitsLookedUp }
        if (gone()) return Step.DONE
        val fixes = p.route.fixes()
        val chunks = RouteSampler.plan(fixes, p.epochOffsetMs)
        val day = now / DAY_MS
        var used = if (store.getLong(DAY_KEY, -1L) == day) store.getLong(CALLS_KEY).toInt() else 0
        // A trip that fits in one day's calls waits for tomorrow rather than being half looked up.
        if (chunks.size <= MAX_CALLS_PER_DAY && used + chunks.size > MAX_CALLS_PER_DAY) return Step.TOMORROW
        val replies = ArrayList<List<Double?>?>()
        for (c in chunks) {
            if (used >= MAX_CALLS_PER_DAY) { replies.add(null); continue }
            // The user may have said no (or cleared the data) since the last call: nothing more leaves the phone.
            if (!allowed(ctx)) return Step.STOP
            if (gone()) return Step.DONE
            used++
            store.put(DAY_KEY, day); store.put(CALLS_KEY, used)
            val r = try {
                call(c, auth, transport)
            } catch (e: IOException) {
                Log.w(TAG, "speed limits: ${e.javaClass.simpleName}")
                return Step.RETRY
            } catch (_: ApiException) {
                return Step.RETRY   // sign-in failed (its message has no URL or token)
            }
            when (r.code) {
                200 -> replies.add(parseLimits(r.body, c.size))
                429 -> { store.put(CALLS_KEY, MAX_CALLS_PER_DAY); return Step.TOMORROW }
                else -> {
                    Log.w(TAG, "speed limits: HTTP ${r.code}")
                    // Server or TomTom trouble: the whole trip again on a later run, at most MAX_TRIES runs.
                    val tries = store.getLong(triesKey) + 1
                    if (r.code >= 500 && tries < MAX_TRIES) { store.put(triesKey, tries); return Step.RETRY }
                    replies.add(null)   // 400, 401, or 5xx too often: unknown
                }
            }
        }
        db.setTripSpeedLimits(p.tripId, SpeedLimitScoring.evaluateChunks(fixes, chunks, replies))
        return Step.DONE
    }

    private fun call(points: List<RoutePoint>, auth: SupabaseAuth, transport: Transport): HttpResult {
        val url = "${BuildConfig.SUPABASE_URL}/functions/v1/speed-limits"
        val body = requestJson(points).toString()
        fun headers(token: String) =
            mapOf("apikey" to BuildConfig.SUPABASE_KEY, "Authorization" to "Bearer $token", "Content-Type" to "application/json",
                REGION_HEADER to REGION)
        val r = transport.post(url, headers(auth.accessToken()), body)
        // An expired session: refresh once (the server answers 401 before it counts a call).
        return if (r.code == 401) transport.post(url, headers(auth.accessToken(forceRefresh = true)), body) else r
    }

    /** `{"points":[{"lat","lon","t"}]}`, `t` in epoch milliseconds. */
    fun requestJson(points: List<RoutePoint>): JSONObject = JSONObject().put("points", JSONArray().apply {
        for (p in points) put(JSONObject().put("lat", p.lat).put("lon", p.lon).put("t", p.epochMs))
    })

    /**
     * The `limits` of a 200 answer as one entry per point (null = unknown), or null when the answer doesn't fit
     * [n] points (wrong length, an index out of range or twice, not JSON): then the whole chunk stays unknown.
     */
    fun parseLimits(body: String, n: Int): List<Double?>? {
        val arr = try { JSONObject(body).optJSONArray("limits") } catch (_: Exception) { null } ?: return null
        if (arr.length() != n) return null
        val out = arrayOfNulls<Double>(n)
        val seen = BooleanArray(n)
        for (k in 0 until n) {
            val o = arr.optJSONObject(k) ?: return null
            val i = if (o.isNull("i")) -1 else o.optInt("i", -1)
            if (i !in 0 until n || seen[i]) return null
            seen[i] = true
            out[i] = if (o.isNull("kmh")) null else o.optDouble("kmh").takeIf { it.isFinite() && it > 0 }
        }
        return out.toList()
    }

    private fun schedule(ctx: Context, id: Int, delayMs: Long) {
        val job = JobInfo.Builder(id, ComponentName(ctx, SyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delayMs)
            .setBackoffCriteria(5 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply { putInt(EXTRA_JOB, 1) })
            .build()
        try {
            ctx.getSystemService(JobScheduler::class.java)?.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "speed limits not scheduled: ${e.javaClass.simpleName}")
        }
    }
}
