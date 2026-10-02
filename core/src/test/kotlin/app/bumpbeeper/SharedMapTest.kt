package app.bumpbeeper

import org.junit.Assert.assertEquals
import org.junit.Test

/** In-memory stand-in for the app's shared-map cache table. */
class ListSpotSource(private val spots: List<RemoteSpot>) : SpotSource {
    var calls = 0
    override fun spotsNear(lat: Double, lon: Double, radiusM: Double): List<RemoteSpot> {
        calls++
        return spots.filter { Geo.distance(lat, lon, it.lat, it.lon) <= radiusM }
    }
}

/** In-memory stand-in for the app's upload outbox. */
class ListSink : ObservationSink {
    val got = ArrayList<Observation>()
    override fun record(o: Observation) { got.add(o) }
}

/** Shared online map, engine side: spots from other phones warn, own observations are recorded. */
class SharedMapTest {
    @Test fun remoteSpotsWarnFirstDrive() = Scenarios.remoteSpotsWarnFirstDrive()
    @Test fun localMuteSuppressesRemote() = Scenarios.localMuteSuppressesRemote()
    @Test fun remotePotholeRules() = Scenarios.remotePotholeRules()
    @Test fun observationsRecorded() = Scenarios.observationsRecorded()

    /** An empty shared map and an outbox must not change anything the engine does. */
    @Test fun emptySourceChangesNothing() {
        val spec = DriveSpec(bumpsAt = listOf(500.0, 1100.0, 1600.0), slowZonesAt = listOf(800.0))
        fun run(shared: Boolean): List<String> {
            val sim = Simulator(65)
            val store = MemoryStore()
            for (trip in 1L..2L) {
                if (shared) sim.drive(store, spec, tripId = trip, spotSource = ListSpotSource(emptyList()), observationSink = ListSink())
                else sim.drive(store, spec, tripId = trip)
            }
            return store.events.map { "${it.type} ${it.bumpId} ${it.note}" }
        }
        assertEquals(run(false), run(true))
    }

    /** A cache that throws must not stop the engine: it keeps warning for its own spots. */
    @Test fun brokenSourceIsIgnored() {
        val sim = Simulator(66)
        val store = MemoryStore()
        val real = listOf(500.0, 1100.0)
        sim.drive(store, DriveSpec(bumpsAt = real), tripId = 1)
        val broken = object : SpotSource {
            override fun spotsNear(lat: Double, lon: Double, radiusM: Double): List<RemoteSpot> = throw IllegalStateException("db closed")
        }
        val r = sim.drive(store, DriveSpec(bumpsAt = real), tripId = 2, spotSource = broken)
        assertEquals(2, r.beepBumpIds.size)
    }

    @Test fun remoteIdRoundTrip() {
        assertEquals(null, BumpEngine.remoteSpotId(5L))
        assertEquals(null, BumpEngine.remoteSpotId(-1L))
        assertEquals(0L, BumpEngine.remoteSpotId(-2L))
        assertEquals(42L, BumpEngine.remoteSpotId(-44L))
    }
}
