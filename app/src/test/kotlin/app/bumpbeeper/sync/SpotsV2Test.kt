package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.BumpDb
import app.bumpbeeper.BumpKind
import app.bumpbeeper.Prefs
import app.bumpbeeper.research.RateAverager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A2b: shared spots from `spots_near_v2` with the fallback to `spots_near`, and the engine's sensor period. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("DEPRECATION")
class SpotsV2Test {
    private lateinit var ctx: Context
    private lateinit var db: BumpDb
    private lateinit var store: SyncStore
    private val base = "https://example.supabase.co"
    private val hour = 60 * 60 * 1000L

    private val v2 = """[
        {"id":7,"lat":30.1,"lon":31.2,"heading":90,"severity":6.5,"severity_band":"strong","confidence":"full","n_devices":3,"n_hits":5,"legacy":true},
        {"id":8,"lat":30.2,"lon":31.3,"heading":null,"severity":null,"severity_band":null,"confidence":"soft","n_devices":1,"n_hits":1,"legacy":false},
        {"id":9,"lat":30.3,"lon":31.4,"heading":180,"severity":4.0,"severity_band":"extreme","confidence":"sure","n_devices":2,"legacy":false},
        {"id":10,"latitude":30.4,"longitude":31.5,"heading":0,"n_devices":2}]"""
    private val v1 = """[{"id":5,"latitude":30.05,"longitude":31.24,"heading":90,"kind":"pothole","side":"left","severity":3.0,"n_devices":2}]"""

