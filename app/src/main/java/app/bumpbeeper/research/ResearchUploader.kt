package app.bumpbeeper.research

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.os.PersistableBundle
import android.util.Log
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.Prefs
import app.bumpbeeper.auto.TripHold
import app.bumpbeeper.sync.ApiException
import app.bumpbeeper.sync.FileTransport
import app.bumpbeeper.sync.Outcome
import app.bumpbeeper.sync.StorageTransport
import app.bumpbeeper.sync.SupabaseApi
import app.bumpbeeper.sync.SupabaseAuth
import app.bumpbeeper.sync.SyncJob
import app.bumpbeeper.sync.Transport
import app.bumpbeeper.sync.UrlTransport
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uploads research recordings on an unmetered network only (JobScheduler: unmetered, battery not low, charging not
 * needed) after each trip and about every 6 hours, as supabase/README.md "Research recordings" says: per file, oldest
 * trip first, `research_reserve(name, bytes)`, then one plain POST of exactly those bytes to Storage.
 *
 * A trip goes only if recorded with research on ([ResearchQueue]) under the current opt-in (research id), ended (files
 * quiet [SETTLE_MS]; one the app died in after [STALE_MS], unless it started by itself), not held for "Was this a
 * drive?", with no file being written, and long enough: its ends are cut off first ([ResearchTrim]). Reserve: "ok"
 * uploads; "full" / "device_daily" pause the queue [PAUSE_MS]; "no_consent" or a dead session (never a new device)
 * switch research off here; 22023 drops the file. Upload: 200 / 409 done; 403 reserves once more, then pauses the queue
 * (a file refused [MAX_FAILS] times is dropped); 413 drops; 401 refreshes once; 5xx or offline backs off. A file over
 * [MAX_FILE_BYTES] (never seen: a 10-minute segment is ~5 MB) is skipped and logged.
 */
object ResearchUploader {
    private const val TAG = "BumpBeeper"
    private const val ID_NOW = 4109
    private const val ID_PERIODIC = 4110
    const val PERIOD_MS = 6 * 60 * 60_000L
    const val PAUSE_MS = 24 * 60 * 60_000L
    /** The server's limit per file (#108). */
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    /** A trip's files are taken once none changed for this long (the last one is closed after the trip). */
    const val SETTLE_MS = 60_000L
    /** A trip without a clean end (the app was killed) is taken once none of its files changed for this long. */
    const val STALE_MS = 12 * 60 * 60_000L
    /** Unexpected answers for one file before it is dropped (offline and 5xx don't count). */
    private const val MAX_FAILS = 5

    /** Set when JobScheduler stops the job (Wi-Fi gone, battery low): the run stops before its next file. */
    @Volatile internal var stopRequested = false
    private val running = AtomicBoolean(false)

    /** What Settings shows: files and bytes still waiting, the last upload, a pause and why, the Research IDs used. */
    class Status(val files: Int, val bytes: Long, val lastUpload: Long, val pausedUntil: Long, val pausedWhy: String, val ids: List<String>)

    @Volatile var status = Status(0, 0L, 0L, 0L, "", emptyList())
        private set

    // ---------------------------------------------------------------- scheduling (any thread)

