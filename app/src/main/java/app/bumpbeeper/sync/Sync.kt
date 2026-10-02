package app.bumpbeeper.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.BumpDb
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
import app.bumpbeeper.TraceWriter
import app.bumpbeeper.crash.CrashLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToLong

/**
 * The shared bump map, phone side. Entry points for the service and the screens; the work itself runs
 * in [SyncJob] (JobScheduler, network required), never on the main thread.
 *
 * Nothing touches the network until the user answered the first-run screen ([Prefs.syncChoice] not "unset").
 * One run: sign in (anonymous) → register_device → upload the outbox and crash reports (choice "share" only)
 * → download confirmed spots within 10 km of the last trip position into the cache.
 */
object Sync {
    private const val TAG = "BumpBeeper"
    const val CONSENT_VERSION = 1
    const val PULL_RADIUS_M = 10_000
    private const val BATCH = 500
    private const val MAX_BATCHES_PER_RUN = 20
    private const val MAX_ATTEMPTS = 20
    private const val KEEP_DAYS = 28L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private const val JOB_DAILY = 4101
    private const val JOB_NOW = 4102
    private const val JOB_PULL = 4103
    const val EXTRA_PULL_ONLY = "pull_only"
    const val EXTRA_LAT = "lat"
    const val EXTRA_LON = "lon"

    // sync_state keys
    private const val LAST_AT = "last_at"
    private const val LAST_ERROR = "last_error"
    private const val UPLOAD_PAUSED_UNTIL = "upload_paused_until"
    private const val POS_LAT = "pos_lat"
    private const val POS_LON = "pos_lon"

    /** Runs never overlap (two parallel first runs would sign in as two devices); [SpeedLimitSync] takes it too. */
    internal val lock = Any()

    // ---------------------------------------------------------------- scheduling (any thread)

    /** App opened: make sure the daily sync exists, sync once now (when online), and load the status numbers. */
    fun onAppStart(ctx: Context) {
        if (online(ctx)) {
            ensureDaily(ctx)
            schedule(ctx, JOB_NOW, false, Double.NaN, Double.NaN)
        }
        val app = ctx.applicationContext ?: ctx
        Thread({
            try {
                withDb(app) { publishStatus(SyncStore(it)) }
            } catch (e: Exception) {
                Log.w(TAG, "sync status", e)
            }
            SpeedLimitSync.onAppStart(app)
        }, "sync-status").start()
    }

    /** Recording started and has a position: refresh the spot cache around it as soon as there is network. */
    fun pullAround(ctx: Context, lat: Double, lon: Double) {
        if (online(ctx)) schedule(ctx, JOB_PULL, true, round2(lat), round2(lon))
    }

    /** Recording stopped (the outbox has this trip's observations): full sync when online. */
    fun afterTrip(ctx: Context, lat: Double, lon: Double) {
        if (!online(ctx)) return
        ensureDaily(ctx)
        // Rounded before it is stored anywhere (JobScheduler keeps job extras on disk): the trip's end is often home.
        schedule(ctx, JOB_NOW, false, round2(lat), round2(lon))
    }

    /**
     * The first-run / settings answer: [Prefs.SYNC_RECEIVE] or [Prefs.SYNC_SHARE] start syncing (a run now tells the
     * server, register_device). Any answer other than "share" empties the outbox right away (background thread), so
     * points collected before are never sent, even if sharing is switched on again later.
     * [Prefs.SYNC_UNSET] stops all sync jobs.
     */
    fun setChoice(ctx: Context, choice: String) {
        Prefs.setSyncChoice(ctx, choice)
        // No network any more: routes waiting for a speed-limit lookup are deleted, never sent.
        if (!online(ctx)) SpeedLimitSync.clearPendingAsync(ctx)
        if (Prefs.syncChoice(ctx) != Prefs.SYNC_SHARE) {
            val app = ctx.applicationContext ?: ctx
            Thread({
                try {
                    synchronized(lock) { withDb(app) { db -> SyncStore(db).outboxClear(); publishStatus(SyncStore(db)) } }
                } catch (e: Exception) {
                    Log.w(TAG, "outbox not cleared", e)
                }
            }, "sync-outbox-clear").start()
        }
        if (online(ctx)) {
            ensureDaily(ctx)
            schedule(ctx, JOB_NOW, false, Double.NaN, Double.NaN)
        } else {
            try { scheduler(ctx)?.let { js -> listOf(JOB_DAILY, JOB_NOW, JOB_PULL).forEach { js.cancel(it) } } } catch (_: Exception) {}
        }
    }

    /** The user answered the first-run screen; before that, nothing goes online. */
    private fun online(ctx: Context) = Prefs.syncChoice(ctx) != Prefs.SYNC_UNSET

