package app.bumpbeeper.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.bumpbeeper.BumpDb
import app.bumpbeeper.Geo
import app.bumpbeeper.LiveState
import app.bumpbeeper.OsmBump
import app.bumpbeeper.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** OpenStreetMap bumps, phone side: download after the shared spots, the cache table, fallback and the setting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsmBumpsSyncTest {
    private lateinit var ctx: Context
    private lateinit var db: BumpDb
    private lateinit var store: SyncStore
    private val base = "https://example.supabase.co"
    private val lat0 = 30.04
    private val lon0 = 31.24

    private val rows = """[
        {"osm_id":"n11","lat":30.0401,"lon":31.2401,"kind":"hump"},
        {"osm_id":"w12","lat":30.0450,"lon":31.2450,"kind":"table"},
        {"osm_id":"","lat":30.05,"lon":31.25,"kind":"hump"},
        {"osm_id":"n13","lat":null,"lon":31.25,"kind":"hump"}]"""

    /** Answers by RPC name; [osm] is what `osm_bumps_near` gets. */
    private class Backend(var osm: HttpResult) : Transport {
        val calls = ArrayList<String>()
        val bodies = HashMap<String, String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            bodies[name] = body
            return when (name) {
                "signup", "token" -> HttpResult(200, """{"access_token":"a","expires_in":3600,"refresh_token":"r","user":{"id":"u"}}""")
                "spots_near_v2" -> HttpResult(200, "[]")
                "osm_bumps_near" -> osm
                else -> HttpResult(204, "")
            }
        }
    }

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
    private fun ids() = store.osmBumpsInBox(lat0, lon0, 20_000.0).map { it.osmId }.sorted()

    @Test fun downloadedWithTheSharedSpots() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        val net = Backend(HttpResult(200, rows))
        assertFalse("no retry", Sync.run(ctx, true, lat0, lon0, net))
        Sync.runOsm(ctx, net)   // the same job, right after the run
        assertTrue(net.calls.indexOf("osm_bumps_near") > net.calls.indexOf("spots_near_v2"))
        assertEquals("rows without an id or a position are skipped", listOf("n11", "w12"), ids())
        assertEquals("hump", store.osmBumpsInBox(30.0401, 31.2401, 10.0).single().kind)
    }

    @Test fun aServerWithoutTheRpcHasNone() {
        store.replaceOsmBumps(lat0, lon0, 10_000.0, SupabaseApi.parseOsmBumps(rows), 1L)
        for (missing in listOf(HttpResult(404, ""), HttpResult(400, """{"code":"PGRST202","message":"Could not find"}"""))) {
            Sync.pullOsm(api(Backend(missing)), store, lat0, lon0, 2L)
            assertEquals(emptyList<String>(), ids())
            store.replaceOsmBumps(lat0, lon0, 10_000.0, SupabaseApi.parseOsmBumps(rows), 1L)
        }
    }

    @Test fun anyOtherFailureKeepsTheCacheAndNeverFailsTheRun() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        store.replaceOsmBumps(lat0, lon0, 10_000.0, SupabaseApi.parseOsmBumps(rows), 1L)
        val net = Backend(HttpResult(503, "busy"))
        assertFalse("no retry", Sync.run(ctx, true, lat0, lon0, net))
        Sync.runOsm(ctx, net)
        assertTrue("osm_bumps_near" in net.calls)
        assertEquals(listOf("n11", "w12"), ids())
        assertFalse("the shared spots still count as downloaded", LiveState.spotsFailed)
    }

    @Test fun offlineOrUnansweredAsksNothing() {
        val net = Backend(HttpResult(200, rows))
        Sync.runOsm(ctx, net)   // shared map not answered yet: no network at all
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        Sync.runOsm(ctx, net)   // no trip position yet
        store.put("pos_lat", lat0); store.put("pos_lon", lon0); store.put("spots_failed", 1)
        Sync.runOsm(ctx, net)   // the shared spots failed (offline): the cache stays
        assertEquals(emptyList<String>(), net.calls)
    }

    @Test fun aRefreshKeepsCleanPassesAndDropsWhatTheServerNoLongerHas() {
        val src = CachedOsmSource(store)
        store.replaceOsmBumps(lat0, lon0, 10_000.0, SupabaseApi.parseOsmBumps(rows), 1L)
        src.osmCleanPass("n11"); src.osmCleanPass("n11")
        src.osmMuted("w12")
        store.replaceOsmBumps(lat0, lon0, 10_000.0, listOf(OsmBump("n11", 30.0402, 31.2402, "hump")), 2L)
        val n11 = src.osmBumpsNear(lat0, lon0, 1000.0).single()
        assertEquals(2, n11.cleanPasses)
        assertEquals(30.0402, n11.lat, 1e-9)
        assertEquals(listOf("n11"), ids())
        src.osmMuted("n11")
        assertTrue(src.osmBumpsNear(lat0, lon0, 1000.0).single().cleanPasses >= SyncStore.OSM_MUTED)
    }

    @Test fun aFullAnswerOnlyCoversTheCircleUpToItsFarthestRow() {
        // 2000 rows within ~1 km: the server stopped there, so a cached bump 5 km out is not "gone".
        store.replaceOsmBumps(lat0, lon0, 10_000.0, listOf(OsmBump("far", 30.085, lon0)), 1L)
        val full = (0 until SyncStore.OSM_MAX_ROWS).map { OsmBump("n$it", lat0 + it * 0.000004, lon0) }
        store.replaceOsmBumps(lat0, lon0, 10_000.0, full, 2L)
        assertTrue("far" in ids())
        assertTrue(Geo.distance(lat0, lon0, 30.085, lon0) > 4000)
        // A short answer covers all of it.
        store.replaceOsmBumps(lat0, lon0, 10_000.0, full.take(5), 3L)
        assertFalse("far" in ids())
        assertEquals(5, store.osmBumpCount())
    }

    @Test fun forgetMeEmptiesTheCache() {
        store.replaceOsmBumps(lat0, lon0, 10_000.0, SupabaseApi.parseOsmBumps(rows), 1L)
        store.clearAll()
        assertEquals(0, store.osmBumpCount())
    }

    @Test fun anOlderDatabaseGetsTheTableOnOpen() {
        store.replaceOsmBumps(lat0, lon0, 10_000.0, SupabaseApi.parseOsmBumps(rows), 1L)
        store.outboxAdd(listOf("obs-1" to "{}"), 1, 1L)
        db.close()
        // A database from before: today's schema without osm_bumps (it takes no version step, BumpDb.onOpen).
        val file = ctx.getDatabasePath("bumps.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.execSQL("DROP TABLE osm_bumps")   // same version: no upgrade step brings it back, the open does
        }
        db = BumpDb(ctx)
        store = SyncStore(db)
        assertTrue(db.writableDatabase.version >= 10)
        assertEquals(0, store.osmBumpCount())
        assertEquals("the rest is kept", 1, store.outboxCount())
        store.replaceOsmBumps(lat0, lon0, 10_000.0, SupabaseApi.parseOsmBumps(rows), 2L)
        assertEquals(2, store.osmBumpCount())
    }

    @Test fun theSettingIsOnByDefault() {
        assertTrue(Prefs.osmWarnings(ctx))
        assertTrue(Prefs.engineConfig(ctx).osmWarnings)
        Prefs.sp(ctx).edit().putBoolean(Prefs.OSM_WARNINGS, false).commit()
        assertFalse(Prefs.engineConfig(ctx).osmWarnings)
    }

    @Test fun theRequestAsksForTheTripCircle() {
        val net = Backend(HttpResult(200, JSONArray().toString()))
        Sync.pullOsm(api(net), store, lat0, lon0, 1L)
        val o = JSONObject(net.bodies.getValue("osm_bumps_near"))
        assertEquals(lat0, o.getDouble("lat"), 0.0)
        assertEquals(Sync.PULL_RADIUS_M, o.getInt("radius_m"))
    }
}
