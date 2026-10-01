package app.bumpbeeper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream

/** Saves files into the phone's Downloads/BumpBeeper folder (no storage permission needed). */
object CsvExport {
    fun save(ctx: Context, fileName: String, content: String): Boolean = saveUri(ctx, fileName, content) != null

    /** Like [save], but returns the new file's address so it can be shared. Null on failure. */
    fun saveUri(ctx: Context, fileName: String, content: String, subDir: String = "", mime: String = "text/csv"): Uri? =
        write(ctx, fileName, subDir, mime) { it.write(content.toByteArray(Charsets.UTF_8)) }

    /** Copies a (possibly large) file into Downloads/BumpBeeper/[subDir]. */
    fun saveFile(ctx: Context, fileName: String, source: File, subDir: String): Boolean =
        write(ctx, fileName, subDir, "text/csv") { out -> source.inputStream().use { it.copyTo(out, 64 * 1024) } } != null

    private fun write(ctx: Context, fileName: String, subDir: String, mime: String, body: (OutputStream) -> Unit): Uri? {
        return try {
            val dir = Environment.DIRECTORY_DOWNLOADS + "/BumpBeeper" + if (subDir.isEmpty()) "" else "/$subDir"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val out = resolver.openOutputStream(uri) ?: return null
            out.use(body)
            uri
        } catch (e: Exception) {
            Log.w("BumpBeeper", "export failed", e)
            null
        }
    }
}
