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
 * handle it as usual. The last [KEEP] crashes are kept. Only when the user shares bumps with the online map,
 * the sync sends each file (app version, device model, stack trace) to the backend and then deletes it.
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

    // A decimal with 4+ digits after the point (dot or comma) looks like a coordinate (4 decimals ≈ 11 m).
    private val COORDINATE = Regex("""-?\d+[.,]\d{4,}""")
    // Two or more /segments: a file path (it can carry a trace or export name, i.e. a date and place).
    private val PATH = Regex("""(?:/[^\s/:()\[\]{}"',;<>]+){2,}/?""")

    /**
     * The crash text as it may leave the phone: anything that looks like a position or a file path is replaced,
     * because exception messages can quote a GPS fix or a recording's file name. Class and method names stay.
     */
    fun scrub(text: String): String = text.replace(PATH, "<path>").replace(COORDINATE, "<num>")

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
