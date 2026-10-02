package app.bumpbeeper.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.bumpbeeper.Geo
import kotlin.math.cos

/**
 * The sync tables inside bumps.db (database version 5):
 *  outbox       – hazard observations waiting for upload (already privacy-filtered), one JSON element each
 *  remote_spots – the cache of confirmed shared-map spots the engine warns for (filled in the background)
 *  sync_state   – small key/value notes (last sync time, back-off, last position for the download)
 */
class SyncStore(private val helper: SQLiteOpenHelper) {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------------- outbox

    /** Queues upload elements (client id → JSON) of one trip; a client id already queued is kept as it was. */
    fun outboxAdd(items: List<Pair<String, String>>, tripId: Long, now: Long) {
        val d = db
        d.beginTransaction()
        try {
            for ((id, json) in items) {
                d.insertWithOnConflict("outbox", null, ContentValues().apply {
                    put("client_obs_id", id); put("json", json); put("trip_id", tripId)
                    put("created_at", now); put("attempts", 0)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** Oldest queued elements first, at most [limit]: (client id, JSON). */
    fun outboxBatch(limit: Int): List<Pair<String, String>> =
        db.rawQuery("SELECT client_obs_id, json FROM outbox ORDER BY created_at, client_obs_id LIMIT $limit", null).use { c ->
            val out = ArrayList<Pair<String, String>>()
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
            out
        }

    fun outboxDelete(ids: Collection<String>) = inChunks(ids) { part ->
        db.delete("outbox", "client_obs_id IN (${part.joinToString(",") { "?" }})", part.toTypedArray())
    }

    fun outboxAttempted(ids: Collection<String>) = inChunks(ids) { part ->
        db.execSQL("UPDATE outbox SET attempts = attempts + 1 WHERE client_obs_id IN (${part.joinToString(",") { "?" }})", arrayOf<Any>(*part.toTypedArray()))
    }

    /** Drops what the server would reject anyway: older than [olderThan] (it takes 30 days at most) or tried too often. */
    fun outboxPrune(olderThan: Long, maxAttempts: Int): Int =
        db.delete("outbox", "created_at < ? OR attempts >= ?", arrayOf(olderThan.toString(), maxAttempts.toString()))

    fun outboxCount(): Int = count("outbox")

    fun outboxClear() {
        db.delete("outbox", null, null)
    }

    // ---------------- remote spot cache

    /** Cached spots in a box around the point (callers check the exact distance). */
    fun remoteSpotsInBox(lat: Double, lon: Double, radiusM: Double): List<SpotRow> {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return db.rawQuery(
            "SELECT id, lat, lon, heading, kind, side, severity, n_devices FROM remote_spots " +
                "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString()),
        ).use { c ->
            val out = ArrayList<SpotRow>()
            while (c.moveToNext()) {
                out.add(SpotRow(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getString(4), c.getString(5),
                    if (c.isNull(6)) null else c.getDouble(6), c.getInt(7)))
            }
            out
        }
    }

    /**
     * Stores a fresh `spots_near` answer for the circle ([lat], [lon], [radiusM]): upserts every row and deletes
     * cached spots inside that circle the server no longer returned (retired, stale, or merged away).
     * Spots without a direction are skipped: the engine only warns cars going the way a spot was hit.
     */
    fun replaceRemoteSpots(lat: Double, lon: Double, radiusM: Double, rows: List<SpotRow>, now: Long) {
        val keep = HashSet<Long>()
        val d = db
        d.beginTransaction()
        try {
            for (r in rows) {
                val heading = r.heading ?: continue
                keep.add(r.id)
                d.insertWithOnConflict("remote_spots", null, ContentValues().apply {
                    put("id", r.id); put("lat", r.lat); put("lon", r.lon); put("heading", heading)
                    put("kind", r.kind); put("side", r.side); put("severity", r.severity ?: 0.0)
                    put("n_devices", r.nDevices); put("fetched_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val gone = remoteSpotsInBox(lat, lon, radiusM)
                .filter { it.id !in keep && Geo.distance(lat, lon, it.lat, it.lon) <= radiusM }
                .map { it.id.toString() }
            inChunks(gone) { part -> d.delete("remote_spots", "id IN (${part.joinToString(",") { "?" }})", part.toTypedArray()) }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun remoteSpotCount(): Int = count("remote_spots")

    // ---------------- key/value

    fun get(key: String): String? =
        db.rawQuery("SELECT value FROM sync_state WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun getLong(key: String, def: Long = 0L): Long = get(key)?.toLongOrNull() ?: def
    fun getDouble(key: String): Double = get(key)?.toDoubleOrNull() ?: Double.NaN

    fun put(key: String, value: Any?) {
        if (value == null) db.delete("sync_state", "key = ?", arrayOf(key))
        else db.insertWithOnConflict("sync_state", null, ContentValues().apply { put("key", key); put("value", value.toString()) },
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Everything the sync keeps on the phone (for "forget me"). */
    fun clearAll() {
        db.delete("outbox", null, null)
        db.delete("remote_spots", null, null)
        db.delete("sync_state", null, null)
    }

    private fun count(table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    private fun <T> inChunks(items: Collection<T>, block: (List<String>) -> Unit) {
        // SQLite allows at most 999 '?' in one statement on old versions.
        items.map { it.toString() }.chunked(500).forEach(block)
    }

    companion object {
        /** Version 5: creates the sync tables (fresh install and upgrade alike). */
        fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS outbox(
                    client_obs_id TEXT PRIMARY KEY, json TEXT NOT NULL, trip_id INTEGER,
                    created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS remote_spots(
                    id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, heading REAL NOT NULL,
                    kind TEXT, side TEXT, severity REAL NOT NULL DEFAULT 0, n_devices INTEGER NOT NULL DEFAULT 0,
                    fetched_at INTEGER NOT NULL)"""
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS remote_spots_lat ON remote_spots(lat)")
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_state(key TEXT PRIMARY KEY, value TEXT)")
        }
    }
}
