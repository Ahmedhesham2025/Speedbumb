package app.bumpbeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Route sampling for the speed-limit lookup, and the limit-based speed score. */
class SpeedLimitTest {
    private val start = doubleArrayOf(30.05, 31.23)

    /** A straight drive north, one fix per second; [kmhAt] gives the speed at each second. */
    private fun drive(seconds: Int, accuracyAt: (Int) -> Double = { 5.0 }, kmhAt: (Int) -> Double): List<Fix> {
        var p = start
        return (0 until seconds).map { s ->
            val kmh = kmhAt(s)
            if (s > 0) p = Geo.move(p[0], p[1], 0.0, kmhAt(s - 1) / 3.6)
            Fix(s * 1000L, p[0], p[1], kmh / 3.6, 0.0, accuracyAt(s))
        }
    }

    private fun stats(fixes: List<Fix>) = DrivingStats().apply {
        for (i in 1 until fixes.size) { movingS += 1.0; distanceM += fixes[i].speedMps }
    }

    private fun speedPart(d: DrivingStats) = d.breakdown().first { it.first == "Speed" }.second

    @Test fun samplingDensityAndAccuracy() {
        // Fast: 72 km/h = 20 m/s, so the 50 m rule wins. Every 7th fix is too inaccurate and must never be sent.
        val fast = drive(600, accuracyAt = { if (it % 7 == 0) 50.0 else 5.0 }) { 72.0 }
        val pts = RouteSampler.sample(fast)
        val bad = fast.filter { it.accuracyM > 30 }.map { it.timeMs }.toSet()
        assertTrue(pts.none { it.timeMs in bad })
        for (i in 1 until pts.size) {
            val d = Geo.distance(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
            assertTrue("gap $d m", d <= 85.0)
            assertTrue(pts[i].timeMs - pts[i - 1].timeMs <= 5000)
        }
        // 12 km minus 2 × 300 m privacy zone, at roughly one point per 50–60 m.
        assertTrue("${pts.size} points", pts.size in 150..240)

        // Slow: 18 km/h = 5 m/s, so the 5 s rule wins.
        val slow = RouteSampler.sample(drive(600) { 18.0 })
        val gaps = (1 until slow.size).map { slow[it].timeMs - slow[it - 1].timeMs }
        assertTrue(gaps.all { it <= 5000 } && gaps.count { it == 5000L } >= gaps.size - 1)
    }

    @Test fun privacyZoneTrimsStartAndEnd() {
        val fixes = drive(600) { 60.0 }
        val pts = RouteSampler.sample(fixes)
        val a = fixes.first(); val b = fixes.last()
        for (p in pts) {
            assertTrue(Geo.distance(p.lat, p.lon, a.lat, a.lon) > TripPrivacy.RADIUS_M)
            assertTrue(Geo.distance(p.lat, p.lon, b.lat, b.lon) > TripPrivacy.RADIUS_M)
        }
        // Trimmed, not more: the first point sent is just outside the zone.
        assertTrue(Geo.distance(pts.first().lat, pts.first().lon, a.lat, a.lon) < TripPrivacy.RADIUS_M + 60)
        assertTrue(Geo.distance(pts.last().lat, pts.last().lon, b.lat, b.lon) < TripPrivacy.RADIUS_M + 60)
        // A trip that never leaves the zone sends nothing.
        assertTrue(RouteSampler.sample(drive(30) { 30.0 }).isEmpty())
    }

    private fun assertChunksOk(all: List<RoutePoint>, chunks: List<List<RoutePoint>>) {
        assertEquals(all, chunks.flatten())
        for (c in chunks) {
            assertTrue(c.size <= RouteSampler.MAX_POINTS)
            val len = (1 until c.size).sumOf { Geo.distance(c[it - 1].lat, c[it - 1].lon, c[it].lat, c[it].lon) }
            assertTrue("chunk $len m", len <= RouteSampler.MAX_CHUNK_M)
            for (i in 1 until c.size) {
                assertTrue(Geo.distance(c[i - 1].lat, c[i - 1].lon, c[i].lat, c[i].lon) <= RouteSampler.MAX_GAP_M)
                assertTrue(c[i].epochMs >= c[i - 1].epochMs)
            }
        }
    }

    private fun line(n: Int, stepM: Double): List<RoutePoint> {
        var p = start
        return (0 until n).map { i -> if (i > 0) p = Geo.move(p[0], p[1], 0.0, stepM); RoutePoint(p[0], p[1], i * 1000L) }
    }

    @Test fun chunkingByPointsAndDistance() {
        // 12 000 points 5 m apart (60 km): split by the point limit.
        val dense = line(12_000, 5.0)
        val c1 = RouteSampler.chunks(dense)
        assertEquals(listOf(5000, 5000, 2000), c1.map { it.size })
        assertChunksOk(dense, c1)
        // 3000 points 80 m apart (240 km): split by distance.
        val sparse = line(3000, 80.0)
        val c2 = RouteSampler.chunks(sparse)
        assertEquals(3, c2.size)
        assertChunksOk(sparse, c2)
        // A 4-hour motorway trip, end to end.
        val long = drive(4 * 3600) { 110.0 }
        val plan = RouteSampler.plan(long)
        assertTrue(plan.size >= 5)
        assertChunksOk(RouteSampler.sample(long), plan)
    }

    @Test fun gpsGapSplitsChunkAndTimesGoForward() {
        // 5 min at 72 km/h, then no GPS for 8 km (tunnel), then 5 min more; one fix arrives out of order.
        val before = drive(300) { 72.0 }
        val jump = Geo.move(before.last().lat, before.last().lon, 0.0, 8_000.0)
        val after = (0 until 300).map { s ->
            val p = Geo.move(jump[0], jump[1], 0.0, s * 20.0)
            Fix(700_000L + s * 1000L, p[0], p[1], 20.0, 0.0, 5.0)
        }
        val late = before[150].let { Fix(710_500L - 400_000L, it.lat, it.lon, 20.0, 0.0, 5.0) }
        val fixes = before + after.take(10) + late + after.drop(10)
        val offset = 1_790_000_000_000L
        val pts = RouteSampler.sample(fixes, epochOffsetMs = offset)
        assertTrue(pts.none { it.timeMs == late.timeMs })
        assertTrue((1 until pts.size).all { pts[it].epochMs >= pts[it - 1].epochMs })
        assertEquals(pts.first().timeMs + offset, pts.first().epochMs)
        val plan = RouteSampler.plan(fixes, epochOffsetMs = offset)
        assertEquals(2, plan.size)
        assertTrue(plan[0].last().timeMs < 700_000L && plan[1].first().timeMs >= 700_000L)
        assertChunksOk(pts, plan)
    }

    @Test fun shortSpikeIgnoredSustainedExcessCounted() {
        // 65 km/h on a 60 road (+5, fine), a 2 s GPS spike to 85, and later 20 s at 85 (+25).
        val fixes = drive(600) { s -> if (s in 100..101 || s in 300..319) 85.0 else 65.0 }
        val pts = RouteSampler.sample(fixes)
        val r = SpeedLimitScoring.evaluate(fixes, pts, pts.map { 60.0 })
        assertEquals(20.0, r.over10S, 0.01)
        assertEquals(20.0, r.over20S, 0.01)
        assertEquals(0.0, r.over30S, 0.01)
        assertEquals(20 * 85 / 3.6, r.over10M, 1.0)
        assertEquals(25.0, r.maxExcessKmh, 0.01)
        assertTrue("known ${r.knownShare}", r.knownShare > 0.9)

        // Rescoring: the road limits now drive the speed part. The old 90 km/h threshold saw nothing.
        val old = stats(fixes)
        val rescored = old.withSpeedLimits(r)
        assertTrue(rescored.usesSpeedLimits)
        assertEquals(20.0, rescored.overLimit10S, 0.01)
        assertEquals(100, speedPart(old))
        assertEquals(SpeedLimitScoring.subScore(r), speedPart(rescored))
        assertTrue(speedPart(rescored) in 80..95)
        assertTrue(rescored.score() < old.score())
        // The original stats are left alone.
        assertEquals(-1.0, old.limitKnownShare, 0.0)

        // Without the spike-free stretch, a trip with only the spike scores a clean 100.
        val spikeOnly = drive(600) { s -> if (s in 100..101) 85.0 else 65.0 }
        val p2 = RouteSampler.sample(spikeOnly)
        assertEquals(100, SpeedLimitScoring.subScore(SpeedLimitScoring.evaluate(spikeOnly, p2, p2.map { 60.0 })))
    }

    @Test fun mostlyUnknownLimitsFallBackToFixedThreshold() {
        val fixes = drive(600) { s -> if (s in 200..400) 110.0 else 70.0 }
        val pts = RouteSampler.sample(fixes)
        // Limit known only for the first 30 % of the points.
        val limits = pts.mapIndexed { i, _ -> if (i < pts.size * 3 / 10) 60.0 else null }
        val r = SpeedLimitScoring.evaluate(fixes, pts, limits)
        assertTrue("known ${r.knownShare}", r.knownShare in 0.2..0.45)

        val old = stats(fixes).apply { speedingS = 200.0; speedingExcess = 200.0 * 20 }
        val rescored = old.withSpeedLimits(r)
        assertFalse(rescored.usesSpeedLimits)
        assertEquals(old.score(), rescored.score())
        assertEquals(speedPart(old), speedPart(rescored))
        // Still stored, for the "limits known for X % of the trip" line.
        assertEquals(r.knownShare, rescored.limitKnownShare, 1e-9)
    }

    @Test fun limitOnlyFromNearbyPoints() {
        val pts = listOf(RoutePoint(30.0, 31.0, 0L), RoutePoint(30.01, 31.0, 60_000L))
        val limits = listOf(50.0, null)
        fun at(t: Long, lat: Double) = SpeedLimitScoring.limitAt(Fix(t, lat, 31.0, 10.0, 0.0, 5.0), pts, limits)
        assertEquals(50.0, at(8_000L, 30.002)!!, 0.0)      // 8 s away
        assertEquals(50.0, at(25_000L, 30.0005)!!, 0.0)    // 25 s away but only ~55 m
        assertNull(at(25_000L, 30.003))                    // far in time and space
        assertNull(at(59_000L, 30.01))                     // nearest point has no limit
    }

    @Test fun scoreAlwaysWithin0To100() {
        val rnd = Random(7)
        repeat(200) {
            val fixes = drive(300 + rnd.nextInt(900)) { rnd.nextDouble(0.0, 220.0) }
            val pts = RouteSampler.sample(fixes)
            val limits = pts.map { if (rnd.nextDouble() < 0.2) null else listOf(30.0, 50.0, 80.0, 120.0)[rnd.nextInt(4)] }
            val r = SpeedLimitScoring.evaluate(fixes, pts, limits)
            assertTrue(SpeedLimitScoring.subScore(r) in 0..100)
            assertTrue(r.knownShare in 0.0..1.0)
            val d = stats(fixes).withSpeedLimits(r)
            assertTrue(d.score() in 0..100)
            assertTrue(d.breakdown().all { it.second in 0..100 })
        }
        // Flat out over every limit: the speed part bottoms out at 0, never below.
        val wild = drive(600) { 200.0 }
        val p = RouteSampler.sample(wild)
        assertEquals(0, SpeedLimitScoring.subScore(SpeedLimitScoring.evaluate(wild, p, p.map { 30.0 })))
    }
}
