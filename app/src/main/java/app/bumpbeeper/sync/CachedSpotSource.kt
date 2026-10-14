package app.bumpbeeper.sync

import app.bumpbeeper.BumpKind
import app.bumpbeeper.Confidence
import app.bumpbeeper.Geo
import app.bumpbeeper.RemoteSpot
import app.bumpbeeper.Severity
import app.bumpbeeper.Side
import app.bumpbeeper.SpotSource

/**
 * The engine's view of the shared map: reads the `remote_spots` cache in SQLite, never the network
 * ([SyncJob] fills the cache in the background). Called on the engine thread.
 */
class CachedSpotSource(private val store: SyncStore) : SpotSource {

    override fun spotsNear(lat: Double, lon: Double, radiusM: Double): List<RemoteSpot> =
        store.remoteSpotsInBox(lat, lon, radiusM)
            .filter { Geo.distance(lat, lon, it.lat, it.lon) <= radiusM }
            .mapNotNull { toRemote(it) }

    companion object {
        fun toRemote(r: SpotRow): RemoteSpot? {
            val heading = r.heading ?: return null
            return RemoteSpot(
                r.id, r.lat, r.lon, heading, kindOf(r.kind), sideOf(r.side), r.severity ?: 0.0, r.nDevices,
                // What spots_near_v2 adds (absent from spots_near: hits = phones, no band or confidence).
                nHits = r.nHits ?: r.nDevices, band = Severity.values().firstOrNull { it.label == r.band },
                confidence = Confidence.values().firstOrNull { it.label == r.confidence }, legacy = r.legacy,
            )
        }

        /** Server kind text → kind; anything else (null, new values) is "unsure". */
        fun kindOf(kind: String?): BumpKind = when (kind) {
            "bump" -> BumpKind.BUMP
            "pothole" -> BumpKind.POTHOLE
            else -> BumpKind.UNSURE
        }

        /** Server side text → side; "both" and null don't name one wheel, so they are "unknown". */
        fun sideOf(side: String?): Side = when (side) {
            "left" -> Side.LEFT
            "right" -> Side.RIGHT
            else -> Side.UNKNOWN
        }
    }
}
