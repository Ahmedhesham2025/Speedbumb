package app.bumpbeeper.ui

import android.content.Context
import app.bumpbeeper.R
import app.bumpbeeper.Ui
import app.bumpbeeper.auto.AutoDetect
import app.bumpbeeper.sync.TrainingConsent

/** Words for Settings → "Start recording when I drive", auto-stop and "Help improve detection". */
object AutoDetectText {

    fun statusRes(s: AutoDetect.Status): Int = when (s) {
        AutoDetect.Status.OFF -> R.string.auto_status_off
        AutoDetect.Status.NEEDS_PERMISSION -> R.string.auto_status_needs_permission
        AutoDetect.Status.GOOGLE_ACTIVITY -> R.string.auto_status_google
        AutoDetect.Status.MOTION_SENSOR -> R.string.auto_status_motion
        AutoDetect.Status.PASSIVE_ONLY -> R.string.auto_status_passive
        AutoDetect.Status.NOT_RUNNING -> R.string.auto_status_not_running
    }

    /** The status line, plus "Watching for driving" while the service waits for a drive. */
    fun status(ctx: Context, s: AutoDetect.Status, watching: Boolean): String {
        val line = ctx.getString(statusRes(s))
        return if (watching && s != AutoDetect.Status.OFF) line + "\n" + ctx.getString(R.string.auto_status_watching) else line
    }

    fun color(s: AutoDetect.Status): Int = when (s) {
        AutoDetect.Status.OFF -> Ui.DIM
        AutoDetect.Status.NEEDS_PERMISSION, AutoDetect.Status.NOT_RUNNING, AutoDetect.Status.PASSIVE_ONLY -> Ui.ORANGE
        else -> Ui.GREEN
    }

    /** "Fix" walks through the permissions again (NOT_RUNNING: often a battery restriction; the walk ends in apply). */
    fun showFix(s: AutoDetect.Status): Boolean = s == AutoDetect.Status.NEEDS_PERMISSION || s == AutoDetect.Status.NOT_RUNNING

    /** The auto-stop slider's label (0 = never). */
    fun autoStop(ctx: Context, minutes: Int): String =
        if (minutes <= 0) ctx.getString(R.string.auto_stop_never) else ctx.getString(R.string.auto_stop_after, minutes)

    /** Status lines under "Help improve detection": (dim info, orange problem); "" = hide. */
    fun training(ctx: Context, st: TrainingConsent.Status): Pair<String, String> {
        val info = when {
            st.enabled && st.serverPending -> ctx.getString(R.string.train_status_pending_on)
            !st.enabled && st.serverPending -> ctx.getString(R.string.train_status_pending_off)
            st.enabled && st.queued > 0 -> ctx.getString(R.string.train_status_queued, st.queued)
            else -> ""
        }
        val problem = when {
            st.lastError == TrainingConsent.SESSION_RESET -> if (st.enabled) "" else ctx.getString(R.string.train_session_reset)
            st.lastError.isNotEmpty() && st.enabled -> ctx.getString(R.string.train_error_other)
            else -> ""
        }
        return info to problem
    }
}
