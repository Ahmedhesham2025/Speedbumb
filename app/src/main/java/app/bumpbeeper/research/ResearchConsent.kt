package app.bumpbeeper.research

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.Prefs
import app.bumpbeeper.TraceWriter
import app.bumpbeeper.sync.ApiException
import app.bumpbeeper.sync.Outcome
import app.bumpbeeper.sync.SupabaseApi
import app.bumpbeeper.sync.SupabaseAuth
import app.bumpbeeper.sync.Sync
import app.bumpbeeper.sync.SyncJob
import app.bumpbeeper.sync.Transport
import app.bumpbeeper.sync.UrlTransport
import org.json.JSONObject

/**
 * Research recordings' consent: the user's choice ([Prefs.researchRecording], off by default, on only after the consent
 * screen) and the server's copy (`set_research_consent`, supabase/README.md "Research recordings"), kept in step like
 * TrainingConsent: one persistent pending flag per direction, cleared only once the server took it, "off" always
 * before "on".
 *
 * It needs no shared-map answer: its own consent screen covers the upload. This phone then registers itself honestly
 * (shared-map consent 0 and sharing off while that question is unanswered). "Off" is sent only when the server may
 * have "on", so a No that never was a Yes creates nothing on the server.
 */
object ResearchConsent {
    private const val TAG = "BumpBeeper"
    /** Version of the research consent text (sent with `set_research_consent`). */
    const val RESEARCH_CONSENT_VERSION = 1
    /** [Prefs.researchNote] after the server lost this phone's consent (a new anonymous ID): switched off here. */
    const val SESSION_RESET = "session_reset"
    /** [SyncJob] extra: [JOB_CONSENT] tells the server a changed choice (any network), [JOB_UPLOAD] uploads (Wi-Fi). */
    const val EXTRA_JOB = "research_job"
    const val JOB_CONSENT = 1
    const val JOB_UPLOAD = 2
    private const val ID_CONSENT = 4108

    /** The server hasn't been told the latest choice yet (offline; retried in the background). */
    fun pending(ctx: Context): Boolean = Prefs.researchOffPending(ctx) || Prefs.researchOnPending(ctx)

    /** Uploads may run: switched on, and the server has it. */
    fun active(ctx: Context): Boolean = Prefs.researchRecording(ctx) && !pending(ctx)

    /**
     * On only after the consent screen's Yes ([version] = the text shown); off from Settings or the screen's No. Off
     * stops recording at once (BumpService listens), withdraws the upload queue (the files stay on the phone) and is
     * told to the server; files already uploaded stay until deleted on request.
     */
    fun setEnabled(ctx: Context, on: Boolean, version: Int = RESEARCH_CONSENT_VERSION) {
        val app = ctx.applicationContext ?: ctx
        val serverOn = Prefs.researchServerOn(app)
        if (on) {
            Prefs.setResearchState(app, true, version, Prefs.researchOffPending(app), onPending = true, serverOn = serverOn, note = "")
        } else {
            val tell = pending(app) || serverOn
            Prefs.setResearchState(app, false, Prefs.researchConsentVersion(app), offPending = tell, onPending = false, serverOn = serverOn, note = "")
            withdrawAsync(app)
        }
        if (pending(app)) schedule(app)
        ResearchUploader.ensurePeriodic(app)   // the 6-hourly upload while on, none while off
    }

    /** "Delete my shared data": the server forgets the device (its research files go at the owner's next run). */
    fun forgetLocal(ctx: Context) {
        Prefs.setResearchState(ctx, false, 0, offPending = false, onPending = false, serverOn = false, note = "")
        withdrawAsync(ctx.applicationContext ?: ctx)
        ResearchUploader.ensurePeriodic(ctx)
    }

    /**
     * First start after a backup restore (RestoreReset): this phone is a new anonymous device that never agreed, so
     * research is off with nothing to tell the server, and the upload queue is forgotten. If it was on, the first-start
     * question comes again.
     */
    fun resetAfterRestore(ctx: Context) {
        if (Prefs.researchRecording(ctx)) Prefs.setResearchAsked(ctx, 0)
        Prefs.setResearchState(ctx, false, 0, offPending = false, onPending = false, serverOn = false, note = "")
        ResearchQueue.clear(ctx)
        ResearchUploader.ensurePeriodic(ctx)
    }

