package app.bumpbeeper

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

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
    @Test fun remoteOldPotholesAreSoft() = Scenarios.remoteOldPotholesAreSoft()
    @Test fun observationsRecorded() = Scenarios.observationsRecorded()
    @Test fun remoteTwin18m() = Scenarios.remoteTwin18m()
    @Test fun muteSharedSpot() = Scenarios.muteSharedSpot()
    @Test fun remoteStandIns() = Scenarios.remoteStandIns()

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

    /** A cache that answers once and then breaks: the spots it gave keep warning. */
    @Test fun sourceThatBreaksKeepsCache() {
        val sim = Simulator(69)
        val spots = listOf(500.0, 1100.0).mapIndexed { i, p ->
            val q = sim.point(p, false)
            RemoteSpot(10L + i, q[0], q[1], 90.0, BumpKind.BUMP, Side.UNKNOWN, 5.0, 2)
        }
        var calls = 0
        val flaky = object : SpotSource {
            override fun spotsNear(lat: Double, lon: Double, radiusM: Double): List<RemoteSpot> {
                if (calls++ > 0) throw IllegalStateException("db closed")
                return spots
            }
        }
        val r = sim.drive(MemoryStore(), DriveSpec(), tripId = 1, spotSource = flaky)
        assertTrue(calls >= 2, "the source should have been asked again (and failed)")
        assertEquals(listOf(10L, 11L), r.beepBumpIds.map { BumpEngine.remoteSpotId(it) })
    }

    /** Asked once at the first good fix; never while parked; then every 30 s of driving (or 300 m). */
    @Test fun refreshSchedule() {
        val source = ListSpotSource(emptyList())
        val eng = BumpEngine(EngineConfig(), MemoryStore(), object : EngineListener {}, { 0L }, 1L, source)
        var lat = 30.0444
        var lon = 31.2357
        // Parked for 2 minutes: one question only.
        for (s in 0 until 120) eng.onFix(Fix(s * 1000L, lat, lon, 0.0, Double.NaN, 5.0))
        assertEquals(1, source.calls)
        // A bad fix never asks.
        eng.onFix(Fix(120_500L, lat, lon, 5.0, 90.0, 80.0))
        assertEquals(1, source.calls)
        // 100 s at 5 m/s (500 m): due at once (30 s since the last question), then at +30, +60, +90 s.
        for (s in 121 until 221) {
            eng.onFix(Fix(s * 1000L, lat, lon, 5.0, 90.0, 5.0))
            val p = Geo.move(lat, lon, 90.0, 5.0); lat = p[0]; lon = p[1]
        }
        assertEquals(5, source.calls)
        // 10 s at 40 m/s (400 m): more than 300 m since the last question, so asked again before 30 s are up.
        for (s in 221 until 231) {
            eng.onFix(Fix(s * 1000L, lat, lon, 40.0, 90.0, 5.0))
            val p = Geo.move(lat, lon, 90.0, 40.0); lat = p[0]; lon = p[1]
        }
        assertEquals(6, source.calls)
    }

    @Test fun remoteIdRoundTrip() {
        assertEquals(null, BumpEngine.remoteSpotId(5L))
        assertEquals(null, BumpEngine.remoteSpotId(-1L))
        assertEquals(0L, BumpEngine.remoteSpotId(-2L))
        assertEquals(42L, BumpEngine.remoteSpotId(-44L))
        // Huge server ids would overflow the stand-in id: they are ignored instead.
        val sim = Simulator(70)
        val q = sim.point(800.0, false)
        val huge = ListSpotSource(listOf(RemoteSpot(Long.MAX_VALUE - 1, q[0], q[1], 90.0, BumpKind.BUMP, Side.UNKNOWN, 5.0, 2)))
        val r = sim.drive(MemoryStore(), DriveSpec(), tripId = 1, spotSource = huge)
        assertTrue(r.beepBumpIds.isEmpty(), "an out-of-range id must not warn: ${r.beepBumpIds}")
    }
}
