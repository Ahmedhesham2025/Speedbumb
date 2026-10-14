package app.bumpbeeper.auto

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import app.bumpbeeper.BumpDb
import app.bumpbeeper.BumpService
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
import app.bumpbeeper.research.ResearchQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/** BumpService with auto-detect on (#49): watch → record → watch, snooze after Stop, sticky restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BumpServiceWatchTest {
    private lateinit var app: Application
    private lateinit var controller: ServiceController<BumpService>
    private lateinit var svc: BumpService

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        Prefs.sp(app).edit().clear().putBoolean(Prefs.AUTO_DETECT, true).commit()
        LiveState.watching = false
        LiveState.recording = false
        controller = Robolectric.buildService(BumpService::class.java).create()
        svc = controller.get()
    }

    @After fun tearDown() {
        controller.destroy()
        Thread.sleep(300)   // let the engine thread finish closing the trip
    }

    private fun send(action: String?): Int =
        svc.onStartCommand(action?.let { Intent(app, BumpService::class.java).setAction(it) }, 0, 1)

    private fun text(n: Notification?): String? = n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test fun watchRecordWatch() {
        assertEquals(Service.START_STICKY, send("app.bumpbeeper.WATCH"))
        assertTrue(LiveState.watching)
        assertNotNull(svc.currentWatcher)
        assertEquals("Ready to detect driving", text(shadowOf(svc).lastForegroundNotification))

        svc.drivingDetected()
        assertFalse(LiveState.recording)     // recording, but silently until 30 s of driving
        assertEquals("Ready to detect driving", text(shadowOf(svc).lastForegroundNotification))
        assertEquals(1, tripCount())
        assertNull(svc.currentWatcher)       // no detection while recording, so nothing can start a second one

        // The car's Bluetooth wasn't involved: a disconnect message ends it after the grace, not as a manual Stop.
        send("app.bumpbeeper.CAR_GONE")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertFalse(LiveState.recording)
        assertTrue(LiveState.watching)       // still in the foreground, waiting for the next drive
        val w = svc.currentWatcher
        assertNotNull(w)
        assertFalse(w!!.snoozed)
        assertFalse(shadowOf(svc).isStoppedBySelf)
    }

    @Test fun manualStopSnoozesDetection() {
        send("app.bumpbeeper.WATCH")
        svc.drivingDetected()
        send("app.bumpbeeper.STOP")
        assertFalse(LiveState.recording)
        assertTrue(LiveState.watching)
        assertTrue(svc.currentWatcher!!.snoozed)   // the user may still be driving: no new recording for 15 min
    }

    private fun tripCount(): Int {
        val db = BumpDb(app)
        try {
            return db.readableDatabase.rawQuery("SELECT COUNT(*) FROM trips", null).use { it.moveToFirst(); it.getInt(0) }
        } finally {
            db.close()
        }
    }

    @Test fun aTripStoppedWithinThirtySecondsIsNotKept() {
        send("app.bumpbeeper.WATCH")
        svc.drivingDetected()
        assertEquals(1, tripCount())
        send("app.bumpbeeper.STOP")
        // The trip is closed (here: deleted) on the engine thread.
        var n = 1
        for (i in 0 until 30) {
            n = tripCount()
            if (n == 0) break
            Thread.sleep(100)
        }
        assertEquals(0, n)
    }

    private fun startAuto(source: String) {
        svc.onStartCommand(Intent(app, BumpService::class.java).setAction("app.bumpbeeper.START").putExtra("source", source), 0, 1)
    }

    /** GPS fixes every 2 s at [kmh], fix times [fromS]..[toS] s, delivered to the recording on its engine thread. */
    private fun gps(fromS: Int, toS: Int, kmh: Double) {
        val lm = app.getSystemService(LocationManager::class.java)
        for (s in fromS..toS step 2) {
            val l = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 30.0 + s * kmh / 3.6 / 111_320.0
                longitude = 31.0
                speed = (kmh / 3.6).toFloat()
                accuracy = 5f
                time = 1_700_000_000_000L + s * 1000L
                elapsedRealtimeNanos = s * 1_000_000_000L
            }
            shadowOf(lm).simulateLocation(l)
        }
    }

    /** Waits for the engine thread, then runs what it posted to the main thread. */
    private fun settle(until: () -> Boolean = { false }) {
        for (i in 0 until 30) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
        }
    }

    @Test fun googleStartWithTwentySecondsOfDrivingIsDiscardedSilently() {
        BumpService.chimes = 0
        startAuto(BumpService.SOURCE_VEHICLE)
        assertFalse(LiveState.recording)     // recording at once, but nothing shown
        assertEquals(1, tripCount())
        gps(0, 20, 40.0)
        settle()
        assertEquals(0, BumpService.chimes)
        assertFalse(LiveState.recording)
        send("app.bumpbeeper.STOP")
        settle { tripCount() == 0 }
        assertEquals(0, tripCount())         // never 30 s of driving: nothing kept
        assertEquals(0, BumpService.chimes)
    }

    @Test fun bluetoothStartWithThirtyFiveSecondsChimesOnceAndRecordsFromZero() {
        BumpService.chimes = 0
        startAuto(BumpService.SOURCE_CAR)
        assertFalse(LiveState.recording)
        assertEquals(1, tripCount())         // the trip (and its data) began with the connection, at 0 s
        gps(0, 34, 40.0)
        settle { BumpService.chimes > 0 }
        assertEquals(1, BumpService.chimes)  // confirmed at 32 s
        assertEquals(-1L, PendingTrip.get(app))   // confirmed: a restart would keep it
        assertTrue(LiveState.recording)      // now shown as running
        assertTrue(LiveState.tripKm > 0.2)   // the fixes from 0 s are in the trip, not only those after 30 s
        gps(36, 60, 40.0)
        settle()
        assertEquals(1, BumpService.chimes)  // once per trip
    }

    @Test fun aCarTripKilledBeforeThirtySecondsIsNeverUploadedAndGoesAtTheNextStart() {
        BumpService.chimes = 0
        Prefs.setResearchRecording(app, true)
        startAuto(BumpService.SOURCE_CAR)
        gps(0, 20, 40.0)
        settle { ResearchQueue.read(app) { it.stamps().isNotEmpty() } }
        val stamp = ResearchQueue.read(app) { it.stamps().single() }
        assertTrue("research files marked pending: the uploader skips them", ResearchQueue.read(app) { it.trip(stamp)!!.optBoolean("pending") })
        val id = PendingTrip.get(app)
        assertTrue(id > 0)
        assertEquals(1, tripCount())
        // Killed here (no stop, no onDestroy). The next process start (AppStart) cleans up:
        PendingTrip.cleanUp(app)
        assertEquals(0, tripCount())
        assertEquals(-1L, PendingTrip.get(app))
        assertEquals("unconfirmed", ResearchQueue.read(app) { it.trip(stamp)!!.getString("skip") })
        assertEquals(0, BumpService.chimes)
    }

    @Test fun aSilentTripNotConfirmedWithinThreeMinutesStopsAndIsDiscarded() {
        startAuto(BumpService.SOURCE_CAR)   // parked with the car's Bluetooth on: no driving
        assertEquals(1, tripCount())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(181))
        settle { tripCount() == 0 }
        assertEquals(0, tripCount())
        assertEquals(-1L, PendingTrip.get(app))
        assertFalse(LiveState.recording)
    }

    /** The phone parts of the drive watcher: the test moves the phone and feeds the GPS check. */
    private class Phone : DriveWatcher.Hardware {
        var motion: (() -> Unit)? = null
        var gps: ((Long, Double) -> Unit)? = null
        override fun armMotion(onMotion: () -> Unit) { motion = onMotion }
        override fun cancelMotion() { motion = null }
        override fun startPassive(onFix: (Long, Double) -> Unit) {}
        override fun stopPassive() {}
        override fun startGps(onFix: (Long, Double) -> Unit) { gps = onFix }
        override fun stopGps() { gps = null }
        override fun acquireWake(timeoutMs: Long) {}
        override fun releaseWake() {}
        override fun sampleMotion(ms: Long, onDone: (MotionKind.Kind) -> Unit) = onDone(MotionKind.of(MotionSignals.car))
        override fun cancelSample() {}
    }

    @Test fun motionThenTwentyFiveForFortySecondsWithoutBluetoothIsAConfirmedTrip() {
        BumpService.chimes = 0
        val phone = Phone()
        BumpService.watchHardware = phone
        try {
            send("app.bumpbeeper.WATCH")
            phone.motion!!.invoke()                       // significant motion: a short GPS check
            val check = phone.gps!!
            val now = SystemClock.elapsedRealtime()
            check(now, 25.0)
            check(now + 2_000, 25.0)                      // driving speed twice: recording starts, silently
            assertNull(phone.gps)
            assertEquals(1, tripCount())
            assertFalse(LiveState.recording)
            gps(0, 40, 25.0)                              // the recording's own GPS: 40 s at 25 km/h
            settle { BumpService.chimes > 0 }
            assertEquals(1, BumpService.chimes)           // confirmed after 30 s
            assertTrue(LiveState.recording)
            val log = StartLog.read(app).list.map { it.second }
            assertTrue(log.toString(), log.containsAll(listOf(StartLog.Reason.MOTION_CHECK, StartLog.Reason.STARTED_MOTION, StartLog.Reason.CONFIRMED)))
        } finally {
            BumpService.watchHardware = null
        }
    }

    @Test fun drivingDetectedWhileRecordingStartsNothingNew() {
        send("app.bumpbeeper.WATCH")
        svc.drivingDetected()
        val started = LiveState.lastEvent
        svc.drivingDetected()
        assertEquals(1, tripCount())
        assertEquals(started, LiveState.lastEvent)
    }

    @Test fun stickyRestartResumesWatchingNeverRecording() {
        assertEquals(Service.START_STICKY, send(null))
        assertTrue(LiveState.watching)
        assertFalse(LiveState.recording)
        assertNotNull(svc.currentWatcher)
    }

    @Test fun stickyRestartWithDetectionOffJustStops() {
        Prefs.sp(app).edit().putBoolean(Prefs.AUTO_DETECT, false).commit()
        assertEquals(Service.START_NOT_STICKY, send(null))
        assertFalse(LiveState.watching)
        assertFalse(LiveState.recording)
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }

    @Test fun unwatchWhileIdleStopsTheService() {
        send("app.bumpbeeper.WATCH")
        send("app.bumpbeeper.UNWATCH")
        assertFalse(LiveState.watching)
        assertNull(svc.currentWatcher)
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }
}
