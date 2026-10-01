package app.bumpbeeper

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/** Saves a text file into the phone's Downloads/BumpBeeper folder (no storage permission needed). */
object CsvExport {
    fun save(ctx: Context, fileName: String, content: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BumpBeeper")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            val out = resolver.openOutputStream(uri) ?: return false
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            Log.w("BumpBeeper", "export failed", e)
            false
        }
    }
}
