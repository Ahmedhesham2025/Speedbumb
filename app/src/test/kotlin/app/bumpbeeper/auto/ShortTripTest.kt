package app.bumpbeeper.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trips under 30 s or 200 m are not kept; a detected drive starts where the movement started. */
class ShortTripTest {
    @Test fun underThirtySecondsIsDiscardedHoweverFar() {
        assertTrue(ShortTrip.discard(29_999, 5_000.0))
        assertTrue(ShortTrip.discard(0, 0.0))
    }

    @Test fun underTwoHundredMetresIsDiscardedHoweverLong() {
        assertTrue(ShortTrip.discard(10 * 60_000L, 199.9))    // sat in a car park with the engine on
        assertTrue(ShortTrip.discard(10 * 60_000L, Double.NaN))
    }

    @Test fun thirtySecondsAndTwoHundredMetresIsKept() {
        assertFalse(ShortTrip.discard(30_000, 200.0))
        assertFalse(ShortTrip.discard(20 * 60_000L, 8_000.0))
    }

    @Test fun aDetectedDriveStartsAtTheMovementStart() {
        assertEquals(1_000_000L - 45_000L, TripStart.wallMs(1_000_000L, 500_000L, 455_000L))
    }

    @Test fun aStartByTheUserOrTheCarIsNow() {
        assertEquals(1_000_000L, TripStart.wallMs(1_000_000L, 500_000L, -1L))
    }

    @Test fun theStartGoesBackAtMostFiveMinutesAndNeverForward() {
        assertEquals(1_000_000L - TripStart.MAX_BACKDATE_MS, TripStart.wallMs(1_000_000L, 900_000L, 1L))
        assertEquals(1_000_000L, TripStart.wallMs(1_000_000L, 500_000L, 600_000L))   // a fix time "in the future"
    }
}
