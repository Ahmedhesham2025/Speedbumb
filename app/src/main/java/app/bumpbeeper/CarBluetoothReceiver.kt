package app.bumpbeeper

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Auto start/stop: Android tells us whenever a Bluetooth device connects or disconnects, even while the app is closed.
 * When it is the car chosen in the app: connected → start recording; disconnected → stop (after a short grace period,
 * so a brief Bluetooth drop doesn't end the trip).
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.autoStart(ctx)) return
        val car = Prefs.carAddress(ctx) ?: return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (device?.address != car) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> BumpService.startFromCar(ctx)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (LiveState.recording) BumpService.carDisconnected(ctx)
        }
    }
}
