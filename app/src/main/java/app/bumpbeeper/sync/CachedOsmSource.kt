package app.bumpbeeper.sync

import app.bumpbeeper.Geo
import app.bumpbeeper.OsmBump
import app.bumpbeeper.OsmSource

/**
 * The engine's view of the OpenStreetMap bumps: reads the `osm_bumps` cache in SQLite, never the network ([Sync] fills
 * it with the shared spots), and keeps this phone's clean passes and mutes there. Called on the engine thread.
 */
class CachedOsmSource(private val store: SyncStore) : OsmSource {

    override fun osmBumpsNear(lat: Double, lon: Double, radiusM: Double): List<OsmBump> =
        store.osmBumpsInBox(lat, lon, radiusM).filter { Geo.distance(lat, lon, it.lat, it.lon) <= radiusM }

    override fun osmCleanPass(osmId: String) = store.osmCleanPass(osmId)

    override fun osmMuted(osmId: String) = store.osmMute(osmId)
}
