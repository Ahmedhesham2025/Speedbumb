package app.bumpbeeper.sync

import app.bumpbeeper.Fix
import app.bumpbeeper.Observation
import app.bumpbeeper.ObservationSink
import app.bumpbeeper.TripPrivacy

/**
 * Collects one trip's hazard observations in memory and, at trip end, queues the ones outside the
 * privacy zone for upload. Only created when the user opted in to sharing. Engine thread only.
 *
 * Nothing about the route is kept or uploaded: only these hazard points, each on its own.
 */
class OutboxSink(
    private val store: SyncStore,
    private val tripId: Long,
    /** An unconfirmed trip (#49): [flush] holds the elements until the user says it was a drive. */
    @Volatile var held: Boolean = false,
) : ObservationSink {
    private val pending = ArrayList<Observation>()
    /** Metres driven when each pending observation came in ([TripPrivacy.driven]). */
    private val drivenAt = ArrayList<Double>()
    private var driven = 0.0
    private var first: Fix? = null
    private var firstGood: Fix? = null
    private var last: Fix? = null
    private var lastGood: Fix? = null

    /** Every GPS fix of the trip; remembers where the trip started and ended, and how far it has gone. */
    fun onFix(f: Fix) {
        val prev = last
        if (prev != null) {
            if (f.timeMs < prev.timeMs) return   // out of order: skipped, as TripPrivacy does
            driven += TripPrivacy.driven(listOf(prev, f))[1]
        }
        if (first == null) first = f
        last = f
        if (f.accuracyM <= GOOD_ACCURACY_M) {
            if (firstGood == null) firstGood = f
            lastGood = f
        }
    }

    /** Tests only: a fix without time or speed, so each step counts as its straight-line distance. */
    fun onFix(lat: Double, lon: Double, accuracyM: Double) =
        onFix(Fix((last?.timeMs ?: 0L) + 1000, lat, lon, Double.NaN, Double.NaN, accuracyM))

    override fun record(o: Observation) {
        if (pending.size < MAX_PER_TRIP) { pending.add(o); drivenAt.add(driven) }
    }

    /**
     * Trip end: drops everything within [PrivacyZone.RADIUS_M] of where the trip started or ended, or within its
     * first or last [PrivacyZone.RADIUS_M] driven (and anything outside the server's service area), queues the rest
     * in the outbox. [share] false (the user switched sharing off during the trip) queues nothing. Returns how many
     * were queued.
     */
    fun flush(share: Boolean, now: Long): Int {
        val anchors = listOfNotNull(first, firstGood, last, lastGood).map { doubleArrayOf(it.lat, it.lon) }
        val keep = if (share) PrivacyZone.keep(pending, anchors, drivenAt, driven) else emptyList()
        pending.clear()
        drivenAt.clear()
        val items = keep.filter { ObservationJson.inServiceArea(it.lat, it.lon) }
            .map { it.clientId to ObservationJson.toJson(it).toString() }
        if (items.isNotEmpty()) {
            if (held) store.holdAdd(items, tripId, now) else store.outboxAdd(items, tripId, now)
        }
        return items.size
    }

    companion object {
        /** A first fix is often far off; the first fix this good is a second start point for the zone. */
        const val GOOD_ACCURACY_M = TripPrivacy.GOOD_ACCURACY_M
        /** Upper bound against a runaway trip (the server takes 2000 per device per day anyway). */
        const val MAX_PER_TRIP = 5000
    }
}

/**
 * The privacy zone: no observation within 300 m of where a trip started or ended (home, work) leaves the phone.
 * One rule for everything that leaves the phone: this delegates to the core's [TripPrivacy], which the
 * speed-limit lookup ([app.bumpbeeper.RouteSampler]) uses too.
 */
object PrivacyZone {
    const val RADIUS_M = TripPrivacy.RADIUS_M

    /** Observations farther than [radiusM] from every anchor point (lat, lon). No anchors (no GPS fix) → nothing. */
    fun keep(obs: List<Observation>, anchors: List<DoubleArray>, radiusM: Double = RADIUS_M): List<Observation> =
        obs.filter { TripPrivacy.outside(it.lat, it.lon, anchors, radiusM) }

    /**
     * [keep], and also drops what came in within the first or last [RADIUS_M] driven, as [TripPrivacy] trims a
     * route: [drivenAtM] holds the metres driven when each observation came in, [totalM] those at trip end.
     */
    fun keep(obs: List<Observation>, anchors: List<DoubleArray>, drivenAtM: List<Double>, totalM: Double): List<Observation> =
        obs.filterIndexed { i, o ->
            val d = drivenAtM.getOrNull(i) ?: return@filterIndexed false
            d > RADIUS_M && totalM - d > RADIUS_M && TripPrivacy.outside(o.lat, o.lon, anchors)
        }
}
