package app.bumpbeeper.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.bumpbeeper.R

/**
 * Every hour while auto start is on ([AutoDetect.apply] schedules it): tries to set detection up again (Android allows
 * it from here only in some cases, e.g. battery optimisation off), then, if something still blocks it
 * ([AutoStartHealth]), a quiet "Auto start is paused" notification that opens the fix. At most once per 12 h for the
 * same problem; it disappears once auto start is ready again.
 */
class AutoStartCheck : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        run(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 4201
        private const val CHANNEL = "autostart_health"
        private const val NOTIF_ID = 3
        private const val PERIOD_MS = 60 * 60 * 1000L
        private const val REPEAT_MS = 12 * 60 * 60 * 1000L

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            try {
                if (js.getPendingJob(JOB_ID) != null) return
                js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoStartCheck::class.java))
                    .setPeriodic(PERIOD_MS).setPersisted(false).build())
            } catch (_: Exception) {}
        }

        fun cancel(ctx: Context) {
            try { ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) } catch (_: Exception) {}
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }

        /** One check (any thread): re-arm, then notify about the first problem that blocks auto start, if any. */
        fun run(ctx: Context, now: Long = System.currentTimeMillis()) {
            if (!AutoDetect.enabled(ctx)) return cancel(ctx)
            try { AutoDetect.apply(ctx) } catch (_: Exception) {}
            val problem = AutoStartHealth.check(ctx)?.firstOrNull { it.blocks }
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val sp = ctx.getSharedPreferences("autostart_health", Context.MODE_PRIVATE)
            if (problem == null) {
                nm.cancel(NOTIF_ID)
                sp.edit().clear().apply()
                return
            }
            if (!due(sp.getString("p", null), sp.getLong("at", 0L), problem.name, now)) return
            sp.edit().putString("p", problem.name).putLong("at", now).apply()
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.autostart_channel),
                NotificationManager.IMPORTANCE_LOW))
            val tap = PendingIntent.getActivity(ctx, NOTIF_ID, AutoStartHealth.fix(ctx, problem),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bump)
                .setContentTitle(ctx.getString(R.string.autostart_paused_title))
                .setContentText(ctx.getString(problem.text))
                .setStyle(Notification.BigTextStyle().bigText(ctx.getString(problem.text)))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build())
        }

        /** Notify again for a new problem at once, for the same one after [REPEAT_MS]. */
        internal fun due(lastProblem: String?, lastAt: Long, problem: String, now: Long): Boolean =
            lastProblem != problem || now - lastAt >= REPEAT_MS || now < lastAt
    }
}
