package app.bumpbeeper.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.BumpDb
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
import app.bumpbeeper.TraceWriter
import org.json.JSONObject
import java.util.Calendar

/**
 * "Help improve detection": the user's separate opt-in (off by default) to upload compact learning samples
 * (supabase/README.md, *Training samples*). Collecting needs [active]: consent on AND the shared-map choice answered
 * (network allowed). Screens call [setEnabled] and read [status]. Never logs sample contents.
 */
object TrainingConsent {
    private const val TAG = "BumpBeeper"
    /** Version of the consent text the user agreed to (sent with `set_training_consent`). */
    const val TRAINING_CONSENT_VERSION = 1
    const val EXTRA_JOB = "training_job"
    private const val JOB_CONSENT = 4106
    private const val KEEP_DAYS = 7L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MAX_BATCHES_PER_RUN = 10
    // sync_state keys
    internal const val PAUSED_UNTIL = "training_paused_until"
    private const val LAST_ERROR = "training_last_error"

    class Status(
        /** The user's choice on this phone. */
        val enabled: Boolean,
        /** The consent text version agreed to (0 = never). */
        val version: Int,
        /** Samples are being collected (consent on and network allowed). */
        val active: Boolean,
        /** The server hasn't been told the latest choice yet (offline; retried in the background). */
        val serverPending: Boolean,
        /** Elements waiting for upload (as of the last sync). */
        val queued: Int,
        /** Why the last upload stopped, or "". */
        val lastError: String,
    )

    fun status(ctx: Context): Status = Status(
        Prefs.trainingConsent(ctx), Prefs.trainingConsentVersion(ctx), active(ctx), Prefs.trainingServerPending(ctx),
        LiveState.trainingQueued, LiveState.trainingLastError,
    )

    fun active(ctx: Context): Boolean = Prefs.trainingActive(ctx)

    /**
     * Switch it on or off ([version] = the consent text shown). The server is told in the background and retried until
     * it has it. Off empties the local outbox at once (the server deletes what it holds when it hears about it).
     */
    fun setEnabled(ctx: Context, on: Boolean, version: Int = TRAINING_CONSENT_VERSION) {
        Prefs.setTrainingConsent(ctx, on, version, serverPending = true)
        if (!on) clearAsync(ctx)
        schedule(ctx)
    }

    /** "Delete my shared data": the server deletes the device (and its samples), so only the phone side is left. */
    fun forgetLocal(ctx: Context) {
        Prefs.setTrainingConsent(ctx, false, Prefs.trainingConsentVersion(ctx), serverPending = false)
        clearAsync(ctx)
    }

    private fun clearAsync(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        Thread({
            try {
                synchronized(Sync.lock) { Sync.withDb(app) { TrainingStore(it).clear() } }
                LiveState.trainingQueued = 0
            } catch (e: Exception) {
                Log.w(TAG, "training outbox not cleared: ${e.javaClass.simpleName}")
            }
        }, "training-clear").start()
    }

    private fun schedule(ctx: Context) {
        if (Prefs.syncChoice(ctx) == Prefs.SYNC_UNSET) return   // no network yet: told at the first sync run
        val job = JobInfo.Builder(JOB_CONSENT, ComponentName(ctx, SyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply { putInt(EXTRA_JOB, 1) })
            .build()
        try {
            ctx.getSystemService(JobScheduler::class.java)?.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "training consent not scheduled: ${e.javaClass.simpleName}")
        }
    }

