package app.bumpbeeper

import android.content.Context
import app.bumpbeeper.sync.SpotRow
import app.bumpbeeper.sync.SyncStore
import org.junit.After
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

/**
 * Database version 8: severity, axle hits and the old-pothole flag get columns (v7's interim encoding is moved over),
 * and the shared-map cache gets what spots_near_v2 adds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BumpDbV8Test {
    private lateinit var ctx: Context
    private val opened = ArrayList<BumpDb>()
    private val cfg = EngineConfig()

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
    }

    @After fun tearDown() {
        opened.forEach { it.close() }
    }

    private fun db() = BumpDb(ctx).also { opened.add(it) }

    /**
     * A v7 database: today's tables (v8 only changed `bumps`) with `bumps` as v7 had it, holding rows as v7 wrote them:
     * E1 kept the old-pothole flag in kind_score / kind_votes (1.0 / 1) and the index was peak_avg; rows from before E1
     * still have their old kind scores (pothole past 0.25).
     */
    private fun v7() {
        db().apply {
            writableDatabase.execSQL("DROP TABLE bumps")
            writableDatabase.execSQL(
                """CREATE TABLE bumps(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    lat REAL NOT NULL, lon REAL NOT NULL, heading REAL NOT NULL,
                    hits INTEGER NOT NULL, passes INTEGER NOT NULL, misses INTEGER NOT NULL,
                    n_pos INTEGER NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                    user_muted INTEGER NOT NULL DEFAULT 0,
                    kind_score REAL NOT NULL DEFAULT 0, kind_votes INTEGER NOT NULL DEFAULT 0,
                    side_score REAL NOT NULL DEFAULT 0, side_votes INTEGER NOT NULL DEFAULT 0, peak_avg REAL NOT NULL DEFAULT 0)"""
            )
            for ((i, row) in listOf(
                "0, 0, 0.0, 0, 3.2, 0",      // 1: a bump (E1)
                "0.6, 3, -0.5, 2, 6.1, 0",   // 2: a pothole from before E1 (left side), never felt since
                "1.0, 1, 0.0, 0, 4.0, 1",    // 3: an old pothole E1 kept (flag 1.0 / 1), muted by the driver
                "-0.7, 4, 0.0, 0, 0.0, 0",   // 4: a bump from before E1 with no jolt average (before v3)
            ).withIndex()) writableDatabase.execSQL(
                "INSERT INTO bumps(lat, lon, heading, hits, passes, misses, n_pos, first_seen, last_seen, " +
                    "kind_score, kind_votes, side_score, side_votes, peak_avg, user_muted) " +
                    "VALUES (30.0${i + 1}, 31.2, 90.0, 2, 3, 1, 2, 1000, 2000, $row)"
            )
            // The shared-map cache as v5..v7 had it, holding an old pothole spot from spots_near.
            writableDatabase.execSQL("DROP TABLE remote_spots")
            writableDatabase.execSQL(
                """CREATE TABLE remote_spots(
                    id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, heading REAL NOT NULL,
                    kind TEXT, side TEXT, severity REAL NOT NULL DEFAULT 0, n_devices INTEGER NOT NULL DEFAULT 0,
                    fetched_at INTEGER NOT NULL)"""
            )
            writableDatabase.execSQL(
                "INSERT INTO remote_spots(id, lat, lon, heading, kind, side, severity, n_devices, fetched_at) " +
                    "VALUES (7, 30.05, 31.2, 180.0, 'pothole', 'left', 6.5, 3, 1000)"
            )
            writableDatabase.version = 7
            close()
        }
    }

    @Test fun upgradeFromV7KeepsTheSpotCacheAndStoresWhatV2Adds() {
        v7()
        val store = SyncStore(db())
        val old = store.remoteSpotsInBox(30.05, 31.2, 100.0).single()
        assertTrue("a cached old pothole stays one", old.legacy)
        assertEquals(listOf(null, null, null), listOf(old.band, old.confidence, old.nHits))
        assertEquals(6.5, old.severity!!, 1e-9)
        store.replaceRemoteSpots(30.05, 31.2, 1000.0, listOf(
            SpotRow(7, 30.05, 31.2, 180.0, "pothole", null, 6.5, 3, "strong", "soft", 5),
            SpotRow(8, 30.051, 31.2, 0.0, "bump", null, 4.2, 1, "moderate", "full", 2),
        ), 2000)
        val v2 = store.remoteSpotsInBox(30.05, 31.2, 1000.0).sortedBy { it.id }
        assertEquals(listOf("strong", "moderate"), v2.map { it.band })
        assertEquals(listOf("soft", "full"), v2.map { it.confidence })
        assertEquals(listOf(5, 2), v2.map { it.nHits })
        assertEquals(listOf(true, false), v2.map { it.legacy })
    }

    @Test fun upgradeFromV7MovesSeverityAndTheOldPotholeFlag() {
        v7()
        val db = db()
        assertEquals(8, db.writableDatabase.version)
        val b = db.loadBumps().sortedBy { it.lat }
        assertEquals(4, b.size)
        assertEquals(listOf(3.2, 6.1, 4.0, 0.0), b.map { it.sevIndex })
        assertEquals("the index is the average jolt v7 kept", b.map { it.peakAvg }, b.map { it.sevIndex })
        assertEquals(listOf(false, true, true, false), b.map { it.legacy })
        assertEquals(listOf(false, false, true, false), b.map { it.userMuted })
        assertTrue("no band remembered yet", b.all { it.lastBand == null && it.axleHits == 0 })
        assertEquals("an old pothole is a soft spot", Confidence.SOFT, b[1].confidence(cfg))
        assertEquals(Confidence.FULL, b[0].confidence(cfg))
        assertEquals("the band comes from the index", listOf(Severity.MILD, Severity.STRONG, Severity.MODERATE, Severity.MILD),
            b.map { it.severity(cfg) })
        assertTrue("the rest is kept", b.all { it.hits == 2 && it.passes == 3 && it.misses == 1 && it.nPos == 2 })
    }

    @Test fun severityTheBandAndAxleHitsArePersisted() {
        val db = db()
        val b = Bump(0, 30.0, 31.2, 90.0, 1, 1, 0, 1, 1000, 2000, peakAvg = 4.8, sevIndex = 4.8, legacy = true)
        b.id = db.insertBump(b)
        db.loadBumps().single().let { assertNull(it.lastBand); assertTrue(it.legacy) }
        b.addSeverity(5.6, hitsBefore = 1, cfg = cfg)   // index 5.2: strong by the plain edge, moderate by the memory
        b.hits = 2; b.axleHits = 1; b.legacy = false   // felt again: no longer only an old pothole
        db.updateBump(b)
        val back = db.loadBumps().single()
        assertEquals(b.sevIndex, back.sevIndex, 1e-9)
        assertEquals(Severity.MODERATE, back.lastBand)
        assertEquals("the hysteresis survives a reload", Severity.MODERATE, back.severity(cfg))
        assertEquals(Severity.STRONG, Severity.of(back.sevIndex, null, cfg))
        assertEquals(1, back.axleHits)
        assertFalse(back.legacy)
    }

    @Test fun theCsvCarriesV8AndStillReadsTheOldFormat() {
        val a = db()
        val b = Bump(0, 30.0, 31.2, 90.0, 3, 3, 0, 3, 1000, 2000, peakAvg = 5.2, sevIndex = 5.6, axleHits = 2,
            legacy = true, lastBand = Severity.STRONG)
        b.id = a.insertBump(b)
        val csv = a.bumpsCsv(cfg)
        a.clearAll()
        assertEquals(1, a.importBumpsCsv(csv, cfg).first)
        val back = a.loadBumps().single()
        assertEquals(5.6, back.sevIndex, 0.005)
        assertEquals(2, back.axleHits)
        assertTrue(back.legacy)
        assertEquals(Severity.STRONG, back.lastBand)
        // An old file: severity from peak_avg_ms2, the old-pothole flag from kind_score / kind_votes.
        a.clearAll()
        val old = "lat,lon,heading,hits,passes,misses,kind_score,kind_votes,peak_avg_ms2\n30.1,31.2,90,2,2,0,0.60,3,6.10\n"
        assertEquals(1, a.importBumpsCsv(old, cfg).first)
        val o = a.loadBumps().single()
        assertEquals(6.1, o.sevIndex, 1e-9)
        assertTrue(o.legacy)
        assertNull(o.lastBand)
    }
}
