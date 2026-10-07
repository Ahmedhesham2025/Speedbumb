package app.bumpbeeper.research

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.bumpbeeper.BumpService
import app.bumpbeeper.Prefs
import app.bumpbeeper.TraceWriter
import java.io.File
import kotlin.math.abs

/**
 * Research recording (opt-in, [Prefs.researchRecording], off by default): every useful phone sensor during a trip, at
 * full rate, into local rr1 files ([ResearchFormat], [ResearchFiles]) for tuning the detector and, later, training
 * models. No microphone, no camera, nothing uploaded here. BumpService starts it with the trip and stops it at the end.
 *
 * Everything arrives on its own "research" thread (never the engine thread) and is encoded there; [ResearchWriter]'s
 * thread does the disk work, so a slow disk only costs counted drops. Sensors are registered only while it records.
 */
class ResearchRecorder private constructor(private val ctx: Context, private val source: String) : SensorEventListener {

    companion object {
        private const val TAG = "BumpBeeper"
        /** Activity transitions, see [activity]. */
        const val IN_VEHICLE = 1
        const val ON_FOOT = 2
        /** Don't start (or open another file) with less free space than this. */
        private const val MIN_FREE_BYTES = 500L * 1024 * 1024
        /** Flush, poll the audio state, maybe start a new file. */
        private const val TICK_MS = 2000L
        /** GNSS status comes about once a second; never more often than this. */
        private const val GNSS_EVERY_MS = 950L

        /** A sensor to record and the sampling period asked for (µs; 3 = SensorManager.SENSOR_DELAY_NORMAL). */
        private class Spec(val type: Int, val code: ResearchFormat.Code, val periodUs: Int)

        // 5,000 µs = 200 Hz: the most Android 12+ allows without HIGH_SAMPLING_RATE_SENSORS (asking more throws on debug builds).
        private val SPECS = listOf(
            Spec(Sensor.TYPE_ACCELEROMETER, ResearchFormat.ACCEL, 5_000),
            Spec(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, ResearchFormat.ACCEL_UNCAL, 5_000),
            Spec(Sensor.TYPE_GYROSCOPE, ResearchFormat.GYRO, 5_000),
            Spec(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, ResearchFormat.GYRO_UNCAL, 5_000),
            Spec(Sensor.TYPE_GRAVITY, ResearchFormat.GRAVITY, 10_000),
            Spec(Sensor.TYPE_LINEAR_ACCELERATION, ResearchFormat.LINEAR, 10_000),
            Spec(Sensor.TYPE_ROTATION_VECTOR, ResearchFormat.ROTATION, 10_000),
            Spec(Sensor.TYPE_GAME_ROTATION_VECTOR, ResearchFormat.GAME_ROTATION, 10_000),
            Spec(Sensor.TYPE_MAGNETIC_FIELD, ResearchFormat.MAGNETIC, SensorManager.SENSOR_DELAY_NORMAL),
            Spec(Sensor.TYPE_PROXIMITY, ResearchFormat.PROXIMITY, SensorManager.SENSOR_DELAY_NORMAL),
            Spec(Sensor.TYPE_LIGHT, ResearchFormat.LIGHT, SensorManager.SENSOR_DELAY_NORMAL),
            Spec(Sensor.TYPE_PRESSURE, ResearchFormat.PRESSURE, SensorManager.SENSOR_DELAY_NORMAL),
            // Needs the "physical activity" permission (play edition, when granted); without it Android hides the sensor.
            Spec(Sensor.TYPE_STEP_DETECTOR, ResearchFormat.STEP, SensorManager.SENSOR_DELAY_NORMAL),
        )

        private fun specFor(type: Int): Spec? = SPECS.firstOrNull { it.type == type }

        /**
         * BumpService, main thread, at the trip start: a running recorder if the user switched research recording on,
         * else null (also when storage is short). [source] started the trip (BumpService.SOURCE_*; null = the user).
         */
        fun startIfEnabled(ctx: Context, source: String?): ResearchRecorder? {
            if (!Prefs.researchRecording(ctx)) return null
            val r = try {
                if (ctx.noBackupFilesDir.usableSpace < MIN_FREE_BYTES) {
                    Log.w(TAG, "research recording: storage almost full, not started")
                    return null
                }
                ResearchRecorder(ctx.applicationContext ?: ctx, source ?: "user")
            } catch (e: Exception) {
                Log.w(TAG, "research recording not started", e)
                return null
            }
            return try {
                r.start()
                r
            } catch (e: Exception) {
                Log.w(TAG, "research recording failed to start", e)
                r.stop()
                null
            }
        }
    }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val t0Ns = SystemClock.elapsedRealtimeNanos()
    private val startUtcMs = System.currentTimeMillis()
    private val thread = HandlerThread("research").apply { start() }
    private val h = Handler(thread.looper)
    /** Header lines that are the same in every file of this trip (set in [start], read on the writer thread). */
    @Volatile private var meta: List<String> = emptyList()
    /** Reads a file: first used on the writer thread, when the first file opens. */
    private val install by lazy { ResearchFiles.installShort(ctx) }
    private val writer = ResearchWriter(
        file = { seg -> File(ResearchFiles.dir(ctx), ResearchFiles.name(install, startUtcMs, seg)) },
        header = { seg -> ResearchFormat.header(meta + listOf("install=$install", "segment=$seg")) },
        beforeSegment = { ResearchFiles.tidy(ResearchFiles.dir(ctx), System.currentTimeMillis()) },
        minFreeBytes = MIN_FREE_BYTES,
    )
    @Volatile private var stopped = false
    // Research thread only:
    private var offsetNs = 0L
    private var offsetKnown = false
    private var lastGnssMs = Long.MIN_VALUE / 2
    private var lastAudio = ""
    private var lastBattery = ""
    private val fix = DoubleArray(10)