    private fun scheduler(ctx: Context): JobScheduler? = ctx.getSystemService(JobScheduler::class.java)

    private fun schedule(ctx: Context, id: Int, pullOnly: Boolean, lat: Double, lon: Double) {
        val extras = PersistableBundle().apply {
            putInt(EXTRA_PULL_ONLY, if (pullOnly) 1 else 0)
            putDouble(EXTRA_LAT, lat)
            putDouble(EXTRA_LON, lon)
        }
        val job = JobInfo.Builder(id, ComponentName(ctx, SyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(extras)
            .build()
        try {
            scheduler(ctx)?.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "sync not scheduled", e)
        }
    }

    private fun ensureDaily(ctx: Context) {
        try {
            val js = scheduler(ctx) ?: return
            if (js.getPendingJob(JOB_DAILY) != null) return
            js.schedule(
                JobInfo.Builder(JOB_DAILY, ComponentName(ctx, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(DAY_MS)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "daily sync not scheduled", e)
        }
    }

    // ---------------------------------------------------------------- forget me

    /**
     * Deletes this phone's data on the server (forget_me), then the outbox, spot cache, sync state and sign-in
     * here, and sets the choice back to "unset" (no network until the user chooses again). [callback] runs on the main thread: true = server data deleted (or there
     * never was any), false = offline/server error (sharing is off and local data is gone; try again later).
     */
    fun forgetMe(ctx: Context, callback: (Boolean) -> Unit) {
        val app = ctx.applicationContext ?: ctx
        setChoice(app, Prefs.SYNC_UNSET)
        Thread({
            val ok = try {
                forgetNow(app)
            } catch (e: Exception) {
                Log.w(TAG, "forget me", e)
                false
            }
            Handler(Looper.getMainLooper()).post { callback(ok) }
        }, "sync-forget").start()
    }

    /** The blocking part of [forgetMe] (background thread). [transport] is swapped for a fake in tests. */
    fun forgetNow(app: Context, transport: Transport = UrlTransport): Boolean = synchronized(lock) {
        // Never signs in as a new device here: with a dead session forget_me would delete the new, empty device
        // and report success while the old device's data stays on the server. Report the failure instead.
        val auth = SupabaseAuth(app, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, transport, mayCreate = false)
        var reason = ""
        val serverOk = !auth.signedIn || try {
            SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, auth, transport).forgetMe()
            true
        } catch (e: ApiException) {
            Log.w(TAG, "forget_me failed: ${e.message}")
            reason = if (e.outcome == Outcome.AUTH) "sign-in expired, could not delete your data on the server"
            else "could not reach the server to delete your data"
            false
        }
        withDb(app) { db ->
            val store = SyncStore(db)
            store.clearAll()
            if (!serverOk) store.put(LAST_ERROR, reason)
            publishStatus(store)
        }
        // Keep the sign-in when the server call failed, so a retry can still delete that device's data.
        if (serverOk) auth.clear()
        serverOk
    }

    // ---------------------------------------------------------------- one run (SyncJob thread)

    /**
     * One sync run. Returns true when it should be retried later (offline, server busy).
     * [transport] is swapped for a fake in tests.
     */
    fun run(ctx: Context, pullOnly: Boolean, lat: Double, lon: Double, transport: Transport = UrlTransport): Boolean = synchronized(lock) {
        val choice = Prefs.syncChoice(ctx)
        if (choice == Prefs.SYNC_UNSET) return false   // not answered yet: no sign-in, no calls at all
        withDb(ctx) { db ->
            val store = SyncStore(db)
            if (!lat.isNaN() && !lon.isNaN()) {
                // Only a rounded position (about 1 km) is kept and sent: enough for a 10 km download circle.
                store.put(POS_LAT, round2(lat))
                store.put(POS_LON, round2(lon))
            }
            val clock = ClockWatch(transport)
            val api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, newAuth(ctx, clock), clock)
            val now = System.currentTimeMillis()
            var retry = false
            var error = ""
            try {
                if (!pullOnly) {
                    val share = choice == Prefs.SYNC_SHARE
                    api.registerDevice(TraceWriter.appVersion(ctx), Build.VERSION.SDK_INT, CONSENT_VERSION, share)
                    if (share) {
                        error = upload(api, store, now, ctx, clock)
                        if (error.isEmpty()) uploadCrashes(api, ctx)
                    } else {
                        store.outboxClear()
                    }
                }
                pull(api, store, now)
            } catch (e: ApiException) {
                Log.w(TAG, "sync: ${e.message}")
                error = ApiErrors.describe(e.outcome)
                retry = e.outcome == Outcome.RETRY
            }
            if (!retry) store.put(LAST_AT, now)
            store.put(LAST_ERROR, error)
            publishStatus(store)
            retry
        }
    }

    /** Uploads the outbox in batches. Returns "" or a short reason it stopped without needing a retry. */
    private fun upload(api: SupabaseApi, store: SyncStore, now: Long, ctx: Context, clock: ClockWatch): String {
        store.outboxPrune(now - KEEP_DAYS * DAY_MS, MAX_ATTEMPTS)
        if (now < store.getLong(UPLOAD_PAUSED_UNTIL)) return ApiErrors.describe(Outcome.LIMIT)
        var reRegistered = false
        var batches = 0
        while (batches < MAX_BATCHES_PER_RUN) {
            // Sharing switched off while this run was going: stop before the next batch (setChoice empties the outbox).
            if (Prefs.syncChoice(ctx) != Prefs.SYNC_SHARE) break
            val batch = store.outboxBatch(BATCH)
            if (batch.isEmpty()) break
            // The server's clock (Date header of the calls so far) wins when the phone's runs more than 1 h ahead.
            val shift = clock.correctionMs()
            val arr = JSONArray()
            val ids = ArrayList<String>()
            val broken = ArrayList<String>()
            for ((id, json) in batch) {
                try {
                    arr.put(ObservationJson.shiftObservedAt(JSONObject(json), shift)); ids.add(id)
                } catch (_: Exception) {
                    broken.add(id)
                }
            }
            store.outboxDelete(broken)
            try {
                val held = api.submitObservations(arr)
                store.outboxDelete(ids.filter { it in held })
                store.outboxAttempted(ids.filter { it !in held })
                batches++
            } catch (e: ApiException) {
                when (e.outcome) {
                    Outcome.DROP -> {   // the server will never take this batch: drop it, never retry
                        store.outboxDelete(ids)
                        batches++
                    }
                    Outcome.LIMIT -> {
                        store.put(UPLOAD_PAUSED_UNTIL, nextMidnight(now))
                        return ApiErrors.describe(Outcome.LIMIT)
                    }
                    Outcome.NOT_ALLOWED -> {
                        // The server lost our device row or thinks sharing is off: register once more, then give up.
                        if (reRegistered) return ApiErrors.describe(Outcome.NOT_ALLOWED)
                        reRegistered = true
                        api.registerDevice(TraceWriter.appVersion(ctx), Build.VERSION.SDK_INT, CONSENT_VERSION, true)
                    }
                    else -> {
                        store.outboxAttempted(ids)
                        throw e
                    }
                }
            }
        }
        return ""
    }

    /** Sends saved crash reports (sharing on only) and deletes each one the server took or will never take. */
    private fun uploadCrashes(api: SupabaseApi, ctx: Context) {
        for (f in CrashLog.list(ctx)) {
            val text = try {
                f.readText()
            } catch (_: Exception) {
                f.delete()
                continue
            }
            fun field(name: String) = text.lineSequence().firstOrNull { it.startsWith("$name=") }?.substringAfter('=') ?: ""
            try {
                api.submitCrashReport(field("app_version"), field("device"), CrashLog.scrub(text))
                f.delete()
            } catch (e: ApiException) {
                when (e.outcome) {
                    Outcome.DROP -> f.delete()
                    Outcome.RETRY -> throw e
                    else -> return   // daily cap or not registered: keep them for another day
                }
            }
        }
    }

    private fun pull(api: SupabaseApi, store: SyncStore, now: Long) {
        val lat = store.getDouble(POS_LAT)
        val lon = store.getDouble(POS_LON)
        if (lat.isNaN() || lon.isNaN()) return   // no trip yet: nothing to download around
        val rows = api.spotsNear(lat, lon, PULL_RADIUS_M)
        store.replaceRemoteSpots(lat, lon, PULL_RADIUS_M.toDouble(), rows, now)
    }

    private fun publishStatus(store: SyncStore) {
        LiveState.syncLastAt = store.getLong(LAST_AT)
        LiveState.syncLastError = store.get(LAST_ERROR) ?: ""
        LiveState.syncPending = store.outboxCount()
        LiveState.syncRemoteSpots = store.remoteSpotCount()
    }

    private fun newAuth(ctx: Context, transport: Transport = UrlTransport) =
        SupabaseAuth(ctx, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, transport)

    internal fun <T> withDb(ctx: Context, block: (BumpDb) -> T): T {
        val db = BumpDb(ctx)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    /** Two decimals (about 1 km): the only precision of a position the sync keeps or sends. */
    fun round2(x: Double): Double = (x * 100).roundToLong() / 100.0

    private fun nextMidnight(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
