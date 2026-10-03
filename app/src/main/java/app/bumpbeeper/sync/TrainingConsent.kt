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
import java.util.TimeZone
import kotlin.random.Random

/**
 * "Help improve detection": the user's separate opt-in (off by default) to upload compact learning samples
 * (supabase/README.md, *Training samples*). Collecting needs [active]: consent on AND the shared-map choice answered
 * (network allowed). Screens call [setEnabled] and read [status]. Never logs sample contents.
 *
 * The server hears every switch-off: off sets a persistent "wipe pending" that is cleared only once
 * `set_training_consent(false)` succeeded, and a later "on" is only sent after that, so off → on can never skip
 * the deletion (or keep the old pseudonym).
 */
object TrainingConsent {
    private const val TAG = "BumpBeeper"
    /** Version of the consent text the user agreed to (sent with `set_training_consent`). */
    const val TRAINING_CONSENT_VERSION = 1
    const val EXTRA_JOB = "training_job"
    private const val JOB_CONSENT = 4106
    private const val JOB_UPLOAD = 4107
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val MAX_BATCHES_PER_RUN = 10
    // sync_state keys
    internal const val PAUSED_UNTIL = "training_paused_until"
    internal const val UPLOAD_AFTER = "training_upload_after"
    /** [Status.lastError] when a new anonymous session found the opt-in off on the server: it was switched off here. */
    const val SESSION_RESET = "session_reset"

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
        /** Why the last upload stopped, "" = fine, or [SESSION_RESET] (show: "turned off, switch on again to help"). */
        val lastError: String,
    )

    fun status(ctx: Context): Status = Status(
        Prefs.trainingConsent(ctx), Prefs.trainingConsentVersion(ctx), active(ctx),
        Prefs.trainingWipePending(ctx) || Prefs.trainingOnPending(ctx), LiveState.trainingQueued, Prefs.trainingNote(ctx),
    )

    fun active(ctx: Context): Boolean = Prefs.trainingActive(ctx)

    /**
     * Switch it on or off ([version] = the consent text shown). The server is told in the background and retried until
     * it has it. Off empties the local outbox at once and makes sure the server deletes what it holds.
     */
    fun setEnabled(ctx: Context, on: Boolean, version: Int = TRAINING_CONSENT_VERSION) {
        Prefs.setTrainingState(ctx, on, version, wipe = Prefs.trainingWipePending(ctx) || !on, sendOn = on, note = "")
        if (!on) clearAsync(ctx)
        schedule(ctx, JOB_CONSENT, 0L)
    }

    /** "Delete my shared data": the server deletes the device (and its samples), so only the phone side is left. */
    fun forgetLocal(ctx: Context) {
        Prefs.setTrainingState(ctx, false, Prefs.trainingConsentVersion(ctx), wipe = false, sendOn = false, note = "")
        clearAsync(ctx)
    }

    /**
     * Some trip's samples were queued (or released): upload 1 to 6 hours from now, at a random time, so request logs
     * can't tie the pseudonym to when a trip ended. A later trip pushes it later. Call under [Sync.lock].
     */
    fun uploadLater(ctx: Context, state: SyncStore, now: Long, random: Random = Random.Default) {
        val at = maxOf(state.getLong(UPLOAD_AFTER), now + HOUR_MS + random.nextLong(5 * HOUR_MS))
        state.put(UPLOAD_AFTER, at)
        schedule(ctx, JOB_UPLOAD, at - now)
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

    private fun schedule(ctx: Context, id: Int, delayMs: Long) {
        if (Prefs.syncChoice(ctx) == Prefs.SYNC_UNSET) return   // no network yet: handled at the first sync run
        val job = JobInfo.Builder(id, ComponentName(ctx, SyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setMinimumLatency(delayMs.coerceAtLeast(0L))
            .setExtras(PersistableBundle().apply { putInt(EXTRA_JOB, 1) })
            .build()
        try {
            ctx.getSystemService(JobScheduler::class.java)?.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "training job not scheduled: ${e.javaClass.simpleName}")
        }
    }

    /** The training jobs ([SyncJob]): register, tell the server, upload when due. True = retry later. */
    fun run(ctx: Context, transport: Transport = UrlTransport, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(Sync.lock) {
            if (Prefs.syncChoice(ctx) == Prefs.SYNC_UNSET) return false
            val clock = ClockWatch(transport)
            val api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY,
                SupabaseAuth(ctx, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, clock), clock)
            val retry = try {
                api.registerDevice(TraceWriter.appVersion(ctx), Build.VERSION.SDK_INT, Sync.CONSENT_VERSION, Prefs.shareBumps(ctx))
                Sync.withDb(ctx) { duringSync(ctx, api, it, now) }
            } catch (e: ApiException) {
                e.outcome == Outcome.RETRY
            }
            // Retried (with back-off) until the server has the latest choice.
            retry || Prefs.trainingWipePending(ctx) || Prefs.trainingOnPending(ctx)
        }

    /**
     * Part of every full sync run (after `register_device`) and of the training jobs: tells the server a changed
     * choice, then uploads if due. Returns true when it should be retried later. Its errors never stop the shared map.
     */
    fun duringSync(ctx: Context, api: SupabaseApi, db: BumpDb, now: Long): Boolean {
        val store = TrainingStore(db)
        val state = SyncStore(db)
        var retry = false
        try {
            store.prune(now)
            tellServer(ctx, api)
            if (active(ctx) && !Prefs.trainingWipePending(ctx) && !Prefs.trainingOnPending(ctx) &&
                now >= state.getLong(UPLOAD_AFTER)
            ) {
                upload(ctx, api, store, state, now)
            }
        } catch (e: ApiException) {
            Log.w(TAG, "training: ${e.message}")
            if (Prefs.trainingNote(ctx) != SESSION_RESET) Prefs.setTrainingNote(ctx, describe(e.outcome))
            retry = e.outcome == Outcome.RETRY
        }
        LiveState.trainingQueued = store.count()
        return retry
    }

    /** First the pending wipe (off), then a pending on. Each flag is cleared only after the server took it. */
    private fun tellServer(ctx: Context, api: SupabaseApi) {
        fun send(on: Boolean) {
            api.rpc("set_training_consent", JSONObject().put("enabled", on).put("version", Prefs.trainingConsentVersion(ctx)))
        }
        if (Prefs.trainingWipePending(ctx)) {
            send(false)
            Prefs.setTrainingWipePending(ctx, false)
        }
        if (Prefs.trainingOnPending(ctx)) {
            if (Prefs.trainingConsent(ctx)) send(true)
            // Switched off meanwhile: that set the wipe flag again, which the next run sends.
            if (!Prefs.trainingWipePending(ctx)) Prefs.setTrainingOnPending(ctx, false)
        }
    }

    /** Short status text for an error while talking to the training RPCs. */
    private fun describe(o: Outcome): String = when (o) {
        Outcome.NOT_ALLOWED -> "this phone isn't registered with the server yet, trying again later"
        else -> ApiErrors.describe(o)
    }

    /** Sends the outbox in batches; deletes what the server acknowledged. Notes why it stopped in [Prefs.trainingNote]. */
    internal fun upload(ctx: Context, api: SupabaseApi, store: TrainingStore, state: SyncStore, now: Long) {
        if (now < state.getLong(PAUSED_UNTIL)) return
        val brand = TrainingJson.brand(Build.BRAND)
        var note = ""
        run batches@{
            repeat(MAX_BATCHES_PER_RUN) {
                if (!active(ctx)) return@batches   // switched off meanwhile: setEnabled empties the outbox
                val items = store.batch()
                if (items.isEmpty()) return@batches
                val body = TrainingJson.batch(TraceWriter.appVersion(ctx), Build.VERSION.SDK_INT, brand, items)
                try {
                    val held = heldIds(api.rpc("submit_training_samples", JSONObject().put("batch", body)))
                    val done = items.filter { it.id in held }.map { it.id }
                    store.delete(done)
                    if (done.isEmpty()) { note = "server kept nothing"; return@batches }   // don't loop on it
                } catch (e: ApiException) {
                    when (e.outcome) {
                        Outcome.DROP -> store.delete(items.map { it.id })   // never accepted: drop, don't retry
                        Outcome.NOT_ALLOWED -> {
                            // No consent on the server for this (possibly new anonymous) session: stop here too.
                            store.clear()
                            Prefs.setTrainingState(ctx, false, Prefs.trainingConsentVersion(ctx), wipe = false, sendOn = false, note = SESSION_RESET)
                            return
                        }
                        Outcome.LIMIT -> { state.put(PAUSED_UNTIL, nextUtcMidnight(now)); note = describe(e.outcome); return@batches }
                        Outcome.FULL -> { state.put(PAUSED_UNTIL, now + 3 * DAY_MS); note = describe(e.outcome); return@batches }
                        else -> throw e
                    }
                }
            }
        }
        Prefs.setTrainingNote(ctx, note)
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

    /** The server counts its daily caps per UTC day (`current_date` on Supabase), not the phone's local day. */
    internal fun nextUtcMidnight(now: Long): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
