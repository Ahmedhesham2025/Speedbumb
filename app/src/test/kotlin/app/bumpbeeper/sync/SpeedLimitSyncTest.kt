package app.bumpbeeper.sync

import android.content.Context
import android.os.SystemClock
import app.bumpbeeper.BumpDb
import app.bumpbeeper.DrivingStats
import app.bumpbeeper.Fix
import app.bumpbeeper.Prefs
import app.bumpbeeper.RoutePoint
import app.bumpbeeper.RouteSampler
import app.bumpbeeper.TripStats
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Speed-limit lookups after a trip: consent gating, request/answer format, quota, expiry, and the rescored trip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedLimitSyncTest {
    private lateinit var ctx: Context
    private val now = 1_790_000_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val offset = 1_789_990_000_000L
    private val m = 1.0 / 111_195.0

    /** The backend: anonymous sign-in, and `speed-limits` answering [status] with [kmh] for every point. */
    private class FakeBackend(var status: Int = 200, val kmh: Any? = 50) : Transport {
        val calls = ArrayList<String>()
        val bodies = ArrayList<JSONObject>()
        val headers = ArrayList<Map<String, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            if (name != "speed-limits") return HttpResult(200, """{"access_token":"jwt","expires_in":3600,"refresh_token":"r","user":{"id":"u"}}""")
            bodies.add(JSONObject(body)); this.headers.add(headers)
            if (status != 200) return HttpResult(status, """{"error":"x"}""")
            val n = JSONObject(body).getJSONArray("points").length()
            val limits = JSONArray().apply { for (i in 0 until n) put(JSONObject().put("i", i).put("kmh", kmh ?: JSONObject.NULL)) }
            return HttpResult(200, JSONObject().put("source", "tomtom").put("attribution", "© TomTom").put("limits", limits).toString())
        }
        fun lookups() = calls.count { it == "speed-limits" }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        SpeedLimitSync.clearPending(ctx)
    }

    private fun allow(choice: String = Prefs.SYNC_RECEIVE, on: Boolean = true) {
        Prefs.setSyncChoice(ctx, choice)
        Prefs.sp(ctx).edit().putBoolean(Prefs.SPEED_LIMITS, on).commit()
    }

    /** 20 minutes north from Heliopolis at 80 km/h, one fix a second (about 26.7 km). */
    private fun route(start: Long = 1_000_000L, lat0: Double = 30.09, r: TripRoute = TripRoute()) = r.apply {
        for (s in 0 until 1200) add(Fix(start + s * 1000L, lat0 + s * 22.222 * m, 31.32, 22.222, 0.0, 5.0))
    }

    private fun <T> withDb(block: (BumpDb) -> T): T { val db = BumpDb(ctx); try { return block(db) } finally { db.close() } }

    private fun trip(): Long = withDb { db ->
        db.startTrip(now - 1_200_000).also {
            db.endTrip(it, now, TripStats().apply { distanceM = 26_667.0 }, DrivingStats().apply { distanceM = 26_667.0; movingS = 1200.0 })
        }
    }

    private fun stored(id: Long) = withDb { db -> db.trips().single { it.id == id } }

    // ---------------------------------------------------------------- format

    @Test fun requestHasPointsWithEpochMillis() {
        val j = SpeedLimitSync.requestJson(listOf(RoutePoint(30.1, 31.2, 5_000, offset + 5_000), RoutePoint(30.2, 31.3, 6_000, offset + 6_000)))
        val pts = j.getJSONArray("points")
        assertEquals(setOf("points"), j.keySet())
        assertEquals(2, pts.length())
        assertEquals(setOf("lat", "lon", "t"), pts.getJSONObject(0).keySet())
        assertEquals(30.1, pts.getJSONObject(0).getDouble("lat"), 0.0)
        assertEquals(31.3, pts.getJSONObject(1).getDouble("lon"), 0.0)
        assertEquals(offset + 6_000, pts.getJSONObject(1).getLong("t"))
    }

    @Test fun answerParsing() {
        fun body(vararg items: String) = """{"source":"tomtom","limits":[${items.joinToString(",")}]}"""
        assertEquals(listOf(50.0, null, 80.0), SpeedLimitSync.parseLimits(body("""{"i":2,"kmh":80}""", """{"i":0,"kmh":50}""", """{"i":1,"kmh":null}"""), 3))
        // Not a positive number, or no kmh at all: unknown.
        assertEquals(listOf(null, null, null), SpeedLimitSync.parseLimits(body("""{"i":0,"kmh":0}""", """{"i":1,"kmh":"fast"}""", """{"i":2}"""), 3))
        assertNull(SpeedLimitSync.parseLimits(body("""{"i":0,"kmh":50}"""), 2))                       // too short
        assertNull(SpeedLimitSync.parseLimits(body("""{"i":0,"kmh":50}""", """{"i":1,"kmh":50}"""), 1)) // too long
        assertNull(SpeedLimitSync.parseLimits(body("""{"i":0,"kmh":50}""", """{"i":0,"kmh":60}"""), 2)) // index twice
        assertNull(SpeedLimitSync.parseLimits(body("""{"i":5,"kmh":50}"""), 1))                       // index out of range
        assertNull(SpeedLimitSync.parseLimits(body("""{"kmh":50}"""), 1))                              // no index
        assertNull(SpeedLimitSync.parseLimits("""{"error":"quota"}""", 1))
        assertNull(SpeedLimitSync.parseLimits("not json", 1))
    }

    // ---------------------------------------------------------------- consent

    @Test fun nothingIsKeptOrSentWithoutBothConsents() {
        val net = FakeBackend()
        allow(Prefs.SYNC_UNSET, on = true)        // opted in, but never allowed the network
        assertFalse(SpeedLimitSync.allowed(ctx))
        SpeedLimitSync.afterTrip(ctx, trip(), route(), now, offset)
        allow(Prefs.SYNC_SHARE, on = false)       // network allowed, but not opted in (the default)
        Prefs.sp(ctx).edit().remove(Prefs.SPEED_LIMITS).commit()
        assertFalse(Prefs.speedLimits(ctx))
        SpeedLimitSync.afterTrip(ctx, trip(), route(), now, offset)
        assertTrue(SpeedLimitSync.pendingFiles(ctx).isEmpty())
        assertFalse(SpeedLimitSync.run(ctx, net, now))
        assertTrue(net.calls.isEmpty())
    }

    @Test fun switchingTheNetworkOffDeletesTheWaitingRouteUnsent() {
        allow(Prefs.SYNC_RECEIVE)
        SpeedLimitSync.afterTrip(ctx, trip(), route(), now, offset)
        assertEquals(1, SpeedLimitSync.pendingFiles(ctx).size)
        Prefs.setSyncChoice(ctx, Prefs.SYNC_UNSET)
        val net = FakeBackend()
        assertFalse(SpeedLimitSync.run(ctx, net, now))
        assertTrue(net.calls.isEmpty())
        assertTrue(SpeedLimitSync.dir(ctx).listFiles().isNullOrEmpty())
    }

    // ---------------------------------------------------------------- lookup

    @Test fun lookupRescoresTheTripAndDeletesTheRoute() {
        allow(Prefs.SYNC_RECEIVE)
        val id = trip()
        assertEquals(100, stored(id).score)
        SpeedLimitSync.afterTrip(ctx, id, route(), now, offset)
        val net = FakeBackend(kmh = 50)
        assertFalse(SpeedLimitSync.run(ctx, net, now))

        assertEquals(1, net.lookups())
        val h = net.headers.single()
        assertEquals("Bearer jwt", h["Authorization"])
        assertTrue(h["apikey"]!!.isNotEmpty())
        val first = net.bodies.single().getJSONArray("points").getJSONObject(0)
        assertTrue(first.getLong("t") >= offset + 1_000_000L)   // since-boot time + offset = epoch ms
        val t = stored(id)
        assertTrue(t.limitsLookedUp)
        assertTrue(t.drive.usesSpeedLimits)
        assertTrue(t.drive.limitKnownShare > 0.9)
        assertTrue(t.drive.overLimit20S > 1000)                  // 80 in a 50 zone the whole way
        assertEquals(0.0, t.drive.overLimit30S, 0.0)
        assertEquals(60, t.score)
        assertTrue(SpeedLimitSync.dir(ctx).listFiles().isNullOrEmpty())   // the route is never kept after use
    }

    @Test fun serverErrorIsRetriedOnLaterRunsThenCountsAsUnknown() {
        allow(Prefs.SYNC_SHARE)
        val id = trip()
        SpeedLimitSync.afterTrip(ctx, id, route(), now, offset)
        val net = FakeBackend(status = 503)
        assertTrue(SpeedLimitSync.run(ctx, net, now))            // again later
        assertTrue(SpeedLimitSync.run(ctx, net, now + 60_000))
        assertEquals(1, SpeedLimitSync.pendingFiles(ctx).size)
        assertFalse(SpeedLimitSync.run(ctx, net, now + 120_000)) // third failure: unknown, done
        assertEquals(SpeedLimitSync.MAX_TRIES, net.lookups())
        val t = stored(id)
        assertEquals(0.0, t.drive.limitKnownShare, 0.0)
        assertFalse(t.drive.usesSpeedLimits)
        assertEquals(100, t.score)
        assertTrue(SpeedLimitSync.pendingFiles(ctx).isEmpty())
    }

    @Test fun quotaAnswerStopsForTheDayAndTriesTomorrow() {
        allow(Prefs.SYNC_RECEIVE)
        val id = trip()
        SpeedLimitSync.afterTrip(ctx, id, route(), now, offset)
        val net = FakeBackend(status = 429)
        assertFalse(SpeedLimitSync.run(ctx, net, now))
        assertEquals(1, net.lookups())
        assertEquals(1, SpeedLimitSync.pendingFiles(ctx).size)
        assertFalse(stored(id).limitsLookedUp)
        // Same day: no more calls at all.
        net.status = 200
        assertFalse(SpeedLimitSync.run(ctx, net, now + 60_000))
        assertEquals(1, net.lookups())
        // Next day: looked up.
        assertFalse(SpeedLimitSync.run(ctx, net, now + day))
        assertEquals(2, net.lookups())
        assertTrue(stored(id).limitsLookedUp)
        assertTrue(SpeedLimitSync.pendingFiles(ctx).isEmpty())
    }

    @Test fun localDailyCapMatchesTheServer() {
        allow(Prefs.SYNC_RECEIVE)
        val net = FakeBackend()
        repeat(SpeedLimitSync.MAX_CALLS_PER_DAY + 1) { SpeedLimitSync.afterTrip(ctx, trip(), route(), now, offset) }
        assertFalse(SpeedLimitSync.run(ctx, net, now))
        assertEquals(SpeedLimitSync.MAX_CALLS_PER_DAY, net.lookups())
        assertEquals(1, SpeedLimitSync.pendingFiles(ctx).size)
    }

    @Test fun routeOlderThan48HoursIsDeletedUnsent() {
        allow(Prefs.SYNC_RECEIVE)
        val id = trip()
        SpeedLimitSync.afterTrip(ctx, id, route(), now, offset)
        val net = FakeBackend()
        assertFalse(SpeedLimitSync.run(ctx, net, now + SpeedLimitSync.MAX_AGE_MS + 1))
        assertTrue(net.calls.isEmpty())
        assertTrue(SpeedLimitSync.pendingFiles(ctx).isEmpty())
        assertFalse(stored(id).limitsLookedUp)
        // The app-start sweep does the same without network.
        SpeedLimitSync.afterTrip(ctx, id, route(), now, offset)
        SpeedLimitSync.onAppStart(ctx, now + SpeedLimitSync.MAX_AGE_MS + 1)
        assertTrue(SpeedLimitSync.pendingFiles(ctx).isEmpty())
    }

    @Test fun clearedTripIsNeverSent() {
        allow(Prefs.SYNC_RECEIVE)
        SpeedLimitSync.afterTrip(ctx, trip(), route(), now, offset)
        withDb { it.clearAll() }
        val net = FakeBackend()
        assertFalse(SpeedLimitSync.run(ctx, net, now))
        assertEquals(0, net.lookups())
        assertTrue(SpeedLimitSync.pendingFiles(ctx).isEmpty())
    }

    @Test fun consentWithdrawnBetweenChunksStopsAtOnce() {
        allow(Prefs.SYNC_RECEIVE)
        // A second stretch 12 km on (a jump no single request may span): two chunks.
        val r = route(r = route(), start = 2_300_000L, lat0 = 30.45)
        assertEquals(2, RouteSampler.plan(r.fixes(), offset).size)
        val id = trip()
        SpeedLimitSync.afterTrip(ctx, id, r, now, offset)
        val net = FakeBackend()
        val withdrawing = Transport { url, h, body ->
            net.post(url, h, body).also { if (url.endsWith("speed-limits")) Prefs.setSyncChoice(ctx, Prefs.SYNC_UNSET) }
        }
        assertFalse(SpeedLimitSync.run(ctx, withdrawing, now))
        assertEquals(1, net.lookups())
        assertFalse(stored(id).limitsLookedUp)
        assertTrue(SpeedLimitSync.dir(ctx).listFiles().isNullOrEmpty())
    }

    @Test fun halfWrittenFilesAreSweptAndAFailedWriteLeavesNothing() {
        allow(Prefs.SYNC_RECEIVE)
        val wall = System.currentTimeMillis()
        val dir = SpeedLimitSync.dir(ctx).apply { mkdirs() }
        val stale = File(dir, "7.tmp").apply { writeText("part"); setLastModified(wall - 3_600_000) }
        val writing = File(dir, "8.tmp").apply { writeText("being written") }
        SpeedLimitSync.onAppStart(ctx, wall)
        assertFalse(stale.exists())
        assertTrue(writing.exists())   // may still be in the middle of being written
        writing.setLastModified(wall - 3_600_000)
        SpeedLimitSync.run(ctx, FakeBackend(), wall)
        assertFalse(writing.exists())
        // The folder can't be written (a file in its place): no exception, nothing saved.
        dir.deleteRecursively(); dir.writeText("x")
        SpeedLimitSync.afterTrip(ctx, trip(), route(), now, offset)
        assertTrue(dir.isFile)
        dir.delete()
    }

    @Test fun defaultEpochOffsetTurnsBootTimeIntoEpochTime() {
        allow(Prefs.SYNC_RECEIVE)
        val wall = System.currentTimeMillis()
        SpeedLimitSync.afterTrip(ctx, trip(), route(start = SystemClock.elapsedRealtime()), now = wall)
        val net = FakeBackend()
        SpeedLimitSync.run(ctx, net, wall)
        // The first point is some 15 s into the trip (its first 300 m are trimmed).
        val t = net.bodies.single().getJSONArray("points").getJSONObject(0).getLong("t")
        assertTrue("t=$t wall=$wall", t in wall..wall + 60_000)
    }

    @Test fun routeIsCappedAtFiveHours() {
        val r = TripRoute(cap = 3)
        repeat(5) { r.add(Fix(it * 1000L, 30.0, 31.0, Double.NaN, Double.NaN, 4.0)) }
        assertEquals(3, r.size)
        assertTrue(r.fixes().all { it.speedMps.isNaN() && it.accuracyM == 4.0 })
        assertEquals(5 * 3600, TripRoute.MAX_FIXES)
    }
}
