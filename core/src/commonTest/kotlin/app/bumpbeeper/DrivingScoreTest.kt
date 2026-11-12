package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals

/** The driving score's shape (owner, beta10): no score for tiny trips, no speeding without a known limit, capped areas. */
class DrivingScoreTest {
    private fun trip(km: Double, minutes: Double, setup: DrivingStats.() -> Unit = {}) =
        DrivingStats().apply { distanceM = km * 1000; movingS = minutes * 60; setup() }

    /** 0.2 km in 16 min (a parking lot), or 5 km in under 2 min of driving: no score ("Too short to score"), not 0. */
    @Test fun aTinyTripGetsNoScore() {
        assertEquals(-1, trip(0.2, 16.0).score())
        assertEquals(-1, trip(0.9, 5.0).score())
        assertEquals(-1, trip(5.0, 1.5).score())
        assertEquals(null, trip(0.2, 16.0).scoreBreakdown())
        assertEquals(100, trip(1.0, 2.0).score())
        assertEquals("Too short to score", DrivingStats.grade(-1))
    }

    /** 91.8 km of highway, 44 min over the fixed 90 km/h, no road limits known: no speeding (it used to cost 40). */
    @Test fun anUnknownLimitIsNoSpeeding() {
        val highway = trip(91.8, 53.0) { speedingS = 2640.0; speedingExcess = 2640.0 * 20; limitKnownShare = -1.0 }
        assertEquals(100, highway.score())
        assertEquals(0, highway.scoreBreakdown()!![ScoreCategory.SPEEDING].points)
        val partly = trip(91.8, 53.0) { speedingS = 2640.0; limitKnownShare = 0.3; limitKnownS = 900.0; overLimit10S = 900.0 }
        assertEquals(100, partly.score(), "limits known for under half the trip: not used")
        // Known: 10 of 50 min more than 10 km/h over costs 12 (1 point per minute per hour).
        val known = trip(91.8, 53.0) { limitKnownShare = 0.9; limitKnownS = 3000.0; overLimit10S = 600.0 }
        assertEquals(88, known.score())
    }

    /** Events cost per 10 km (a trip under 5 km counts as 5 km), so one event can't crush a short trip. */
    @Test fun eventsAreNormalisedPerDistance() {
        assertEquals(90, trip(2.0, 6.0) { harshBrakes = 1 }.score())     // counts as 5 km: 5 x 2
        assertEquals(99, trip(50.0, 40.0) { harshBrakes = 1 }.score())   // 5 / 5
        assertEquals(95, trip(10.0, 15.0) { harshBrakes = 1 }.score())
    }

    /** No area takes more than its cap: 8 phone uses on 10.9 km cost 10 (they used to cost 44 and score 18). */
    @Test fun eachAreaIsCapped() {
        val t = trip(10.9, 13.0) { phoneUse = 8; harshBrakes = 1; harshAccels = 1; harshCorners = 1 }
        val p = t.penalties()
        assertEquals(10.0, p.getValue(ScoreCategory.PHONE_USE))
        assertEquals(79, t.score())   // 100 - 10 - (5 + 3 + 4) x 10 / 10.9
        val b = t.scoreBreakdown()!!
        assertEquals(100 - t.score(), b.items.sumOf { it.points })
        assertEquals(100.0, DrivingStats.CAPS.values.sum())
        val worst = trip(10.0, 15.0) { harshBrakes = 50; harshAccels = 50; harshCorners = 50; swerves = 50; phoneUse = 50; bumpsFast = 50 }
        assertEquals(40, worst.score(), "everything but speeding at its cap")
    }
}
