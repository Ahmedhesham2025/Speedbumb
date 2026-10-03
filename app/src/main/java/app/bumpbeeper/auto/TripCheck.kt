package app.bumpbeeper.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import app.bumpbeeper.R

/**
 * "Was this a drive?" after a trip that started by itself (motion detection or Google, #49): it may have been a bus,
 * a train or a bike. Until the user answers, everything that would leave the phone is held ([TripHold]).
 *  - Yes, I drove: the held data is sent as usual.
 *  - No: the held data, the trip, its events and the spots only it found are deleted.
 *  - No answer within 24 h: the held data is deleted unsent, the trip stays on the phone; the question disappears.
 * A trip during which the car's Bluetooth connected counts as confirmed and is never asked about.
 */
object TripCheck {
    private const val CHANNEL = "trip_check"
    private const val ACTION_YES = "app.bumpbeeper.TRIP_YES"
    private const val ACTION_NO = "app.bumpbeeper.TRIP_NO"
    private const val EXTRA_TRIP = "trip_id"

    fun notificationId(tripId: Long): Int = 1000 + (tripId % 1000).toInt()

    /** Any thread. Posts the question for [tripId]. */
    fun ask(ctx: Context, tripId: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Was this a drive?", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "After a trip that started by itself: was it you driving?"
            }
        )
        fun button(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, Answer::class.java).setAction(action).putExtra(EXTRA_TRIP, tripId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val code = notificationId(tripId) * 2
        val icon = Icon.createWithResource(ctx, R.drawable.ic_stat_bump)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bump)
            .setContentTitle("Was this a drive?")
            .setContentText("A trip was recorded by itself. Were you driving?")
            .addAction(Notification.Action.Builder(icon, "Yes, I drove", button(ACTION_YES, code)).build())
            .addAction(Notification.Action.Builder(icon, "No", button(ACTION_NO, code + 1)).build())
            .setTimeoutAfter(TripHold.MAX_AGE_MS)
            .setAutoCancel(true)
            .build()
        try { nm.notify(notificationId(tripId), n) } catch (_: SecurityException) {}
    }

    /** Background thread: apply the answer ([TripHold]). Internal for tests. */
    internal fun answer(ctx: Context, tripId: Long, drove: Boolean) {
        TripHold.installBuiltIns()
        TripHold.installTraining(ctx)
        if (drove) TripHold.confirm(ctx, tripId) else TripHold.reject(ctx, tripId)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(tripId))
    }

    /** The notification's Yes / No buttons. The database work runs off the main thread. */
    class Answer : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val tripId = intent.getLongExtra(EXTRA_TRIP, -1L)
            val drove = when (intent.action) {
                ACTION_YES -> true
                ACTION_NO -> false
                else -> return
            }
            if (tripId < 0) return
            val app = ctx.applicationContext
            val done = goAsync()
            Thread({
                try {
                    answer(app, tripId, drove)
                } catch (e: Exception) {
                    Log.w("BumpBeeper", "trip answer not saved", e)
                } finally {
                    done.finish()
                }
            }, "trip-check").start()
        }
    }
}
