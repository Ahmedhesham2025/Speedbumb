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
import app.bumpbeeper.auto.AutoDetect
import app.bumpbeeper.auto.AutoStop
import app.bumpbeeper.auto.DriveWatcher
import app.bumpbeeper.auto.PowerPolicy
import app.bumpbeeper.auto.TripCheck
import app.bumpbeeper.auto.TripHold
import app.bumpbeeper.crash.CrashLog
import app.bumpbeeper.sync.CachedSpotSource
import app.bumpbeeper.sync.LiveSpeedLimit
import app.bumpbeeper.sync.OutboxSink
import app.bumpbeeper.sync.SpeedLimitSync
import app.bumpbeeper.sync.SpeedWarner
import app.bumpbeeper.sync.Sync
import app.bumpbeeper.sync.SyncStore
import app.bumpbeeper.sync.TrainingSink
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
        private const val ACTION_WATCH = "app.bumpbeeper.WATCH"
        private const val ACTION_UNWATCH = "app.bumpbeeper.UNWATCH"
        private const val ACTION_WALKING = "app.bumpbeeper.WALKING"
        /** Who started an automatic recording: [SOURCE_CAR], [SOURCE_VEHICLE] or [SOURCE_MOTION]; absent = the user. */
        private const val EXTRA_SOURCE = "source"
        const val SOURCE_CAR = "car"
        const val SOURCE_VEHICLE = "vehicle"
        const val SOURCE_MOTION = "motion"
        private const val CHANNEL_ID = "recording"
        private const val CHANNEL_AUTO = "auto_start"
        private const val CHANNEL_WATCH = "watching"
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
        /** After the user presses Stop, motion detection waits this long (they may still be driving). */
        private const val SNOOZE_AFTER_STOP_MS = 15 * 60_000L
        /** A speeding warning waits this long after a bump or pothole warning (those come first). */
        private const val HAZARD_FIRST_MS = 6_000L

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
        fun startFromCar(ctx: Context) = startAuto(ctx, SOURCE_CAR)

        /**
         * Automatic start ([SOURCE_CAR] Bluetooth, [SOURCE_VEHICLE] Google activity recognition). Android 12+ only lets
         * a background app start a foreground service in some cases (activity-recognition transitions are one); when it
         * refuses, a "tap to start" notification is the fallback. While the service already runs (watching for
         * driving) a plain start is enough and always allowed.
         */
        fun startAuto(ctx: Context, source: String) {
            val i = Intent(ctx, BumpService::class.java).setAction(ACTION_START).putExtra(EXTRA_SOURCE, source)
            if (instance == null && !mayRecordInBackground(ctx)) {
                // startForeground would throw after startForegroundService, and Android then kills the app.
                notifyTapToStart(ctx, tapText(ctx, source))
                return
            }
            try {
                if (instance != null) ctx.startService(i) else ctx.startForegroundService(i)
            } catch (e: Exception) {
                // Android refused to start from the background (missing permission or battery restriction).
                Log.w(TAG, "auto start refused", e)
                notifyTapToStart(ctx, tapText(ctx, source))
            }
        }

        private fun tapText(ctx: Context, source: String?): String =
            ctx.getString(if (source == SOURCE_CAR) R.string.notif_tap_car else R.string.notif_tap_driving)

        /** Google saw the user walking (they left the car): a recording then stops after 1 min parked. */
        fun userWalking(ctx: Context) {
            if (instance?.running != true) return
            try { ctx.startService(Intent(ctx, BumpService::class.java).setAction(ACTION_WALKING)) } catch (_: Exception) {}
        }

        /**
         * Keep running with a quiet "ready to detect driving" notification and start recording when the car moves
         * (no Bluetooth needed). Call from the app or a boot broadcast: Android 12+ refuses it from other background
         * moments. Returns false when Android refused. See [app.bumpbeeper.auto.AutoDetect].
         */
        fun watch(ctx: Context): Boolean {
            if (!mayRecordInBackground(ctx)) return false   // see startAuto
            return try {
                ctx.startForegroundService(Intent(ctx, BumpService::class.java).setAction(ACTION_WATCH))
                true
            } catch (e: Exception) {
                Log.w(TAG, "watching refused", e)
                false
            }
        }

        /**
         * A location foreground service may start without the app on screen: Android 14+ throws in startForeground
         * otherwise. Checked before startForegroundService, because a service that then fails to call startForeground
         * crashes the app ("did not then call startForeground"), even if it stops itself.
         */
        private fun mayRecordInBackground(ctx: Context): Boolean = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ).all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

        /** Stop watching for driving (a recording in progress continues). */
        fun unwatch(ctx: Context) {
            if (instance == null) return
            try { ctx.startService(Intent(ctx, BumpService::class.java).setAction(ACTION_UNWATCH)) } catch (_: Exception) {}
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
        fun notifyTapToStart(ctx: Context, text: String, resume: Boolean = false) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_AUTO, ctx.getString(R.string.notif_channel_auto), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.notif_channel_auto_desc)
                }
            )
            val open = PendingIntent.getActivity(
                ctx, if (resume) 3 else 2, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, !resume)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL_AUTO)
                .setSmallIcon(R.drawable.ic_stat_bump)
                .setContentTitle(ctx.getString(R.string.app_name))
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
    /** Started by a guess (motion / Google) and not confirmed by the car's Bluetooth yet: ask at the end (#49). */
    @Volatile private var unconfirmed = false
    private var route: TripRoute? = null                // only used on the engine thread; null = no speed-limit lookup
    private var pulledThisTrip = false                  // only used on the engine thread
    private var training: TrainingSink? = null          // only used on the engine thread; null = not helping improve detection
    private var live: LiveSpeedLimit? = null            // only used on the engine thread: live road speed limit (opt-in)
    private var warner = SpeedWarner()                  // only used on the engine thread
    private var phoneFeed: PhoneFeed? = null            // only used on the engine thread: screen, unlock, calls… for the phone state
    @Volatile private var lastHazardWarnMs = -1L        // elapsedRealtime of the last bump / pothole warning
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
    /** Auto-detect driving is on: between recordings the service stays up and [watcher] waits for the car to move. */
    @Volatile private var watching = false
    private var watcher: DriveWatcher? = null           // main thread; only while watching and not recording

    /** Engine thread, every [PARKED_CHECK_MS] while recording (the wake lock keeps it on time). */
    private val parkedCheck = object : Runnable {
        override fun run() {
            val h = handler ?: return
            live?.tick()
            if (autoStop?.shouldStop(SystemClock.elapsedRealtime()) == true) {
                autoStop = null
                main.post {
                    if (running) {
                        LiveState.lastEvent = "Parked: recording stopped"
                        stopRecording(manual = false)
                    }
                }
                return
            }
            h.postDelayed(this, PARKED_CHECK_MS)
        }
    }

    private val stopForCarGone = Runnable {
        LiveState.lastEvent = "Car disconnected: stopped"
        stopRecording(manual = false)
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
        if (intent == null) {
            // Restarted by Android after it killed us while watching (START_STICKY): resume watching only, never
            // recording. If Android refuses the foreground start, the user gets a "tap to resume" notification.
            if (!running && AutoDetect.enabled(this) && AutoDetect.missingPermissions(this).isEmpty()) {
                startWatching(restarted = true)
            } else {
                stopIfIdle()
            }
            return if (watching) START_STICKY else START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                main.removeCallbacks(stopForCarGone)
                stopRecording(manual = true)
            }
            ACTION_MUTE_LAST -> if (running) muteLast() else stopIfIdle()
            ACTION_CAR_GONE -> if (running) {
                main.removeCallbacks(stopForCarGone)
                main.postDelayed(stopForCarGone, CAR_GONE_GRACE_MS)
                handler?.post { autoStop?.carConnected = false }
                LiveState.lastEvent = "Car disconnected: stopping in ${CAR_GONE_GRACE_MS / 1000} s"
            } else stopIfIdle()
            ACTION_WATCH -> startWatching()
            ACTION_UNWATCH -> stopWatching()
            ACTION_WALKING -> if (running) handler?.post { autoStop?.leftVehicle() } else stopIfIdle()
            else -> {
                main.removeCallbacks(stopForCarGone)   // car came back (or Start pressed): keep going
                startRecording(intent.getStringExtra(EXTRA_SOURCE))
            }
        }
        // While watching, Android restarts us after killing the process (a null intent, handled above).
        return if (watching) START_STICKY else START_NOT_STICKY
    }

    /** Nothing to do (not recording, not watching): let the service end. */
    private fun stopIfIdle() {
        if (!running && !watching) stopSelf()
    }

    // ---------------------------------------------------------------- watching for driving (#49)

    private fun startWatching(restarted: Boolean = false) {
        // Always answer startForegroundService with startForeground, even when already in the foreground.
        val n = if (running) buildNotification(getString(R.string.notif_recording)) else buildWatchNotification()
        try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Only reachable when permissions changed after the check in watch(), or on a sticky restart.
            Log.w(TAG, "watching: startForeground refused", e)
            if (!running) {
                if (restarted) notifyTapToStart(this, getString(R.string.notif_detection_paused), resume = true)
                stopIfIdle()
                return
            }
        }
        watching = true
        LiveState.watching = true
        if (!running) beginWatch()
    }

    private fun stopWatching() {
        watching = false
        LiveState.watching = false
        watcher?.stop()
        watcher = null
        if (!running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Main thread. Between recordings: wait for the car to move (motion sensor, then a short GPS check). */
    private fun beginWatch(snoozeMs: Long = 0L) {
        if (watcher != null) return
        watcher = DriveWatcher(this) { drivingDetected() }.also {
            if (snoozeMs > 0) it.snooze(snoozeMs)
            it.start()
        }
    }

    /** The watcher saw a drive. Internal for tests. */
    internal fun drivingDetected() {
        if (watching && !running) startRecording(SOURCE_MOTION)
    }

    /** For tests: the watcher waiting for the next drive (null while recording or not watching). */
    internal val currentWatcher: DriveWatcher? get() = watcher

    private fun buildWatchNotification(): Notification {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, getString(R.string.notif_channel_watch), NotificationManager.IMPORTANCE_MIN).apply {
                description = getString(R.string.notif_channel_watch_desc)
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_stat_bump)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_watch_text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
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
        watching = false
        LiveState.watching = false
        watcher?.stop()
        watcher = null
        if (running) stopRecording(manual = false)
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

    /** [source]: who started it ([SOURCE_CAR], [SOURCE_VEHICLE], [SOURCE_MOTION]); null = the user. */
    private fun startRecording(source: String?) {
        if (running) {
            if (source == SOURCE_CAR) {
                LiveState.lastEvent = "Car reconnected: still recording"
                handler?.post {
                    autoStop?.carConnected = true
                    // The car's Bluetooth confirms it was a drive: nothing to ask, nothing to hold.
                    unconfirmed = false
                    sink?.held = false
                }
            }
            if (source == SOURCE_VEHICLE) handler?.post { autoStop?.backInVehicle() }
            return
        }
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            if (watching) {
                // Already in the foreground (watching): Android may refuse a second startForeground from the
                // background, but the service stays in the foreground; only the notification has to change.
                Log.w(TAG, "startForeground while watching refused; keeping the watching one", e)
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(getString(R.string.notif_starting)))
            } else {
                Log.e(TAG, "startForeground failed", e)
                LiveState.recording = false
                LiveState.lastEvent = "Could not start: ${e.message}"
                if (source != null) notifyTapToStart(this, tapText(this, source))
                stopSelf()
                return
            }
        }
        watcher?.stop()
        watcher = null
        running = true
        LiveState.resetTrip()
        LiveState.recording = true
        LiveState.lastEvent = when (source) {
            SOURCE_CAR -> "Car connected: recording started"
            SOURCE_VEHICLE, SOURCE_MOTION -> "Driving detected: recording started"
            else -> "Recording started"
        }

        db = BumpDb(this)
        beeper = Beeper(this)
        // No speech available → the two-tone strong-bump sound instead, and no group announcements (EngineConfig.groupWarnings).
        voice = Voice(this, { ok -> engine?.cfg?.groupWarnings = ok }) { beeper.strong() }
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
        // Started by a guess (motion / Google): give up if the car never really drives.
        val guessed = source == SOURCE_VEHICLE || source == SOURCE_MOTION
        h.post {
            // A trip the car's Bluetooth started only ends when it disconnects (ACTION_CAR_GONE), never as "parked".
            autoStop = AutoStop(stopAfterMs).also {
                it.carConnected = source == SOURCE_CAR
                if (guessed) it.autoStarted(SystemClock.elapsedRealtime())
            }
            h.postDelayed(parkedCheck, PARKED_CHECK_MS)
            val store = TracingStore(db, null)
            tracingStore = store
            if (record) openTrace(store)
            LiveState.labelMode = trace != null && Prefs.labelMode(this)
            // Shared map: warn for cached confirmed spots; collect observations only if the user opted in.
            val syncStore = SyncStore(db)
            unconfirmed = guessed
            val outbox = if (Prefs.shareBumps(this)) OutboxSink(syncStore, tripId, held = guessed) else null
            sink = outbox
            // Road speed limits (opt-in): the trip's fixes stay in memory until the trip ends.
            route = if (SpeedLimitSync.allowed(this)) TripRoute() else null
            pulledThisTrip = false
            // Live road speed limit (opt-in, checked again on every fix). An unconfirmed trip may look it up, but logs nothing.
            live = LiveSpeedLimit(this, h, quiet = { unconfirmed })
            warner = SpeedWarner()
            lastHazardWarnMs = -1L
            // "Help improve detection" (separate opt-in): jolt samples with their signal window, no coordinates uploaded.
            training = if (Prefs.trainingActive(this)) TrainingSink(Prefs.placement(this), System.currentTimeMillis(), batteryPercent()) else null
            val eng = BumpEngine(cfg, store, this, { System.currentTimeMillis() }, tripId, CachedSpotSource(syncStore), outbox,
                training ?: JoltSampleSink.NONE)
            eng.cfg.groupWarnings = voice?.speaks == true
            engine = eng
            val logStore = store
            monitor = DrivingMonitor(DrivingConfig().also { Prefs.applyTo(it, this) }, eng) { type, lat, lon, kmh, value, note ->
                logStore.logEvent(BumpEvent(System.currentTimeMillis(), tripId, type, -1, lat, lon, kmh, Double.NaN, value, Double.NaN, Double.NaN, note))
                LiveState.lastDriveEvent = DriveText.event(type, note)
            }
            // Screen, unlock, proximity, light, calls, charging: is the phone in someone's hand? (phone use, E3)
            phoneFeed = PhoneFeed(this, h, eng.phone.signals).also { it.start() }
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

    /** [manual]: the user pressed Stop, so motion detection pauses for a while (they may still be in the car). */
    private fun stopRecording(manual: Boolean) {
        if (!running) {
            stopIfIdle()
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
            phoneFeed?.stop()
            phoneFeed = null
            autoStop = null
            // Again on this thread: a fix handled just before stopRecording may have re-requested GPS (applyPower).
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
            monitor?.finish()
            engine?.let { database.endTrip(tripId, System.currentTimeMillis(), it.trip, monitor?.stats) }
            monitor?.stats?.let { LiveState.lastTripScore = it.score() }
            // Started by itself and never confirmed: hold everything that would leave the phone (#49).
            val ask = unconfirmed && engine != null
            if (ask) TripHold.hold(this, tripId)
            // Privacy zone filter, then into the outbox; the upload runs later in the background.
            try {
                sink?.flush(Prefs.shareBumps(this), System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "outbox not written", e)
            }
            sink = null
            unconfirmed = false
            try {
                // Also sets the training upload delay before Sync.afterTrip below, so the trip-end sync can't upload it.
                training?.flush(this, tripId, System.currentTimeMillis(), engine?.trip, monitor?.stats, batteryPercent())
            } catch (e: Exception) {
                Log.w(TAG, "training samples not queued: ${e.javaClass.simpleName}")
            }
            training = null
            // Speed-limit lookup (opt-in): the route waits on disk for a background job, then is deleted.
            try {
                if (engine != null) route?.let { SpeedLimitSync.afterTrip(this, tripId, it) }
            } catch (e: Exception) {
                Log.w(TAG, "speed-limit lookup not queued: ${e.javaClass.simpleName}")
            }
            route = null
            live?.close()
            live = null
            LiveState.overLimit = 0
            if (ask) TripCheck.ask(this, tripId)
            val last = engine?.lastFix
            Sync.afterTrip(this, last?.lat ?: Double.NaN, last?.lon ?: Double.NaN)
            engine = null
            monitor = null
            trace?.close()
            trace = null
            tracingStore = null
            database.close()
            // A GPS fix handled just before this runnable may have re-posted the notification. Remove (or replace) it.
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (watching) nm.notify(NOTIF_ID, buildWatchNotification()) else nm.cancel(NOTIF_ID)
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
        if (!LiveState.lastEvent.startsWith("Car disconnected") && !LiveState.lastEvent.startsWith("Parked")) {
            LiveState.lastEvent = "Stopped"
        }
        if (watching) {
            // Stay in the foreground and wait for the next drive.
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildWatchNotification())
            beginWatch(if (manual) SNOOZE_AFTER_STOP_MS else 0L)
            return
        }
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
        training?.onFix(fix)
        route?.add(fix)
        if (!pulledThisTrip && fix.accuracyM <= 100.0) {
            // First usable position of the trip: refresh the shared spots around it (when there is network).
            pulledThisTrip = true
            Sync.pullAround(this, fix.lat, fix.lon)
        }
        eng.onFix(fix)
        monitor?.onFix(eng.lastFix ?: fix)
        live?.let { checkSpeedLimit(it, fix) }
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

    /** Engine thread, each fix: the live limit, and the speeding warning (tone, then the limit spoken). */
    private fun checkSpeedLimit(l: LiveSpeedLimit, fix: Fix) {
        l.onFix(fix)
        val now = SystemClock.elapsedRealtime()
        // Never over a bump warning: wait until it has played. "Quiet when slow" can't apply: this needs ≥ 15 km/h.
        val mayPlay = Prefs.limitSound(this) && (lastHazardWarnMs < 0 || now - lastHazardWarnMs > HAZARD_FIRST_MS)
        val limit = warner.onSpeed(now, fix.speedMps * 3.6, LiveState.speedLimitKmh, Prefs.limitMarginKmh(this), mayPlay)
        LiveState.overLimit = warner.overLimit
        if (limit == null) return
        beeper.speeding()
        val v = voice ?: return
        handler?.postDelayed({
            // A bump warning that came meanwhile wins: no voice over it.
            if (lastHazardWarnMs < now) v.speedLimit(limit)
        }, Beeper.SPEEDING_MS)
    }

    // ---------------------------------------------------------------- engine events (engine thread)

    private fun describe(b: Bump): String = b.describe(engine?.cfg ?: EngineConfig())

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
        lastHazardWarnMs = SystemClock.elapsedRealtime()
        val b = w.spot
        val plain = { beeper.warn(w.sound) }
        // The group line couldn't be spoken: play this spot's sound and let the silenced ones warn on their own.
        val groupFallback = { plain(); engine?.ungroup(); Unit }
        val v = voice
        when {
            // Several spots close together: say it once ("3 bumps ahead"); the ones after it stay silent. A group of
            // spots that are all only a "maybe" is not announced: each one plays its own soft beep instead.
            w.cluster != null && w.cluster!!.anyFull && v != null -> v.cluster(w.cluster!!, groupFallback)
            w.cluster != null -> groupFallback()
            w.sound == WarnSound.STRONG && v != null -> v.strongBump()
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
        // Until the Drive screen is reworked (A1), its pothole tile shows the strong bumps felt this trip.
        LiveState.tripPotholes = tr.strong
        LiveState.tripHarshPotholes = 0
        LiveState.potholesOnMap = eng.bumps.count { it.legacy }
        LiveState.harshOnMap = eng.bumps.count { it.severity(eng.cfg) == Severity.STRONG }
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
                Locale.US, getString(R.string.notif_recording_counts),
                eng.bumps.size, tr.hits, tr.newBumps, tr.beeps,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notif_channel_recording_desc)
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
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_bump), getString(R.string.notif_stop), stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }
}
