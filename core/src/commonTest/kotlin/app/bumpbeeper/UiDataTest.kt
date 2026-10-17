@file:Suppress("DEPRECATION")   // RemoteSpot still carries the shared map's old kind and side

package app.bumpbeeper

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Data for the redesigned screens: the next spots ahead (Drive card) and where a trip's points went. */
class UiDataTest {
    private fun stats(km: Double, block: DrivingStats.() -> Unit = {}) = DrivingStats().apply {
        distanceM = km * 1000; movingS = km * 72.0   // 50 km/h
        block()
    }

    @Test fun breakdownAddsUpTo100MinusTheScore() {
        val trips = listOf(
            stats(12.0),
            stats(12.0) { harshBrakes = 1 },
            stats(7.3) { harshBrakes = 2; harshAccels = 1; harshCorners = 1; swerves = 1; phoneUse = 2; bumpsFast = 1; speedingS = 50.0; speedingExcess = 400.0 },
            stats(3.0) { harshBrakes = 3; harshAccels = 3; swerves = 2 },
            stats(20.0) { speedingS = 600.0; speedingExcess = 9000.0; maxExcessKmh = 31.0; harshAccels = 4 },
            stats(5.0) { harshBrakes = 9; harshAccels = 9; harshCorners = 9; swerves = 9; phoneUse = 9; bumpsFast = 9; speedingS = 300.0; speedingExcess = 9000.0 },
        )
        for (d in trips) {
            val b = d.scoreBreakdown()!!
            assertEquals(d.score(), b.score)
            assertEquals(100 - b.score, b.items.sumOf { it.points }, "points ${b.items.map { "${it.category}=${it.points}" }}")
            assertEquals(ScoreCategory.entries.toList(), b.items.map { it.category })
            assertTrue(b.items.all { it.points >= 0 })
            // Whole points stay close to the exact share (unless the score hit 0).
            if (b.score > 0) assertTrue(b.items.all { abs(it.points - it.rawPoints) < 1.5 }, "${b.items.map { it.rawPoints }}")
        }
        val mixed = trips[2].scoreBreakdown()!!
        assertEquals(2, mixed[ScoreCategory.HARSH_BRAKING].count)
        assertEquals(1, mixed[ScoreCategory.HARSH_ACCEL].count)
        assertTrue(mixed[ScoreCategory.HARSH_BRAKING].rawPoints > mixed[ScoreCategory.HARSH_ACCEL].rawPoints, "braking costs more")
        val fast = trips[4].scoreBreakdown()!!
        assertEquals(600.0 / 1440.0 * 100, fast.speedingPct, 1e-9)
        assertEquals(31.0, fast.maxOverKmh)
        assertEquals(0, trips[0].scoreBreakdown()!!.items.sumOf { it.points })
        assertNull(stats(0.3).scoreBreakdown(), "too short to score")
    }

    @Test fun shareSplitsWholePoints() {
        assertEquals(listOf(1, 1, 1), ScoreBreakdown.share(listOf(1.0, 1.0, 1.0), 3))
        assertEquals(listOf(2, 1, 0), ScoreBreakdown.share(listOf(1.6, 1.3, 0.1), 3))
        assertEquals(listOf(0, 0), ScoreBreakdown.share(listOf(0.0, 0.0), 0))
        assertEquals(listOf(50, 50), ScoreBreakdown.share(listOf(80.0, 80.0), 100))
    }

    /** The monitor keeps the most over its fixed threshold. */
    @Test fun maxOverFromTheMonitor() {
        val r = Simulator(5).drive(MemoryStore(), DriveSpec(cruiseKmh = 70.0), drivingCfg = DrivingConfig().apply { speedLimitKmh = 60.0 })
        assertTrue(r.driving.maxExcessKmh in 5.0..20.0, "max over ${r.driving.maxExcessKmh}")
        assertTrue(r.driving.scoreBreakdown()!!.maxOverKmh == r.driving.maxExcessKmh)
    }

    // ---------- next spots ahead ----------

    private fun at(p: Double) = Geo.move(30.0444, 31.2357, 90.0, p)
    private fun spot(id: Long, p: Double, severity: Double = 7.0, devices: Int = 3, heading: Double = 90.0) =
        at(p).let { RemoteSpot(id, it[0], it[1], heading, BumpKind.BUMP, Side.RIGHT, severity, devices) }

    @Test fun nextSpotsAheadInOrder() {
        val spots = listOf(
            spot(1, 900.0, severity = 3.0), spot(2, 1550.0, severity = 7.0), spot(3, 1200.0, severity = 4.2, devices = 1),
            spot(4, 950.0, heading = 270.0),   // the other direction
            spot(5, 400.0),                    // behind by then
        )
        val e = BumpEngine(EngineConfig(), MemoryStore(), object : EngineListener {}, { 0L }, spotSource = ListSpotSource(spots))
        var t = 0L
        var seen: List<SpotAhead> = emptyList()
        var p = 0.0
        while (p <= 800.0) {
            val q = at(p)
            e.onFix(Fix(t, q[0], q[1], 14.0, 90.0, 5.0))
            if (p >= 700.0 && seen.isEmpty()) seen = e.spotsAhead
            p += 14.0; t += 1000
        }
        assertEquals(2, seen.size, "two spots ahead from 700 m on")
        val a = e.spotsAhead
        assertEquals(2, a.size)
        assertTrue(a[0].distanceM < a[1].distanceM)
        assertEquals(Severity.MILD, a[0].severity)
        assertEquals(1, a[0].beeps)
        assertEquals(Confidence.FULL, a[0].confidence)
        assertTrue(abs(a[0].distanceM - (900.0 - (p - 14.0))) < 5.0, "first ${a[0].distanceM} m")
        assertEquals(Confidence.SOFT, a[1].confidence, "then the maybe at 1200 m")
        assertTrue(abs(a[1].distanceM - (1200.0 - (p - 14.0))) < 5.0, "then ${a[1].distanceM} m")
        // Past the first: the strong one at 1550 m moves up behind the maybe.
        while (p <= 1000.0) { val q = at(p); e.onFix(Fix(t, q[0], q[1], 14.0, 90.0, 5.0)); p += 14.0; t += 1000 }
        assertEquals(listOf(Confidence.SOFT, Confidence.FULL), e.spotsAhead.map { it.confidence })
        assertEquals(Severity.STRONG, e.spotsAhead[1].severity)
        assertEquals(3, e.spotsAhead[1].beeps)
    }
}
