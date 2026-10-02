package app.bumpbeeper.sync

import app.bumpbeeper.BumpKind
import app.bumpbeeper.Geo
import app.bumpbeeper.RemoteSpot
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
            return RemoteSpot(r.id, r.lat, r.lon, heading, kindOf(r.kind), sideOf(r.side), r.severity ?: 0.0, r.nDevices)
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
