package app.bumpbeeper.sync

import app.bumpbeeper.Geo
import app.bumpbeeper.Observation
import app.bumpbeeper.ObservationSink

/**
 * Collects one trip's hazard observations in memory and, at trip end, queues the ones outside the
 * privacy zone for upload. Only created when the user opted in to sharing. Engine thread only.
 *
 * Nothing about the route is kept or uploaded: only these hazard points, each on its own.
 */
class OutboxSink(private val store: SyncStore, private val tripId: Long) : ObservationSink {
    private val pending = ArrayList<Observation>()
    private var first: DoubleArray? = null
    private var firstGood: DoubleArray? = null
    private var last: DoubleArray? = null
    private var lastGood: DoubleArray? = null

    /** Every GPS fix of the trip; remembers where the trip started and ended. */
    fun onFix(lat: Double, lon: Double, accuracyM: Double) {
        val p = doubleArrayOf(lat, lon)
        if (first == null) first = p
        last = p
        if (accuracyM <= GOOD_ACCURACY_M) {
            if (firstGood == null) firstGood = p
            lastGood = p
        }
    }

    override fun record(o: Observation) {
        if (pending.size < MAX_PER_TRIP) pending.add(o)
    }

    /**
     * Trip end: drops everything within [PrivacyZone.RADIUS_M] of where the trip started or ended (and anything
     * outside the server's service area), queues the rest in the outbox. [share] false (the user switched sharing
     * off during the trip) queues nothing. Returns how many were queued.
     */
    fun flush(share: Boolean, now: Long): Int {
        val anchors = listOfNotNull(first, firstGood, last, lastGood)
        val keep = if (share) PrivacyZone.keep(pending, anchors) else emptyList()
        pending.clear()
        val items = keep.filter { ObservationJson.inServiceArea(it.lat, it.lon) }
            .map { it.clientId to ObservationJson.toJson(it).toString() }
        if (items.isNotEmpty()) store.outboxAdd(items, tripId, now)
        return items.size
    }

    companion object {
        /** A first fix is often far off; the first fix this good is a second start point for the zone. */
        const val GOOD_ACCURACY_M = 30.0
        /** Upper bound against a runaway trip (the server takes 2000 per device per day anyway). */
        const val MAX_PER_TRIP = 5000
    }
}

/** The privacy zone: no observation within 300 m of where a trip started or ended (home, work) leaves the phone. */
object PrivacyZone {
    const val RADIUS_M = 300.0

    /** Observations farther than [radiusM] from every anchor point (lat, lon). No anchors (no GPS fix) → nothing. */
    fun keep(obs: List<Observation>, anchors: List<DoubleArray>, radiusM: Double = RADIUS_M): List<Observation> {
        if (anchors.isEmpty()) return emptyList()
        return obs.filter { o -> anchors.all { a -> Geo.distance(o.lat, o.lon, a[0], a[1]) > radiusM } }
    }
}
