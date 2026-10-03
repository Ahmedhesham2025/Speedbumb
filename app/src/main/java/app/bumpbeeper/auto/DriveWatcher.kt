package app.bumpbeeper.auto

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Waits for a drive with built-in Android parts only (#49; the foss edition, and play without Google services).
 * Runs inside BumpService while it is in the foreground with the quiet "ready to detect driving" notification:
 *
 *  - The significant-motion sensor (a hardware trigger that costs next to nothing) wakes us when the phone moves.
 *  - Then a short GPS check (2 s fixes, at most 3.5 min per window, under a wake lock) asks [DriveDetector] whether
 *    it is a drive: 20 km/h or more for 60 s. Walking around ends the check and backs off.
 *  - Fixes other apps ask for (the passive provider, free) at driving speed start a check too; on phones without a
 *    significant-motion sensor they are, with the car's Bluetooth, the only trigger.
 *
 * Main thread only. [onDriving] is called once per detected drive; the owner then starts recording and stops this.
 * The Android parts sit behind [Hardware], so tests can drive the whole lifecycle.
 */
class DriveWatcher(
    private val hw: Hardware,
    private val onDriving: () -> Unit,
    private val detector: DriveDetector = DriveDetector(),
) {
    constructor(ctx: Context, onDriving: () -> Unit) : this(AndroidHardware(ctx), onDriving)

    /** The sensors, GPS and wake lock this needs. Fix callbacks get (fix time on the elapsedRealtime clock, km/h). */
    interface Hardware {
        fun armMotion(onMotion: () -> Unit)
        fun cancelMotion()
        fun startPassive(onFix: (Long, Double) -> Unit)
        fun stopPassive()
        fun startGps(onFix: (Long, Double) -> Unit)
        fun stopGps()
        fun acquireWake(timeoutMs: Long)
        fun releaseWake()
    }

    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private val tick = object : Runnable {
        override fun run() {
            act(detector.onTimer(SystemClock.elapsedRealtime()))
            if (detector.checking) main.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (active) return
        active = true
        hw.startPassive { t, kmh -> act(detector.onPassiveFix(SystemClock.elapsedRealtime(), t, kmh)) }
        arm()
    }

    fun stop() {
        active = false
        hw.cancelMotion()
        hw.stopPassive()
        endCheck()
    }

    fun snooze(ms: Long) = detector.snooze(SystemClock.elapsedRealtime(), ms)

    val snoozed: Boolean get() = detector.snoozed(SystemClock.elapsedRealtime())

    private fun arm() {
        if (active) hw.armMotion {
            if (active) {
                act(detector.onMotion(SystemClock.elapsedRealtime()))
                arm()   // one-shot sensor: arm it again for the next movement
            }
        }
    }

    private fun act(a: DriveDetector.Action) {
        if (!active) return
        when (a) {
            DriveDetector.Action.START_CHECK -> beginCheck()
            DriveDetector.Action.END_CHECK -> endCheck()
            DriveDetector.Action.DRIVING -> {
                stop()           // once per drive, even if more fixes are already queued
                onDriving()
            }
            DriveDetector.Action.NONE -> {}
        }
    }

    private fun beginCheck() {
        // GPS fixes alone would wake the phone, but the timer must run on time to end the check and free the GPS.
        hw.acquireWake(detector.checkMs * (detector.maxFastRechecks + 1) + detector.gate.sustainMs + 10_000L)
        hw.startGps { t, kmh -> act(detector.onFix(t, kmh)) }
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    private fun endCheck() {
        hw.stopGps()
        main.removeCallbacks(tick)
        hw.releaseWake()
    }

    /** The real phone: significant-motion trigger, passive + GPS location, a partial wake lock. */
    private class AndroidHardware(private val ctx: Context) : Hardware {
        private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        private val motion: Sensor? = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        private var trigger: TriggerEventListener? = null
        private var passive: LocationListener? = null
        private var gps: LocationListener? = null
        private var wake: PowerManager.WakeLock? = null

        override fun armMotion(onMotion: () -> Unit) {
            val s = motion ?: return
            val t = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent?) = onMotion()
            }
            trigger = t
            sm.requestTriggerSensor(t, s)
        }

        override fun cancelMotion() {
            val s = motion ?: return
            trigger?.let { sm.cancelTriggerSensor(it, s) }
            trigger = null
        }

        override fun startPassive(onFix: (Long, Double) -> Unit) {
            stopPassive()
            passive = request(LocationManager.PASSIVE_PROVIDER, 5000L, onFix)
        }

        override fun stopPassive() {
            passive?.let { lm.removeUpdates(it) }
            passive = null
        }

        override fun startGps(onFix: (Long, Double) -> Unit) {
            stopGps()
            gps = request(LocationManager.GPS_PROVIDER, 2000L, onFix)
        }

        override fun stopGps() {
            gps?.let { lm.removeUpdates(it) }
            gps = null
        }

        override fun acquireWake(timeoutMs: Long) {
            releaseWake()
            wake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BumpBeeper:drive-check")
                .apply {
                    setReferenceCounted(false)
                    acquire(timeoutMs)
                }
        }

        override fun releaseWake() {
            wake?.let { if (it.isHeld) it.release() }
            wake = null
        }

        private fun request(provider: String, everyMs: Long, onFix: (Long, Double) -> Unit): LocationListener? {
            val l = Fixes { onFix(it.elapsedRealtimeNanos / 1_000_000, if (it.hasSpeed()) it.speed * 3.6 else Double.NaN) }
            return try {
                lm.requestLocationUpdates(provider, everyMs, 0f, l, Looper.getMainLooper())
                l
            } catch (e: Exception) {
                // No permission or no such provider: a GPS check then ends after DriveDetector.noFixMs.
                Log.w(TAG, "$provider location not available", e)
                null
            }
        }
    }

    /** Android 10 needs all four LocationListener methods implemented. */
    private class Fixes(val f: (Location) -> Unit) : LocationListener {
        override fun onLocationChanged(location: Location) = f(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Android, still called on Android 10")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private companion object {
        const val TAG = "BumpBeeper"
        const val TICK_MS = 10_000L
    }
}