    /** The consent job ([SyncJob]): register, tell the server, upload. True = retry later. */
    fun run(ctx: Context, transport: Transport = UrlTransport): Boolean = synchronized(Sync.lock) {
        if (Prefs.syncChoice(ctx) == Prefs.SYNC_UNSET) return false
        val clock = ClockWatch(transport)
        val api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY,
            SupabaseAuth(ctx, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, clock), clock)
        try {
            api.registerDevice(TraceWriter.appVersion(ctx), Build.VERSION.SDK_INT, Sync.CONSENT_VERSION, Prefs.shareBumps(ctx))
        } catch (e: ApiException) {
            return e.outcome == Outcome.RETRY || Prefs.trainingServerPending(ctx)
        }
        // Retried (with back-off) until the server has the latest choice.
        Sync.withDb(ctx) { duringSync(ctx, api, it, System.currentTimeMillis()) } || Prefs.trainingServerPending(ctx)
    }

    /**
     * Part of every full sync run, after `register_device`: tells the server a changed choice, then uploads.
     * Returns true when it should be retried later. Its errors never stop the rest of the sync.
     */
    fun duringSync(ctx: Context, api: SupabaseApi, db: BumpDb, now: Long): Boolean {
        val store = TrainingStore(db)
        val state = SyncStore(db)
        var retry = false
        var error = ""
        try {
            store.prune(now - KEEP_DAYS * DAY_MS)
            if (Prefs.trainingServerPending(ctx)) {
                val on = Prefs.trainingConsent(ctx)
                api.rpc("set_training_consent", JSONObject().put("enabled", on).put("version", Prefs.trainingConsentVersion(ctx)))
                // Only clear the flag if the choice didn't change while the call was running.
                if (Prefs.trainingConsent(ctx) == on) Prefs.setTrainingServerPending(ctx, false)
            }
            if (active(ctx) && !Prefs.trainingServerPending(ctx)) error = upload(ctx, api, store, state, now)
        } catch (e: ApiException) {
            Log.w(TAG, "training: ${e.message}")
            error = ApiErrors.describe(e.outcome)
            retry = e.outcome == Outcome.RETRY
        }
        state.put(LAST_ERROR, error)
        LiveState.trainingLastError = error
        LiveState.trainingQueued = store.count()
        return retry
    }

    /** Sends the outbox in batches; deletes what the server acknowledged. Returns "" or why it stopped. */
    internal fun upload(ctx: Context, api: SupabaseApi, store: TrainingStore, state: SyncStore, now: Long): String {
        if (now < state.getLong(PAUSED_UNTIL)) return "paused until tomorrow or later"
        val brand = TrainingJson.brand(Build.BRAND)
        repeat(MAX_BATCHES_PER_RUN) {
            if (!active(ctx)) return ""   // switched off meanwhile: setEnabled empties the outbox
            val items = store.batch()
            if (items.isEmpty()) return ""
            val body = TrainingJson.batch(TraceWriter.appVersion(ctx), Build.VERSION.SDK_INT, brand, items)
            try {
                val held = api.rpc("submit_training_samples", JSONObject().put("batch", body)).let(::heldIds)
                val done = items.filter { it.id in held }.map { it.id }
                store.delete(done)
                if (done.isEmpty()) return "server kept nothing"   // don't loop on a batch it ignores
            } catch (e: ApiException) {
                when (e.outcome) {
                    Outcome.DROP -> store.delete(items.map { it.id })   // never accepted: drop, don't retry
                    Outcome.NOT_ALLOWED -> {
                        // The server has no consent for this device: stop collecting here too.
                        store.clear()
                        Prefs.setTrainingConsent(ctx, false, Prefs.trainingConsentVersion(ctx), serverPending = false)
                        return "Help improve detection is off on the server"
                    }
                    Outcome.LIMIT -> { state.put(PAUSED_UNTIL, nextMidnight(now)); return ApiErrors.describe(Outcome.LIMIT) }
                    Outcome.FULL -> { state.put(PAUSED_UNTIL, now + 3 * DAY_MS); return ApiErrors.describe(Outcome.FULL) }
                    else -> throw e
                }
            }
        }
        return ""
    }

    /** `{"samples": [ids], "trips": [ids]}` → all ids. */
    internal fun heldIds(body: String): Set<String> {
        val o = try { JSONObject(body) } catch (_: Exception) { return emptySet() }
        val out = HashSet<String>()
        for (k in listOf("samples", "trips")) {
            val a = o.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) a.optString(i, "").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }

    private fun nextMidnight(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
