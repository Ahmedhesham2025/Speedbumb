package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.BumpDb
import app.bumpbeeper.BumpKind
import app.bumpbeeper.Observation
import app.bumpbeeper.Side
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The privacy zone, the outbox sink and the engine's view of the spot cache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSharingTest {
    private lateinit var ctx: Context
    private val opened = ArrayList<BumpDb>()

    // A trip from home (Heliopolis) to work (Downtown Cairo), about 11 km apart.
    private val home = doubleArrayOf(30.0900, 31.3200)
    private val work = doubleArrayOf(30.0444, 31.2357)
    /** About 1 m of latitude in degrees. */
    private val m = 1.0 / 111_195.0

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
    }

    @After fun tearDown() {
        opened.forEach { it.close() }
    }

    private fun store() = SyncStore(BumpDb(ctx).also { opened.add(it) })

    private fun obs(id: String, lat: Double, lon: Double) =
        Observation(id, "jolt", lat, lon, 90.0, 30.0, 4.0, 0.5, 0.0, 1_790_000_000_000L)

    // ---------------------------------------------------------------- privacy zone

    @Test fun privacyZoneDropsPointsNearStartAndEnd() {
        val list = listOf(
            obs("home-100m", home[0] + 100 * m, home[1]),
            obs("home-290m", home[0] - 290 * m, home[1]),
            obs("home-320m", home[0] + 320 * m, home[1]),
            obs("middle", 30.0670, 31.2780),
            obs("work-50m", work[0], work[1] + 50 * m / 0.866),
            obs("work-310m", work[0] - 310 * m, work[1]),
        )
        val kept = PrivacyZone.keep(list, listOf(home, work)).map { it.clientId }
        assertEquals(listOf("home-320m", "middle", "work-310m"), kept)
    }

    @Test fun privacyZoneWithoutAnyFixKeepsNothing() {
        assertTrue(PrivacyZone.keep(listOf(obs("x", 30.0, 31.0)), emptyList()).isEmpty())
    }

    @Test fun outboxSinkQueuesOnlyFilteredPointsAtTripEnd() {
        val s = store()
        val sink = OutboxSink(s, tripId = 9)
        // A poor first fix 2 km off, then good fixes from home to work: both starts count.
        sink.onFix(home[0] + 0.018, home[1], 900.0)
        sink.onFix(home[0], home[1], 8.0)
        sink.record(obs("near-home", home[0] + 150 * m, home[1]))
        sink.record(obs("near-bad-first-fix", home[0] + 0.018 + 100 * m, home[1]))
        sink.record(obs("middle", 30.0670, 31.2780))
        sink.record(obs("abroad", 48.85, 2.35))   // outside the server's area: would reject the whole batch
        sink.onFix(work[0], work[1], 6.0)
        sink.record(obs("near-work", work[0] + 200 * m, work[1]))
        assertEquals(0, s.outboxCount())   // nothing is written during the trip

        assertEquals(1, sink.flush(share = true, now = 5_000))
        val queued = s.outboxBatch(10).single()
        assertEquals("middle", queued.first)
        val json = JSONObject(queued.second)
        assertEquals("middle", json.getString("client_obs_id"))
        assertEquals(90, json.getInt("heading"))
    }

    @Test fun outboxSinkQueuesNothingWhenSharingWasSwitchedOff() {
        val s = store()
        val sink = OutboxSink(s, tripId = 1)
        sink.onFix(home[0], home[1], 5.0)
        sink.record(obs("middle", 30.0670, 31.2780))
        sink.onFix(work[0], work[1], 5.0)
        assertEquals(0, sink.flush(share = false, now = 1))
        assertEquals(0, s.outboxCount())
    }

    // ---------------------------------------------------------------- spot cache → engine

    @Test fun kindAndSideTextMapToEngineValues() {
        assertEquals(BumpKind.BUMP, CachedSpotSource.kindOf("bump"))
        assertEquals(BumpKind.POTHOLE, CachedSpotSource.kindOf("pothole"))
        assertEquals(BumpKind.UNSURE, CachedSpotSource.kindOf(null))
        assertEquals(Side.LEFT, CachedSpotSource.sideOf("left"))
        assertEquals(Side.RIGHT, CachedSpotSource.sideOf("right"))
        assertEquals(Side.UNKNOWN, CachedSpotSource.sideOf("both"))
        assertEquals(Side.UNKNOWN, CachedSpotSource.sideOf(null))
    }

    @Test fun cachedSpotSourceReturnsSpotsWithinRadius() {
        val s = store()
        s.replaceRemoteSpots(work[0], work[1], 10_000.0, listOf(
            SpotRow(11, work[0] + 500 * m, work[1], 180.0, "pothole", "right", 6.5, 3),
            SpotRow(12, work[0] + 1400 * m, work[1], 0.0, null, "both", null, 2),
            SpotRow(13, work[0] + 2500 * m, work[1], 90.0, "bump", null, 3.0, 4),
        ), now = 1)
        val near = CachedSpotSource(s).spotsNear(work[0], work[1], 1500.0).sortedBy { it.id }
        assertEquals(listOf(11L, 12L), near.map { it.id })
        val p = near[0]
        assertEquals(BumpKind.POTHOLE, p.kind)
        assertEquals(Side.RIGHT, p.side)
        assertEquals(180.0, p.heading, 0.0)
        assertEquals(6.5, p.severity, 1e-6)
        assertEquals(3, p.nDevices)
        assertEquals(BumpKind.UNSURE, near[1].kind)
        assertEquals(Side.UNKNOWN, near[1].side)
        assertEquals(0.0, near[1].severity, 0.0)
    }

    @Test fun positionsAreRoundedToAboutAKilometre() {
        assertEquals(30.04, Sync.round2(30.0444), 0.0)
        assertEquals(31.24, Sync.round2(31.2357), 0.0)
    }
}
