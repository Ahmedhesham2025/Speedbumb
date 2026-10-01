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
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import kotlin.math.abs

/**
 * Runs while you drive (screen can be off). Feeds the accelerometer and gyroscope (50×/s) and GPS (1×/s)
 * into [BumpEngine] on its own background thread, plays the warnings, and keeps the notification up to date.
 */
class BumpService : Service(), SensorEventListener, LocationListener, EngineListener {

    companion object {
        private const val ACTION_START = "app.bumpbeeper.START"
        private const val ACTION_STOP = "app.bumpbeeper.STOP"
        private const val ACTION_MUTE_LAST = "app.bumpbeeper.MUTE_LAST"
        private const val ACTION_CAR_GONE = "app.bumpbeeper.CAR_GONE"
        private const val EXTRA_AUTO = "auto"
        private const val CHANNEL_ID = "recording"
        private const val CHANNEL_AUTO = "auto_start"
        private const val NOTIF_ID = 1
        private const val NOTIF_AUTO_ID = 2
        private const val TAG = "BumpBeeper"
        /** After the car's Bluetooth disconnects, keep recording this long in case it comes back. */
        private const val CAR_GONE_GRACE_MS = 60_000L

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

        /** The car's Bluetooth connected. Start recording (or, if it was about to stop, keep going). */
        fun startFromCar(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, BumpService::class.java).setAction(ACTION_START).putExtra(EXTRA_AUTO, true))
            } catch (e: Exception) {
                // Android refused to start from the background (missing permission or battery restriction).
                Log.w(TAG, "auto start refused", e)
                notifyTapToStart(ctx, "Your car connected. Tap to start recording.")
            }
        }

        fun carDisconnected(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, BumpService::class.java).setAction(ACTION_CAR_GONE))
            } catch (e: Exception) {
                Log.w(TAG, "car-gone message refused", e)
            }
        }

        /** Fallback when auto start isn't allowed: a notification that starts recording with one tap. */
        fun notifyTapToStart(ctx: Context, text: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_AUTO, "Auto start", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shown when your car connects but recording could not start by itself"
                }
            )
            val open = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL_AUTO)
                .setSmallIcon(R.drawable.ic_stat_bump)
                .setContentTitle("Bump Beeper")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try { nm.notify(NOTIF_AUTO_ID, n) } catch (_: SecurityException) {}
        }
    }

    private var running = false
    private lateinit var db: BumpDb
    private lateinit var beeper: Beeper
    private var voice: Voice? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: BumpEngine? = null   // only used on the engine thread
    private var trace: TraceWriter? = null              // only used on the engine thread
    private var wakeLock: PowerManager.WakeLock? = null
    private var tripId = 0L
    private var sensorOffsetMs: Long? = null
    private var graphMax = 0.0
    private var graphLastPushMs = 0L
    private var lastNotifMs = 0L
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0

    private val stopForCarGone = Runnable {
        LiveState.lastEvent = "Car disconnected: stopped"
        stopRecording()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler?.post { engine?.cfg?.let { Prefs.applyTo(it, this) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                main.removeCallbacks(stopForCarGone)
                stopRecording()
            }
            ACTION_MUTE_LAST -> if (running) muteLast() else stopSelf()
            ACTION_CAR_GONE -> if (running) {
                main.removeCallbacks(stopForCarGone)
                main.postDelayed(stopForCarGone, CAR_GONE_GRACE_MS)
                LiveState.lastEvent = "Car disconnected: stopping in ${CAR_GONE_GRACE_MS / 1000} s"
            } else stopSelf()
            else -> {
                main.removeCallbacks(stopForCarGone)   // car came back (or Start pressed): keep going
                startRecording(intent?.getBooleanExtra(EXTRA_AUTO, false) == true)
            }
        }
        return START_NOT_STICKY
    }

    private fun muteLast() {
        handler?.post {
            val eng = engine ?: return@post
            val b = eng.muteBump(eng.lastBeepedId)
            LiveState.lastEvent = if (b != null) "Muted #${b.id}. It won't beep again." else "Nothing has beeped yet on this trip"
            publish(eng, force = true)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(stopForCarGone)
        if (running) stopRecording()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start / stop

    private fun startRecording(auto: Boolean) {
        if (running) {
            if (auto) LiveState.lastEvent = "Car reconnected: still recording"
            return
        }
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            LiveState.recording = false
            LiveState.lastEvent = "Could not start: ${e.message}"
            if (auto) notifyTapToStart(this, "Your car connected, but recording couldn't start by itself. Tap to start.")
            stopSelf()
            return
        }
        running = true
        LiveState.resetTrip()
        LiveState.recording = true
        LiveState.lastEvent = if (auto) "Car connected: recording started" else "Recording started"

        db = BumpDb(this)
        beeper = Beeper(this)
        voice = Voice(this) { beeper.pothole() }   // no speech available → the two-tone pothole sound instead
        tripId = db.startTrip(System.currentTimeMillis())

        val t = HandlerThread("bump-engine").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        val cfg = EngineConfig().also { Prefs.applyTo(it, this) }
        val debug = Prefs.debugRecording(this)
        h.post {
            var store: BumpStore = db
            if (debug) {
                try {
                    TraceWriter.prune(this)
                    val tw = TraceWriter(TraceWriter.dir(this))
                    trace = tw
                    store = TracingStore(db, tw)
                } catch (e: Exception) {
                    Log.w(TAG, "debug recording failed to start", e)
                }
            }
            val eng = BumpEngine(cfg, store, this, { System.currentTimeMillis() }, tripId)
            engine = eng
            publish(eng, force = true)
        }

        // Keep the CPU awake with the screen off, so the sensors keep flowing.
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
        // Optional: tells speed bumps (car pitches) from potholes (car rolls). Works without it, less surely.
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, 20_000, h) }
        LiveState.hasGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

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
            trace?.close()
            trace = null
            database.close()
            // A GPS fix handled just before this runnable may have re-posted the notification. Remove it.
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
            t?.quitSafely()
        }
        handler = null
        thread = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        // Let a warning that is still being spoken finish.
        voice?.let { v -> main.postDelayed({ v.shutdown() }, 5000) }
        voice = null
        LiveState.recording = false
        if (!LiveState.lastEvent.startsWith("Car disconnected")) LiveState.lastEvent = "Stopped"
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
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gx = x; gy = y; gz = z
            eng.onGyro(tMs, x, y, z)
            return
        }

        eng.onAccel(tMs, x, y, z)
        trace?.accel(tMs, x, y, z, gx, gy, gz, eng.lastVertical)

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
        trace?.gps(fix.timeMs, fix.lat, fix.lon, fix.speedMps * 3.6, fix.bearingDeg, fix.accuracyM)
        eng.onFix(fix)
        LiveState.forwardKnown = eng.forwardKnown
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

    private fun describe(b: Bump): String = when {
        b.kind != BumpKind.POTHOLE -> b.kind.label
        b.side == Side.UNKNOWN -> if (b.isHarsh(engine?.cfg ?: EngineConfig())) "harsh pothole" else "pothole"
        else -> (if (b.isHarsh(engine?.cfg ?: EngineConfig())) "harsh pothole, " else "pothole, ") + b.side.label
    }

    override fun onNewBump(b: Bump) {
        LiveState.lastEvent = "New ${describe(b)} recorded (#${b.id})"
        if (Prefs.clickOnNew(this)) beeper.click()
        engine?.let { publish(it, force = true) }
    }

    override fun onKnownBumpHit(b: Bump) {
        LiveState.lastEvent = "Hit known ${describe(b)} #${b.id} (felt ${b.hits} of ${b.passes} times)"
        engine?.let { publish(it, force = true) }
    }

    override fun onBeep(b: Bump, distanceM: Double, speedKmh: Double) {
        if (b.kind == BumpKind.POTHOLE) voice?.pothole(b.side) else beeper.warn(b, speedKmh)
        LiveState.lastEvent = String.format(Locale.US, "WARNING: %s #%d in %.0f m", describe(b), b.id, distanceM)
        engine?.let { publish(it, force = true) }
    }

    override fun onPassed(b: Bump, felt: Boolean) {
        if (!felt) LiveState.lastEvent = "Passed #${b.id} without feeling it"
    }

    override fun onJoltRejected(peak: Double, reason: String) {
        val why = when (reason) {
            "phone_moving" -> "phone was being moved"
            "no_gps" -> "no GPS fix yet"
            "weak_gps" -> "GPS too inaccurate"
            "too_slow" -> "car (almost) stopped"
            "too_fast" -> "too fast for a speed bump, and not clearly a pothole"
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
        LiveState.tripPotholes = tr.potholes
        LiveState.tripHarshPotholes = tr.harshPotholes
        LiveState.potholesOnMap = eng.bumps.count { it.kind == BumpKind.POTHOLE }
        LiveState.harshOnMap = eng.bumps.count { it.isHarsh(eng.cfg) }

        val now = SystemClock.elapsedRealtime()
        if (force || now - lastNotifMs > 5000) {
            lastNotifMs = now
            val text = String.format(
                Locale.US, "%d on map · this trip: %d hit, %d new, %d warnings",
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
