package app.bumpbeeper.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * "Help improve detection" outbox inside bumps.db (database version 7): `training_outbox` holds upload elements
 * (one JSON object each, `kind` = sample or trip) until the server has them. No coordinates are ever stored here.
 * Rows of a trip that isn't confirmed yet (auto-started) are `held`: never uploaded until [release], gone after
 * [HELD_MS]. `held` and `trip_key` (the local trip id) stay on the phone; they are never part of the JSON.
 */
class TrainingStore(private val helper: SQLiteOpenHelper) {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    class Item(val id: String, val kind: String, val json: String)

    fun add(items: List<Item>, now: Long, tripKey: String? = null, held: Boolean = false) {
        val d = db
        d.beginTransaction()
        try {
            for (it in items) {
                d.insertWithOnConflict(TABLE, null, ContentValues().apply {
                    put("client_id", it.id); put("kind", it.kind); put("json", it.json); put("created_at", now)
                    put("trip_key", tripKey); put("held", if (held) 1 else 0)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /**
     * The next upload: oldest first, at most [maxSamples] samples and [maxTrips] trips and about [maxBytes] of JSON.
     * Within a trip the order is the random client id, so the server can't put samples in driving order.
     */
    fun batch(maxSamples: Int = MAX_SAMPLES, maxTrips: Int = MAX_TRIPS, maxBytes: Int = MAX_BYTES): List<Item> {
        val out = ArrayList<Item>()
        var bytes = 0
        for ((kind, limit) in listOf(TRIP to maxTrips, SAMPLE to maxSamples)) {
            db.rawQuery(
                "SELECT client_id, json FROM $TABLE WHERE kind = ? AND held = 0 ORDER BY created_at, client_id LIMIT $limit", arrayOf(kind),
            ).use { c ->
                while (c.moveToNext()) {
                    val json = c.getString(1)
                    if (out.isNotEmpty() && bytes + json.length > maxBytes) return out
                    bytes += json.length
                    out.add(Item(c.getString(0), kind, json))
                }
            }
        }
        return out
    }

    fun delete(ids: Collection<String>) = ids.chunked(500).forEach { part ->
        db.delete(TABLE, "client_id IN (${part.joinToString(",") { "?" }})", part.toTypedArray())
    }

    /** The trip was confirmed: its rows may be uploaded. */
    fun release(tripKey: String) = db.execSQL("UPDATE $TABLE SET held = 0 WHERE trip_key = ?", arrayOf<Any>(tripKey))

    /** Not a drive, or consent gone: the trip's rows are deleted. */
    fun discard(tripKey: String) = db.delete(TABLE, "trip_key = ?", arrayOf(tripKey))

    /** Drops unsent rows older than [KEEP_MS] (the server takes 31 days, but stale is useless) and held ones older than [HELD_MS]. */
    fun prune(now: Long): Int = db.delete(
        TABLE, "(held = 0 AND created_at < ?) OR (held != 0 AND created_at < ?)",
        arrayOf((now - KEEP_MS).toString(), (now - HELD_MS).toString()),
    )

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { it.moveToFirst(); it.getInt(0) }

    fun clear() {
        db.delete(TABLE, null, null)
    }

    companion object {
        const val TABLE = "training_outbox"
        const val SAMPLE = "sample"
        const val TRIP = "trip"
        /** Server limits per call: 100 samples, 10 trips, 1 MB (measured as jsonb text, which adds spaces). */
        const val MAX_SAMPLES = 100
        const val MAX_TRIPS = 10
        const val MAX_BYTES = 700_000
        const val KEEP_MS = 7 * 24 * 3_600_000L
        const val HELD_MS = 24 * 3_600_000L

        /** Version 7 (fresh install and upgrade alike). */
        fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS $TABLE(
                    client_id TEXT PRIMARY KEY, kind TEXT NOT NULL, json TEXT NOT NULL, created_at INTEGER NOT NULL,
                    held INTEGER NOT NULL DEFAULT 0, trip_key TEXT)"""
            )
        }
    }
}