    /** Answers by RPC name; [v2Answer] is what `spots_near_v2` gets. */
    private class Backend(var v2Answer: HttpResult) : Transport {
        val calls = ArrayList<String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            return when (name) {
                "signup", "token" -> HttpResult(200, """{"access_token":"a","expires_in":3600,"refresh_token":"r","user":{"id":"u"}}""")
                "spots_near_v2" -> v2Answer
                "spots_near" -> HttpResult(200, """[{"id":5,"latitude":30.05,"longitude":31.24,"heading":90,"kind":"bump","side":null,"severity":3.0,"n_devices":2}]""")
                else -> HttpResult(204, "")
            }
        }
    }

    private val missing = HttpResult(404, """{"code":"PGRST202","message":"Could not find the function public.spots_near_v2"}""")

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        db = BumpDb(ctx)
        store = SyncStore(db)
    }

    @After fun tearDown() = db.close()

    private fun api(t: Transport) = SupabaseApi(base, "pk", SupabaseAuth(ctx, base, "pk", t), t)

    @Test fun v2RowsCarryBandConfidenceHitsAndLegacy() {
        val rows = SupabaseApi.parseSpotsV2(v2)
        assertEquals("a row without lat / lon (a v1 row) is skipped", listOf(7L, 8L, 9L), rows.map { it.id })
        val (old, plain, odd) = rows
        assertTrue(old.legacy)
        assertEquals("pothole", old.kind)
        assertEquals("a legacy spot is always soft", "soft", old.confidence)
        assertEquals(listOf("strong", null, null), rows.map { it.band })   // "extreme": a band this version doesn't know
        assertEquals(listOf(5, 1, 2), rows.map { it.nHits })               // missing n_hits: at least its phones
        assertEquals(listOf("soft", "soft", null), rows.map { it.confidence })
        assertEquals("bump", plain.kind)
        assertNull(plain.heading)
        assertNull(plain.severity)
        assertEquals(4.0, odd.severity!!, 0.0)
        // In the engine's cache, a legacy spot is an old pothole spot: soft until it is felt.
        assertEquals(BumpKind.POTHOLE, CachedSpotSource.toRemote(old)!!.kind)
        assertEquals(BumpKind.BUMP, CachedSpotSource.toRemote(odd)!!.kind)
        assertEquals(listOf("pothole"), SupabaseApi.parseSpots(v1).map { it.kind })
    }

    @Test fun v2IsAskedFirst() {
        val net = Backend(HttpResult(200, v2))
        val rows = Sync.spotsNear(api(net), store, 30.04, 31.23, 1000L)
        assertEquals(listOf("signup", "spots_near_v2"), net.calls)
        assertEquals(3, rows.size)
        assertEquals(0L, store.getLong(Sync.SPOTS_V1_UNTIL))
    }

    @Test fun aServerWithoutV2FallsBackAndIsRememberedForAWhile() {
        for (answer in listOf(missing, HttpResult(404, ""))) {   // PGRST202, or a plain 404
            store.put(Sync.SPOTS_V1_UNTIL, null)
            SupabaseAuth.prefs(ctx).edit().clear().commit()   // a first run: it signs in
            val net = Backend(answer)
            val now = 10 * hour
            assertEquals(listOf(5L), Sync.spotsNear(api(net), store, 30.04, 31.23, now).map { it.id })
            assertEquals(listOf("signup", "spots_near_v2", "spots_near"), net.calls)
            assertEquals(now + Sync.SPOTS_V1_MS, store.getLong(Sync.SPOTS_V1_UNTIL))
            // The next runs go straight to spots_near, until the time is up.
            net.calls.clear()
            Sync.spotsNear(api(net), store, 30.04, 31.23, now + Sync.SPOTS_V1_MS - 1)
            assertEquals(listOf("spots_near"), net.calls)
            net.calls.clear()
            net.v2Answer = HttpResult(200, v2)   // the migration is applied now
            assertEquals(3, Sync.spotsNear(api(net), store, 30.04, 31.23, now + Sync.SPOTS_V1_MS).size)
            assertEquals(listOf("spots_near_v2"), net.calls)
        }
    }

    @Test fun aPhoneClockMovedBackDoesNotStretchTheFallback() {
        store.put(Sync.SPOTS_V1_UNTIL, 100 * hour)   // set while the clock ran days ahead
        val net = Backend(HttpResult(200, v2))
        assertEquals(3, Sync.spotsNear(api(net), store, 30.04, 31.23, 10 * hour).size)
        assertEquals(listOf("signup", "spots_near_v2"), net.calls)
    }

    @Test fun otherErrorsAreNotAFallback() {
        val net = Backend(HttpResult(503, """{"message":"busy"}"""))
        try {
            Sync.spotsNear(api(net), store, 30.04, 31.23, 1000L)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(Outcome.RETRY, e.outcome)
            assertEquals(503, e.httpCode)
        }
        assertEquals(listOf("signup", "spots_near_v2"), net.calls)
        assertEquals("a busy server says nothing about v2", 0L, store.getLong(Sync.SPOTS_V1_UNTIL))
    }

    @Test fun theEngineSensorPeriodStaysFiftyHertzUnlessSet() {
        assertEquals(20_000, Prefs.sensorPeriodUs(ctx))
        Prefs.sp(ctx).edit().putInt(Prefs.SENSOR_PERIOD_US, 1_000).commit()
        assertEquals(5_000, Prefs.sensorPeriodUs(ctx))
        Prefs.sp(ctx).edit().putInt(Prefs.SENSOR_PERIOD_US, 10_000).commit()
        assertEquals(10_000, Prefs.sensorPeriodUs(ctx))
    }

    @Test fun whileResearchRunsTheEngineGetsItsOwnRate() {
        // 50 Hz asked (as always): 10 ms bins, as before. 125 Hz: 8 ms bins. 200 Hz asked: nothing averaged.
        for ((period, bins) in listOf(20_000 to 5, 8_000 to 6, 5_000 to 41)) {
            val a = RateAverager.forPeriod(period)
            var n = 0
            for (i in 0..40) if (a == null || a.add(i.toLong(), 1.0, 2.0, 3.0)) n++   // a sample each ms for 40 ms
            assertEquals("period $period µs", bins, n)
        }
        assertNull(RateAverager.forPeriod(RateAverager.RESEARCH_PERIOD_US))
    }
}
