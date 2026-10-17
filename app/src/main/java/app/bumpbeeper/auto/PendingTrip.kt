package app.bumpbeeper.auto

import android.content.Context
import android.util.Log
import app.bumpbeeper.BumpDb
import app.bumpbeeper.research.ResearchQueue

/**
 * The BumpDb trip of an automatic start not confirmed yet ([TripConfirm]), kept on disk while it records. If the app
 * dies before the trip is confirmed or ended, [cleanUp] at the next start deletes the trip, the spots only it found
 * and its research files: a drive never confirmed leaves nothing behind, and nothing of it is uploaded.
 */
object PendingTrip {
    private const val TAG = "PendingTrip"
    private const val KEY = "trip"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("pending_trip", Context.MODE_PRIVATE)

    fun set(ctx: Context, tripId: Long) {
        sp(ctx).edit().putLong(KEY, tripId).commit()
    }

    fun get(ctx: Context): Long = sp(ctx).getLong(KEY, -1L)

    /** Confirmed or ended: forgotten. Only if it is still [tripId] (a newer trip may have started meanwhile). */
    fun clear(ctx: Context, tripId: Long) {
        synchronized(this) { if (get(ctx) == tripId) sp(ctx).edit().remove(KEY).commit() }
    }

    /** Process start, nothing recording: a pending trip left over by a killed app goes, with its spots and files. */
    fun cleanUp(ctx: Context) {
        val id = get(ctx)
        if (id < 0) return
        try {
            ResearchQueue.discardTrip(ctx, id, "unconfirmed")
            val db = BumpDb(ctx)
            try {
                db.deleteSpotsOnlyFrom(id)
                db.deleteTrip(id)
            } finally {
                db.close()
            }
            clear(ctx, id)
        } catch (e: Exception) {
            Log.w(TAG, "unconfirmed trip not deleted; again next start", e)   // stays pending: never uploaded
        }
    }
}
