package app.bumpbeeper.crash

import android.content.Context
import android.os.Build
import app.bumpbeeper.TraceWriter
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a crash to `files/crash/crash_<time>.txt` (app version, device, stack trace), then lets Android
 * handle it as usual. Stays on the phone; nothing is sent anywhere. The last [KEEP] crashes are kept.
 */
object CrashLog {
    const val KEEP = 10

    @Volatile private var installed = false

    /** Call early (Application/Activity/Service onCreate). Safe to call more than once. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
            } catch (_: Throwable) {
                // Never let the crash log hide the real crash.
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, "crash")

    /** Saved crash files, newest first. */
    fun list(ctx: Context): List<File> =
        (dir(ctx).listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.name }

    private fun write(ctx: Context, thread: Thread, error: Throwable) {
        val d = dir(ctx)
        d.mkdirs()
        val now = Date()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(now)
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(now)).append('\n')
            append("app_version=").append(TraceWriter.appVersion(ctx)).append('\n')
            append("device=").append(Build.MANUFACTURER).append('/').append(Build.MODEL).append('\n')
            append("android=").append(Build.VERSION.SDK_INT).append('\n')
            append("thread=").append(thread.name).append('\n')
            append('\n')
            append(sw.toString())
        }
        File(d, "crash_$stamp.txt").writeText(text)
        prune(ctx)
    }

    private fun prune(ctx: Context) {
        val files = list(ctx)
        if (files.size > KEEP) files.drop(KEEP).forEach { it.delete() }
    }
}
