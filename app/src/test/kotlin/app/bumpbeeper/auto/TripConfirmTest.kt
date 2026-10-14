package app.bumpbeeper.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic trips record from 0 s but count only after 30 s of driving; short ones are not kept. */
class TripConfirmTest {
    /** Fixes every 2 s from 0 s for [seconds] at [kmh]; returns how often the trip got confirmed (the chime). */
    private fun TripConfirm.drive(seconds: Int, kmh: Double): Int = (0 until seconds step 2).count { onFix(it * 1000L, kmh) }

    @Test fun googleStartWithTwentySecondsOfDrivingIsDiscardedWithoutChime() {
        val c = TripConfirm(automatic = true)
        assertEquals(0, c.drive(20, 40.0))
        assertFalse(c.confirmed)
        assertFalse(c.keep(20_000, 220.0))
        assertFalse(c.keep(10 * 60_000L, 5_000.0))   // however long it then sat still: never 30 s of driving
    }

    @Test fun bluetoothStartWithThirtyFiveSecondsChimesOnceAndCountsFromZero() {
        val c = TripConfirm(automatic = true)
        assertEquals(1, c.drive(36, 40.0))            // fixes at 0..34 s: confirmed at 32 s, once
        assertEquals(0, c.drive(60, 40.0))            // more driving: no second chime
        assertTrue(c.confirmed)
        assertTrue(c.keep(35_000, 390.0))             // the trip is the whole 35 s, from 0 s
    }

    @Test fun exactlyThirtySecondsIsNotEnough() {
        val c = TripConfirm(automatic = true)
        assertEquals(0, c.drive(32, 40.0))            // fixes at 0..30 s
    }

    @Test fun walkingAndCyclingNeverConfirm() {
        assertEquals(0, TripConfirm(automatic = true).drive(600, 5.0))
        assertEquals(0, TripConfirm(automatic = true).drive(600, 18.0))
    }

    @Test fun manualStartIsConfirmedAtOnceWithoutChime() {
        val c = TripConfirm(automatic = false)
        assertTrue(c.confirmed)
        assertEquals(0, c.drive(60, 40.0))
        assertFalse(c.confirmNow())
    }

    @Test fun startPressedDuringASilentTripConfirmsIt() {
        val c = TripConfirm(automatic = true)
        assertTrue(c.confirmNow())
        assertEquals(0, c.drive(60, 40.0))            // no chime later either
    }

    @Test fun confirmedTripsUnderThirtySecondsOrTwoHundredMetresAreDiscarded() {
        val c = TripConfirm(automatic = false)
        assertFalse(c.keep(29_999, 5_000.0))
        assertFalse(c.keep(10 * 60_000L, 199.9))
        assertFalse(c.keep(10 * 60_000L, Double.NaN))
        assertTrue(c.keep(30_000, 200.0))
        assertTrue(ShortTrip.discard(0, 0.0))
    }
}