    /** The server no longer has this phone's consent (a new anonymous ID): off here too, and Settings says why. */
    internal fun sessionReset(ctx: Context) {
        Prefs.setResearchState(ctx, false, Prefs.researchConsentVersion(ctx), offPending = false, onPending = false, serverOn = false, note = SESSION_RESET)
        ResearchQueue.withdraw(ctx)
        ResearchUploader.ensurePeriodic(ctx)
    }

    /** App start: a choice not yet told (the job may have been lost with a reboot) is sent when online; uploads go on. */
    fun onAppStart(ctx: Context) {
        if (pending(ctx)) schedule(ctx)
        ResearchUploader.ensurePeriodic(ctx)
    }

    /** The consent job ([SyncJob]): tell the server. True = retry later. */
    fun run(ctx: Context, transport: Transport = UrlTransport): Boolean = synchronized(Sync.lock) {
        if (!pending(ctx)) return false
        // Only "on" may sign in as a new anonymous device: an "off" without a session has nothing to switch off.
        val mayCreate = Prefs.researchRecording(ctx) && Prefs.researchOnPending(ctx)
        val auth = SupabaseAuth(ctx, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, transport, mayCreate = mayCreate)
        try {
            tellServer(ctx, SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, auth, transport))
            if (active(ctx)) ResearchUploader.scheduleNow(ctx)   // the server has "on": files may go
            false
        } catch (e: ApiException) {
            Log.w(TAG, "research consent: ${e.message}")
            if (e.outcome == Outcome.AUTH && !mayCreate) {
                Prefs.setResearchFlag(ctx, Prefs.RESEARCH_OFF_PENDING, false)
                Prefs.setResearchFlag(ctx, Prefs.RESEARCH_SERVER_ON, false)
                false
            } else {
                e.outcome == Outcome.RETRY
            }
        }
    }

    /** First the pending "off", then a pending "on"; each flag is cleared only after the server took it. */
    internal fun tellServer(ctx: Context, api: SupabaseApi) {
        if (Prefs.researchOffPending(ctx)) {
            send(ctx, api, false)
            Prefs.setResearchFlag(ctx, Prefs.RESEARCH_SERVER_ON, false)
            Prefs.setResearchFlag(ctx, Prefs.RESEARCH_OFF_PENDING, false)
        }
        if (Prefs.researchOnPending(ctx)) {
            if (Prefs.researchRecording(ctx)) {
                send(ctx, api, true)
                Prefs.setResearchFlag(ctx, Prefs.RESEARCH_SERVER_ON, true)
            }
            // Switched off meanwhile: that set "off" pending again, which the next run sends.
            if (!Prefs.researchOffPending(ctx)) Prefs.setResearchFlag(ctx, Prefs.RESEARCH_ON_PENDING, false)
        }
    }

    /** `{"enabled":true,"version":V}` or `{"enabled":false,"version":null}`; 42501 = not registered yet. */
    private fun send(ctx: Context, api: SupabaseApi, on: Boolean) {
        val args = JSONObject().put("enabled", on).put("version", if (on) Prefs.researchConsentVersion(ctx) else JSONObject.NULL)
        try {
            api.rpc("set_research_consent", args)
        } catch (e: ApiException) {
            if (e.outcome != Outcome.NOT_ALLOWED) throw e
            if (!on) return   // no device on the server: no consent to switch off
            register(ctx, api)
            api.rpc("set_research_consent", args)
        }
    }

    /** register_device as the shared map sends it, or consent 0 and sharing off while that question is unanswered. */
    internal fun register(ctx: Context, api: SupabaseApi) {
        val mapAnswered = Prefs.syncChoice(ctx) != Prefs.SYNC_UNSET
        api.registerDevice(TraceWriter.appVersion(ctx), Build.VERSION.SDK_INT, if (mapAnswered) Sync.CONSENT_VERSION else 0, Prefs.shareBumps(ctx))
    }

    private fun withdrawAsync(app: Context) = Thread({ ResearchQueue.withdraw(app) }, "research-withdraw").start()

    private fun schedule(ctx: Context) {
        val job = JobInfo.Builder(ID_CONSENT, ComponentName(ctx, SyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)   // an "off" must reach the server even after a reboot
            .setExtras(PersistableBundle().apply { putInt(EXTRA_JOB, JOB_CONSENT) })
            .build()
        try {
            ctx.getSystemService(JobScheduler::class.java)?.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "research consent job not scheduled: ${e.javaClass.simpleName}")
        }
    }
}
