package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.BumpDb
import app.bumpbeeper.Fix
import app.bumpbeeper.Observation
import app.bumpbeeper.TripPrivacy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The shared-map privacy zone is the core's [TripPrivacy] rule, including the trim of the first and last 300 m driven. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyZoneDelegationTest {
    private lateinit var ctx: Context
    private val opened = ArrayList<BumpDb>()
    private val home = doubleArrayOf(30.0900, 31.3200)
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
        Observation(id, "jolt", lat, lon, 0.0, 30.0, 4.0, 0.5, 0.0, 1_790_000_000_000L)

    /** Driving north from home at [mps] m/s, one fix a second. */
    private fun fix(s: Int, mps: Double = 10.0, jumpM: Double = 0.0) =
        Fix(1_000_000L + s * 1000L, home[0] + (s * mps + jumpM) * m, home[1], mps, 0.0, 5.0)

    @Test fun sameRadiusAndSameAnswerAsTripPrivacy() {
        assertEquals(TripPrivacy.RADIUS_M, PrivacyZone.RADIUS_M, 0.0)
        assertEquals(TripPrivacy.GOOD_ACCURACY_M, OutboxSink.GOOD_ACCURACY_M, 0.0)
        val anchors = listOf(home)
        for (d in listOf(0, 100, 299, 301, 1000)) {
            val o = obs("o$d", home[0] + d * m, home[1])
            assertEquals(TripPrivacy.outside(o.lat, o.lon, anchors), PrivacyZone.keep(listOf(o), anchors).isNotEmpty())
        }
    }

    @Test fun observationsInTheFirstOrLast300mDrivenAreDroppedEvenAfterAGpsJump() {
        val s = store()
        val sink = OutboxSink(s, tripId = 3)
        sink.onFix(fix(0))
        // 10 s at 10 m/s = 100 m driven, but the fix jumps 1 km: far from home, still inside the first 300 m driven.
        sink.onFix(fix(10, jumpM = 1000.0))
        sink.record(obs("after-jump", home[0] + 1100 * m, home[1]))
        for (t in 11..200) sink.onFix(fix(t))
        sink.record(obs("middle", home[0] + 1000 * m, home[1]))   // 2,000 m driven, 1,000 m from both ends
        for (t in 201..290) sink.onFix(fix(t))
        sink.record(obs("near-end-driven", home[0] + 2850 * m, home[1]))   // 2,900 m driven of 2,900: in the last 300 m

        assertEquals(1, sink.flush(share = true, now = 1))
        assertEquals(listOf("middle"), s.outboxBatch(10).map { it.first })
    }

    @Test fun drivenTrimUsesTheCoreDistance() {
        val fixes = listOf(fix(0), fix(10, jumpM = 1000.0), fix(20))
        val core = TripPrivacy.driven(fixes)
        val list = listOf(obs("a", 30.2, 31.3), obs("b", 30.3, 31.3), obs("c", 30.4, 31.3))
        val far = listOf(doubleArrayOf(31.0, 31.0))
        // a at 0 m, b at 100 m, c at 400 m driven, of 800: only c is more than 300 m from both ends.
        assertEquals(listOf("c"), PrivacyZone.keep(list, far, listOf(core[0], core[1], 400.0), 800.0).map { it.clientId })
        // No anchors (no GPS fix): nothing leaves the phone.
        assertEquals(emptyList<Observation>(), PrivacyZone.keep(list, emptyList(), listOf(400.0, 400.0, 400.0), 800.0))
    }
}
