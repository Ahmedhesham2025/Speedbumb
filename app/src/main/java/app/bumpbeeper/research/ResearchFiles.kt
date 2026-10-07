package app.bumpbeeper.research

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import app.bumpbeeper.CsvExport
import app.bumpbeeper.R
import app.bumpbeeper.TraceWriter
import java.io.File
import java.io.OutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater

/**
 * The research recording's files on this phone, in noBackupFilesDir/research. They never go into a backup or a move to
 * a new phone: they hold GPS tracks, and they would blow Android's 25 MB backup quota (the bump database and settings
 * would then not be backed up at all). Finished segments are `rr_<research id>_<start UTC>_<seg>.csv.gz`; the one
 * being written ends in `.part`. Kept at most 14 days and 2 GB, whichever is smaller; the oldest go first, except that
 * a file still waiting for its upload is never pruned for size, and an uploaded one goes 7 days after its upload
 * ([tidy]). The limits are applied before each new file, at app start and after each trip, also when research is off.
 */
object ResearchFiles {
    private const val TAG = "BumpBeeper"
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_AGE_MS = 14 * 24 * 60 * 60_000L
    /** [share] leaves its zip in public Downloads (file managers, a PC; the 14-day limit doesn't reach it): say so on screen. */
    const val SHARE_KEEPS_COPY = true
    const val SHARE_COPY_FOLDER = "Downloads/BumpBeeper/research"
    /** Kept on the phone this long after a successful upload (for sharing), then deleted. */
    const val UPLOADED_KEEP_MS = 7 * 24 * 60 * 60_000L
    private const val ID_FILE = "research_id"
    /** A `.part` file untouched this long is a leftover of a killed app (the writer flushes every 2 s). */
    private const val PART_STALE_MS = 60_000L
    private val HEX8 = Regex("[0-9a-f]{8}")
    private val NAME = Regex("rr_([0-9a-f]{8})_([0-9]{8}T[0-9]{6})_([0-9]{1,4})\\.csv\\.gz")
    private val ui = Handler(Looper.getMainLooper())

    fun dir(ctx: Context): File = File(ctx.noBackupFilesDir, "research")

    /** `rr_<id>_<yyyyMMdd'T'HHmmss UTC>_<segment, 3 digits>.csv.gz`: a trip's files sort together, in order. */
    fun name(id: String, startUtcMs: Long, seg: Int): String =
        String.format(Locale.US, "rr_%s_%s_%03d.csv.gz", id, stamp(startUtcMs), seg)

