package app.bumpbeeper.auto

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Looper
import app.bumpbeeper.BumpService
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
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
        assertTrue(LiveState.recording)
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

    @Test fun drivingDetectedWhileRecordingStartsNothingNew() {
        send("app.bumpbeeper.WATCH")
        svc.drivingDetected()
        val started = LiveState.lastEvent
        svc.drivingDetected()
        assertTrue(LiveState.recording)
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
