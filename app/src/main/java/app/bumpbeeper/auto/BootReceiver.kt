package app.bumpbeeper.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * After a reboot or an app update, detection is gone (Google's transition requests and our service alike): set it up
 * again without the app being opened. Not exported: these system broadcasts still arrive, other apps can't send them.
 *
 * What may start from here: BOOT_COMPLETED and MY_PACKAGE_REPLACED are exempt from Android 12's ban on starting a
 * foreground service from the background, and Android 15's boot restriction covers other service types (data sync,
 * camera, media, phone, microphone), not location. A location service started in the background needs "Allow all the
 * time" on Android 14+, which [AutoDetect.apply] requires before it starts anything. So with the switch on and that
 * permission, the watching service (significant motion, then a GPS check) runs again right after the restart, and on
 * the play edition Google's in-vehicle transitions are registered again (they can start a trip on their own).
 * If Android still refuses (a phone maker's "Autostart" off, battery Restricted), it is logged, and the hourly
 * [AutoStartCheck] (a persisted job, it survives the restart) shows "Auto start is paused" with the fix.
 * LOCKED_BOOT_COMPLETED is not used: before the first unlock the settings and permissions can't be read (the app isn't
 * direct-boot aware), and BOOT_COMPLETED follows at the unlock.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> rearm(ctx)
        }
    }

    companion object {
        /** Tests: what re-arming does (the real one: [AutoDetect.apply]). */
        @Volatile internal var apply: (Context) -> Boolean = { AutoDetect.apply(it) }

        /** Sets detection up again; never throws (a crash here would cost the next boot's chance too). */
        internal fun rearm(ctx: Context) {
            if (!AutoDetect.enabled(ctx)) return
            try {
                AutoStartCheck.schedule(ctx)
                // A refusal is logged where it happens (BumpService.watch); only a working start is "re-armed".
                if (apply(ctx)) StartLog.add(ctx, StartLog.Reason.BOOT_REARMED)
            } catch (e: Exception) {
                Log.w("BumpBeeper", "auto start not re-armed after restart", e)
                StartLog.add(ctx, StartLog.Reason.START_REFUSED)
            }
        }
    }
}
