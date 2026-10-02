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
    private fun drive(seconds: Int, accuracyAt: (Int) -> Double = { 5.0 }, stepMs: Long = 1000L, kmhAt: (Int) -> Double): List<Fix> {
        var p = start
        return (0 until seconds).map { s ->
            val kmh = kmhAt(s)
            if (s > 0) p = Geo.move(p[0], p[1], 0.0, kmhAt(s - 1) / 3.6 * stepMs / 1000.0)
            Fix(s * stepMs, p[0], p[1], kmh / 3.6, 0.0, accuracyAt(s))
        }
    }
    private fun stats(fixes: List<Fix>) = DrivingStats().apply { for (i in 1 until fixes.size) { movingS += 1.0; distanceM += fixes[i].speedMps } }
    private fun speedPart(d: DrivingStats) = d.breakdown().first { it.first == "Speed" }.second
    private fun d(a: RoutePoint, b: RoutePoint) = Geo.distance(a.lat, a.lon, b.lat, b.lon)
    private fun d(a: RoutePoint, b: Fix) = Geo.distance(a.lat, a.lon, b.lat, b.lon)

    @Test fun samplingDensityAndAccuracy() {
        // Fast: 72 km/h = 20 m/s, so the 50 m rule wins. Every 7th fix is too inaccurate and must never be sent.
        val fast = drive(600, accuracyAt = { if (it % 7 == 0) 50.0 else 5.0 }) { 72.0 }
        val pts = RouteSampler.sample(fast, 0L)
        val bad = fast.filter { it.accuracyM > 30 }.map { it.timeMs }.toSet()
        assertTrue(pts.none { it.timeMs in bad })
        for (i in 1 until pts.size) assertTrue(d(pts[i - 1], pts[i]) <= 85.0 && pts[i].timeMs - pts[i - 1].timeMs <= 5000)
        // 12 km minus 2 × 300 m privacy zone, at roughly one point per 50–60 m.
        assertTrue("${pts.size} points", pts.size in 150..240)
        // Slow: 18 km/h = 5 m/s, so the 5 s rule wins.
        val slow = RouteSampler.sample(drive(600) { 18.0 }, 0L)
        val gaps = (1 until slow.size).map { slow[it].timeMs - slow[it - 1].timeMs }
        assertTrue(gaps.all { it <= 5000 } && gaps.count { it == 5000L } >= gaps.size - 1)
    }

    @Test fun privacyZoneTrimsStartAndEnd() {
        val fixes = drive(600) { 60.0 }
        val pts = RouteSampler.sample(fixes, 0L)
        val a = fixes.first(); val b = fixes.last()
        assertTrue(pts.all { d(it, a) > TripPrivacy.RADIUS_M && d(it, b) > TripPrivacy.RADIUS_M })
        // Trimmed, not more: the first and last points sent are just outside the zone.
        assertTrue(d(pts.first(), a) < TripPrivacy.RADIUS_M + 60 && d(pts.last(), b) < TripPrivacy.RADIUS_M + 60)
        // A trip that never leaves the zone sends nothing.
        assertTrue(RouteSampler.sample(drive(30) { 30.0 }, 0L).isEmpty())
    }

    /** Every chunk is sendable; together they are [all] in order, less [dropped] points. */
    private fun assertChunksOk(all: List<RoutePoint>, chunks: List<List<RoutePoint>>, dropped: Int = 0) {
        val flat = chunks.flatten()
        assertEquals(all.size - dropped, flat.size)
        val kept = flat.toSet()
        assertEquals(flat, all.filter { it in kept })
        for (c in chunks) {
            assertTrue(c.size in 2..RouteSampler.MAX_POINTS)
            assertTrue((1 until c.size).sumOf { d(c[it - 1], c[it]) } <= RouteSampler.MAX_CHUNK_M)
            for (i in 1 until c.size) assertTrue(d(c[i - 1], c[i]) <= RouteSampler.MAX_GAP_M && c[i].epochMs >= c[i - 1].epochMs)
        }
    }

    private fun line(n: Int, stepM: Double): List<RoutePoint> {
        var p = start
        return (0 until n).map { i -> if (i > 0) p = Geo.move(p[0], p[1], 0.0, stepM); RoutePoint(p[0], p[1], i * 1000L, i * 1000L) }
    }

    @Test fun chunkingByPointsAndDistance() {
        // 12 000 points 5 m apart (60 km): split by the point limit.
        val dense = line(12_000, 5.0)
        assertEquals(listOf(5000, 5000, 2000), RouteSampler.chunks(dense).map { it.size })
        assertChunksOk(dense, RouteSampler.chunks(dense))
        // 3000 points 80 m apart (240 km): split by distance, 80 km each.
        val sparse = line(3000, 80.0)
        assertEquals(3, RouteSampler.chunks(sparse).size)
        assertChunksOk(sparse, RouteSampler.chunks(sparse))
        // A 4-hour motorway trip, end to end.
        val long = drive(4 * 3600) { 110.0 }
        val plan = RouteSampler.plan(long, 0L)
        assertTrue(plan.size >= 5)
        assertChunksOk(RouteSampler.sample(long, 0L), plan)
    }

    @Test fun gpsGapSplitsChunkAndTimesGoForward() {
        // 5 min at 72 km/h, then no GPS for 8 km (tunnel), then 5 min more; one fix arrives out of order.
        val before = drive(300) { 72.0 }
        val jump = Geo.move(before.last().lat, before.last().lon, 0.0, 8_000.0)
        val after = (0 until 300).map { s -> Geo.move(jump[0], jump[1], 0.0, s * 20.0).let { Fix(700_000L + s * 1000L, it[0], it[1], 20.0, 0.0, 5.0) } }
        val late = before[150].let { Fix(710_500L - 400_000L, it.lat, it.lon, 20.0, 0.0, 5.0) }
        val fixes = before + after.take(10) + late + after.drop(10)
        val offset = 1_790_000_000_000L
        val pts = RouteSampler.sample(fixes, epochOffsetMs = offset)
        assertTrue(pts.none { it.timeMs == late.timeMs })
        assertTrue(pts.all { it.epochMs == it.timeMs + offset } && (1 until pts.size).all { pts[it].epochMs >= pts[it - 1].epochMs })
        val plan = RouteSampler.plan(fixes, epochOffsetMs = offset)
        assertEquals(2, plan.size)
        assertTrue(plan[0].last().timeMs < 700_000L && plan[1].first().timeMs >= 700_000L)
        assertChunksOk(pts, plan)
    }

    @Test fun shortSpikeIgnoredSustainedExcessCounted() {
        // 65 km/h on a 60 road (+5, fine), a 2 s GPS spike to 85, and later 20 s at 85 (+25).
        val fixes = drive(600) { s -> if (s in 100..101 || s in 300..319) 85.0 else 65.0 }
        val pts = RouteSampler.sample(fixes, 0L)
        val r = SpeedLimitScoring.evaluate(fixes, pts, pts.map { 60.0 })
        assertEquals(listOf(20.0, 20.0, 0.0), listOf(r.over10S, r.over20S, r.over30S).map { Math.round(it * 100) / 100.0 })
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
        assertTrue(speedPart(rescored) in 80..95 && rescored.score() < old.score())
        assertEquals(-1.0, old.limitKnownShare, 0.0)   // the original stats are left alone
        // Without the spike-free stretch, a trip with only the spike scores a clean 100.
        val spikeOnly = drive(600) { s -> if (s in 100..101) 85.0 else 65.0 }
        val p2 = RouteSampler.sample(spikeOnly, 0L)
        assertEquals(100, SpeedLimitScoring.subScore(SpeedLimitScoring.evaluate(spikeOnly, p2, p2.map { 60.0 })))
    }

    @Test fun mostlyUnknownLimitsFallBackToFixedThreshold() {
        val fixes = drive(600) { s -> if (s in 200..400) 110.0 else 70.0 }
        val pts = RouteSampler.sample(fixes, 0L)
        // Limit known only for the first 30 % of the points.
        val limits = pts.mapIndexed { i, _ -> if (i < pts.size * 3 / 10) 60.0 else null }
        val r = SpeedLimitScoring.evaluate(fixes, pts, limits)
        assertTrue("known ${r.knownShare}", r.knownShare in 0.2..0.45)
        val old = stats(fixes).apply { speedingS = 200.0; speedingExcess = 200.0 * 20 }
        val rescored = old.withSpeedLimits(r)
        assertFalse(rescored.usesSpeedLimits)
        assertTrue(old.score() == rescored.score() && speedPart(old) == speedPart(rescored))
        // Still stored, for the "limits known for X % of the trip" line.
        assertEquals(r.knownShare, rescored.limitKnownShare, 1e-9)
    }

    @Test fun limitOnlyFromNearbyPoints() {
        val pts = listOf(RoutePoint(30.0, 31.0, 0L, 0L), RoutePoint(30.01, 31.0, 60_000L, 60_000L))
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
            val pts = RouteSampler.sample(fixes, 0L)
            val limits = pts.map { if (rnd.nextDouble() < 0.2) null else listOf(30.0, 50.0, 80.0, 120.0)[rnd.nextInt(4)] }
            val r = SpeedLimitScoring.evaluate(fixes, pts, limits)
            assertTrue(SpeedLimitScoring.subScore(r) in 0..100 && r.knownShare in 0.0..1.0)
            val s = stats(fixes).withSpeedLimits(r)
            assertTrue(s.score() in 0..100 && s.breakdown().all { it.second in 0..100 })
        }
        // Flat out over every limit: the speed part bottoms out at 0, never below.
        val wild = drive(600) { 200.0 }
        val p = RouteSampler.sample(wild, 0L)
        assertEquals(0, SpeedLimitScoring.subScore(SpeedLimitScoring.evaluate(wild, p, p.map { 30.0 })))
    }

    @Test fun chunksNeverHaveOnePoint() {
        // 5001 points, or a distance split, leaving one point: it takes the last point of the chunk before.
        for ((pts, sizes) in listOf(line(5001, 5.0) to listOf(4999, 2), line(1003, 79.9) to listOf(1001, 2))) {
            assertEquals(sizes, RouteSampler.chunks(pts).map { it.size })
            assertChunksOk(pts, RouteSampler.chunks(pts))
        }
        // One point between two 8 km gaps can't go anywhere: dropped.
        val a = line(10, 50.0)
        fun shift(pts: List<RoutePoint>, m: Double, t: Long) =
            pts.map { Geo.move(it.lat, it.lon, 0.0, m).let { q -> RoutePoint(q[0], q[1], it.timeMs + t, it.epochMs + t) } }
        val lone = shift(a.takeLast(1), 8_000.0, 100_000L)
        val all = a + lone + shift(a, 16_000.0, 200_000L)   // the next stretch starts 7.5 km past the lone point
        val c = RouteSampler.chunks(all)
        assertEquals(listOf(10, 10), c.map { it.size })
        assertChunksOk(all, c, dropped = 1)
        // A sample of one point gives no request at all.
        assertTrue(RouteSampler.chunks(line(1, 0.0)).isEmpty())
        assertTrue(RouteSampler.plan(drive(40) { 60.0 }, 0L).all { it.size >= 2 })
    }

    @Test fun failedChunksStayAligned() {
        val fixes = drive(3000) { 72.0 }        // 72 on a 50 road: +22 the whole way
        val chunks = RouteSampler.chunks(RouteSampler.sample(fixes, 0L), maxPoints = 300)
        assertTrue(chunks.size >= 4)
        // Chunk 1 failed (429), chunk 2's reply has the wrong length, one reply has junk values; the last is missing.
        val replies = chunks.dropLast(1).mapIndexed { i, ch ->
            when (i) { 1 -> null; 2 -> List(ch.size - 1) { 50.0 }; else -> ch.mapIndexed { j, _ -> if (j == 0) Double.NaN else 50.0 } }
        }
        val lim = SpeedLimitScoring.align(chunks, replies)
        assertEquals(lim.points.size, lim.limitsKmh.size)
        assertEquals(chunks.flatten(), lim.points)
        assertTrue(lim.limitsKmh.take(chunks[0].size).drop(1).all { it == 50.0 } && lim.limitsKmh[0] == null)
        val off = chunks[0].size
        assertTrue(lim.limitsKmh.subList(off, off + chunks[1].size + chunks[2].size).all { it == null })
        assertTrue(lim.limitsKmh.takeLast(chunks.last().size).all { it == null })
        val r = SpeedLimitScoring.evaluateChunks(fixes, chunks, replies)
        assertTrue("known ${r.knownShare}", r.knownShare in 0.1..0.7)
        assertEquals(r.knownS, r.over20S, 10.0)   // every known second is over +20
        assertEquals(0.0, r.over30S, 0.0)
        // Mismatched flat lists: nothing trusted, no crash.
        val pts = chunks.flatten()
        assertEquals(0.0, SpeedLimitScoring.evaluate(fixes, pts, pts.drop(1).map { 50.0 }).knownShare, 0.0)
    }

    @Test fun privacyHoldsWithGpsJumpAndOutOfOrderEnd() {
        // Parked at home, the last fix jumps 2 km away while claiming 10 m accuracy.
        val fixes = drive(600) { 60.0 }
        val home = fixes.last()
        val jump = Geo.move(home.lat, home.lon, 0.0, 2_000.0).let { Fix(600_000L, it[0], it[1], 0.0, 0.0, 10.0) }
        val pts = RouteSampler.sample(fixes + jump, 0L)
        // (The jump fix itself adds ~8 m driven at its 0 km/h, hence the small slack.)
        assertTrue(pts.isNotEmpty() && pts.all { d(it, home) > TripPrivacy.RADIUS_M - 10 })
        // Anchors go by time: a mid-trip fix delivered last is not taken for the end.
        val shuffled = fixes.toMutableList().apply { add(removeAt(300)) }
        val end = TripPrivacy.anchors(shuffled).last()
        assertEquals(home.lat, end[0], 1e-9)
        assertTrue(RouteSampler.sample(shuffled, 0L).all { d(it, home) > TripPrivacy.RADIUS_M })
    }

    @Test fun oneFutureTimestampDropsOnlyThatFix() {
        val fixes = drive(600) { 60.0 }.toMutableList()
        fixes[200] = fixes[200].let { Fix(1_000_000_000L, it.lat, it.lon, it.speedMps, 0.0, 5.0) }
        val pts = RouteSampler.sample(fixes, 0L)
        assertTrue(pts.none { it.timeMs == 1_000_000_000L } && pts.last().timeMs > 500_000L)
        assertTrue(SpeedLimitScoring.evaluate(fixes, pts, pts.map { 50.0 }).knownShare > 0.9)
    }

    @Test fun scoringEdgeCases() {
        val fixes = drive(600) { s -> if (s in 300..309) 85.0 else 65.0 }
        val pts = RouteSampler.sample(fixes, 0L)
        fun r(f: List<Fix>, lim: (Int) -> Double? = { 60.0 }) = SpeedLimitScoring.evaluate(f, pts, pts.indices.map(lim))
        // Empty trip, no points, all-null limits, zero duration, unknown speed: nothing known, nothing counted.
        val empty = SpeedLimitScoring.evaluate(emptyList(), emptyList(), emptyList())
        val nulls = r(fixes) { null }
        val frozen = r(fixes.map { Fix(0L, it.lat, it.lon, it.speedMps, 0.0, 5.0) })
        val noSpeed = r(fixes.map { Fix(it.timeMs, it.lat, it.lon, Double.NaN, 0.0, 5.0) })
        for (x in listOf(empty, r(emptyList()), nulls, frozen, noSpeed)) {
            assertTrue(x.knownShare == 0.0 && SpeedLimitScoring.subScore(x) == 100)
        }
        assertTrue(RouteSampler.plan(emptyList(), 0L).isEmpty())
        val old = stats(fixes).apply { speedingS = 50.0; speedingExcess = 500.0 }
        assertEquals(old.score(), old.withSpeedLimits(nulls).score())
        // A 4 s hole in a 10 s stretch: 2 s before (too short), 3 s after (counted).
        assertEquals(10.0, r(fixes).over10S, 0.01)
        assertEquals(3.0, r(fixes.filter { (it.timeMs / 1000).toInt() !in 302..305 }).over10S, 0.01)
        // Fixes 999 ms apart: 3 over-limit fixes are 2.997 s and count; 2 don't.
        val jitter = drive(600, stepMs = 999L) { s -> if (s in 300..302) 85.0 else 65.0 }
        val jp = RouteSampler.sample(jitter, 0L)
        val j = SpeedLimitScoring.evaluate(jitter, jp, jp.map { 60.0 })
        assertEquals(2.997, j.over10S, 0.001)
        assertEquals(25.0, j.maxExcessKmh, 0.01)
        val two = drive(600, stepMs = 999L) { s -> if (s in 300..301) 85.0 else 65.0 }
        assertEquals(0.0, SpeedLimitScoring.evaluate(two, jp, jp.map { 60.0 }).over10S, 0.0)
        // Penalty cap: 40 min/h over +10 is exactly the cap; more stays there.
        assertEquals(40.0, SpeedLimitScoring.penalty(3600.0, 2400.0, 0.0, 0.0), 1e-9)
        assertTrue(SpeedLimitScoring.penalty(3600.0, 2340.0, 0.0, 0.0) < 40.0)
        assertEquals(40.0, SpeedLimitScoring.penalty(3600.0, 3600.0, 3600.0, 3600.0), 0.0)
    }
}
