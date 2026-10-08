package app.bumpbeeper.research

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * What the phone knows about uploading its research files, in one small JSON file next to them (noBackupFilesDir:
 * never backed up or moved, like the files themselves):
 *  - per trip (the UTC stamp in its file names): the BumpDb trip id, whether it started by itself, whether it ended
 *    cleanly, the part of it that may leave the phone ([ResearchTrim.Window]), or why none of it may (`skip`);
 *  - per file: uploaded (when), or never to be uploaded (why), and failed attempts;
 *  - the pause after a refused reservation, the last upload, and the Research IDs files went up under.
 * Only trips recorded with research switched on get a record (BumpService, [tripStarted]); files without one are
 * never uploaded. Each change loads, edits and saves the file under [lock], which is never held during network calls.
 */
object ResearchQueue {
    private const val TAG = "BumpBeeper"

    internal val lock = Any()

    fun file(ctx: Context) = File(ctx.noBackupFilesDir, "research_queue.json")

    /** Trimmed copies waiting for their upload ([ResearchTrim.prepare]). */
    fun cacheDir(ctx: Context) = File(ResearchFiles.dir(ctx), "upload")

    internal fun <T> read(ctx: Context, block: (State) -> T): T = synchronized(lock) { block(load(ctx)) }

    internal fun <T> edit(ctx: Context, block: (State) -> T): T = synchronized(lock) {
        val s = load(ctx)
        val r = block(s)
        save(ctx, s)
        r
    }

    // ---------------------------------------------------------------- trips (BumpService, engine thread)

    /** Research recording started for BumpDb trip [tripId]; [guessed]: it started by itself and may be held. */
    fun tripStarted(ctx: Context, stamp: String, tripId: Long, guessed: Boolean, now: Long = System.currentTimeMillis()) =
        quietly("trip start") { edit(ctx) { it.startTrip(stamp, tripId, guessed, now) } }

    /** Trip end, after a trip that started by itself was held (TripHold.hold): from now on it may be uploaded. */
    fun tripEnded(ctx: Context, stamp: String) = quietly("trip end") { edit(ctx) { it.trip(stamp)?.put("ended", true) } }

    /**
     * TripHold: "No", or no answer in time: trip [tripId] is never uploaded and its files are deleted here. Throws if
     * that failed, so TripHold keeps holding the trip (nothing of it is sent) and runs this again later.
     */
    fun discardTrip(ctx: Context, tripId: Long, why: String) {
        val stamps = edit(ctx) { s -> s.stamps().filter { s.trip(it)?.optLong("id", -1L) == tripId }.onEach { s.skip(it, why) } }
        if (stamps.isEmpty()) return
        for (dir in listOf(ResearchFiles.dir(ctx), cacheDir(ctx))) {
            dir.listFiles { f -> ResearchFiles.stampOf(f.name.removeSuffix(".tmp")) in stamps }?.forEach {
                if (!it.delete() && it.exists()) throw IOException("research file of trip $tripId not deleted")
            }
        }
    }

    /** Research switched off, or "Delete my shared data": nothing still waiting is ever uploaded; files stay here. */
    fun withdraw(ctx: Context) = quietly("withdraw") { edit(ctx) { s -> s.stamps().forEach { s.skip(it, "off") } } }

    /** First start after a backup restore: start over (the files it describes never come along with a backup). */
    fun clear(ctx: Context) {
        synchronized(lock) {
            file(ctx).delete()
            cacheDir(ctx).listFiles()?.forEach { it.delete() }
        }
    }

    // ---------------------------------------------------------------- retention, status

    /**
     * [ResearchFiles.tidy] with what this queue knows: uploaded files go 7 days after their upload, files still
     * waiting are never pruned for size. Also deletes trimmed copies and records nothing needs any more. Any thread.
     */
    internal fun tidy(ctx: Context, now: Long, maxBytes: Long = ResearchFiles.MAX_BYTES): List<String> = edit(ctx) { s ->
        val dir = ResearchFiles.dir(ctx)
        val gone = ResearchFiles.tidy(dir, now, maxBytes, uploadedAt = s::uploadedAt, waiting = s::waiting)
        val names = dir.list()?.toSet() ?: emptySet()
        s.forgetFilesExcept(names)
        val stamps = names.mapNotNullTo(HashSet()) { ResearchFiles.stampOf(it) }
        s.forgetTripsExcept(stamps, now)
        cacheDir(ctx).listFiles()?.forEach { if (!s.waiting(it.name.removeSuffix(".tmp"))) it.delete() }
        gone
    }