    private fun t(nowNs: Long = SystemClock.elapsedRealtimeNanos()): Long = (nowNs - t0Ns) / 1_000_000

    // ---------------------------------------------------------------- start / stop (main thread)

    @SuppressLint("MissingPermission")   // a recording always has the location permission; a refusal is caught
    private fun start() {
        val lines = arrayListOf(
            "app_version=${TraceWriter.appVersion(ctx)}",
            "android_sdk=${Build.VERSION.SDK_INT}",
            "device=${Build.MANUFACTURER}/${Build.MODEL}",   // sensor quirks are per model
            "placement=${Prefs.placement(ctx)}",
            "start_source=$source",
            "start_utc_ms=$startUtcMs",
            "start_elapsed_ns=$t0Ns",
        )
        for (s in SPECS) {
            val sensor = sm.getDefaultSensor(s.type)
            val ok = sensor != null && try { sm.registerListener(this, sensor, s.periodUs, h) } catch (e: Exception) { false }
            lines += "sensor.${s.code.code}=" + (if (sensor == null) "absent" else describe(sensor, s.periodUs, ok))
        }
        meta = lines
        try {
            lm.registerGnssStatusCallback(gnss, h)
        } catch (e: Exception) {
            Log.w(TAG, "research recording: no GNSS status (${e.javaClass.simpleName})")
        }
        // Only Android sends these (protected broadcasts). Exported, because USER_PRESENT comes from System UI, which a
        // not-exported receiver would never hear.
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ctx.registerReceiver(screen, screenFilter, null, h, flags)
        ctx.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, h, flags)   // sticky: the level now
        h.post {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            line(ResearchFormat.SCREEN.code, if (pm.isInteractive) 1 else 0)
            if (source == BumpService.SOURCE_CAR) line(ResearchFormat.CAR_BT.code, 1)
            if (source == BumpService.SOURCE_VEHICLE) line(ResearchFormat.ACTIVITY.code, IN_VEHICLE)
        }
        h.post(tick)
        Log.i(TAG, "research recording started (${lines.count { it.startsWith("sensor.") && !it.endsWith("=absent") }} sensors)")
    }

    /** End of the trip (main thread; again is harmless): sensors off at once, the last file finishes in the background. */
    fun stop() {
        if (stopped) return
        stopped = true
        unregister()
        h.post {
            writer.finish(listOf("end_lines=${writer.lines}", "end_dropped=${writer.dropped}"))
            if (writer.dropped > 0) Log.w(TAG, "research recording: ${writer.dropped} lines dropped (storage too slow)")
            thread.quitSafely()
        }
    }

    /** Tests: waits until the last file is closed. */
    internal fun awaitStopped(ms: Long): Boolean {
        thread.join(ms)
        return !thread.isAlive && writer.awaitClosed(ms)
    }

    private fun unregister() {
        sm.unregisterListener(this)
        try { lm.unregisterGnssStatusCallback(gnss) } catch (_: Exception) {}
        for (r in listOf(screen, battery)) try { ctx.unregisterReceiver(r) } catch (_: Exception) {}
        h.removeCallbacks(tick)
    }

    private fun describe(s: Sensor, periodUs: Int, ok: Boolean): String {
        val askedUs = if (periodUs == SensorManager.SENSOR_DELAY_NORMAL) 200_000 else periodUs
        return "${s.name};${s.vendor};v${s.version};res=${s.resolution};max=${s.maximumRange};min_delay_us=${s.minDelay};" +
            "max_delay_us=${s.maxDelay};power_ma=${s.power};fifo=${s.fifoMaxEventCount};wakeup=${s.isWakeUpSensor};" +
            "asked_us=$askedUs" + (if (ok) "" else ";registered=no")
    }

    // ---------------------------------------------------------------- from BumpService (any thread)

    /** Each GPS fix the recording gets: reused here, so research asks for no GPS of its own. */
    fun onLocation(l: Location) {
        if (stopped) return
        val arrivedNs = SystemClock.elapsedRealtimeNanos()
        h.post { writeFix(l, arrivedNs) }
    }

    /** The car's Bluetooth (the car picked for auto start) connected or disconnected. */
    fun carBluetooth(connected: Boolean) = post(ResearchFormat.CAR_BT.code, if (connected) 1 else 0)

    /** A Google activity transition (play edition): [IN_VEHICLE] or [ON_FOOT]. */
    fun activity(kind: Int) = post(ResearchFormat.ACTIVITY.code, kind)

    /** What the driver tapped in label mode. */
    fun label(kind: String) {
        if (stopped) return
        val tMs = t()
        h.post { writer.room()?.start(tMs, ResearchFormat.LABEL.code)?.text(kind)?.end() }
    }

    private fun post(code: String, v: Int) {
        if (stopped) return
        val tMs = t()
        h.post { line(code, v, tMs) }
    }

    // ---------------------------------------------------------------- research thread

    private fun line(code: String, v: Int, tMs: Long = t()) {
        writer.room()?.start(tMs, code)?.int(v)?.end()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val spec = specFor(e.sensor.type) ?: return
        if (!offsetKnown && e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Sensor time should be the elapsedRealtime clock, like GPS. If this phone's isn't, correct once (as BumpService does).
            val diff = SystemClock.elapsedRealtimeNanos() - e.timestamp
            offsetNs = if (abs(diff) < 1_000_000_000L) 0L else diff
            offsetKnown = true
        }
        writer.room()?.sample((e.timestamp + offsetNs - t0Ns) / 1_000_000, spec.code, e.values)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val spec = sensor?.let { specFor(it.type) } ?: return
        writer.room()?.start(t(), ResearchFormat.ACCURACY.code)?.text(spec.code.code)?.int(accuracy)?.end()
    }

    private fun writeFix(l: Location, arrivedNs: Long) {
        val atNs = if (l.elapsedRealtimeNanos > 0) l.elapsedRealtimeNanos else arrivedNs
        val v = fix
        v[0] = l.latitude
        v[1] = l.longitude
        v[2] = if (l.hasAltitude()) l.altitude else Double.NaN
        v[3] = if (l.hasSpeed()) l.speed.toDouble() else Double.NaN
        v[4] = if (l.hasBearing()) l.bearing.toDouble() else Double.NaN
        v[5] = if (l.hasAccuracy()) l.accuracy.toDouble() else Double.NaN
        v[6] = if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toDouble() else Double.NaN
        v[7] = if (l.hasBearingAccuracy()) l.bearingAccuracyDegrees.toDouble() else Double.NaN
        v[8] = if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else Double.NaN
        v[9] = ((arrivedNs - atNs) / 1_000_000).toDouble()   // how late the fix reached the app
        writer.room()?.sample(t(atNs), ResearchFormat.GPS, v)
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastGnssMs < GNSS_EVERY_MS) return
            lastGnssMs = now
            var used = 0
            var cn0 = 0.0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                    cn0 += status.getCn0DbHz(i)
                }
            }
            val mean = if (used > 0) cn0 / used else Double.NaN
            writer.room()?.sample(t(), ResearchFormat.GNSS, doubleArrayOf(used.toDouble(), status.satelliteCount.toDouble(), mean))
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> line(ResearchFormat.SCREEN.code, 1)
                Intent.ACTION_SCREEN_OFF -> line(ResearchFormat.SCREEN.code, 0)
                Intent.ACTION_USER_PRESENT -> writer.room()?.start(t(), ResearchFormat.UNLOCK.code)?.end()
            }
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)   // 0.1 °C
            // Android repeats this broadcast for every small voltage change: write only what changed.
            val key = "$pct,$plugged,$status,${temp / 10}"
            if (key == lastBattery) return
            lastBattery = key
            writer.room()?.sample(t(), ResearchFormat.BATTERY, doubleArrayOf(
                if (pct >= 0) pct.toDouble() else Double.NaN, plugged.toDouble(), status.toDouble(),
                if (temp != Int.MIN_VALUE) temp / 10.0 else Double.NaN,
            ))
        }
    }

    /** Every 2 s: the audio state, then the writer's flush (and maybe a new file). Stops itself if the disk failed. */
    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            pollAudio()
            writer.tick(t())
            val failure = writer.failure
            if (failure != null) {
                Log.w(TAG, "research recording stopped: $failure")
                unregister()
                return
            }
            h.postDelayed(this, TICK_MS)
        }
    }

    /** Mode (ringtone, call, VoIP…) and where sound goes: hand-held calls are phone use, Bluetooth ones are not. */
    private fun pollAudio() {
        try {
            var outputs = 0
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                outputs = outputs or when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
                    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 1
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> 2
                    else -> 0
                }
            }
            val mode = am.mode
            val call = callDevice()
            val key = "$mode,$outputs,$call"
            if (key == lastAudio) return
            lastAudio = key
            writer.room()?.sample(t(), ResearchFormat.AUDIO, doubleArrayOf(mode.toDouble(), outputs.toDouble(), call?.toDouble() ?: Double.NaN))
        } catch (e: Exception) {
            // The audio service can be briefly unavailable on some phones: the next tick tries again.
        }
    }

    /** The device a call uses now (AudioDeviceInfo.TYPE_*), null when unknown. */
    @Suppress("DEPRECATION")
    private fun callDevice(): Int? = when {
        Build.VERSION.SDK_INT >= 31 -> am.communicationDevice?.type
        am.isBluetoothScoOn -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        am.isSpeakerphoneOn -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        else -> null
    }
}
