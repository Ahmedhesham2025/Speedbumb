package app.bumpbeeper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Saves files into the phone's Downloads/BumpBeeper folder (no storage permission needed). */
object CsvExport {
    private const val TAG = "BumpBeeper"

    fun save(ctx: Context, fileName: String, content: String): Boolean = saveUri(ctx, fileName, content) != null

    /** Like [save], but returns the new file's address so it can be shared. Null on failure. */
    fun saveUri(ctx: Context, fileName: String, content: String, subDir: String = "", mime: String = "text/csv"): Uri? =
        write(ctx, fileName, subDir, mime) { it.write(content.toByteArray(Charsets.UTF_8)) }

    /** Copies a (possibly large) file into Downloads/BumpBeeper/[subDir]. Null on failure. */
    fun saveFile(ctx: Context, fileName: String, source: File, subDir: String): Uri? =
        write(ctx, fileName, subDir, "text/csv") { out -> source.inputStream().use { it.copyTo(out, 64 * 1024) } }

    /** Writes whatever [body] produces (a zip, say) into Downloads/BumpBeeper/[subDir]. Null on failure. */
    fun saveStream(ctx: Context, fileName: String, subDir: String, mime: String, body: (OutputStream) -> Unit): Uri? =
        write(ctx, fileName, subDir, mime, body)

    /**
     * "trace.csv" → "trace_2.csv" for the second try, and so on. Some phones (Android 10, some Samsungs) refuse a
     * name that is already taken in the folder instead of numbering it themselves.
     */
    fun altName(fileName: String, attempt: Int): String {
        if (attempt <= 0) return fileName
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) "${fileName}_${attempt + 1}" else "${fileName.substring(0, dot)}_${attempt + 1}${fileName.substring(dot)}"
    }

    private fun write(ctx: Context, fileName: String, subDir: String, mime: String, body: (OutputStream) -> Unit): Uri? {
        val resolver = ctx.contentResolver
        val dir = Environment.DIRECTORY_DOWNLOADS + "/BumpBeeper" + if (subDir.isEmpty()) "" else "/$subDir"
        var uri: Uri? = null
        for (attempt in 0 until 4) {
            // IS_PENDING hides the file while it is written, then publishes it with its final size. Without it,
            // some file managers (Samsung My Files) show nothing, or an empty file, until the media scanner comes by.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, altName(fileName, attempt))
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = try {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                Log.w(TAG, "export: could not create $dir/${altName(fileName, attempt)}", e)
                null
            }
            if (uri != null) break
        }
        val target = uri ?: return null
        return try {
            (resolver.openOutputStream(target, "w") ?: throw IOException("no output stream for $target")).use(body)
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            target
        } catch (e: Exception) {
            Log.w(TAG, "export of $fileName failed", e)
            // Don't leave a hidden, half-written file behind.
            try { resolver.delete(target, null, null) } catch (_: Exception) {}
            null
        }
    }
}
