package app.bumpbeeper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Fills in [PhoneSignals] for [PhoneStateDetector] during a recording: screen on/off and unlock (broadcasts), proximity
 * and light (sensors), and every [POLL_MS] the audio mode and route (a hand-held call), the lock screen and charging.
 * No permissions needed; no microphone. BumpService starts it with the trip and stops it at the end.
 * Everything runs on [handler] (the engine thread), so the engine sees the values in order.
 */
internal class PhoneFeed(private val ctx: Context, private val handler: Handler, private val signals: PhoneSignals) : SensorEventListener {

    companion object {
        private const val TAG = "BumpBeeper"
        private const val POLL_MS = 2000L
        /** Outputs a call goes to when it isn't held to the ear (Android 10–11; from 12 the call's own device says it). */
        private val HANDS_FREE = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private var receiving = false
    /** The lock screen was seen up on this trip (a swipe lock is not "secure" but is a lock screen). */
    private var sawLocked = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> signals.screenOn = true
                Intent.ACTION_SCREEN_OFF -> signals.screenOn = false
                // Same clock as the sensor samples (BumpService aligns them to elapsedRealtime).
                Intent.ACTION_USER_PRESENT -> signals.unlockedAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            pollNow()
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** Engine thread, at the trip start. */
    fun start() {
        signals.screenOn = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        try {
            // Exported: USER_PRESENT comes from System UI, which a not-exported receiver would never hear (Android 13+).
            val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ctx.registerReceiver(screen, filter, null, handler, flags)
            receiving = true
        } catch (e: Exception) {
            Log.w(TAG, "screen/unlock not watched: ${e.javaClass.simpleName}")
        }
        // On-change sensors: a reading only when the value changes. Only during trips; the research recorder may listen too.
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        handler.post(poll)
    }

    /** Engine thread, at the trip end: everything off. */
    fun stop() {
        handler.removeCallbacks(poll)
        sm.unregisterListener(this)
        if (receiving) {
            receiving = false
            try { ctx.unregisterReceiver(screen) } catch (_: Exception) {}
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            // Most proximity sensors only say near (0) or far (their maximum range).
            Sensor.TYPE_PROXIMITY -> signals.proximityNear = e.values[0] < minOf(e.sensor.maximumRange, 5f)
            Sensor.TYPE_LIGHT -> signals.lux = e.values[0].toDouble()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun pollNow() {
        try {
            val locked = km.isKeyguardLocked
            signals.keyguardLocked = locked
            sawLocked = sawLocked || locked
            // No lock screen: every screen-on also sends USER_PRESENT, so the detector asks for motion too.
            signals.keyguardPresent = km.isDeviceSecure || sawLocked
            val mode = am.mode
            val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
            signals.handheldCall = inCall && !handsFree()
        } catch (_: Exception) {
            // The audio service can be briefly unavailable on some phones: the next poll tries again.
        }
        // The sticky battery broadcast: read, nothing registered.
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battery = try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(null, filter)
        } catch (_: Exception) {
            null
        }
        if (battery != null) signals.charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** The call goes to Bluetooth, a wire, a hearing aid or the loudspeaker: not held to the ear. */
    @Suppress("DEPRECATION")
    private fun handsFree(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            am.communicationDevice?.let { return it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        if (am.isBluetoothScoOn || am.isSpeakerphoneOn) return true
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HANDS_FREE }
    }
}