    /** Files still to be uploaded, and their bytes (Settings). */
    fun waiting(ctx: Context): Pair<Int, Long> = read(ctx) { s ->
        val files = ResearchFiles.list(ctx).filter { s.waiting(it.name) }
        files.size to files.sumOf { it.length() }
    }

    private fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "research queue, $what: ${e.javaClass.simpleName}")
        }
    }

    private fun load(ctx: Context): State {
        val f = file(ctx)
        if (!f.exists()) return State()
        return try {
            State(JSONObject(f.readText()))
        } catch (e: Exception) {
            // Starting over is safe: trips without a record are never uploaded.
            Log.w(TAG, "research queue unreadable, starting over: ${e.javaClass.simpleName}")
            State()
        }
    }

    private fun save(ctx: Context, s: State) {
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(s.json.toString())
        if (!tmp.renameTo(f) && !(f.delete() && tmp.renameTo(f))) throw IOException("research queue not saved")
    }

    /** The queue file's content. Not thread safe: use [read] / [edit]. */
    internal class State(val json: JSONObject = JSONObject()) {
        private fun obj(k: String): JSONObject = json.optJSONObject(k) ?: JSONObject().also { json.put(k, it) }
        private val trips get() = obj("trips")
        private val files get() = obj("files")

        fun stamps(): List<String> = trips.keys().asSequence().toList()
        fun trip(stamp: String): JSONObject? = trips.optJSONObject(stamp)
        fun startTrip(stamp: String, id: Long, guessed: Boolean, at: Long) {
            trips.put(stamp, JSONObject().put("id", id).put("guessed", guessed).put("at", at))
        }
        /** Never uploaded, and why; the first reason stays. */
        fun skip(stamp: String, why: String) {
            trip(stamp)?.let { if (!it.has("skip")) it.put("skip", why) }
        }
        fun window(stamp: String): ResearchTrim.Window? =
            trip(stamp)?.takeIf { it.has("from") && it.has("to") }?.let { ResearchTrim.Window(it.getLong("from"), it.getLong("to")) }
        fun setWindow(stamp: String, w: ResearchTrim.Window) {
            trip(stamp)?.put("from", w.fromT)?.put("to", w.toT)
        }

        fun uploadedAt(name: String): Long = files.optJSONObject(name)?.optLong("up", 0L) ?: 0L
        fun dropped(name: String): String? = files.optJSONObject(name)?.optString("drop", "")?.takeIf { it.isNotEmpty() }
        fun uploaded(name: String, at: Long) { files.put(name, JSONObject().put("up", at)) }
        fun drop(name: String, why: String) { files.put(name, JSONObject().put("drop", why)) }
        /** One more failed attempt for [name]; returns how many so far. */
        fun failed(name: String): Int {
            val f = files.optJSONObject(name) ?: JSONObject().also { files.put(name, it) }
            val n = f.optInt("fail", 0) + 1
            f.put("fail", n)
            return n
        }

        /** Still to be uploaded: its trip has a record and isn't skipped, and it is neither uploaded nor dropped. */
        fun waiting(name: String): Boolean {
            val t = ResearchFiles.parse(name)?.let { trip(it.stamp) } ?: return false
            return !t.has("skip") && uploadedAt(name) == 0L && dropped(name) == null
        }

        fun forgetFilesExcept(names: Set<String>) {
            files.keys().asSequence().toList().filter { it !in names }.forEach { files.remove(it) }
        }

        /** Records without files go once their trip ended (or was skipped), or after 14 days in any case. */
        fun forgetTripsExcept(stamps: Set<String>, now: Long) {
            for (k in stamps()) {
                val t = trip(k) ?: continue
                val over = t.optBoolean("ended") || t.has("skip") || now - t.optLong("at", 0L) > ResearchFiles.MAX_AGE_MS
                if (k !in stamps && over) trips.remove(k)
            }
        }

        var pausedUntil: Long
            get() = json.optLong("paused_until", 0L)
            set(v) { json.put("paused_until", v) }
        var pausedWhy: String
            get() = json.optString("paused_why", "")
            set(v) { json.put("paused_why", v) }
        var lastUpload: Long
            get() = json.optLong("last_upload", 0L)
            set(v) { json.put("last_upload", v) }

        /** Research IDs (anonymous account ids) files were uploaded under: shown in Settings for deletion requests. */
        fun ids(): List<String> = json.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        fun addId(id: String) {
            if (id !in ids()) json.put("ids", (json.optJSONArray("ids") ?: JSONArray()).put(id))
        }
    }
}