    /** After a trip, a "Yes, I drove" or the server taking "on": upload soon, once there is an unmetered network. */
    fun scheduleNow(ctx: Context) {
        if (!Prefs.researchRecording(ctx)) return
        schedule(ctx, job(ctx, ID_NOW).setMinimumLatency(2 * SETTLE_MS)
            .setBackoffCriteria(5 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        ensurePeriodic(ctx)
    }

    /** About every 6 hours while research is on (kept across reboots); cancelled once it is off. */
    fun ensurePeriodic(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (!Prefs.researchRecording(ctx)) { js.cancel(ID_PERIODIC); js.cancel(ID_NOW) }
            else if (js.getPendingJob(ID_PERIODIC) == null) schedule(ctx, job(ctx, ID_PERIODIC).setPeriodic(PERIOD_MS).setPersisted(true).build())
        } catch (e: Exception) {
            Log.w(TAG, "research upload job: ${e.javaClass.simpleName}")
        }
    }

    private fun job(ctx: Context, id: Int) = JobInfo.Builder(id, ComponentName(ctx, SyncJob::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(false)
        .setExtras(PersistableBundle().apply { putInt(ResearchConsent.EXTRA_JOB, ResearchConsent.JOB_UPLOAD) })

    private fun schedule(ctx: Context, job: JobInfo) {
        try {
            ctx.getSystemService(JobScheduler::class.java)?.schedule(job)
        } catch (e: Exception) {
            Log.w(TAG, "research upload not scheduled: ${e.javaClass.simpleName}")
        }
    }

    /** Settings: recount what waits, in the background. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        Thread({ publish(app) }, "research-status").start()
    }

    // ---------------------------------------------------------------- one run (SyncJob thread)

    /**
     * One upload run. True = retry later (offline, server busy). [metered] is asked again before every file; the
     * transports, the clock and the size limit are swapped in tests.
     */
    fun run(
        ctx: Context, transport: Transport = UrlTransport, files: FileTransport = StorageTransport,
        now: Long = System.currentTimeMillis(), metered: () -> Boolean = { isMetered(ctx) }, maxBytes: Long = MAX_FILE_BYTES,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false   // the run already going does it
        try {
            stopRequested = false
            // A choice not told yet goes first: nothing uploads before the server has "on".
            if (ResearchConsent.pending(ctx) && ResearchConsent.run(ctx, transport)) return true
            if (!ResearchConsent.active(ctx)) return false
            ResearchQueue.tidy(ctx, now)   // also finishes a `.part` a killed app left (untouched for a minute)
            // Unreadable, or not saved at the last opt-in: nothing counts as current, so no clean-up and no upload.
            val id = ResearchFiles.storedId(ctx) ?: return false
            deleteWithdrawn(ctx, id)
            if (now < ResearchQueue.read(ctx) { it.pausedUntil } || metered()) return false
            // Never a new device: a dead session takes this phone's consent with it ([failed]).
            val auth = SupabaseAuth(ctx, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, transport, mayCreate = false)
            val api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, auth, transport)
            for (stamp in trips(ctx)) {
                for (item in items(ctx, stamp, id, now)) {
                    if (stopRequested || metered() || !ResearchConsent.active(ctx)) return stopRequested
                    val uid = try {
                        auth.accessToken()
                        auth.userId?.lowercase(Locale.US)
                    } catch (e: ApiException) {
                        return failed(ctx, e) == Next.RETRY
                    } ?: return true
                    when (send(ctx, api, files, uid, item, now, maxBytes)) {
                        Next.NEXT -> continue
                        Next.STOP -> return false
                        Next.RETRY -> return true
                    }
                }
            }
            return false
        } finally {
            running.set(false)
            publish(ctx)
        }
    }

    private class Item(val name: String, val src: File, val window: ResearchTrim.Window, val stats: ResearchTrim.Stats?)

    private enum class Next { NEXT, STOP, RETRY }

    /** Trip stamps with files here, oldest first. */
    private fun trips(ctx: Context): List<String> =
        (ResearchFiles.dir(ctx).list() ?: emptyArray()).mapNotNull { ResearchFiles.stampOf(it) }.distinct().sorted()

    private fun idOf(f: File) = ResearchFiles.parse(f.name.removeSuffix(ResearchWriter.PART))?.id

    /** Another research id = recorded before an "off" (each "on" gets a new id): withdrawn, never uploaded, deleted. */
    private fun deleteWithdrawn(ctx: Context, id: String) =
        ResearchFiles.dir(ctx).listFiles { f -> idOf(f).let { it != null && it != id } }?.forEach { it.delete() }

    /** The files of trip [stamp] that may go now (see the class comment); finds and keeps its trim window once. */
    private fun items(ctx: Context, stamp: String, id: String, now: Long): List<Item> {
        val files = ResearchFiles.dir(ctx).listFiles { f -> ResearchFiles.stampOf(f.name) == stamp } ?: return emptyList()
        if (files.any { idOf(it) != id }) return emptyList()   // an earlier opt-in's file not deleted: still never
        val q = ResearchQueue.read(ctx) { ResearchQueue.State(JSONObject(it.json.toString())) }   // a snapshot
        val t = q.trip(stamp) ?: return emptyList()   // recorded without research consent: never
        if (t.has("skip") || files.isEmpty() || files.any { it.name.endsWith(ResearchWriter.PART) }) return emptyList()
        val quiet = now - files.maxOf { it.lastModified() }
        if (t.optBoolean("ended")) {
            if (quiet < SETTLE_MS) return emptyList()   // its last file may still be closing
        } else {
            if (quiet < STALE_MS) return emptyList()    // still recording
            if (t.optBoolean("guessed")) { ResearchQueue.edit(ctx) { it.skip(stamp, "unended") }; return emptyList() }   // never asked
        }
        if (TripHold.mustHold(ctx, t.optLong("id", -1L))) return emptyList()   // "Was this a drive?" not answered yet
        val segments = files.sortedBy { ResearchFiles.parse(it.name)?.seg ?: Int.MAX_VALUE }
        var stats: List<ResearchTrim.Stats>? = null
        val w = q.window(stamp) ?: ResearchTrim.scan(segments).let { scan ->
            stats = scan.stats
            val w = ResearchTrim.window(scan)
            val why = if (!scan.known) "format" else "short"
            ResearchQueue.edit(ctx) { if (w == null) it.skip(stamp, why) else it.setWindow(stamp, w) }
            w
        } ?: return emptyList()   // shorter than 600 m (or unreadable): nothing of it uploads
        return segments.mapIndexedNotNull { i, f -> if (q.waiting(f.name)) Item(f.name, f, w, stats?.get(i)) else null }
    }

    /** Trims (if needed), reserves and uploads one file. */
    private fun send(ctx: Context, api: SupabaseApi, files: FileTransport, uid: String, item: Item, now: Long, maxBytes: Long): Next {
        val body = try {
            ResearchTrim.prepare(item.src, item.window, item.stats, ResearchQueue.cacheDir(ctx))
        } catch (e: IOException) {
            Log.w(TAG, "research: ${item.name} not prepared: ${e.javaClass.simpleName}")
            return Next.RETRY
        } ?: return drop(ctx, item.name, "empty")   // nothing of it inside the window
        val bytes = body.length()
        if (bytes > maxBytes) return drop(ctx, item.name, "too_big ($bytes bytes)")
        val name = "$uid/${item.name}"
        var again = false
        while (true) {
            if (again && !ResearchConsent.active(ctx)) return Next.STOP   // switched off meanwhile: no second reservation
            val answer = try {
                api.rpc("research_reserve", JSONObject().put("name", name).put("bytes", bytes)).trim().removeSurrounding("\"")
            } catch (e: ApiException) {
                return if (e.outcome == Outcome.DROP) drop(ctx, item.name, "invalid") else failed(ctx, e)
            }
            when (answer) {
                "ok" -> {}
                "full", "device_daily" -> return pause(ctx, now, answer)
                "no_consent" -> { ResearchConsent.sessionReset(ctx); return Next.STOP }
                else -> return Next.RETRY
            }
            if (stopRequested) return Next.RETRY   // the job lost its network or the battery is low
            val code = try {
                api.upload("research", name, body, bytes, "application/gzip", files)
            } catch (e: ApiException) {
                return failed(ctx, e)
            }
            when {
                code in 200..299 || code == 409 -> {   // 409: stored by an earlier try
                    ResearchQueue.edit(ctx) { it.uploaded(item.name, now); it.lastUpload = now; it.addId(uid) }
                    if (body != item.src) body.delete()
                    return Next.NEXT
                }
                code == 403 && !again -> again = true   // reserve once more, then try once more
                code == 413 -> return drop(ctx, item.name, "http 413")
                code == 401 || code >= 500 -> return Next.RETRY
                // Refused twice is a server rule, not this file: the queue pauses a day. Given up after MAX_FAILS.
                ResearchQueue.edit(ctx) { it.failed(item.name) } >= MAX_FAILS -> return drop(ctx, item.name, "http $code")
                else -> return if (code == 403) pause(ctx, now, "refused") else Next.RETRY
            }
        }
    }

    /** The queue waits [PAUSE_MS] ([why] shows in Settings); nothing is deleted. */
    private fun pause(ctx: Context, now: Long, why: String) =
        Next.STOP.also { ResearchQueue.edit(ctx) { s -> s.pausedUntil = now + PAUSE_MS; s.pausedWhy = why } }

    /** A call failed: a dead session (we never sign in anew) takes this phone's consent with it; else try later. */
    private fun failed(ctx: Context, e: ApiException) =
        if (e.outcome != Outcome.AUTH) Next.RETRY else Next.STOP.also { ResearchConsent.sessionReset(ctx) }

    /** Never to be uploaded: marked, its trimmed copy deleted, and logged. */
    private fun drop(ctx: Context, name: String, why: String): Next {
        ResearchQueue.edit(ctx) { it.drop(name, why) }
        File(ResearchQueue.cacheDir(ctx), name).delete()
        Log.w(TAG, "research: $name not uploaded: $why")
        return Next.NEXT
    }

    private fun isMetered(ctx: Context): Boolean = ctx.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private fun publish(ctx: Context) {
        try {
            val (n, b) = ResearchQueue.waiting(ctx)
            status = ResearchQueue.read(ctx) { Status(n, b, it.lastUpload, it.pausedUntil, it.pausedWhy, it.ids()) }
        } catch (e: Exception) {
            Log.w(TAG, "research status: ${e.javaClass.simpleName}")
        }
    }
}
