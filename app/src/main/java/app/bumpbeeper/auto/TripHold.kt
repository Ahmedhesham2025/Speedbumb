package app.bumpbeeper.auto

import android.content.Context
import android.util.Log
import app.bumpbeeper.BumpDb
import app.bumpbeeper.sync.SpeedLimitSync
import app.bumpbeeper.sync.Sync
import app.bumpbeeper.sync.SyncStore
import app.bumpbeeper.sync.TrainingSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trips that started by themselves (motion detection / Google, #49) are **held** until the user answers
 * "Was this a drive?" ([TripCheck]). Nothing from a held trip may leave the phone. One generic API for every kind of
 * held data: shared-map points and the speed-limit route ([installBuiltIns]) and training samples ([installTraining]).
 *
 * To hold your own data: at trip end check [isHeld] and keep the data local; register listeners once per process
 * from [AppStart.onCreate]:
 *  - [onConfirmed]: "Yes, I drove" → send it as usual.
 *  - [onRejected]: "No" → delete it. The trip, its events and the spots only it found are deleted after the listeners.
 *  - [onExpired]: no answer within [MAX_AGE_MS] → delete it unsent; the trip stays on the phone.
 * Listeners run on a background thread. Delete listeners run BEFORE the hold is removed, and the hold is removed only
 * if every one of them succeeded (throw if you couldn't delete): until then nothing of the trip may be sent.
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
    private val training = AtomicBoolean(false)
    /** One answer at a time per process: check-and-remove of a hold is atomic. */
    private val lock = Any()
    private const val TAG = "BumpBeeper"

    fun onConfirmed(l: Listener) { confirmed += l }
    fun onRejected(l: Listener) { rejected += l }
    fun onExpired(l: Listener) { expired += l }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("trip_hold", Context.MODE_PRIVATE)

    /** Trip end, before anything of the trip is queued. */
    fun hold(ctx: Context, tripId: Long, now: Long = System.currentTimeMillis()) {
        sp(ctx).edit().putLong(tripId.toString(), now).commit()
    }

    fun isHeld(ctx: Context, tripId: Long): Boolean = sp(ctx).contains(tripId.toString())

    /** Answered "No" or expired (kept [2 × MAX_AGE_MS]): data that arrives late for this trip must be held too. */
    private fun done(ctx: Context) = ctx.getSharedPreferences("trip_hold_done", Context.MODE_PRIVATE)

    fun wasDiscarded(ctx: Context, tripId: Long): Boolean = done(ctx).contains(tripId.toString())

    /**
     * Data written after trip end on another thread (training samples) must be held when the trip is held now, or
     * when it was already rejected / expired by the time the data lands (a quick "No" can win that race).
     */
    fun mustHold(ctx: Context, tripId: Long): Boolean = isHeld(ctx, tripId) || wasDiscarded(ctx, tripId)

    /** Held trips, oldest first: (trip id, held since). */
    fun heldTrips(ctx: Context): List<Pair<Long, Long>> =
        sp(ctx).all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> id to ((v as? Long) ?: 0L) } }.sortedBy { it.second }

    /**
     * "Yes, I drove" (background thread). The hold goes first, atomically: a tap and the 24 h expiry can't both act.
     * Too late (held [MAX_AGE_MS] or longer) is treated as expired: nothing is sent.
     */
    fun confirm(ctx: Context, tripId: Long, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val since = sp(ctx).getLong(tripId.toString(), Long.MIN_VALUE)
            if (since == Long.MIN_VALUE) return
            if (now - since >= MAX_AGE_MS) { dropHeld(ctx, tripId, expired); return }
            sp(ctx).edit().remove(tripId.toString()).commit()
        }
        // Data released by mistake here would be data the user approved; a failing listener only logs.
        runAll(confirmed, ctx, tripId)
    }

    /**
     * "No" (background thread): held data is deleted, then the trip itself and the spots only it found.
     * A recording running at that moment may still hold such a spot in memory; the engine only UPDATEs spots, so it
     * is not re-created and is gone from the next trip on.
     */
    fun reject(ctx: Context, tripId: Long) {
        synchronized(lock) {
            if (isHeld(ctx, tripId)) dropHeld(ctx, tripId, rejected)
        }
        val db = BumpDb(ctx)
        try {
            db.deleteSpotsOnlyFrom(tripId)
            db.deleteTrip(tripId)
        } finally {
            db.close()
        }
    }

    /**
     * Deletes the held data of trips unanswered for [MAX_AGE_MS] (background thread), and any held points left without
     * a hold. A trip whose deletion failed stays held (nothing of it is sent) and is retried next time.
     * Returns how many trips were cleared.
     */
    fun expire(ctx: Context, now: Long = System.currentTimeMillis()): Int {
        var n = 0
        for ((id, since) in heldTrips(ctx)) {
            if (now - since < MAX_AGE_MS) continue
            synchronized(lock) { if (isHeld(ctx, id) && dropHeld(ctx, id, expired)) n++ }
        }
        try {
            synchronized(lock) {   // a "Yes" between reading and dropping would otherwise lose approved points
                withStore(ctx) { s ->
                    s.heldTrips().filter { (id, at) -> !isHeld(ctx, id) || now - at >= MAX_AGE_MS }.forEach { s.heldDrop(it.first) }
                }
                val d = done(ctx)
                val stale = d.all.filter { (_, v) -> now - ((v as? Long) ?: 0L) >= 2 * MAX_AGE_MS }.keys
                if (stale.isNotEmpty()) d.edit().apply { stale.forEach { remove(it) } }.commit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "held points not swept", e)
        }
        return n
    }

    /** Under [lock]: the delete listeners first, each on its own; the hold goes only if all of them succeeded. */
    private fun dropHeld(ctx: Context, tripId: Long, listeners: List<Listener>): Boolean {
        if (!runAll(listeners, ctx, tripId)) return false
        done(ctx).edit().putLong(tripId.toString(), System.currentTimeMillis()).commit()
        sp(ctx).edit().remove(tripId.toString()).commit()
        return true
    }

    private fun runAll(listeners: List<Listener>, ctx: Context, tripId: Long): Boolean {
        var ok = true
        for (l in listeners) {
            try {
                l.onTrip(ctx, tripId)
            } catch (e: Exception) {
                Log.w(TAG, "trip $tripId: held data listener failed", e)
                ok = false
            }
        }
        return ok
    }

    /** Tests only: forget every listener (Robolectric keeps this object between tests). */
    internal fun reset() {
        confirmed.clear(); rejected.clear(); expired.clear()
        builtIns.set(false)
        training.set(false)
    }

    /** Shared-map points ([SyncStore.holdAdd]) and the speed-limit route ([SpeedLimitSync]). Once per process. */
    fun installBuiltIns() {
        if (!builtIns.compareAndSet(false, true)) return
        onConfirmed { ctx, id ->
            if (withStore(ctx) { it.heldRelease(id) } > 0) Sync.afterTrip(ctx, Double.NaN, Double.NaN)   // upload soon
        }
        onConfirmed { ctx, id -> SpeedLimitSync.release(ctx, id) }
        // Two listeners, so a failing one doesn't skip the other.
        val points = Listener { ctx, id -> withStore(ctx) { it.heldDrop(id) } }
        val route = Listener { ctx, id -> SpeedLimitSync.drop(ctx, id) }
        for (l in listOf(points, route)) { onRejected(l); onExpired(l) }
    }

    /**
     * Training samples ([TrainingSink]): held while the trip is held (or already rejected), released on "Yes",
     * deleted on "No" or expiry ([TrainingSink.discard] throws if it can't delete). Once per process.
     */
    fun installTraining(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        TrainingSink.isHeld = { mustHold(app, it) }   // every time: follows the current Application (tests make new ones)
        if (!training.compareAndSet(false, true)) return
        onConfirmed { c, id -> TrainingSink.release(c, id.toString()) }
        val discard = Listener { c, id -> TrainingSink.discard(c, id.toString()) }
        onRejected(discard)
        onExpired(discard)
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
