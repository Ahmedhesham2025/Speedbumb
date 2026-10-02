package app.bumpbeeper.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Battery mode switching while recording (#50): slow GPS + batching + no gyro only when really stopped. */
class PowerPolicyTest {

    /** One fix a second at [kmh] from [fromS] for [seconds]; returns every mode change. */
    private fun PowerPolicy.drive(fromS: Int, seconds: Int, kmh: Double): List<PowerPolicy.Mode> =
        (fromS until fromS + seconds).mapNotNull { onFix(it * 1000L, kmh) }

    @Test fun startsAtFullRateWithGyroOffUntilMoving() {
        val p = PowerPolicy()
        assertFalse(p.mode.stopped)
        assertFalse(p.mode.gyro)
        assertEquals(1000L, p.mode.gpsIntervalMs)
        assertEquals(0, p.mode.sensorLatencyUs)
        val changes = p.drive(0, 3, 30.0)
        assertEquals(listOf(PowerPolicy.Mode(stopped = false, gyro = true)), changes)
    }

    @Test fun walkingPaceDoesNotTurnTheGyroOn() {
        val p = PowerPolicy()
        assertTrue(p.drive(0, 20, 5.5).isEmpty())
        assertFalse(p.mode.gyro)
    }

    @Test fun stoppedAfter30sBelow5kmh() {
        val p = PowerPolicy()
        p.drive(0, 60, 40.0)
        assertTrue(p.drive(60, 30, 2.0).isEmpty())   // 0..29 s below: not yet
        val changes = p.drive(90, 2, 2.0)
        assertEquals(1, changes.size)
        val m = changes[0]
        assertTrue(m.stopped)
        assertFalse(m.gyro)
        assertEquals(5000L, m.gpsIntervalMs)
        assertEquals(1_000_000, m.sensorLatencyUs)
    }

    @Test fun shortStopKeepsFullRate() {
        val p = PowerPolicy()
        p.drive(0, 60, 40.0)
        p.drive(60, 20, 0.0)          // 20 s at a light
        p.drive(80, 10, 30.0)
        p.drive(90, 20, 0.0)          // another 20 s: the timer started again
        assertFalse(p.mode.stopped)
    }

    @Test fun movingAgainSwitchesBackAtOnce() {
        val p = PowerPolicy()
        p.drive(0, 10, 40.0)
        p.drive(10, 40, 0.0)
        assertTrue(p.mode.stopped)
        val back = p.onFix(55_000, 12.0)
        assertEquals(PowerPolicy.Mode(stopped = false, gyro = true), back)
        assertEquals(1000L, back!!.gpsIntervalMs)
    }

    @Test fun creepingBetweenThresholdsKeepsTheMode() {
        val p = PowerPolicy()
        p.drive(0, 10, 40.0)
        p.drive(10, 40, 0.0)
        assertTrue(p.mode.stopped)
        assertTrue(p.drive(50, 10, 5.5).isEmpty())   // 5–6 km/h: still stopped (GPS jitter at a standstill)
        assertTrue(p.mode.stopped)
    }

    @Test fun fixesWithoutSpeedAreIgnored() {
        val p = PowerPolicy()
        p.drive(0, 10, 40.0)
        assertNull(p.onFix(11_000, Double.NaN))
        assertTrue(p.drive(12, 100, Double.NaN).isEmpty())
        assertFalse(p.mode.stopped)
    }
}
