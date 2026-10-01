package app.bumpbeeper

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SQLite database on the phone (file: bumps.db). Three tables:
 *  bumps  – the learned map
 *  events – everything that happened (new bump, hit, miss, beep, rejected jolt) → for tuning
 *  trips  – one row per Start…Stop
 */
class BumpDb(ctx: Context) : SQLiteOpenHelper(ctx, "bumps.db", null, 1), BumpStore {

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
                user_muted INTEGER NOT NULL DEFAULT 0)"""
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------------- BumpStore (used by the engine) ----------------

    override fun loadBumps(): List<Bump> {
        val out = ArrayList<Bump>()
        readableDatabase.rawQuery(
            "SELECT id, lat, lon, heading, hits, passes, misses, n_pos, first_seen, last_seen, user_muted FROM bumps", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Bump(
                        c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3),
                        c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getLong(8), c.getLong(9),
                        c.getInt(10) != 0,
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

    private fun values(b: Bump) = ContentValues().apply {
        put("lat", b.lat); put("lon", b.lon); put("heading", b.heading)
        put("hits", b.hits); put("passes", b.passes); put("misses", b.misses)
        put("n_pos", b.nPos); put("first_seen", b.firstSeen); put("last_seen", b.lastSeen)
        put("user_muted", if (b.userMuted) 1 else 0)
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

    fun endTrip(id: Long, ts: Long, s: TripStats) {
        val v = ContentValues().apply {
            put("end_ts", ts); put("hits", s.hits); put("new_bumps", s.newBumps); put("beeps", s.beeps)
            put("misses", s.misses); put("rejected", s.rejected); put("distance_m", s.distanceM)
        }
        writableDatabase.update("trips", v, "id = ?", arrayOf(id.toString()))
    }

    // ---------------- screen helpers ----------------

    /** (total bumps, muted bumps) */
    fun counts(cfg: EngineConfig = EngineConfig()): Pair<Int, Int> {
        val bumps = loadBumps()
        return Pair(bumps.size, bumps.count { it.isMuted(cfg) })
    }

    fun clearAll() {
        writableDatabase.apply {
            delete("bumps", null, null)
            delete("events", null, null)
            delete("trips", null, null)
        }
    }

    // ---------------- CSV export ----------------

    fun bumpsCsv(cfg: EngineConfig = EngineConfig()): String {
        val sb = StringBuilder("id,lat,lon,heading,hits,passes,misses,hit_rate,muted,first_seen,last_seen\n")
        for (b in loadBumps()) {
            sb.append(b.id).append(',')
                .append(num(b.lat, 7)).append(',').append(num(b.lon, 7)).append(',')
                .append(num(b.heading, 0)).append(',')
                .append(b.hits).append(',').append(b.passes).append(',').append(b.misses).append(',')
                .append(num(b.hitRate, 2)).append(',').append(if (b.isMuted(cfg)) 1 else 0).append(',')
                .append(time(b.firstSeen)).append(',').append(time(b.lastSeen)).append('\n')
        }
        return sb.toString()
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
