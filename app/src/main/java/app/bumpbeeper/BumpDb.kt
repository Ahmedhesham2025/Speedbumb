package app.bumpbeeper

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.bumpbeeper.sync.SyncStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * SQLite database on the phone (file: bumps.db). Three tables:
 *  bumps  – the learned map
 *  events – everything that happened (new bump, hit, miss, beep, rejected jolt) → for tuning
 *  trips  – one row per Start…Stop
 * plus the shared-map sync tables (outbox, remote_spots, sync_state), see [SyncStore].
 */
class BumpDb(ctx: Context) : SQLiteOpenHelper(ctx, "bumps.db", null, 6), BumpStore {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE bumps(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lat REAL NOT NULL, lon REAL NOT NULL, heading REAL NOT NULL,
                hits INTEGER NOT NULL, passes INTEGER NOT NULL, misses INTEGER NOT NULL,
                n_pos INTEGER NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                user_muted INTEGER NOT NULL DEFAULT 0,
                kind_score REAL NOT NULL DEFAULT 0, kind_votes INTEGER NOT NULL DEFAULT 0,
                side_score REAL NOT NULL DEFAULT 0, side_votes INTEGER NOT NULL DEFAULT 0, peak_avg REAL NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL, trip_id INTEGER, type TEXT NOT NULL, bump_id INTEGER,
                lat REAL, lon REAL, speed_kmh REAL, heading REAL, peak REAL,
                slowdown_kmh REAL, distance_m REAL, note TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE trips(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ts INTEGER NOT NULL, end_ts INTEGER,
                hits INTEGER, new_bumps INTEGER, beeps INTEGER, misses INTEGER, rejected INTEGER, distance_m REAL)"""
        )
        addTripColumns(db)
        SyncStore.createTables(db)
        addSpeedLimitColumns(db)
    }

    /** Version 6: speeding against road limits ([DrivingStats.withSpeedLimits]); -1 = not looked up. */
    private fun addSpeedLimitColumns(db: SQLiteDatabase) {
        for (c in listOf(
            "limit_known_share REAL NOT NULL DEFAULT -1", "limit_known_s REAL NOT NULL DEFAULT 0",
            "over_limit_10_s REAL NOT NULL DEFAULT 0", "over_limit_20_s REAL NOT NULL DEFAULT 0",
            "over_limit_30_s REAL NOT NULL DEFAULT 0", "max_over_limit_kmh REAL NOT NULL DEFAULT 0",
        )) db.execSQL("ALTER TABLE trips ADD COLUMN $c")
    }

    /** Version 4: driving statistics and score per trip. */
    private fun addTripColumns(db: SQLiteDatabase) {
        for (c in listOf(
            "moving_s REAL", "speeding_s REAL", "speeding_excess REAL", "max_speed REAL",
            "harsh_brakes INTEGER", "harsh_accels INTEGER", "harsh_corners INTEGER", "swerves INTEGER",
            "bumps_fast INTEGER", "phone_use INTEGER", "potholes INTEGER", "score INTEGER",
        )) db.execSQL("ALTER TABLE trips ADD COLUMN $c")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 2: speed bump / pothole score. Existing bumps start as "unsure" and learn on the next pass.
            db.execSQL("ALTER TABLE bumps ADD COLUMN kind_score REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE bumps ADD COLUMN kind_votes INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // Version 3: which side a pothole is on, and how harsh it is.
            db.execSQL("ALTER TABLE bumps ADD COLUMN side_score REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE bumps ADD COLUMN side_votes INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE bumps ADD COLUMN peak_avg REAL NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) addTripColumns(db)
        // Version 5: shared-map sync (outbox, remote spot cache, sync state). New tables only; nothing else changes.
        if (oldVersion < 5) SyncStore.createTables(db)
        // Version 6: road speed limit columns; existing trips read as "not looked up".
        if (oldVersion < 6) addSpeedLimitColumns(db)
    }

    // ---------------- BumpStore (used by the engine) ----------------

    override fun loadBumps(): List<Bump> {
        val out = ArrayList<Bump>()
        readableDatabase.rawQuery(
            "SELECT id, lat, lon, heading, hits, passes, misses, n_pos, first_seen, last_seen, user_muted, " +
                "kind_score, kind_votes, side_score, side_votes, peak_avg FROM bumps", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Bump(
                        c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3),
                        c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getLong(8), c.getLong(9),
                        c.getInt(10) != 0, c.getDouble(11), c.getInt(12), c.getDouble(13), c.getInt(14), c.getDouble(15),
                    )
                )
            }
        }
        return out
    }

    override fun insertBump(b: Bump): Long = writableDatabase.insert("bumps", null, values(b))

    override fun updateBump(b: Bump) {
        writableDatabase.update("bumps", values(b), "id = ?", arrayOf(b.id.toString()))
    }

    fun deleteBump(id: Long) {
        writableDatabase.delete("bumps", "id = ?", arrayOf(id.toString()))
    }

    private fun values(b: Bump) = ContentValues().apply {
        put("lat", b.lat); put("lon", b.lon); put("heading", b.heading)
        put("hits", b.hits); put("passes", b.passes); put("misses", b.misses)
        put("n_pos", b.nPos); put("first_seen", b.firstSeen); put("last_seen", b.lastSeen)
        put("user_muted", if (b.userMuted) 1 else 0)
        put("kind_score", b.kindScore); put("kind_votes", b.kindVotes)
        put("side_score", b.sideScore); put("side_votes", b.sideVotes); put("peak_avg", b.peakAvg)
    }

    override fun logEvent(e: BumpEvent) {
        val v = ContentValues().apply {
            put("ts", e.wallTime); put("trip_id", e.tripId); put("type", e.type)
            if (e.bumpId >= 0) put("bump_id", e.bumpId)
            putNum("lat", e.lat); putNum("lon", e.lon); putNum("speed_kmh", e.speedKmh)
            putNum("heading", e.heading); putNum("peak", e.peak)
            putNum("slowdown_kmh", e.slowdownKmh); putNum("distance_m", e.distanceM)
            put("note", e.note)
        }
        writableDatabase.insert("events", null, v)
    }

    private fun ContentValues.putNum(key: String, x: Double) {
        if (x.isNaN()) putNull(key) else put(key, x)
    }

    // ---------------- trips ----------------

    fun startTrip(ts: Long): Long =
        writableDatabase.insert("trips", null, ContentValues().apply { put("start_ts", ts) })

    fun endTrip(id: Long, ts: Long, s: TripStats, d: DrivingStats? = null) {
        val v = ContentValues().apply {
            put("end_ts", ts); put("hits", s.hits); put("new_bumps", s.newBumps); put("beeps", s.beeps)
            put("misses", s.misses); put("rejected", s.rejected); put("distance_m", s.distanceM)
            put("potholes", s.potholes)
            if (d != null) {
                put("moving_s", d.movingS); put("speeding_s", d.speedingS); put("speeding_excess", d.speedingExcess)
                put("max_speed", d.maxSpeedKmh); put("harsh_brakes", d.harshBrakes); put("harsh_accels", d.harshAccels)
                put("harsh_corners", d.harshCorners); put("swerves", d.swerves); put("bumps_fast", d.bumpsFast)
                put("phone_use", d.phoneUse); put("score", d.score())
                putSpeedLimits(d)
                // The driving monitor measures distance the same way; prefer it when the bump engine had poor GPS.
                if (d.distanceM > s.distanceM) put("distance_m", d.distanceM)
            }
        }
        writableDatabase.update("trips", v, "id = ?", arrayOf(id.toString()))
    }

    private fun ContentValues.putSpeedLimits(d: DrivingStats) {
        put("limit_known_share", d.limitKnownShare); put("limit_known_s", d.limitKnownS)
        put("over_limit_10_s", d.overLimit10S); put("over_limit_20_s", d.overLimit20S); put("over_limit_30_s", d.overLimit30S)
        put("max_over_limit_kmh", d.maxOverLimitKmh)
    }

    /** One trip by id, finished or not (null if it is gone). */
    fun trip(id: Long): TripRow? = tripRows("WHERE id = $id", 1).singleOrNull()

    /**
     * After the speed-limit lookup: stores [r] for a finished trip and, when the limits now drive the speed part
     * ([DrivingStats.usesSpeedLimits]), its new score. False when the trip is gone.
     *
     * Only the speed part of the score changes: new score = old score + old speed penalty − new speed penalty.
     * Recomputing the whole score here would use the stored distance (the larger of engine and monitor), not the
     * monitor's distance the trip-end score used, and move the other penalties too.
     */
    fun setTripSpeedLimits(id: Long, r: SpeedLimitResult): Boolean {
        val row = trip(id) ?: return false
        val d = row.drive.withSpeedLimits(r)
        val v = ContentValues().apply {
            putSpeedLimits(d)
            if (d.usesSpeedLimits && row.score >= 0) {
                put("score", (row.score + speedPenalty(row.drive) - speedPenalty(d)).roundToInt().coerceIn(0, 100))
            }
        }
        return writableDatabase.update("trips", v, "id = ?", arrayOf(id.toString())) > 0
    }

    /** The speed part of [DrivingStats.score] (0..40): road limits when used, else the fixed threshold (same formula). */
    internal fun speedPenalty(d: DrivingStats): Double =
        if (d.usesSpeedLimits) SpeedLimitScoring.penalty(d.limitKnownS, d.overLimit10S, d.overLimit20S, d.overLimit30S)
        else min(40.0, d.speedingShare * 60.0 + d.speedingShare * d.avgExcessKmh)

    /**
     * One finished trip, for the Trips screen. Road speed limits are in [drive]: [DrivingStats.limitKnownShare]
     * (-1 = not looked up, see [limitsLookedUp]), [DrivingStats.limitKnownS], [DrivingStats.overLimit10S] /
     * 20 / 30, [DrivingStats.maxOverLimitKmh], and [DrivingStats.usesSpeedLimits] (show "© TomTom" then).
     */
    class TripRow(
        val id: Long, val startTs: Long, val endTs: Long, val hits: Int, val newBumps: Int, val beeps: Int,
        val potholes: Int, val drive: DrivingStats, val score: Int,
    ) {
        val durationS: Long get() = ((endTs - startTs) / 1000).coerceAtLeast(0)
        val limitsLookedUp: Boolean get() = drive.limitKnownShare >= 0
    }

    /** Finished trips, newest first. Trips shorter than 200 m (started by mistake) are left out. */
    fun trips(limit: Int = 200): List<TripRow> =
        tripRows("WHERE end_ts IS NOT NULL AND distance_m >= 200 ORDER BY start_ts DESC", limit)

    private fun tripRows(where: String, limit: Int): List<TripRow> {
        val out = ArrayList<TripRow>()
        readableDatabase.rawQuery(
            "SELECT id, start_ts, end_ts, hits, new_bumps, beeps, potholes, distance_m, moving_s, speeding_s, speeding_excess, " +
                "max_speed, harsh_brakes, harsh_accels, harsh_corners, swerves, bumps_fast, phone_use, score, " +
                "limit_known_share, limit_known_s, over_limit_10_s, over_limit_20_s, over_limit_30_s, max_over_limit_kmh " +
                "FROM trips $where LIMIT $limit", null
        ).use { c ->
            fun i(k: Int) = if (c.isNull(k)) 0 else c.getInt(k)
            fun dd(k: Int) = if (c.isNull(k)) 0.0 else c.getDouble(k)
            while (c.moveToNext()) {
                val d = DrivingStats().apply {
                    distanceM = dd(7); movingS = dd(8); speedingS = dd(9); speedingExcess = dd(10); maxSpeedKmh = dd(11)
                    harshBrakes = i(12); harshAccels = i(13); harshCorners = i(14); swerves = i(15)
                    bumpsFast = i(16); phoneUse = i(17)
                    limitKnownShare = if (c.isNull(19)) -1.0 else c.getDouble(19); limitKnownS = dd(20)
                    overLimit10S = dd(21); overLimit20S = dd(22); overLimit30S = dd(23); maxOverLimitKmh = dd(24)
                }
                // Trips recorded before driving scores existed have no score: leave them unscored.
                val score = if (c.isNull(18)) -1 else c.getInt(18)
                out.add(TripRow(c.getLong(0), c.getLong(1), if (c.isNull(2)) 0L else c.getLong(2), i(3), i(4), i(5), i(6), d, score))
            }
        }
        return out
    }

    /** Driving events (harsh braking etc.) of one trip, oldest first. */
    fun tripEvents(tripId: Long): List<BumpEvent> {
        val out = ArrayList<BumpEvent>()
        readableDatabase.rawQuery(
            "SELECT ts, type, lat, lon, speed_kmh, peak, note FROM events WHERE trip_id = ? AND type IN " +
                "('harsh_brake','harsh_accel','harsh_corner','swerve','speeding','bump_fast','phone_use') ORDER BY id",
            arrayOf(tripId.toString()),
        ).use { c ->
            fun dd(k: Int) = if (c.isNull(k)) Double.NaN else c.getDouble(k)
            while (c.moveToNext()) {
                out.add(BumpEvent(c.getLong(0), tripId, c.getString(1), -1, dd(2), dd(3), dd(4), Double.NaN, dd(5), Double.NaN, Double.NaN, c.getString(6) ?: ""))
            }
        }
        return out
    }

    fun tripsCsv(): String {
        val sb = StringBuilder(
            "trip_id,start,end,duration_min,distance_km,avg_speed_kmh,max_speed_kmh,speeding_pct,avg_excess_kmh," +
                "harsh_brakes,harsh_accels,harsh_corners,swerves,bumps_fast,phone_use,score,grade,bumps_hit,potholes_hit,warnings\n"
        )
        for (t in trips(10_000)) {
            val d = t.drive
            sb.append(t.id).append(',').append(time(t.startTs)).append(',').append(time(t.endTs)).append(',')
                .append(num(t.durationS / 60.0, 1)).append(',').append(num(d.distanceM / 1000, 2)).append(',')
                .append(num(d.avgSpeedKmh, 1)).append(',').append(num(d.maxSpeedKmh, 0)).append(',')
                .append(num(d.speedingShare * 100, 1)).append(',').append(num(d.avgExcessKmh, 1)).append(',')
                .append(d.harshBrakes).append(',').append(d.harshAccels).append(',').append(d.harshCorners).append(',')
                .append(d.swerves).append(',').append(d.bumpsFast).append(',').append(d.phoneUse).append(',')
                .append(if (t.score >= 0) t.score.toString() else "").append(',').append(DrivingStats.grade(t.score)).append(',')
                .append(t.hits).append(',').append(t.potholes).append(',').append(t.beeps).append('\n')
        }
        return sb.toString()
    }

    // ---------------- screen helpers ----------------

    class Counts(val total: Int, val muted: Int, val bumps: Int, val potholes: Int, val harsh: Int, val unsure: Int)

    fun counts(cfg: EngineConfig = EngineConfig()): Counts {
        val all = loadBumps()
        return Counts(
            all.size, all.count { it.isMuted(cfg) },
            all.count { it.kind == BumpKind.BUMP }, all.count { it.kind == BumpKind.POTHOLE },
            all.count { it.isHarsh(cfg) }, all.count { it.kind == BumpKind.UNSURE },
        )
    }

    fun clearAll() {
        writableDatabase.apply {
            delete("bumps", null, null)
            delete("events", null, null)
            delete("trips", null, null)
        }
    }

    // ---------------- CSV export / import ----------------

    fun bumpsCsv(cfg: EngineConfig = EngineConfig()): String {
        val sb = StringBuilder(
            "id,lat,lon,heading,hits,passes,misses,hit_rate,muted,first_seen,last_seen,kind,kind_score,kind_votes,user_muted,harsh,side,side_score,side_votes,peak_avg_ms2\n"
        )
        for (b in loadBumps()) {
            sb.append(b.id).append(',')
                .append(num(b.lat, 7)).append(',').append(num(b.lon, 7)).append(',')
                .append(num(b.heading, 0)).append(',')
                .append(b.hits).append(',').append(b.passes).append(',').append(b.misses).append(',')
                .append(num(b.hitRate, 2)).append(',').append(if (b.isMuted(cfg)) 1 else 0).append(',')
                .append(time(b.firstSeen)).append(',').append(time(b.lastSeen)).append(',')
                .append(b.kind.name.lowercase()).append(',').append(num(b.kindScore, 2)).append(',')
                .append(b.kindVotes).append(',').append(if (b.userMuted) 1 else 0).append(',')
                .append(if (b.isHarsh(cfg)) 1 else 0).append(',').append(b.side.name.lowercase()).append(',')
                .append(num(b.sideScore, 2)).append(',').append(b.sideVotes).append(',').append(num(b.peakAvg, 2)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Adds the bumps from a shared bumps CSV (this app's export, old or new format).
     * A bump already on the map (within [EngineConfig.matchRadiusM], same direction) is kept as it is.
     * Returns (added, already known, unreadable rows).
     */
    fun importBumpsCsv(text: String, cfg: EngineConfig = EngineConfig()): Triple<Int, Int, Int> {
        val lines = text.lineSequence().map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return Triple(0, 0, 0)
        val head = lines[0].split(',').map { it.trim().lowercase() }
        fun idx(name: String) = head.indexOf(name)
        val iLat = idx("lat"); val iLon = idx("lon"); val iHead = idx("heading")
        if (iLat < 0 || iLon < 0 || iHead < 0) return Triple(0, 0, lines.size - 1)
        val iHits = idx("hits"); val iPasses = idx("passes"); val iMisses = idx("misses")
        val iScore = idx("kind_score"); val iVotes = idx("kind_votes"); val iUserMuted = idx("user_muted")
        val iSide = idx("side_score"); val iSideVotes = idx("side_votes"); val iPeak = idx("peak_avg_ms2")

        val known = loadBumps().toMutableList()
        var added = 0; var dup = 0; var bad = 0
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (line in lines.drop(1)) {
                val f = line.split(',')
                fun d(i: Int) = if (i in f.indices) f[i].trim().toDoubleOrNull() else null
                fun n(i: Int) = if (i in f.indices) f[i].trim().toIntOrNull() else null
                val lat = d(iLat); val lon = d(iLon); val hd = d(iHead)
                if (lat == null || lon == null || hd == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) { bad++; continue }
                val same = known.any {
                    Geo.distance(lat, lon, it.lat, it.lon) <= cfg.matchRadiusM && Geo.angleDiff(hd, it.heading) <= cfg.headingTolDeg
                }
                if (same) { dup++; continue }
                val hits = (n(iHits) ?: 1).coerceAtLeast(1)
                val passes = (n(iPasses) ?: hits).coerceAtLeast(hits)
                val misses = (n(iMisses) ?: (passes - hits)).coerceIn(0, passes)
                val b = Bump(
                    0, lat, lon, hd, hits, passes, misses, hits, now, now,
                    userMuted = (n(iUserMuted) ?: 0) != 0,
                    kindScore = (d(iScore) ?: 0.0).coerceIn(-1.0, 1.0),
                    kindVotes = (n(iVotes) ?: 0).coerceAtLeast(0),
                    sideScore = (d(iSide) ?: 0.0).coerceIn(-1.0, 1.0),
                    sideVotes = (n(iSideVotes) ?: 0).coerceAtLeast(0),
                    peakAvg = (d(iPeak) ?: 0.0).coerceIn(0.0, 50.0),
                )
                b.id = db.insert("bumps", null, values(b))
                known.add(b)
                added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return Triple(added, dup, bad)
    }

    fun eventsCsv(): String {
        val sb = StringBuilder("time,trip_id,type,bump_id,lat,lon,speed_kmh,heading,peak_ms2,slowdown_kmh,distance_m,note\n")
        readableDatabase.rawQuery(
            "SELECT ts, trip_id, type, bump_id, lat, lon, speed_kmh, heading, peak, slowdown_kmh, distance_m, note " +
                "FROM events ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) {
                sb.append(time(c.getLong(0))).append(',')
                    .append(if (c.isNull(1)) "" else c.getLong(1).toString()).append(',')
                    .append(c.getString(2)).append(',')
                    .append(if (c.isNull(3)) "" else c.getLong(3).toString()).append(',')
                    .append(col(c, 4, 7)).append(',').append(col(c, 5, 7)).append(',')
                    .append(col(c, 6, 1)).append(',').append(col(c, 7, 0)).append(',')
                    .append(col(c, 8, 2)).append(',').append(col(c, 9, 1)).append(',')
                    .append(col(c, 10, 1)).append(',')
                    .append((c.getString(11) ?: "").replace(',', ';')).append('\n')
            }
        }
        return sb.toString()
    }

    private fun col(c: Cursor, i: Int, digits: Int) = if (c.isNull(i)) "" else num(c.getDouble(i), digits)

    // Always US format: an Arabic-locale phone would otherwise write Arabic digits into the CSV.
    private fun num(x: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", x)
    private fun time(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
