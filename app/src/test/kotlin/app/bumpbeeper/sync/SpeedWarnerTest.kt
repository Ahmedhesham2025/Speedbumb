package app.bumpbeeper.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The speeding warning: margin, 3 s over, once per 60 s, again on a new limit, waiting for bump warnings. */
class SpeedWarnerTest {
    private val w = SpeedWarner()
    private fun at(s: Int, kmh: Double, limit: Int? = 60, margin: Int = 10, mayPlay: Boolean = true) =
        w.onSpeed(s * 1000L, kmh, limit, margin, mayPlay)

    @Test fun overLimitLevels() {
        at(0, 55.0); assertEquals(0, w.overLimit)
        at(1, 65.0); assertEquals(1, w.overLimit)
        at(2, 71.0); assertEquals(2, w.overLimit)
        at(3, 71.0, limit = null); assertEquals(0, w.overLimit)   // unknown limit
        at(4, Double.NaN); assertEquals(0, w.overLimit)
    }

    @Test fun warnsAfterThreeSecondsOverTheMargin() {
        assertNull(at(0, 70.0))   // exactly limit + margin: not over it
        assertNull(at(1, 75.0))
        assertNull(at(3, 75.0))
        assertEquals(60, at(4, 75.0))
        assertNull(at(5, 75.0))
    }

    @Test fun dipBelowRestartsTheThreeSeconds() {
        at(0, 75.0); at(2, 75.0); at(3, 65.0)
        assertNull(at(4, 75.0))
        assertNull(at(6, 75.0))
        assertEquals(60, at(7, 75.0))
    }

    @Test fun marginIsTheChosenOne() {
        at(0, 68.0, margin = 5)
        assertEquals(60, at(3, 68.0, margin = 5))
        val w20 = SpeedWarner()
        for (s in 0..10) assertNull(w20.onSpeed(s * 1000L, 78.0, 60, 20, true))
    }

    @Test fun repeatsAtMostOncePerMinute() {
        at(0, 75.0)
        assertEquals(60, at(3, 75.0))
        for (s in 4..62) assertNull(at(s, 75.0))
        assertEquals(60, at(63, 75.0))
    }

    @Test fun newLimitWarnsAgainAtOnce() {
        at(0, 75.0)
        assertEquals(60, at(3, 75.0))
        assertEquals(50, at(4, 75.0, limit = 50))   // still over for 4 s: the new limit is said now
    }

    @Test fun waitsWhileMutedOrABumpWarningPlays() {
        at(0, 75.0)
        assertNull(at(3, 75.0, mayPlay = false))
        assertNull(at(5, 75.0, mayPlay = false))
        assertEquals(60, at(6, 75.0))   // not counted as given: plays as soon as it may
    }
}