    /** The trip part of a file name, `yyyyMMdd'T'HHmmss` in UTC: the same in every segment of one recording. */
    fun stamp(startUtcMs: Long): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(startUtcMs))

    /** A finished file's name taken apart. */
    class Name(val id: String, val stamp: String, val seg: Int)

    /** Null for anything that isn't a finished research file with an 8-hex research id. */
    fun parse(name: String): Name? = NAME.matchEntire(name)?.let { Name(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt()) }

    /** The trip stamp of a research file, finished or still `.part`; null for other files. */
    fun stampOf(fileName: String): String? = parse(fileName.removeSuffix(ResearchWriter.PART))?.stamp

    /**
     * 8 random hex digits naming this phone's research files, used for nothing else. Kept in noBackupFilesDir (a restored
     * backup or a new phone gets a new one) and replaced by [renewId] at each new opt-in, so files from before an opt-out
     * can't be linked to later ones.
     */
    @Synchronized
    fun researchId(ctx: Context): String =
        try { File(ctx.noBackupFilesDir, ID_FILE).readText().trim().takeIf { HEX8.matches(it) } } catch (_: Exception) { null } ?: renewId(ctx)

    /** A new random [researchId]. */
    @Synchronized
    fun renewId(ctx: Context): String {
        val id = String.format(Locale.US, "%08x", SecureRandom().nextInt())
        try { File(ctx.noBackupFilesDir, ID_FILE).writeText(id) } catch (e: Exception) { Log.w(TAG, "research id not saved: $e") }
        return id
    }

    /** Finished files, oldest first (the one being written is left out). */
    fun list(ctx: Context): List<File> = finished(dir(ctx))

    /** Bytes in [list]. */
    fun size(ctx: Context): Long = list(ctx).sumOf { it.length() }

    internal fun finished(dir: File): List<File> =
        (dir.listFiles { f -> f.name.startsWith("rr_") && f.name.endsWith(".csv.gz") } ?: emptyArray())
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))

    /**
     * Applies the limits. `.part` files a killed app left behind (untouched for a minute) get their final names: they
     * read up to their last flush. Then empty files go (the server refuses them), files older than [maxAgeMs], files
     * uploaded ([uploadedAt], 0 = not) more than [UPLOADED_KEEP_MS] ago, and the oldest until at most [maxBytes] are left,
     * skipping files still [waiting] for their upload: those go only at [maxAgeMs] (a phone short of space stops recording
     * instead). Use [ResearchQueue.tidy], which knows the uploads. Returns the names deleted.
     */
    @Synchronized
    internal fun tidy(
        dir: File, nowMs: Long, maxBytes: Long = MAX_BYTES, maxAgeMs: Long = MAX_AGE_MS,
        uploadedAt: (String) -> Long = { 0L }, waiting: (String) -> Boolean = { false },
    ): List<String> {
        dir.listFiles { f -> f.name.startsWith("rr_") && f.name.endsWith(".csv.gz" + ResearchWriter.PART) && nowMs - f.lastModified() > PART_STALE_MS }
            ?.forEach { it.renameTo(File(it.path.removeSuffix(ResearchWriter.PART))) }
        val gone = ArrayList<String>()
        val young = ArrayList<File>()
        var total = 0L
        for (f in finished(dir)) {
            val up = uploadedAt(f.name)
            if (f.length() == 0L || nowMs - f.lastModified() > maxAgeMs || (up > 0 && nowMs - up > UPLOADED_KEEP_MS)) {
                if (f.delete()) gone.add(f.name)
            } else {
                young.add(f); total += f.length()
            }
        }
        for (f in young) {
            if (total <= maxBytes) break
            if (waiting(f.name)) continue
            total -= f.length()
            if (f.delete()) gone.add(f.name)
        }
        return gone
    }

    /**
     * [tidy] in the background (any thread): at app start and after each trip, research recording on or off. Through
     * [ResearchQueue.tidy], so files still waiting for their upload are never pruned for size.
     */
    fun tidyLater(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        Thread({
            try { ResearchQueue.tidy(app, System.currentTimeMillis()) } catch (e: Exception) { Log.w(TAG, "research files not tidied: $e") }
        }, "research-tidy").start()
    }

    /** [files] as one zip, stored as they are: they are gzip already. */
    fun zipTo(files: List<File>, out: OutputStream) = TraceWriter.zipTo(files, out, Deflater.NO_COMPRESSION)

    /** Off the main thread: [files] as one zip in Downloads/BumpBeeper/research. Null on failure. */
    fun zipForShare(ctx: Context, files: List<File> = list(ctx)): Uri? {
        val name = "research_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".zip"
        return CsvExport.saveStream(ctx, name, "research", "application/zip") { zipTo(files, it) }
    }

    /**
     * Shares research files (by default every finished one) as one zip through Android's share sheet, the way
     * [app.bumpbeeper.Sharing.shareRecordings] shares recordings; a copy stays in Downloads ([SHARE_KEEPS_COPY]).
     * Call on the main thread: the files are listed and packed in the background.
     */
    fun share(a: Activity, files: List<File>? = null) {
        Thread {
            val chosen = files ?: list(a)
            val toast = if (chosen.isEmpty()) a.getString(R.string.settings_no_recordings_title) else a.getString(R.string.share_recordings_preparing, chosen.size)
            ui.post { Toast.makeText(a, toast, Toast.LENGTH_SHORT).show() }
            if (chosen.isEmpty()) return@Thread
            val uri = zipForShare(a, chosen)
            ui.post {
                if (a.isFinishing) return@post
                if (uri == null) return@post Toast.makeText(a, a.getString(R.string.share_file_failed), Toast.LENGTH_LONG).show()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, a.getString(R.string.share_subject_recordings))
                    clipData = ClipData.newRawUri("research", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                a.startActivity(Intent.createChooser(send, a.getString(R.string.share_via)))
            }
        }.start()
    }
}
