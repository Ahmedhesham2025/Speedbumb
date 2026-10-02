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
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.bumpbeeper.auto.AutoStop
import app.bumpbeeper.auto.PowerPolicy
import app.bumpbeeper.crash.CrashLog
import app.bumpbeeper.sync.CachedSpotSource
import app.bumpbeeper.sync.OutboxSink
import app.bumpbeeper.sync.SpeedLimitSync
import app.bumpbeeper.sync.Sync
import app.bumpbeeper.sync.SyncStore
import app.bumpbeeper.sync.TripRoute
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
        /** Settings that [Prefs.applyTo] copies into the engine or driving monitor. */
        private val ENGINE_KEYS: Set<String?> = setOf(
            Prefs.SENSITIVITY, Prefs.LEAD_SECONDS, Prefs.QUIET_BELOW_KMH, Prefs.MAX_BUMP_KMH,
            Prefs.WARN_POTHOLES, Prefs.HARSH_MS2, Prefs.SPEED_LIMIT,
        )
        private const val NOTIF_AUTO_ID = 2
        private const val TAG = "BumpBeeper"
        /** After the car's Bluetooth disconnects, keep recording this long in case it comes back. */
        private const val CAR_GONE_GRACE_MS = 60_000L
        /** How often the battery level goes into the recording. */
        private const val BATTERY_EVERY_MS = 5 * 60_000L
        /** How often the engine thread asks [AutoStop] whether the car is parked. */
        private const val PARKED_CHECK_MS = 15_000L

        /** The running service, for [label]. Set in onCreate, cleared in onDestroy. */
        @Volatile private var instance: BumpService? = null

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

        /**
         * Label what you just drove over (one of [Labels.ALL]); safe to call from any thread.
         * Written to the recording on the engine thread within milliseconds, stamped with the latest sensor
         * time and the last GPS position and speed. Returns false (nothing written) when not recording in
         * label mode (see [Prefs.setLabelMode] / [LiveState.labelMode]) or when [kind] is unknown.
         */
        @Suppress("UNUSED_PARAMETER")
        fun label(ctx: Context, kind: String): Boolean {
            if (kind !in Labels.ALL) return false
            return instance?.postLabel(kind) ?: false
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

    @Volatile private var running = false
    private lateinit var db: BumpDb
    private lateinit var beeper: Beeper
    private var voice: Voice? = null
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: BumpEngine? = null   // only used on the engine thread
    private var monitor: DrivingMonitor? = null         // only used on the engine thread
    private var trace: TraceWriter? = null              // only used on the engine thread
    private var tracingStore: TracingStore? = null      // only used on the engine thread
    private var lastBatteryMs = -1L                     // only used on the engine thread
    private var sink: OutboxSink? = null                // only used on the engine thread; null = not sharing
    private var route: TripRoute? = null                // only used on the engine thread; null = no speed-limit lookup
    private var pulledThisTrip = false                  // only used on the engine thread
    private var wakeLock: PowerManager.WakeLock? = null
    private var tripId = 0L
    private var sensorOffsetMs: Long? = null
    private var graphMax = 0.0
    private var graphLastPushMs = 0L
    private var lastNotifMs = 0L
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    /** No gyroscope reading yet this trip: the recording leaves gx/gy/gz empty instead of a fake 0. */
    private var gyroSeen = false
    // Battery saving (#50) and auto-stop when parked: only used on the engine thread.
    private var power = PowerPolicy()
    private var autoStop: AutoStop? = null

    /** Engine thread, every [PARKED_CHECK_MS] while recording (the wake lock keeps it on time). */
    private val parkedCheck = object : Runnable {
        override fun run() {
            val h = handler ?: return
            if (autoStop?.shouldStop(SystemClock.elapsedRealtime()) == true) {
                autoStop = null
                main.post {
                    if (running) {
                        LiveState.lastEvent = "Parked: recording stopped"
                        stopRecording()
                    }
                }
                return
            }
            h.postDelayed(this, PARKED_CHECK_MS)
        }
    }

    private val stopForCarGone = Runnable {
        LiveState.lastEvent = "Car disconnected: stopped"
        stopRecording()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Only settings that change detection or scoring touch the engine; others (update check,
        // voice, car…) are written often enough that re-applying on each one would be wasted work.
        val engineKey = key == null || key in ENGINE_KEYS   // null = all settings cleared
        if (key == Prefs.AUTO_STOP_MIN || key == null) {
            val ms = Prefs.autoStopMinutes(this) * 60_000L
            handler?.post { autoStop?.stopAfterMs = ms }
        }
        if (!engineKey && key != Prefs.LABEL_MODE) return@OnSharedPreferenceChangeListener
        handler?.post {
            if (engineKey) {
                engine?.cfg?.let { Prefs.applyTo(it, this) }
                monitor?.cfg?.let { Prefs.applyTo(it, this) }
            } else {
                // Label mode switched on while driving: start a recording file now.
                val labels = Prefs.labelMode(this)
                if (labels && trace == null) tracingStore?.let { openTrace(it) }
                LiveState.labelMode = labels && trace != null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        instance = this
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
                handler?.post { autoStop?.carConnected = false }
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
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Any thread. Writes the label on the engine thread; the on-screen count only moves once it is saved. */
    private fun postLabel(kind: String): Boolean {
        val h = handler ?: return false
        if (!LiveState.labelMode) return false
        return h.post {
            val tw = trace ?: return@post
            val f = engine?.lastFix
            try {
                tw.label(kind, f?.lat ?: Double.NaN, f?.lon ?: Double.NaN, (f?.speedMps ?: Double.NaN) * 3.6)
            } catch (e: Exception) {
                Log.w(TAG, "label not written", e)
                return@post
            }
            synchronized(Labels) {
                LiveState.lastLabel = kind
                LiveState.labelCount = if (kind == Labels.UNDO) maxOf(0, LiveState.labelCount - 1) else LiveState.labelCount + 1
            }
        }
    }

    /** Engine thread. Opens a recording file and starts copying engine events into it. */
    private fun openTrace(store: TracingStore) {
        try {
            TraceWriter.prune(this)
            val tw = TraceWriter(TraceWriter.dir(this), TraceWriter.meta(this))
            trace = tw
            store.trace = tw
            lastBatteryMs = -1L
        } catch (e: Exception) {
            Log.w(TAG, "debug recording failed to start", e)
        }
    }

    private fun batteryPercent(): Int = try {
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) {
        -1
    }

    // ---------------------------------------------------------------- start / stop

    private fun startRecording(auto: Boolean) {
        if (running) {
            if (auto) {
                LiveState.lastEvent = "Car reconnected: still recording"
                handler?.post { autoStop?.carConnected = true }
            }
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
        // No speech available → the two-tone pothole sound instead, and no group announcements (EngineConfig.groupWarnings).
        voice = Voice(this, { ok -> engine?.cfg?.groupWarnings = ok }) { beeper.pothole() }
        tripId = db.startTrip(System.currentTimeMillis())

        val t = HandlerThread("bump-engine").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        val cfg = EngineConfig().also { Prefs.applyTo(it, this) }
        // Debug recording or label mode: write a recording file of this drive.
        val record = Prefs.recordTrace(this)
        gx = 0.0; gy = 0.0; gz = 0.0
        gyroSeen = false
        power = PowerPolicy()
        val stopAfterMs = Prefs.autoStopMinutes(this) * 60_000L
        h.post {
            // A trip the car's Bluetooth started only ends when it disconnects (ACTION_CAR_GONE), never as "parked".
            autoStop = AutoStop(stopAfterMs).also { it.carConnected = auto }
            h.postDelayed(parkedCheck, PARKED_CHECK_MS)
            val store = TracingStore(db, null)
            tracingStore = store
            if (record) openTrace(store)
            LiveState.labelMode = trace != null && Prefs.labelMode(this)
            // Shared map: warn for cached confirmed spots; collect observations only if the user opted in.
            val syncStore = SyncStore(db)
            val outbox = if (Prefs.shareBumps(this)) OutboxSink(syncStore, tripId) else null
            sink = outbox
            // Road speed limits (opt-in): the trip's fixes stay in memory until the trip ends.
            route = if (SpeedLimitSync.allowed(this)) TripRoute() else null
            pulledThisTrip = false
            val eng = BumpEngine(cfg, store, this, { System.currentTimeMillis() }, tripId, CachedSpotSource(syncStore), outbox)
            eng.cfg.groupWarnings = voice?.speaks == true
            engine = eng
            val logStore = store
            monitor = DrivingMonitor(DrivingConfig().also { Prefs.applyTo(it, this) }, eng) { type, lat, lon, kmh, value, note ->
                logStore.logEvent(BumpEvent(System.currentTimeMillis(), tripId, type, -1, lat, lon, kmh, Double.NaN, value, Double.NaN, Double.NaN, note))
                LiveState.lastDriveEvent = DriveText.event(type, note)
            }
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
        // Stays on for the whole trip: switched off, the engine would keep using its last (frozen) reading.
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, 20_000, h) }
        LiveState.hasGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

        requestGps(PowerPolicy.MOVING_GPS_MS, t.looper)

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
        h?.removeCallbacks(parkedCheck)
        h?.post {
            autoStop = null
            // Again on this thread: a fix handled just before stopRecording may have re-requested GPS (applyPower).
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
            monitor?.finish()
            engine?.let { database.endTrip(tripId, System.currentTimeMillis(), it.trip, monitor?.stats) }
            monitor?.stats?.let { LiveState.lastTripScore = it.score() }
            // Privacy zone filter, then into the outbox; the upload runs later in the background.
            try {
                sink?.flush(Prefs.shareBumps(this), System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "outbox not written", e)
            }
            sink = null
            // Speed-limit lookup (opt-in): the route waits on disk for a background job, then is deleted.
            try {
                if (engine != null) route?.let { SpeedLimitSync.afterTrip(this, tripId, it) }
            } catch (e: Exception) {
                Log.w(TAG, "speed-limit lookup not queued: ${e.javaClass.simpleName}")
            }
            route = null
            val last = engine?.lastFix
            Sync.afterTrip(this, last?.lat ?: Double.NaN, last?.lon ?: Double.NaN)
            engine = null
            monitor = null
            trace?.close()
            trace = null
            tracingStore = null
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
        LiveState.labelMode = false
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
            gyroSeen = true
            eng.onGyro(tMs, x, y, z)
            monitor?.onGyro(x, y, z)
            return
        }

        eng.onAccel(tMs, x, y, z)
        monitor?.onAccel(tMs, x, y, z)
        trace?.let { tw ->
            if (gyroSeen) tw.accel(tMs, x, y, z, gx, gy, gz, eng.lastVertical)
            else tw.accel(tMs, x, y, z, Double.NaN, Double.NaN, Double.NaN, eng.lastVertical)
            if (lastBatteryMs < 0 || tMs - lastBatteryMs >= BATTERY_EVERY_MS) {
                lastBatteryMs = tMs
                val pct = batteryPercent()
                if (pct in 0..100) tw.battery(pct)
            }
        }

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
        sink?.onFix(fix)
        route?.add(fix)
        if (!pulledThisTrip && fix.accuracyM <= 100.0) {
            // First usable position of the trip: refresh the shared spots around it (when there is network).
            pulledThisTrip = true
            Sync.pullAround(this, fix.lat, fix.lon)
        }
        eng.onFix(fix)
        monitor?.onFix(eng.lastFix ?: fix)
        LiveState.forwardKnown = eng.forwardKnown
        val kmh = fix.speedMps * 3.6
        autoStop?.onFix(fix.timeMs, kmh, fix.lat, fix.lon)
        if (power.onFix(fix.timeMs, kmh)) applyPower()
        publish(eng, force = false)
    }

    /** Engine thread. Stopped: GPS every 5 s; moving: every 1 s (#50). The sensors never change. */
    private fun applyPower() {
        val h = handler ?: return
        if (!running) return
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        requestGps(power.gpsIntervalMs, h.looper)
        trace?.power(power.stopped)
    }

    private fun requestGps(intervalMs: Long, looper: Looper) {
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, this, looper)
        } catch (e: SecurityException) {
            LiveState.lastEvent = "No location permission"
        } catch (e: IllegalArgumentException) {
            LiveState.lastEvent = "This phone has no GPS"
        }
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
        monitor?.onBumpHit(b, (engine?.lastFix?.speedMps ?: 0.0) * 3.6)
        engine?.let { publish(it, force = true) }
    }

    /** First pass over a new spot: a soft tick (the engine rate-limits it). */
    override fun onNewSpotTick(b: Bump) {
        if (Prefs.clickOnNew(this)) beeper.click()
    }

    override fun onKnownBumpHit(b: Bump) {
        LiveState.lastEvent = "Hit known ${describe(b)} #${b.id} (felt ${b.hits} of ${b.passes} times)"
        monitor?.onBumpHit(b, (engine?.lastFix?.speedMps ?: 0.0) * 3.6)
        engine?.let { publish(it, force = true) }
    }

    override fun onWarning(w: Warning) {
        val b = w.spot
        val plain = { beeper.warn(w.sound, w.speedKmh) }
        // The group line couldn't be spoken: play this spot's sound and let the silenced ones warn on their own.
        val groupFallback = { plain(); engine?.ungroup(); Unit }
        val v = voice
        when {
            // Several spots close together: say it once ("3 bumps ahead"); the ones after it stay silent.
            w.cluster != null && v != null -> v.cluster(w.cluster!!, groupFallback)
            w.cluster != null -> groupFallback()
            w.sound == WarnSound.HARSH_POTHOLE && v != null -> v.pothole(b.side)
            else -> plain()
        }
        val group = w.cluster?.let { " (group of ${it.count})" } ?: ""
        LiveState.lastEvent = String.format(Locale.US, "WARNING: %s #%d in %.0f m%s", describe(b), b.id, w.distanceM, group)
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
        monitor?.stats?.let { d ->
            LiveState.liveScore = d.score()
            LiveState.tripMovingS = d.movingS
            LiveState.tripEvents = d.harshBrakes + d.harshAccels + d.harshCorners + d.swerves + d.bumpsFast + d.phoneUse
            LiveState.speedingPct = d.speedingShare * 100
        }

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
