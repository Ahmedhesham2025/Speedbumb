package app.bumpbeeper.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.bumpbeeper.BumpDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Database version 5: the v4 → v5 upgrade keeps everything, and the outbox / spot cache / state tables work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncDbTest {
    private lateinit var ctx: Context
    private val opened = ArrayList<BumpDb>()
    private val lat0 = 30.0444
    private val lon0 = 31.2357

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
    }

    @After fun tearDown() {
        opened.forEach { it.close() }
    }

    private fun db() = BumpDb(ctx).also { opened.add(it) }

    private fun tables(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    @Test fun freshInstallHasSyncTables() {
        val sql = db().writableDatabase
        assertEquals(5, sql.version)
        assertTrue(tables(sql).containsAll(setOf("bumps", "events", "trips", "outbox", "remote_spots", "sync_state")))
    }

    @Test fun upgradeFromV4KeepsEverythingAndAddsSyncTables() {
        // The version 4 schema as BumpDb created it before sync existed.
        val file = ctx.getDatabasePath("bumps.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v4 ->
            v4.execSQL(
                """CREATE TABLE bumps(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    lat REAL NOT NULL, lon REAL NOT NULL, heading REAL NOT NULL,
                    hits INTEGER NOT NULL, passes INTEGER NOT NULL, misses INTEGER NOT NULL,
                    n_pos INTEGER NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                    user_muted INTEGER NOT NULL DEFAULT 0,
                    kind_score REAL NOT NULL DEFAULT 0, kind_votes INTEGER NOT NULL DEFAULT 0,
                    side_score REAL NOT NULL DEFAULT 0, side_votes INTEGER NOT NULL DEFAULT 0, peak_avg REAL NOT NULL DEFAULT 0)"""
            )
            v4.execSQL(
                """CREATE TABLE events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL, trip_id INTEGER, type TEXT NOT NULL, bump_id INTEGER,
                    lat REAL, lon REAL, speed_kmh REAL, heading REAL, peak REAL,
                    slowdown_kmh REAL, distance_m REAL, note TEXT)"""
            )
            v4.execSQL(
                """CREATE TABLE trips(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    start_ts INTEGER NOT NULL, end_ts INTEGER,
                    hits INTEGER, new_bumps INTEGER, beeps INTEGER, misses INTEGER, rejected INTEGER, distance_m REAL,
                    moving_s REAL, speeding_s REAL, speeding_excess REAL, max_speed REAL, harsh_brakes INTEGER,
                    harsh_accels INTEGER, harsh_corners INTEGER, swerves INTEGER, bumps_fast INTEGER, phone_use INTEGER,
                    potholes INTEGER, score INTEGER)"""
            )
            v4.execSQL(
                "INSERT INTO bumps(lat, lon, heading, hits, passes, misses, n_pos, first_seen, last_seen, kind_score, kind_votes, peak_avg) " +
                    "VALUES ($lat0, $lon0, 90.0, 3, 4, 1, 3, 1000, 2000, 0.8, 3, 7.5)"
            )
            v4.execSQL("INSERT INTO events(ts, trip_id, type, bump_id, note) VALUES (1500, 1, 'hit', 1, 'old')")
            v4.execSQL("INSERT INTO trips(start_ts, end_ts, distance_m, score) VALUES (1000, 601000, 1500.0, 88)")
            v4.version = 4
        }

        val db = db()
        val sql = db.writableDatabase
        assertEquals(5, sql.version)
        assertTrue(tables(sql).containsAll(setOf("outbox", "remote_spots", "sync_state")))
        val b = db.loadBumps().single()
        assertEquals(lat0, b.lat, 0.0)
        assertEquals(0.8, b.kindScore, 0.0)
        assertEquals(7.5, b.peakAvg, 0.0)
        assertEquals(88, db.trips().single().score)
        assertEquals(1, sql.rawQuery("SELECT COUNT(*) FROM events", null).use { it.moveToFirst(); it.getInt(0) })
        // The new tables are empty and usable straight away.
        val s = SyncStore(db)
        assertEquals(0, s.outboxCount())
        s.outboxAdd(listOf("a" to "{}"), 1, 10)
        assertEquals(1, s.outboxCount())
    }

    @Test fun outboxQueuesBatchesDeletesAndPrunes() {
        val s = SyncStore(db())
        s.outboxAdd((1..7).map { "id$it" to """{"n":$it}""" }, tripId = 3, now = 1_000)
        s.outboxAdd(listOf("id1" to """{"n":"dup"}"""), tripId = 4, now = 2_000)   // already queued: kept as it was
        assertEquals(7, s.outboxCount())
        val first = s.outboxBatch(5)
        assertEquals(5, first.size)
        assertEquals("""{"n":1}""", first.first { it.first == "id1" }.second)
        s.outboxDelete(first.map { it.first })
        assertEquals(2, s.outboxCount())
        s.outboxAdd(listOf("new" to "{}"), tripId = 5, now = 50_000)
        s.outboxAttempted(listOf("id6"))
        s.outboxAttempted(listOf("id6"))
        // id6 was tried twice, id7 is old: each goes by its own rule; the new one stays.
        assertEquals(1, s.outboxPrune(olderThan = 500, maxAttempts = 2))
        assertEquals(1, s.outboxPrune(olderThan = 10_000, maxAttempts = 99))
        assertEquals(listOf("new"), s.outboxBatch(10).map { it.first })
        s.outboxClear()
        assertEquals(0, s.outboxCount())
    }

    @Test fun remoteSpotsReplaceTheDownloadedCircleOnly() {
        val s = SyncStore(db())
        fun row(id: Long, lat: Double, lon: Double, heading: Double? = 90.0) =
            SpotRow(id, lat, lon, heading, "bump", null, 4.0, 2)
        // 1 and 2 near Cairo, 3 about 120 km away (Alexandria road).
        s.replaceRemoteSpots(lat0, lon0, 10_000.0, listOf(row(1, lat0, lon0), row(2, lat0 + 0.01, lon0)), now = 1)
        s.replaceRemoteSpots(30.9, 30.4, 10_000.0, listOf(row(3, 30.9, 30.4)), now = 2)
        assertEquals(3, s.remoteSpotCount())
        // A new answer around Cairo without spot 2 (retired) and with a spot that has no direction (skipped).
        s.replaceRemoteSpots(lat0, lon0, 10_000.0, listOf(row(1, lat0, lon0), row(4, lat0, lon0 + 0.01, heading = null)), now = 3)
        val near = s.remoteSpotsInBox(lat0, lon0, 10_000.0).map { it.id }.toSet()
        assertEquals(setOf(1L), near)
        assertEquals(2, s.remoteSpotCount())   // spot 3, outside the circle, is kept
    }

    @Test fun syncStateStoresValues() {
        val s = SyncStore(db())
        s.put("last_at", 1234L)
        s.put("lat", 30.5)
        assertEquals(1234L, s.getLong("last_at"))
        assertEquals(30.5, s.getDouble("lat"), 0.0)
        s.put("lat", null)
        assertTrue(s.getDouble("lat").isNaN())
        s.clearAll()
        assertEquals(0L, s.getLong("last_at"))
    }
}
