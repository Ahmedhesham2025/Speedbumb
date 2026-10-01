package app.bumpbeeper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import kotlin.math.abs

/**
 * Runs while you drive (screen can be off). Feeds the accelerometer (50×/s) and GPS (1×/s)
 * into [BumpEngine] on its own background thread, plays the beeps, and keeps the notification up to date.
 */
class BumpService : Service(), SensorEventListener, LocationListener, EngineListener {

    companion object {
        private const val ACTION_START = "app.bumpbeeper.START"
        private const val ACTION_STOP = "app.bumpbeeper.STOP"
        private const val ACTION_MUTE_LAST = "app.bumpbeeper.MUTE_LAST"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val TAG = "BumpBeeper"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BumpService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BumpService::class.java).setAction(ACTION_STOP))
        }

        /** Silence the bump that beeped last (a false alarm). Only works while recording. */
        fun muteLastBeep(ctx: Context) {
            ctx.startService(Intent(ctx, BumpService::class.java).setAction(ACTION_MUTE_LAST))
        }
    }

    private var running = false
    private lateinit var db: BumpDb
    private lateinit var beeper: Beeper
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var engine: BumpEngine? = null   // only used on the engine thread
    private var wakeLock: PowerManager.WakeLock? = null
    private var tripId = 0L
    private var sensorOffsetMs: Long? = null
    private var graphMax = 0.0
    private var graphLastPushMs = 0L
    private var lastNotifMs = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.SENSITIVITY) {
            val th = Prefs.threshold(this)
            handler?.post { engine?.cfg?.joltThreshold = th }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_MUTE_LAST -> if (running) muteLast() else stopSelf()
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    private fun muteLast() {
        handler?.post {
            val eng = engine ?: return@post
            val b = eng.muteBump(eng.lastBeepedId)
            LiveState.lastEvent = if (b != null) "Muted bump #${b.id}. It won't beep again." else "Nothing has beeped yet on this trip"
            publish(eng, force = true)
        }
    }

    override fun onDestroy() {
        if (running) stopRecording()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start / stop

    private fun startRecording() {
        if (running) return
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            LiveState.recording = false
            LiveState.lastEvent = "Could not start: ${e.message}"
            stopSelf()
            return
        }
        running = true
        LiveState.resetTrip()
        LiveState.recording = true
        LiveState.lastEvent = "Recording started"

        db = BumpDb(this)
        beeper = Beeper(this)
        tripId = db.startTrip(System.currentTimeMillis())

        val t = HandlerThread("bump-engine").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        val cfg = EngineConfig().apply { joltThreshold = Prefs.threshold(this@BumpService) }
        h.post {
            val eng = BumpEngine(cfg, db, this, { System.currentTimeMillis() }, tripId)
            engine = eng
            publish(eng, force = true)
        }

        // Keep the CPU awake with the screen off, so the accelerometer keeps flowing.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BumpBeeper:recording")
            .apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }

        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            LiveState.lastEvent = "This phone has no accelerometer"
        } else {
            sm.registerListener(this, accel, 20_000 /* µs → 50 Hz */, h)
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, t.looper)
        } catch (e: SecurityException) {
            LiveState.lastEvent = "No location permission"
        } catch (e: IllegalArgumentException) {
            LiveState.lastEvent = "This phone has no GPS"
        }

        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun stopRecording() {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)

        val h = handler
        val t = thread
        val database = db
        h?.post {
            engine?.let { database.endTrip(tripId, System.currentTimeMillis(), it.trip) }
            engine = null
            database.close()
            // A GPS fix handled just before this runnable may have re-posted the notification. Remove it.
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
            t?.quitSafely()
        }
        handler = null
        thread = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        LiveState.recording = false
        LiveState.lastEvent = "Stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- sensors (engine thread)

    override fun onSensorChanged(event: SensorEvent) {
        val eng = engine ?: return
        val nowMs = SystemClock.elapsedRealtime()
        // Sensor timestamps should share the GPS clock (elapsedRealtime). If this phone doesn't, correct once.
        val offset = sensorOffsetMs ?: run {
            val diff = nowMs - event.timestamp / 1_000_000
            val o = if (abs(diff) < 1000) 0L else diff
            sensorOffsetMs = o
            o
        }
        val tMs = event.timestamp / 1_000_000 + offset
        eng.onAccel(tMs, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())

        val v = abs(eng.lastVertical)
        if (v > graphMax) graphMax = v
        if (nowMs - graphLastPushMs >= 100) {
            LiveState.pushGraph(graphMax.toFloat())
            graphMax = 0.0
            graphLastPushMs = nowMs
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        val eng = engine ?: return
        val fix = Fix(
            location.elapsedRealtimeNanos / 1_000_000,
            location.latitude,
            location.longitude,
            if (location.hasSpeed()) location.speed.toDouble() else Double.NaN,
            if (location.hasBearing()) location.bearing.toDouble() else Double.NaN,
            if (location.hasAccuracy()) location.accuracy.toDouble() else 99.0,
        )
        eng.onFix(fix)
        publish(eng, force = false)
    }

    // Android 10 needs these three implemented explicitly.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        LiveState.lastEvent = "Location was turned off"
    }
    @Deprecated("Deprecated in Android, still called on Android 10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---------------------------------------------------------------- engine events (engine thread)

    override fun onNewBump(b: Bump) {
        LiveState.lastEvent = "New bump recorded (#${b.id})"
        if (Prefs.clickOnNew(this)) beeper.click()
        engine?.let { publish(it, force = true) }
    }

    override fun onKnownBumpHit(b: Bump) {
        LiveState.lastEvent = "Hit known bump #${b.id} (felt ${b.hits} of ${b.passes} times)"
        engine?.let { publish(it, force = true) }
    }

    override fun onBeep(b: Bump, distanceM: Double, speedKmh: Double) {
        beeper.beep(if (speedKmh >= 50) 3 else 2)
        LiveState.lastEvent = String.format(Locale.US, "BEEP: bump #%d in %.0f m", b.id, distanceM)
        engine?.let { publish(it, force = true) }
    }

    override fun onPassed(b: Bump, felt: Boolean) {
        if (!felt) LiveState.lastEvent = "Passed bump #${b.id} without feeling it"
    }

    override fun onJoltRejected(peak: Double, reason: String) {
        val why = when (reason) {
            "phone_moving" -> "phone was being moved"
            "no_gps" -> "no GPS fix yet"
            "weak_gps" -> "GPS too inaccurate"
            "too_slow" -> "car (almost) stopped"
            "too_fast" -> "too fast for a speed bump"
            "no_heading" -> "direction not known yet"
            else -> reason
        }
        LiveState.lastIgnored = String.format(Locale.US, "Ignored a %.1f m/s² jolt: %s", peak, why)
    }

    // ---------------------------------------------------------------- screen + notification

    private fun publish(eng: BumpEngine, force: Boolean) {
        val f = eng.lastFix
        if (f != null) {
            LiveState.speedKmh = f.speedMps * 3.6
            LiveState.accuracyM = f.accuracyM
            LiveState.lastFixAtMs = SystemClock.elapsedRealtime()
        }
        LiveState.bumpsOnMap = eng.bumps.size
        LiveState.mutedBumps = eng.mutedCount
        val tr = eng.trip
        LiveState.tripHits = tr.hits
        LiveState.tripNew = tr.newBumps
        LiveState.tripBeeps = tr.beeps
        LiveState.tripMisses = tr.misses
        LiveState.tripKm = tr.distanceM / 1000.0

        val now = SystemClock.elapsedRealtime()
        if (force || now - lastNotifMs > 5000) {
            lastNotifMs = now
            val text = String.format(
                Locale.US, "%d bumps on map · this trip: %d hit, %d new, %d beeps",
                eng.bumps.size, tr.hits, tr.newBumps, tr.beeps,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Bump Beeper is recording"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BumpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bump)
            .setContentTitle("Recording speed bumps")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_bump), "Stop", stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }
}
