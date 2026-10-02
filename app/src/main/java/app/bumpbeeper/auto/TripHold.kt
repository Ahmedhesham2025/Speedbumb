package app.bumpbeeper.auto

import android.content.Context
import app.bumpbeeper.BumpDb
import app.bumpbeeper.sync.SpeedLimitSync
import app.bumpbeeper.sync.Sync
import app.bumpbeeper.sync.SyncStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trips that started by themselves (motion detection / Google, #49) are **held** until the user answers
 * "Was this a drive?" ([TripCheck]). Nothing from a held trip may leave the phone. One generic API for every kind of
 * held data: shared-map points and the speed-limit route (built in, [installBuiltIns]); training samples plug in the
 * same way.
 *
 * To hold your own data: at trip end check [isHeld] and keep the data local; register listeners once per process
 * from [AppStart.onCreate]:
 *  - [onConfirmed]: "Yes, I drove" → send it as usual.
 *  - [onRejected]: "No" → delete it. The trip, its events and the spots only it found are deleted after the listeners.
 *  - [onExpired]: no answer within [MAX_AGE_MS] → delete it unsent; the trip stays on the phone.
 * Listeners run on a background thread.
 */
object TripHold {
    /** Unanswered "Was this a drive?": held data is deleted unsent after this long. */
    const val MAX_AGE_MS = 24 * 60 * 60_000L

    fun interface Listener {
        fun onTrip(ctx: Context, tripId: Long)
    }

    private val confirmed = CopyOnWriteArrayList<Listener>()
    private val rejected = CopyOnWriteArrayList<Listener>()
    private val expired = CopyOnWriteArrayList<Listener>()
    private val builtIns = AtomicBoolean(false)

    fun onConfirmed(l: Listener) { confirmed += l }
    fun onRejected(l: Listener) { rejected += l }
    fun onExpired(l: Listener) { expired += l }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("trip_hold", Context.MODE_PRIVATE)

    /** Trip end, before anything of the trip is queued. */
    fun hold(ctx: Context, tripId: Long, now: Long = System.currentTimeMillis()) {
        sp(ctx).edit().putLong(tripId.toString(), now).commit()
    }

    fun isHeld(ctx: Context, tripId: Long): Boolean = sp(ctx).contains(tripId.toString())

    /** Held trips, oldest first: (trip id, held since). */
    fun heldTrips(ctx: Context): List<Pair<Long, Long>> =
        sp(ctx).all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> id to ((v as? Long) ?: 0L) } }.sortedBy { it.second }

    /** "Yes, I drove" (background thread). */
    fun confirm(ctx: Context, tripId: Long) {
        if (!release(ctx, tripId)) return
        confirmed.forEach { it.onTrip(ctx, tripId) }
    }

    /** "No" (background thread): held data, then the trip itself and the spots only it found, are deleted. */
    fun reject(ctx: Context, tripId: Long) {
        release(ctx, tripId)
        rejected.forEach { it.onTrip(ctx, tripId) }
        val db = BumpDb(ctx)
        try {
            db.deleteSpotsOnlyFrom(tripId)
            db.deleteTrip(tripId)
        } finally {
            db.close()
        }
    }

    /** Deletes the held data of trips unanswered for [MAX_AGE_MS] (background thread). Returns how many. */
    fun expire(ctx: Context, now: Long = System.currentTimeMillis()): Int {
        val old = heldTrips(ctx).filter { now - it.second >= MAX_AGE_MS }
        for ((id, _) in old) if (release(ctx, id)) expired.forEach { it.onTrip(ctx, id) }
        return old.size
    }

    private fun release(ctx: Context, tripId: Long): Boolean {
        val p = sp(ctx)
        if (!p.contains(tripId.toString())) return false
        p.edit().remove(tripId.toString()).commit()
        return true
    }

    /** Shared-map points ([SyncStore.holdAdd]) and the speed-limit route ([SpeedLimitSync]). Once per process. */
    fun installBuiltIns() {
        if (!builtIns.compareAndSet(false, true)) return
        onConfirmed { ctx, id ->
            if (withStore(ctx) { it.heldRelease(id) } > 0) Sync.afterTrip(ctx, Double.NaN, Double.NaN)   // upload soon
            SpeedLimitSync.release(ctx, id)
        }
        val drop = Listener { ctx, id ->
            withStore(ctx) { it.heldDrop(id) }
            SpeedLimitSync.drop(ctx, id)
        }
        onRejected(drop)
        onExpired(drop)
    }

    private fun <T> withStore(ctx: Context, block: (SyncStore) -> T): T {
        val db = BumpDb(ctx)
        try {
            return block(SyncStore(db))
        } finally {
            db.close()
        }
    }
}
