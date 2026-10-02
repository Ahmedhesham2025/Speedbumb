package app.bumpbeeper.ui

import app.bumpbeeper.DrivingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The speed-limit words on Settings and Trips, and how trips add up for "Your driving". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedLimitTextTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun trip(km: Double, share: Double, knownS: Double = 0.0, o10: Double = 0.0, o20: Double = 0.0,
                     o30: Double = 0.0, maxOver: Double = 0.0) = DrivingStats().apply {
        distanceM = km * 1000; movingS = km * 60   // 60 km/h
        limitKnownShare = share; limitKnownS = knownS
        overLimit10S = o10; overLimit20S = o20; overLimit30S = o30; maxOverLimitKmh = maxOver
    }

    @Test fun minSecFormat() {
        assertEquals("0:00", SpeedLimitText.minSec(0.0))
        assertEquals("0:09", SpeedLimitText.minSec(9.4))
        assertEquals("2:05", SpeedLimitText.minSec(125.0))
        assertEquals("75:00", SpeedLimitText.minSec(4500.0))
        assertEquals("0:00", SpeedLimitText.minSec(-3.0))
        assertEquals("0:00", SpeedLimitText.minSec(Double.NaN))
        assertEquals("2:05 / 0:40 / 0:00", SpeedLimitText.bands(trip(10.0, 0.9, 600.0, 125.0, 40.0, 0.0)))
    }

    @Test fun percentRoundsDown() {
        assertEquals("49", SpeedLimitText.percent(0.499))
        assertEquals("57", SpeedLimitText.percent(0.57))
        assertEquals("100", SpeedLimitText.percent(1.0))
        assertEquals("0", SpeedLimitText.percent(-1.0))
    }

    @Test fun tripLines() {
        assertEquals("Speed limits known for 82 % of the trip · © TomTom",
            SpeedLimitText.tripLine(ctx, trip(10.0, 0.82), waiting = false))
        assertEquals("Speed limits known for 30 % only, so the usual speed rule was used. © TomTom",
            SpeedLimitText.tripLine(ctx, trip(10.0, 0.30), waiting = false))
        assertEquals("Looking up speed limits…", SpeedLimitText.tripLine(ctx, trip(10.0, -1.0), waiting = true))
        assertNull("not opted in, or never queued", SpeedLimitText.tripLine(ctx, trip(10.0, -1.0), waiting = false))
        assertEquals("+12 km/h", SpeedLimitText.maxOver(ctx, trip(10.0, 0.9, maxOver = 12.4)))
    }

    @Test fun settingsStatus() {
        assertEquals("", SpeedLimitText.settingsStatus(ctx, on = false, allowed = false))
        assertEquals("", SpeedLimitText.settingsStatus(ctx, on = true, allowed = true))
        assertTrue(SpeedLimitText.settingsStatus(ctx, on = true, allowed = false).contains("shared map is off"))
    }

    @Test fun combineSumsTheNewFields() {
        val sum = SpeedLimitText.combine(listOf(
            trip(30.0, 0.9, knownS = 1600.0, o10 = 100.0, o20 = 20.0, o30 = 5.0, maxOver = 34.0),
            trip(10.0, 0.2, knownS = 120.0, o10 = 10.0, maxOver = 12.0),
        ))
        assertEquals(40_000.0, sum.distanceM, 1e-9)
        // (0.9 × 30 + 0.2 × 10) / 40 km = 0.725 of the distance known.
        assertEquals(0.725, sum.limitKnownShare, 1e-9)
        assertEquals(1720.0, sum.limitKnownS, 1e-9)
        assertEquals(110.0, sum.overLimit10S, 1e-9)
        assertEquals(20.0, sum.overLimit20S, 1e-9)
        assertEquals(5.0, sum.overLimit30S, 1e-9)
        assertEquals(34.0, sum.maxOverLimitKmh, 1e-9)
        assertTrue(sum.usesSpeedLimits)
        assertEquals("Speed scored against real limits (known for 72 % of the distance) · © TomTom", SpeedLimitText.summaryLine(ctx, sum))
    }

    @Test fun tripsNeverLookedUpCountAsUnknown() {
        val sum = SpeedLimitText.combine(listOf(trip(10.0, 1.0, knownS = 600.0), trip(30.0, -1.0)))
        assertEquals(0.25, sum.limitKnownShare, 1e-9)
        assertFalse("most of the distance has no known limit", sum.usesSpeedLimits)
        assertNull(SpeedLimitText.summaryLine(ctx, sum))
        assertEquals(-1.0, SpeedLimitText.combine(listOf(trip(10.0, -1.0))).limitKnownShare, 0.0)
        assertEquals(-1.0, SpeedLimitText.combine(emptyList()).limitKnownShare, 0.0)
    }

    @Test fun combineMatchesTheOldSumWithoutLimits() {
        val a = trip(12.0, -1.0).apply { speedingS = 60.0; speedingExcess = 300.0; harshBrakes = 2; maxSpeedKmh = 110.0 }
        val b = trip(8.0, -1.0).apply { speedingS = 30.0; speedingExcess = 90.0; phoneUse = 1; maxSpeedKmh = 95.0 }
        val sum = SpeedLimitText.combine(listOf(a, b))
        assertEquals(90.0, sum.speedingS, 1e-9)
        assertEquals(390.0, sum.speedingExcess, 1e-9)
        assertEquals(2, sum.harshBrakes)
        assertEquals(1, sum.phoneUse)
        assertEquals(110.0, sum.maxSpeedKmh, 1e-9)
        assertEquals(1200.0, sum.movingS, 1e-9)
    }
}
