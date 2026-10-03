package app.bumpbeeper.auto

import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** DriveWatcher's lifecycle (#49): trigger, GPS and wake lock taken and released at the right moments. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveWatcherTest {

    /** Records what the watcher asked of the phone. */
    private class FakeHardware : DriveWatcher.Hardware {
        var motion: (() -> Unit)? = null
        var arms = 0
        var passive: ((Long, Double) -> Unit)? = null
        var gps: ((Long, Double) -> Unit)? = null
        var wakeHeld = false
        override fun armMotion(onMotion: () -> Unit) { motion = onMotion; arms++ }
        override fun cancelMotion() { motion = null }
        override fun startPassive(onFix: (Long, Double) -> Unit) { passive = onFix }
        override fun stopPassive() { passive = null }
        override fun startGps(onFix: (Long, Double) -> Unit) { gps = onFix }
        override fun stopGps() { gps = null }
        override fun acquireWake(timeoutMs: Long) { wakeHeld = true }
        override fun releaseWake() { wakeHeld = false }

        /** The one-shot trigger fires (and is used up, like the real sensor). */
        fun move() { val m = motion; motion = null; m?.invoke() }
    }

    private val hw = FakeHardware()
    private var drives = 0
    private val watcher = DriveWatcher(hw, { drives++ })

    /** [seconds] of GPS fixes every 2 s at [kmh], letting the main looper (and its 10 s timer) run in between. */
    private fun gpsFor(seconds: Int, kmh: Double) {
        repeat(seconds / 2) {
            hw.gps?.invoke(SystemClock.elapsedRealtime(), kmh)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        }
    }

    @Test fun startArmsTheTriggerAndListensPassively() {
        watcher.start()
        assertNotNull(hw.motion)
        assertNotNull(hw.passive)
        assertNull(hw.gps)
        assertFalse(hw.wakeHeld)
    }

    @Test fun motionTakesGpsAndAWakeLockAndRearms() {
        watcher.start()
        hw.move()
        assertNotNull(hw.gps)
        assertTrue(hw.wakeHeld)
        assertNotNull(hw.motion)          // armed again for the next movement
        assertEquals(2, hw.arms)
    }

    @Test fun drivingCallsBackOnceAndReleasesEverything() {
        watcher.start()
        hw.move()
        val gps = hw.gps!!
        gpsFor(70, 35.0)
        assertEquals(1, drives)
        assertNull(hw.gps)
        assertFalse(hw.wakeHeld)
        assertNull(hw.motion)
        assertNull(hw.passive)
        gps(SystemClock.elapsedRealtime(), 35.0)   // a fix already queued: no second recording
        assertEquals(1, drives)
    }

    @Test fun walkingEndsTheCheckAndFreesGps() {
        watcher.start()
        hw.move()
        gpsFor(160, 5.0)
        assertEquals(0, drives)
        assertNull(hw.gps)
        assertFalse(hw.wakeHeld)
        assertNotNull(hw.motion)
        hw.move()                         // within the 3 min pause: no new GPS check
        assertNull(hw.gps)
    }

    @Test fun stopReleasesEverything() {
        watcher.start()
        hw.move()
        watcher.stop()
        assertNull(hw.gps)
        assertNull(hw.motion)
        assertNull(hw.passive)
        assertFalse(hw.wakeHeld)
        hw.passive?.invoke(SystemClock.elapsedRealtime(), 80.0)
        assertEquals(0, drives)
    }

    @Test fun snoozeIgnoresMotionAndPassiveFixes() {
        watcher.snooze(15 * 60_000L)
        watcher.start()
        assertTrue(watcher.snoozed)
        hw.move()
        hw.passive!!.invoke(SystemClock.elapsedRealtime(), 80.0)
        assertNull(hw.gps)
        assertFalse(hw.wakeHeld)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(15))
        assertFalse(watcher.snoozed)
        hw.move()
        assertNotNull(hw.gps)
    }

    @Test fun passiveFixAtSpeedStartsACheck() {
        watcher.start()
        hw.passive!!.invoke(SystemClock.elapsedRealtime(), 50.0)
        assertNotNull(hw.gps)
        assertTrue(hw.wakeHeld)
    }
}
