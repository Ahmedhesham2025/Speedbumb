package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E4c: a hump's shape from the standard profiles (H 7-15 cm, L 0.6-4 m), crossed at 15 km/h. */
class HumpShapeTest {
    private fun shapeOf(hump: ProfileHump, seed: Long = 71): Pair<BumpShape?, MemoryStore> {
        val store = MemoryStore()
        val r = Simulator(seed).drive(store, DriveSpec(profileHumps = listOf(hump), bumpKmh = 15.0, cruiseKmh = 40.0, slowZonesAt = listOf(300.0)))
        assertEquals(1, r.newBumps, "${hump.kind} ${hump.lengthM} m: ${r.rejected} ${store.events.filter { it.type == "rejected" }.map { it.note }}")
        return store.saved.single().shape to store
    }

    @Test fun shortSteepHumps() {
        for (h in listOf(ProfileHump(900.0, "circular", 0.10, 0.6), ProfileHump(900.0, "sinus", 0.12, 1.0), ProfileHump(900.0, "circular", 0.15, 0.9))) {
            val (shape, store) = shapeOf(h)
            assertEquals(BumpShape.SHORT_STEEP, shape, "${h.kind} ${h.heightM} x ${h.lengthM}: ${store.events.filter { it.type == "new_bump" }.map { it.note }}")
        }
    }

    @Test fun aLongFlatTopWarnsLikeAStrongBump() {
        val hump = ProfileHump(900.0, "trapez", 0.10, 4.0, plateauM = 2.5)
        val (shape, store) = shapeOf(hump)
        assertEquals(BumpShape.LONG_FLAT, shape, "${store.events.filter { it.type == "new_bump" }.map { it.note }}")
        // Felt again, confirmed: 3 beeps and the voice, whatever its band.
        val spec = DriveSpec(profileHumps = listOf(hump), bumpKmh = 15.0, cruiseKmh = 40.0, slowZonesAt = listOf(300.0))
        Simulator(72).drive(store, spec, tripId = 2)
        val t3 = Simulator(73).drive(store, spec, EngineConfig().apply { quietBelowKmh = 0.0 }, tripId = 3)
        assertEquals(Confidence.FULL, store.saved.single().confidence(EngineConfig()))
        assertEquals(WarnSound.STRONG, t3.warnings.first().sound, "${t3.warnings.map { it.sound }}")
    }

    @Test fun aLowHump() {
        val (shape, store) = shapeOf(ProfileHump(900.0, "sinus", 0.03, 0.6))
        assertEquals(BumpShape.LOW, shape, "${store.events.filter { it.type == "new_bump" }.map { it.note }}")
    }

    @Test fun theShapeIsStoredAndObserved() {
        // Judged after the first pass, so the next hit's observation carries it.
        val sink = ListSink()
        val store = MemoryStore()
        val spec = DriveSpec(profileHumps = listOf(ProfileHump(900.0, "sinus", 0.12, 1.0)), bumpKmh = 15.0, cruiseKmh = 40.0)
        Simulator(74).drive(store, spec, tripId = 1, observationSink = sink)
        Simulator(75).drive(store, spec, tripId = 2, observationSink = sink)
        assertEquals(1, store.events.count { it.type == "hump_shape" && it.tripId == 1L }, "${store.events.map { it.type }}")
        val hit = sink.got.single { it.kind == "known_hit" }
        assertEquals(BumpShape.SHORT_STEEP, hit.shape, "$hit")
        val b = Bump(1, 0.0, 0.0, 0.0, 1, 1, 0, 1, 0, 0, shape = BumpShape.LONG_FLAT)
        assertEquals(BumpShape.LONG_FLAT, b.copy().shape)
    }
}
