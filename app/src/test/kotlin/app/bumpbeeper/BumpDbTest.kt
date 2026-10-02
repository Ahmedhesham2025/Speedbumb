package app.bumpbeeper

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The phone's database: schema on a fresh install, upgrades from old versions, and the bump file import. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BumpDbTest {
    private lateinit var ctx: Context
    private val opened = ArrayList<BumpDb>()

    // A spot in Cairo; every import test places its rows relative to it.
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

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(c.getColumnIndexOrThrow("name")))
            out
        }

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    private val bumpV2toV3 = setOf("kind_score", "kind_votes", "side_score", "side_votes", "peak_avg")
    private val tripV4 = setOf(
        "moving_s", "speeding_s", "speeding_excess", "max_speed", "harsh_brakes", "harsh_accels",
        "harsh_corners", "swerves", "bumps_fast", "phone_use", "potholes", "score",
    )

    // ---------------------------------------------------------------- schema

    @Test fun freshInstallCreatesSchemaV4() {
        val sql = db().writableDatabase
        assertEquals(4, sql.version)
        assertTrue(columns(sql, "bumps").containsAll(bumpV2toV3 + "user_muted"))
        assertTrue(columns(sql, "trips").containsAll(tripV4 + "distance_m"))
        assertTrue(columns(sql, "events").containsAll(setOf("ts", "trip_id", "type", "note")))
    }

    @Test fun upgradeFromV1KeepsRowsAndAddsColumnsWithDefaults() {
        // The version 1 schema exactly as the first release created it (no kind/side/peak, no driving columns).
        val file = ctx.getDatabasePath("bumps.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            v1.execSQL(
                """CREATE TABLE bumps(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    lat REAL NOT NULL, lon REAL NOT NULL, heading REAL NOT NULL,
                    hits INTEGER NOT NULL, passes INTEGER NOT NULL, misses INTEGER NOT NULL,
                    n_pos INTEGER NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                    user_muted INTEGER NOT NULL DEFAULT 0)"""
            )
            v1.execSQL(
                """CREATE TABLE events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL, trip_id INTEGER, type TEXT NOT NULL, bump_id INTEGER,
                    lat REAL, lon REAL, speed_kmh REAL, heading REAL, peak REAL,
                    slowdown_kmh REAL, distance_m REAL, note TEXT)"""
            )
            v1.execSQL(
                """CREATE TABLE trips(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    start_ts INTEGER NOT NULL, end_ts INTEGER,
                    hits INTEGER, new_bumps INTEGER, beeps INTEGER, misses INTEGER, rejected INTEGER, distance_m REAL)"""
            )
            v1.execSQL(
                "INSERT INTO bumps(lat, lon, heading, hits, passes, misses, n_pos, first_seen, last_seen, user_muted) " +
                    "VALUES ($lat0, $lon0, 90.0, 3, 4, 1, 3, 1000, 2000, 1)"
            )
            v1.execSQL("INSERT INTO events(ts, trip_id, type, bump_id, note) VALUES (1500, 1, 'hit', 1, 'old')")
            v1.execSQL(
                "INSERT INTO trips(start_ts, end_ts, hits, new_bumps, beeps, misses, rejected, distance_m) " +
                    "VALUES (1000, 601000, 3, 1, 2, 1, 0, 1500.0)"
            )
            v1.version = 1
        }

        val db = db()
        val sql = db.writableDatabase
        assertEquals(4, sql.version)
        assertTrue(columns(sql, "bumps").containsAll(bumpV2toV3))
        assertTrue(columns(sql, "trips").containsAll(tripV4))
        assertEquals(1, count(sql, "events"))

        val b = db.loadBumps().single()
        assertEquals(lat0, b.lat, 1e-9); assertEquals(lon0, b.lon, 1e-9); assertEquals(90.0, b.heading, 0.0)
        assertEquals(3, b.hits); assertEquals(4, b.passes); assertEquals(1, b.misses)
        assertEquals(1000L, b.firstSeen); assertEquals(2000L, b.lastSeen); assertTrue(b.userMuted)
        // New columns start neutral: kind unsure, side unknown, no jolt average.
        assertEquals(0.0, b.kindScore, 0.0); assertEquals(0, b.kindVotes); assertEquals(BumpKind.UNSURE, b.kind)
        assertEquals(0.0, b.sideScore, 0.0); assertEquals(0, b.sideVotes); assertEquals(Side.UNKNOWN, b.side)
        assertEquals(0.0, b.peakAvg, 0.0)

        // The old trip still shows, unscored (it was recorded before driving scores existed).
        val old = db.trips().single()
        assertEquals(3, old.hits); assertEquals(2, old.beeps); assertEquals(-1, old.score); assertEquals(600L, old.durationS)

        // And the upgraded database takes new-style writes.
        val id = db.startTrip(700_000)
        db.endTrip(id, 1_300_000, TripStats().apply { distanceM = 2000.0; potholes = 2 },
            DrivingStats().apply { distanceM = 2000.0; movingS = 300.0 })
        val fresh = db.trips().first { it.id == id }
        assertEquals(2, fresh.potholes)
        assertTrue("new trip is scored", fresh.score in 0..100)
    }

    // ---------------------------------------------------------------- CSV import

    private fun spot(id: Long = 0, lat: Double = lat0, lon: Double = lon0, heading: Double = 90.0) =
        Bump(id, lat, lon, heading, 2, 2, 0, 2, 1000, 2000)

    private fun row(lat: Double, lon: Double, heading: Double) =
        String.format(Locale.US, "%.7f,%.7f,%.0f", lat, lon, heading)

    /** Imports one spot (old minimal format) into a DB that already holds the reference spot. */
    private fun importNextToKnown(lat: Double, lon: Double, heading: Double): Triple<Int, Int, Int> {
        val db = db()
        db.insertBump(spot())
        return db.importBumpsCsv("lat,lon,heading\n" + row(lat, lon, heading) + "\n")
    }

    @Test fun importSpot19mAwaySameHeadingIsDuplicate() {
        val p = Geo.move(lat0, lon0, 0.0, 19.0)
        assertEquals(Triple(0, 1, 0), importNextToKnown(p[0], p[1], 90.0))
    }

    @Test fun importSpot21mAwaySameHeadingIsAdded() {
        val p = Geo.move(lat0, lon0, 0.0, 21.0)
        assertEquals(Triple(1, 0, 0), importNextToKnown(p[0], p[1], 90.0))
        assertEquals(2, opened.last().loadBumps().size)
    }

    @Test fun importSameSpotHeading44DegreesOffIsDuplicate() {
        assertEquals(Triple(0, 1, 0), importNextToKnown(lat0, lon0, 134.0))
    }

    @Test fun importSameSpotHeading46DegreesOffIsAdded() {
        // The other side of the road: same place, different direction of travel.
        assertEquals(Triple(1, 0, 0), importNextToKnown(lat0, lon0, 44.0))
    }

    @Test fun headingDifferenceWrapsAroundNorth() {
        val db = db()
        db.insertBump(spot(heading = 350.0))
        assertEquals(Triple(0, 1, 0), db.importBumpsCsv("lat,lon,heading\n" + row(lat0, lon0, 30.0)))
    }

    @Test fun twoRowsOfTheSameFileAtOneSpotAreAddedOnce() {
        val db = db()
        val r = row(lat0, lon0, 90.0)
        assertEquals(Triple(1, 1, 0), db.importBumpsCsv("lat,lon,heading\n$r\n$r\n"))
    }

    @Test fun unreadableRowsAreCountedAndSkipped() {
        val db = db()
        val csv = listOf(
            "lat,lon,heading",
            "abc,31.2,90",                // not a number
            "91.0,31.2,90",               // latitude out of range
            "30.0,181.0,90",              // longitude out of range
            "30.0,,90",                   // missing value
            "30.0",                       // short row
            row(lat0, lon0, 90.0),        // the one good row
        ).joinToString("\n")
        assertEquals(Triple(1, 0, 5), db.importBumpsCsv(csv))
        assertEquals(1, db.loadBumps().size)
    }

    @Test fun fileWithoutPositionColumnsAddsNothing() {
        val db = db()
        assertEquals(Triple(0, 0, 2), db.importBumpsCsv("name,score\nx,1\ny,2\n"))
        assertEquals(Triple(0, 0, 0), db.importBumpsCsv(""))
        assertTrue(db.loadBumps().isEmpty())
    }

    @Test fun oldFormatCsvWithoutNewColumnsImports() {
        val db = db()
        // Export header of the first release (before kind/side/peak), with a byte-order mark and Windows line ends.
        val csv = "﻿id,lat,lon,heading,hits,passes,misses,hit_rate,muted,first_seen,last_seen\r\n" +
            "7,30.0444000,31.2357000,90,3,5,2,0.60,0,2024-01-01 10:00:00,2024-02-01 10:00:00\r\n"
        assertEquals(Triple(1, 0, 0), db.importBumpsCsv(csv))
        val b = db.loadBumps().single()
        assertEquals(3, b.hits); assertEquals(5, b.passes); assertEquals(2, b.misses)
        assertFalse(b.userMuted)
        assertEquals(0, b.kindVotes); assertEquals(0, b.sideVotes); assertEquals(0.0, b.peakAvg, 0.0)
    }

    @Test fun exportThenImportIntoEmptyDbGivesTheSameSpots() {
        val source = db()
        val spots = listOf(
            Bump(0, lat0, lon0, 90.0, 4, 5, 1, 4, 1000, 2000, kindScore = -0.75, kindVotes = 4),
            Bump(0, lat0 + 0.001, lon0, 180.0, 2, 2, 0, 2, 1000, 2000,
                kindScore = 0.5, kindVotes = 2, sideScore = 1.0, sideVotes = 2, peakAvg = 7.25),
            Bump(0, lat0 + 0.002, lon0 + 0.001, 271.0, 1, 4, 3, 1, 1000, 2000, userMuted = true),
        )
        spots.forEach { source.insertBump(it) }
        val csv = source.bumpsCsv()
        source.clearAll()
        assertTrue(source.loadBumps().isEmpty())

        assertEquals(Triple(3, 0, 0), source.importBumpsCsv(csv))
        val back = source.loadBumps().sortedBy { it.lat }
        assertEquals(spots.size, back.size)
        for ((a, b) in spots.sortedBy { it.lat }.zip(back)) {
            assertEquals(a.lat, b.lat, 1e-7); assertEquals(a.lon, b.lon, 1e-7); assertEquals(a.heading, b.heading, 0.5)
            assertEquals(a.hits, b.hits); assertEquals(a.passes, b.passes); assertEquals(a.misses, b.misses)
            assertEquals(a.userMuted, b.userMuted)
            assertEquals(a.kindScore, b.kindScore, 0.005); assertEquals(a.kindVotes, b.kindVotes)
            assertEquals(a.sideScore, b.sideScore, 0.005); assertEquals(a.sideVotes, b.sideVotes)
            assertEquals(a.peakAvg, b.peakAvg, 0.005)
            assertEquals(a.kind, b.kind); assertEquals(a.side, b.side)
        }
    }
}
