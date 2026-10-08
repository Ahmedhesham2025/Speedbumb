package app.bumpbeeper.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs

/**
 * The Drive screen's "Offline — shared bumps not updated": while recording with the shared map on, when the last
 * shared-spot download failed or is older than [Sync.SPOTS_STALE_MS], and the phone has no network. A download that
 * succeeds hides it ([LiveState.spotsFailed] cleared, [LiveState.spotsOkAt] fresh). Uses ACCESS_NETWORK_STATE only.
 */
object OfflineBanner {
    /** [online] is asked last, and only when the rest says the spots are out of date. */
    fun show(recording: Boolean, syncOn: Boolean, okAtMs: Long, failed: Boolean, nowMs: Long, online: () -> Boolean): Boolean =
        recording && syncOn && (failed || nowMs - okAtMs > Sync.SPOTS_STALE_MS) && !online()

    fun show(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        show(LiveState.recording, Prefs.syncChoice(ctx) != Prefs.SYNC_UNSET, LiveState.spotsOkAt, LiveState.spotsFailed, nowMs) {
            networkUp(ctx)
        }

    /** A network that can reach the internet. When Android can't tell, true: the banner would only be noise. */
    fun networkUp(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        when {
            cm == null -> true
            net == null -> false
            else -> cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
        }
    } catch (_: Exception) {
        true
    }
}
