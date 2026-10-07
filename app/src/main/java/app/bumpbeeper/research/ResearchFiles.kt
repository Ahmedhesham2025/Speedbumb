package app.bumpbeeper.research

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.bumpbeeper.CsvExport
import app.bumpbeeper.R
import app.bumpbeeper.TraceWriter
import app.bumpbeeper.sync.RestoreReset
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
 * would then not be backed up at all). Finished segments are `rr_<install>_<start UTC>_<seg>.csv.gz`; the one being
 * written ends in `.part`. Kept at most 14 days and 2 GB, whichever is smaller; the oldest go first.
 */
object ResearchFiles {
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_AGE_MS = 14 * 24 * 60 * 60_000L
    private const val OWN_ID = "research_id"
    private val HEX8 = Regex("[0-9a-f]{8}")
    private val ui = Handler(Looper.getMainLooper())

    fun dir(ctx: Context): File = File(ctx.noBackupFilesDir, "research")

    /** `rr_<install>_<yyyyMMdd'T'HHmmss UTC>_<segment, 3 digits>.csv.gz`: a trip's files sort together, in order. */
    fun name(install: String, startUtcMs: Long, seg: Int): String {
        val utc = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return String.format(Locale.US, "rr_%s_%s_%03d.csv.gz", install, utc.format(Date(startUtcMs)), seg)
    }

    /**
     * 8 hex digits that tell this install's files apart: the start of the random install id that [RestoreReset] keeps in
     * noBackupFilesDir (a restored backup or a new phone gets a new one). If that can't be read, an id of our own, so
     * restore detection is never touched.
     */
    fun installShort(ctx: Context): String {
        val fromInstallId = try {
            RestoreReset.marker(ctx).readText().replace("-", "").trim().lowercase(Locale.US).take(8)
        } catch (_: Exception) {
            ""
        }
        if (HEX8.matches(fromInstallId)) return fromInstallId
        val own = File(ctx.noBackupFilesDir, OWN_ID)
        try {
            own.readText().trim().takeIf { HEX8.matches(it) }?.let { return it }
        } catch (_: Exception) {}
        val id = String.format(Locale.US, "%08x", SecureRandom().nextInt())
        try { own.writeText(id) } catch (_: Exception) {}
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
     * Before each new segment (writer thread). Files a killed app left as `.part` get their final names (they read up to
     * their last flush); then files older than [maxAgeMs] go, then the oldest until at most [maxBytes] are left.
     */
    internal fun tidy(dir: File, nowMs: Long, maxBytes: Long = MAX_BYTES, maxAgeMs: Long = MAX_AGE_MS) {
        dir.listFiles { f -> f.name.startsWith("rr_") && f.name.endsWith(".csv.gz" + ResearchWriter.PART) }
            ?.forEach { it.renameTo(File(it.path.removeSuffix(ResearchWriter.PART))) }
        val young = ArrayList<File>()
        var total = 0L
        for (f in finished(dir)) {
            if (nowMs - f.lastModified() > maxAgeMs) f.delete() else { young.add(f); total += f.length() }
        }
        for (f in young) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
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
     * [app.bumpbeeper.Sharing.shareRecordings] shares recordings; a copy stays in Downloads/BumpBeeper/research.
     * Call on the main thread: the files are listed and packed in the background.
     */
    fun share(a: Activity, files: List<File>? = null) {
        Thread {
            val chosen = files ?: list(a)
            if (chosen.isEmpty()) {
                ui.post { Toast.makeText(a, a.getString(R.string.settings_no_recordings_title), Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            ui.post { Toast.makeText(a, a.getString(R.string.share_recordings_preparing, chosen.size), Toast.LENGTH_SHORT).show() }
            val uri = zipForShare(a, chosen)
            ui.post {
                if (a.isFinishing) return@post
                if (uri == null) {
                    Toast.makeText(a, a.getString(R.string.share_file_failed), Toast.LENGTH_LONG).show()
                    return@post
                }
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
