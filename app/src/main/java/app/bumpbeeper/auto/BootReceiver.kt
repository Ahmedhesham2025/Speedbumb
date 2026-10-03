package app.bumpbeeper.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or an app update, detection is gone (Google's transition requests and our service alike): set it up
 * again. Both broadcasts may start a location foreground service on Android 12–15. Not exported: these system
 * broadcasts still arrive (the system may deliver to any receiver), other apps can't send them.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> AutoDetect.apply(ctx)
        }
    }
}
