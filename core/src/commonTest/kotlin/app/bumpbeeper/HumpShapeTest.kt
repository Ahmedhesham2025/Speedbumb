package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E4c: a hump's shape from the standard profiles (H 7-15 cm, L 0.6-4 m), crossed at 15 km/h. */
class HumpShapeTest {
    private fun shapeOf(hump: ProfileHump, seed: Long = 71): Pair<BumpShape?, MemoryStore> {
        // (One spot for a hump, however long: its far end and rear axle are no hump of their own.)
        val store = MemoryStore()
        // A steady 15 km/h, so the GPS speed is right and the axle pair is found (braking, GPS lags 1 s).
        val r = Simulator(seed).drive(store, DriveSpec(profileHumps = listOf(hump), bumpKmh = 15.0, cruiseKmh = 15.0, roadM = 1200.0))
        assertEquals(1, r.newBumps, "${hump.kind} ${hump.lengthM} m: ${r.rejected} ${store.events.filter { it.type == "rejected" || it.type == "new_bump" }.map { it.note }}")
        return store.saved.single().shape to store
    }

    @Test fun shortSteepHumps() {
        for (h in listOf(ProfileHump(900.0, "circular", 0.10, 0.6), ProfileHump(900.0, "sinus", 0.12, 1.0), ProfileHump(900.0, "circular", 0.15, 0.9))) {
            val (shape, store) = shapeOf(h)
            assertEquals(BumpShape.SHORT_STEEP, shape, "${h.kind} ${h.heightM} x ${h.lengthM}: ${store.events.filter { it.type == "new_bump" }.map { it.note }}")
        }
    }

    @Test fun aLongFlatTopWarnsLikeAStrongBump() {
        // (A 3 m top: with 2.5 m the front's way down and the rear's climb onto the top meet, a wheelbase apart.)
        val hump = ProfileHump(900.0, "trapez", 0.10, 4.0, plateauM = 3.0)
        val (shape, store) = shapeOf(hump)
        // Never short. LONG_FLAT when the climb onto the top shows clearly; else unknown (labelled drives will tune it).
        assertTrue(shape == BumpShape.LONG_FLAT || shape == null, "${store.events.filter { it.type == "new_bump" }.map { it.note }}")
        if (shape == null) return
        // Felt again, confirmed: 3 beeps and the voice, whatever its band.
        val spec = DriveSpec(profileHumps = listOf(hump), bumpKmh = 15.0, cruiseKmh = 15.0, roadM = 1200.0)
        Simulator(72).drive(store, spec, tripId = 2)
        val t3 = Simulator(73).drive(store, spec, EngineConfig().apply { quietBelowKmh = 0.0 }, tripId = 3)
        assertEquals(Confidence.FULL, store.saved.single().confidence(EngineConfig()))
        assertEquals(WarnSound.STRONG, t3.warnings.first().sound, "${t3.warnings.map { it.sound }}")
    }

    @Test fun humpsInARowAreSeparateSpotsAndOneWarning() {
        // 2 and 3 humps 8 m apart at 15 km/h: each learned on its own (never the first one's rear axle), then one
        // group warning for the row.
        for (n in listOf(2, 3)) {
            val store = MemoryStore()
            // Short humps (their shape known), at a steady 15 km/h so the axle pairs are found.
            val spec = DriveSpec(profileHumps = List(n) { ProfileHump(900.0 + 8.0 * it, "sinus", 0.10, 0.6) }, bumpKmh = 15.0, cruiseKmh = 15.0, roadM = 1100.0)
            val t1 = Simulator(81).drive(store, spec, tripId = 1)
            assertEquals(n, t1.newBumps, "$n humps: ${t1.rejected} ${store.events.filter { it.type in setOf("new_bump", "hit_repeat") }.map { it.type }}")
            val t2 = Simulator(82).drive(store, spec, EngineConfig().apply { quietBelowKmh = 0.0 }, tripId = 2)
            val g = t2.warnings.firstOrNull()?.cluster
            assertTrue(g != null && g.count == n, "$n humps: ${t2.warnings.map { it.cluster?.count }}")
            assertEquals(1, t2.warnings.size, "one warning for the row")
            assertEquals(n - 1, store.events.count { it.tripId == 2L && it.type == "beep_grouped" })
        }
        assertEquals("2 bumps ahead.", Phrases.cluster("en", 2, anyStrong = false))
    }

    @Test fun shortHumpsInARowAreNeverLongFlat() {
        // Short humps 4, 5 and 6 m apart, crossed at 5-20 km/h: each is short, whatever comes after it.
        for (gap in listOf(4.0, 5.0, 6.0)) for (kmh in listOf(5.0, 10.0, 15.0, 20.0)) {
            val store = MemoryStore()
            val humps = List(3) { ProfileHump(900.0 + gap * it, "sinus", 0.10, 0.6) }
            Simulator(83).drive(store, DriveSpec(profileHumps = humps, bumpKmh = kmh, cruiseKmh = kmh, roadM = 1000.0))
            assertTrue(store.saved.none { it.shape == BumpShape.LONG_FLAT },
                "gap $gap m, $kmh km/h: ${store.events.filter { it.type == "new_bump" || it.type == "hit_repeat" }.map { it.note.substringAfter("hump=") }}")
        }
    }

    @Test fun inAPocketWithoutAStrongPairTheShapeIsUnknown() {
        val pocket = DrivingConfig().apply { placement = "pocket" }
        val store = MemoryStore()
        // A strong-pair gate no pair can reach: every judged hump in the pocket stays unknown.
        val cfg = EngineConfig().apply { shapePocketMinScore = 1.1 }
        // (Slowing from 40 to 15 km/h for it: the slowdown is the evidence that learns it in a pocket.)
        Simulator(84).drive(store, DriveSpec(profileHumps = listOf(ProfileHump(900.0, "sinus", 0.12, 1.0)), bumpKmh = 15.0, cruiseKmh = 40.0), cfg, drivingCfg = pocket)
        assertTrue(store.saved.isNotEmpty() && store.saved.all { it.shape == null }, "${store.saved.map { it.shape }}")
    }

    @Test fun aLowHump() {
        val (shape, store) = shapeOf(ProfileHump(900.0, "sinus", 0.03, 0.6))
        assertEquals(BumpShape.LOW, shape, "${store.events.filter { it.type == "new_bump" }.map { it.note }}")
    }

    @Test fun theShapeIsStoredAndObserved() {
        // Judged after the first pass, so the next hit's observation carries it.
        val sink = ListSink()
        val store = MemoryStore()
        val spec = DriveSpec(profileHumps = listOf(ProfileHump(900.0, "sinus", 0.12, 1.0)), bumpKmh = 15.0, cruiseKmh = 15.0, roadM = 1200.0)
        Simulator(74).drive(store, spec, tripId = 1, observationSink = sink)
        Simulator(75).drive(store, spec, tripId = 2, observationSink = sink)
        val hit = sink.got.single { it.kind == "known_hit" }
        assertEquals(BumpShape.SHORT_STEEP, hit.shape, "$hit")
        val b = Bump(1, 0.0, 0.0, 0.0, 1, 1, 0, 1, 0, 0, shape = BumpShape.LONG_FLAT)
        assertEquals(BumpShape.LONG_FLAT, b.copy().shape)
    }
}
