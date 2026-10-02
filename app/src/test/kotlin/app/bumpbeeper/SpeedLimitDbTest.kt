package app.bumpbeeper

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.bumpbeeper.sync.SyncStore
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

/** Database version 6: road speed limit columns on trips (upgrade from v5, writes and reads). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedLimitDbTest {
    private lateinit var ctx: Context
    private val opened = ArrayList<BumpDb>()
    private val limitColumns = setOf(
        "limit_known_share", "limit_known_s", "over_limit_10_s", "over_limit_20_s", "over_limit_30_s", "max_over_limit_kmh",
    )

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
    }

    @After fun tearDown() {
        opened.forEach { it.close() }
    }

    private fun db() = BumpDb(ctx).also { opened.add(it) }

    private fun columns(db: SQLiteDatabase): Set<String> =
        db.rawQuery("PRAGMA table_info(trips)", null).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(c.getColumnIndexOrThrow("name")))
            out
        }

    /** A 20 km trip at 80 km/h with a fixed-threshold score of 100. */
    private fun finishedTrip(db: BumpDb): Long {
        val id = db.startTrip(1_000)
        db.endTrip(id, 901_000, TripStats().apply { distanceM = 20_000.0 }, DrivingStats().apply { distanceM = 20_000.0; movingS = 900.0 })
        return id
    }

    private fun result(knownShare: Double, knownS: Double, over10: Double, over20: Double) =
        SpeedLimitResult(20_000.0, 20_000.0 * knownShare, knownS, over10, over20, 0.0, 0.0, 0.0, 0.0, 27.0)

    @Test fun freshInstallHasLimitColumns() {
        val sql = db().writableDatabase
        assertTrue(sql.version >= 6)
        assertTrue(columns(sql).containsAll(limitColumns))
    }

    @Test fun upgradeFromV5KeepsTripsAndMarksThemNotLookedUp() {
        val file = ctx.getDatabasePath("bumps.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v5 ->
            v5.execSQL(
                """CREATE TABLE bumps(id INTEGER PRIMARY KEY AUTOINCREMENT, lat REAL NOT NULL, lon REAL NOT NULL,
                    heading REAL NOT NULL, hits INTEGER NOT NULL, passes INTEGER NOT NULL, misses INTEGER NOT NULL,
                    n_pos INTEGER NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                    user_muted INTEGER NOT NULL DEFAULT 0, kind_score REAL NOT NULL DEFAULT 0, kind_votes INTEGER NOT NULL DEFAULT 0,
                    side_score REAL NOT NULL DEFAULT 0, side_votes INTEGER NOT NULL DEFAULT 0, peak_avg REAL NOT NULL DEFAULT 0)"""
            )
            v5.execSQL(
                """CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, trip_id INTEGER,
                    type TEXT NOT NULL, bump_id INTEGER, lat REAL, lon REAL, speed_kmh REAL, heading REAL, peak REAL,
                    slowdown_kmh REAL, distance_m REAL, note TEXT)"""
            )
            v5.execSQL(
                """CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER NOT NULL, end_ts INTEGER,
                    hits INTEGER, new_bumps INTEGER, beeps INTEGER, misses INTEGER, rejected INTEGER, distance_m REAL,
                    moving_s REAL, speeding_s REAL, speeding_excess REAL, max_speed REAL, harsh_brakes INTEGER,
                    harsh_accels INTEGER, harsh_corners INTEGER, swerves INTEGER, bumps_fast INTEGER, phone_use INTEGER,
                    potholes INTEGER, score INTEGER)"""
            )
            SyncStore.createTables(v5)
            v5.execSQL("INSERT INTO trips(start_ts, end_ts, distance_m, moving_s, speeding_s, score) VALUES (1000, 601000, 1500.0, 120.0, 30.0, 88)")
            v5.execSQL("INSERT INTO outbox(client_obs_id, json, trip_id, created_at) VALUES ('a', '{}', 1, 5)")
            v5.version = 5
        }

        val db = db()
        val sql = db.writableDatabase
        assertEquals(6, sql.version)
        assertTrue(columns(sql).containsAll(limitColumns))
        val old = db.trips().single()
        assertEquals(88, old.score)
        assertEquals(30.0, old.drive.speedingS, 0.0)
        assertFalse(old.limitsLookedUp)
        assertEquals(-1.0, old.drive.limitKnownShare, 0.0)
        assertEquals(0.0, old.drive.limitKnownS, 0.0)
        assertEquals(0.0, old.drive.overLimit10S + old.drive.overLimit20S + old.drive.overLimit30S + old.drive.maxOverLimitKmh, 0.0)
        assertFalse(old.drive.usesSpeedLimits)
        assertEquals(1, SyncStore(db).outboxCount())
        // An old trip can still be looked up later.
        assertTrue(db.setTripSpeedLimits(old.id, result(0.9, 100.0, 0.0, 0.0)))
        assertTrue(db.trips().single().limitsLookedUp)
    }

    @Test fun newTripStartsNotLookedUpThenTakesLimitsAndNewScore() {
        val db = db()
        val id = finishedTrip(db)
        val before = db.trips().single()
        assertFalse(before.limitsLookedUp)
        assertEquals(100, before.score)

        // Known for 90 % of the distance, a third of the time over +20: the limits now drive the speed part.
        assertTrue(db.setTripSpeedLimits(id, result(0.9, 810.0, 270.0, 270.0)))
        val after = db.trips().single()
        assertTrue(after.limitsLookedUp)
        assertTrue(after.drive.usesSpeedLimits)
        assertEquals(0.9, after.drive.limitKnownShare, 1e-9)
        assertEquals(810.0, after.drive.limitKnownS, 0.0)
        assertEquals(270.0, after.drive.overLimit10S, 0.0)
        assertEquals(270.0, after.drive.overLimit20S, 0.0)
        assertEquals(27.0, after.drive.maxOverLimitKmh, 0.0)
        assertEquals(after.drive.score(), after.score)
        assertEquals(60, after.score)   // penalty min(40, 20 + 20)
    }

    @Test fun limitsKnownForTooLittleKeepTheOldScore() {
        val db = db()
        val id = finishedTrip(db)
        assertTrue(db.setTripSpeedLimits(id, result(0.2, 100.0, 100.0, 100.0)))
        val t = db.trips().single()
        assertTrue(t.limitsLookedUp)
        assertFalse(t.drive.usesSpeedLimits)
        assertEquals(100, t.score)
    }

    @Test fun missingTripIsReported() {
        assertFalse(db().setTripSpeedLimits(42, result(1.0, 1.0, 0.0, 0.0)))
    }
}
