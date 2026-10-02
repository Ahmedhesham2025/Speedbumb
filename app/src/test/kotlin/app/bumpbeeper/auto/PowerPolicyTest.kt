package app.bumpbeeper.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Battery mode switching while recording (#50): 5 s GPS only when really stopped, 1 s again at the first move. */
class PowerPolicyTest {

    /** One fix a second at [kmh] from [fromS] for [seconds]; returns how many times the mode changed. */
    private fun PowerPolicy.drive(fromS: Int, seconds: Int, kmh: Double): Int =
        (fromS until fromS + seconds).count { onFix(it * 1000L, kmh) }

    @Test fun startsAtFullRate() {
        val p = PowerPolicy()
        assertFalse(p.stopped)
        assertEquals(1000L, p.gpsIntervalMs)
        assertEquals(0, p.drive(0, 10, 30.0))
    }

    @Test fun stoppedAfter30sBelow5kmh() {
        val p = PowerPolicy()
        p.drive(0, 60, 40.0)
        assertEquals(0, p.drive(60, 30, 2.0))   // 0..29 s below: not yet
        assertEquals(1, p.drive(90, 2, 2.0))
        assertTrue(p.stopped)
        assertEquals(5000L, p.gpsIntervalMs)
    }

    @Test fun shortStopKeepsFullRate() {
        val p = PowerPolicy()
        p.drive(0, 60, 40.0)
        p.drive(60, 20, 0.0)          // 20 s at a light
        p.drive(80, 10, 30.0)
        p.drive(90, 20, 0.0)          // another 20 s: the timer started again
        assertFalse(p.stopped)
    }

    @Test fun firstMovingFixSwitchesBackAtOnce() {
        val p = PowerPolicy()
        p.drive(0, 10, 40.0)
        p.drive(10, 40, 0.0)
        assertTrue(p.stopped)
        assertTrue(p.onFix(55_000, 6.0))
        assertFalse(p.stopped)
        assertEquals(1000L, p.gpsIntervalMs)
    }

    @Test fun creepingBetweenThresholdsKeepsTheMode() {
        val p = PowerPolicy()
        p.drive(0, 10, 40.0)
        p.drive(10, 40, 0.0)
        assertEquals(0, p.drive(50, 10, 5.5))   // 5–6 km/h: still stopped (GPS jitter at a standstill)
        assertTrue(p.stopped)
    }

    @Test fun fixesWithoutSpeedAreIgnored() {
        val p = PowerPolicy()
        p.drive(0, 10, 40.0)
        assertEquals(0, p.drive(10, 100, Double.NaN))
        assertFalse(p.stopped)
    }
}
