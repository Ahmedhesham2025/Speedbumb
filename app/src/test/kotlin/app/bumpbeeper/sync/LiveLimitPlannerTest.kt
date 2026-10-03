package app.bumpbeeper.sync

import app.bumpbeeper.Fix
import app.bumpbeeper.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live speed limit scheduling: start zone, distance, road change, age, rate limit, speed floor, 429/503, cache. */
class LiveLimitPlannerTest {
    private val day = LiveLimitPlanner.DAY_MS

    /** A drive with one GPS fix a second; every due lookup is answered at once with [code] / [kmh]. */
    private class Drive(var left: Int = 1_000) {
        val p = LiveLimitPlanner { if (left > 0) { left--; true } else false }
        var t = 1_000_000L
        var wall = 1_790_000_000_000L
        var lat = 30.0; var lon = 31.0; var bearing = 0.0
        var code = 200; var kmh: Int? = 50; var answer = true
        val calls = ArrayList<Pair<Long, List<Fix>>>()
        val firstLat = lat; val firstLon = lon

        fun drive(kmhSpeed: Double, seconds: Int, acc: Double = 5.0) = repeat(seconds) {
            t += 1000; wall += 1000
            val mps = kmhSpeed / 3.6
            Geo.move(lat, lon, bearing, mps).let { lat = it[0]; lon = it[1] }
            p.onFix(Fix(t, lat, lon, mps, bearing, acc))
            p.next(t, wall)?.let { calls.add(t to it); if (answer) p.onResult(t, wall, code, if (code == 200) kmh else null) }
        }
    }

    @Test fun nothingInTheStartZoneThenGoodPointsOutsideIt() {
        val d = Drive()
        d.drive(72.0, 15)   // 20 m/s: 280 m driven
        assertEquals(0, d.calls.size)
        d.drive(72.0, 10)
        assertEquals(1, d.calls.size)
        val pts = d.calls[0].second
        assertTrue(pts.size in LiveLimitPlanner.MIN_POINTS..LiveLimitPlanner.MAX_POINTS)
        // Every point is out of the zone, and in time order.
        for (f in pts) assertTrue(Geo.distance(d.firstLat, d.firstLon, f.lat, f.lon) > 300.0)
        assertTrue(pts.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
        assertEquals(50, d.p.limit(d.t))
    }

    @Test fun aboutEveryKilometre() {
        val d = Drive()
        d.drive(72.0, 200)
        val gaps = d.calls.zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.isNotEmpty())
        assertTrue(gaps.all { it == 50_000L })   // 1 km at 20 m/s
    }

    @Test fun oldAnswerRefreshedAfterTwoMinutes() {
        val d = Drive()
        d.drive(12.0, 600)   // 1 km would take 5 min
        val gaps = d.calls.zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.isNotEmpty())
        assertTrue(gaps.all { it == LiveLimitPlanner.REFRESH_MS })
    }

    @Test fun roadChangeDropsTheLimitAndLooksUpAgainAfterTheMinimumGap() {
        val d = Drive()
        d.drive(72.0, 25)
        val first = d.calls.single().first
        d.drive(72.0, 9)
        d.bearing = 90.0
        d.drive(72.0, 5)   // turned for 4 s: not yet a new road
        assertEquals(50, d.p.limit(d.t))
        d.drive(72.0, 2)   // 5 s: a new road, the old limit is gone
        assertNull(d.p.limit(d.t))
        assertEquals(1, d.calls.size)
        d.kmh = 80
        d.drive(72.0, 20)
        assertEquals(first + LiveLimitPlanner.MIN_GAP_MS, d.calls[1].first)
        assertEquals(80, d.p.limit(d.t))
    }

    @Test fun shortBendIsNotARoadChange() {
        val d = Drive()
        d.drive(72.0, 25)
        d.bearing = 90.0; d.drive(72.0, 3)
        d.bearing = 0.0; d.drive(72.0, 30)
        assertEquals(1, d.calls.size)
    }

    @Test fun nothingBelowTenKmh() {
        val d = Drive()
        d.drive(72.0, 25)
        d.drive(5.0, 300)   // due by age long ago, but crawling
        assertEquals(1, d.calls.size)
        d.drive(30.0, 1)
        assertEquals(2, d.calls.size)
    }

    @Test fun poorFixesAreNeverSent() {
        val d = Drive()
        d.drive(72.0, 300, acc = 50.0)
        assertEquals(0, d.calls.size)
    }

    @Test fun quota429StopsForTheRestOfTheUtcDay() {
        val d = Drive()
        d.code = 429
        d.drive(72.0, 600)
        assertEquals(1, d.calls.size)
        d.code = 200
        d.wall = (d.wall / day + 1) * day   // next UTC day
        d.drive(72.0, 1)
        assertEquals(2, d.calls.size)
    }

    @Test fun serverTroubleOrOfflineWaitsFiveMinutes() {
        for (code in listOf(503, LiveLimitPlanner.OFFLINE)) {
            val d = Drive()
            d.code = code
            d.drive(72.0, 25 + 300)
            assertEquals(2, d.calls.size)
            assertEquals(LiveLimitPlanner.BACKOFF_MS, d.calls[1].first - d.calls[0].first)
            assertNull(d.p.limit(d.t))
        }
    }

    @Test fun noCallWithoutQuota() {
        val d = Drive(left = 1)
        d.drive(72.0, 300)
        assertEquals(1, d.calls.size)
        assertEquals(0, d.left)
    }

    @Test fun cachedAtMostFiveMinutes() {
        val d = Drive()
        d.drive(72.0, 25)
        val at = d.calls.single().first
        assertEquals(50, d.p.limit(at + LiveLimitPlanner.MAX_CACHE_MS))
        assertNull(d.p.limit(at + LiveLimitPlanner.MAX_CACHE_MS + 1))
        assertNull(d.p.limit(at))   // dropped, not just hidden
    }

    @Test fun answerForTheRoadBeforeIsDropped() {
        val d = Drive()
        d.answer = false
        d.drive(72.0, 25)
        assertEquals(1, d.calls.size)   // in flight: no second call meanwhile
        d.bearing = 180.0
        d.drive(72.0, 40)
        assertEquals(1, d.calls.size)
        d.p.onResult(d.t, d.wall, 200, 90)   // the answer arrives after the road changed
        assertNull(d.p.limit(d.t))
    }
}
