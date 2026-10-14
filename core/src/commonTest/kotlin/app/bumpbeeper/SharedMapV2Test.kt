package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E2 part 2b: the shared map's v2 fields, read (RemoteSpot) and written (Observation). */
class SharedMapV2Test {
    /** The first warning for one shared spot at 1000 m, driving east at 50 km/h. */
    private fun soundFor(spot: (DoubleArray) -> RemoteSpot): WarnSound {
        val sim = Simulator(31)
        val r = sim.drive(MemoryStore(), DriveSpec(cruiseKmh = 50.0), spotSource = ListSpotSource(listOf(spot(sim.point(1000.0, false)))))
        return r.warnings.single().sound
    }

    private fun spot(
        p: DoubleArray, severity: Double, nDevices: Int, nHits: Int = nDevices, band: Severity? = null,
        confidence: Confidence? = null, legacy: Boolean = false,
    ) = RemoteSpot(7, p[0], p[1], 90.0, BumpKind.BUMP, Side.UNKNOWN, severity, nDevices, nHits, band, confidence, legacy)

    @Test fun theServersConfidenceAndBandAreUsed() {
        // Without v2 fields: one hit per phone, the band from the index (as before).
        assertEquals(WarnSound.SOFT, soundFor { spot(it, 6.0, 1) })
        assertEquals(WarnSound.STRONG, soundFor { spot(it, 6.0, 2) })
        // nHits: one phone that felt it twice confirms it.
        assertEquals(WarnSound.STRONG, soundFor { spot(it, 6.0, 1, nHits = 2) })
        // The server's confidence wins over the count either way.
        assertEquals(WarnSound.SOFT, soundFor { spot(it, 6.0, 3, nHits = 5, confidence = Confidence.SOFT) })
        assertEquals(WarnSound.STRONG, soundFor { spot(it, 6.0, 1, nHits = 1, confidence = Confidence.FULL) })
        // The server's band is kept inside the hysteresis: 4.9 is moderate by its index, still strong after a strong band.
        assertEquals(WarnSound.MODERATE, soundFor { spot(it, 4.9, 2) })
        assertEquals(WarnSound.STRONG, soundFor { spot(it, 4.9, 2, band = Severity.STRONG) })
        // An old pothole spot stays a maybe.
        assertEquals(WarnSound.SOFT, soundFor { spot(it, 6.0, 4, legacy = true) })
    }

    @Test fun hitsCarryTheirDoseAndAxleScore() {
        val sink = ListSink()
        val store = MemoryStore()
        val spec = DriveSpec(bumpsAt = listOf(800.0), cruiseKmh = 50.0)
        Simulator(32).drive(store, spec, tripId = 1, observationSink = sink)
        Simulator(33).drive(store, spec, tripId = 2, observationSink = sink)
        val hits = sink.got.filter { it.kind == "jolt" || it.kind == "known_hit" }
        assertEquals(listOf("jolt", "known_hit"), hits.map { it.kind })
        for (o in hits) {
            val sev = assertNotNull(o.sevIndex, "$o")
            assertTrue(sev > 0.0 && sev <= 100.0, "$o")
            val axle = assertNotNull(o.axle, "$o")
            assertTrue(axle in 0.0..1.0, "$o")
        }
        assertTrue(hits.any { it.axle!! >= 0.6 }, "both axles felt at least once: $hits")
        sink.got.filter { it.kind == "pass_clear" }.forEach { assertNull(it.sevIndex); assertNull(it.axle) }
    }
}
