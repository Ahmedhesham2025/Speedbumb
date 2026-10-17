package app.bumpbeeper

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E2 part 2: a bump needs the car's own signature, both axles or a slowdown ([EngineConfig.axle]). */
class BumpEvidenceTest {
    private val pocket = DrivingConfig().apply { placement = "pocket" }

    private fun notes(store: MemoryStore, type: String) = store.events.filter { it.type == type }.map { it.note }

    @Test fun bothAxlesAtEverySpeed() {
        // The driver slows to the bump's speed; both axles are felt. (The simulator's hit lasts 0.2 s, 2.2 m at 40 km/h:
        // there its two axles merge, so 40 and 70 km/h pairs are AxleSignatureTest's; here 40 km/h is learned from the
        // slowdown and never "no axle", and 70 km/h is too fast for a speed bump whatever its axles.)
        for (kmh in listOf(10.0, 15.0, 20.0, 40.0)) {
            val store = MemoryStore()
            val r = Simulator(7).drive(store, DriveSpec(bumpsAt = listOf(800.0), bumpKmh = kmh, cruiseKmh = 50.0))
            val n = notes(store, "new_bump")
            assertEquals(1, r.newBumps, "$kmh km/h: ${r.rejected} $n")
            assertTrue(n.single().contains(if (kmh <= 20.0) "axle=both" else "axle="), "$kmh km/h: $n")
            assertTrue(!n.single().contains("axle=one"), "$kmh km/h: $n")
        }
        val store = MemoryStore()
        val r = Simulator(7).drive(store, DriveSpec(bumpsAt = listOf(800.0), bumpKmh = 70.0, cruiseKmh = 70.0))
        assertEquals(0, r.newBumps)
        assertTrue(notes(store, "rejected").any { it.startsWith("too_fast") && "axle=" in it }, "${notes(store, "rejected")}")
    }

    @Test fun aLoneJoltAtASteadySpeedIsOnlyAMaybe() {
        // One knock, no rear axle, no slowdown: a maybe in a holder that never confirms, rejected in a pocket.
        // (At 30 km/h: above 35 such a jolt is no new spot at all, see aLoneFastJoltIsNoNewSpot.)
        val store = MemoryStore()
        val spec = DriveSpec(oneOffJoltsAt = listOf(900.0), cruiseKmh = 30.0)
        val t1 = Simulator(3).drive(store, spec, tripId = 1)
        assertEquals(1, t1.newBumps, "${t1.rejected}")
        val t2 = Simulator(4).drive(store, spec, tripId = 2)
        assertEquals(0, t2.knownHits)
        assertEquals(1, notes(store, "hit_weak").size, "${store.events.map { it.type }}")
        val b = store.saved.single()
        assertEquals(1, b.hits)
        assertEquals(Confidence.SOFT, b.confidence(EngineConfig()))
        val p = Simulator(3).drive(MemoryStore(), spec, drivingCfg = pocket)
        assertEquals(0, p.newBumps)
        assertTrue(p.rejected.last() in listOf("no_evidence", "no_axle"), "${p.rejected}")
    }

    @Test fun aLoneFastJoltIsNoNewSpot() {
        // E5: above 35 km/h a new spot needs both axles or a GPS slowdown; a known spot is still hit.
        val spec = DriveSpec(oneOffJoltsAt = listOf(900.0), cruiseKmh = 40.0)
        val t = Simulator(3).drive(MemoryStore(), spec)
        assertEquals(0, t.newBumps, "${t.rejected}")
        assertEquals(listOf("fast_unconfirmed"), t.rejected)
        val store = MemoryStore()
        Simulator(3).drive(store, DriveSpec(oneOffJoltsAt = listOf(900.0), cruiseKmh = 30.0), tripId = 1)
        assertEquals(1, store.saved.size)
        Simulator(4).drive(store, spec, tripId = 2)
        assertEquals(1, notes(store, "hit_weak").size, "known spot still felt at 40 km/h: ${store.events.map { it.type }}")
    }

    @Test fun aSlowCrossingInAPocketIsEvidence() {
        // E5: in a pocket, a jolt at 20 km/h or slower with no axle pair and no slowdown is a hit, not no_evidence.
        val p = Simulator(3).drive(MemoryStore(), DriveSpec(oneOffJoltsAt = listOf(900.0), cruiseKmh = 18.0), drivingCfg = pocket)
        assertEquals(1, p.newBumps, "${p.rejected}")
    }

    @Test fun aSteadyCrawlInAPocketIsABump() {
        // 5-7 km/h all the way: the rear axle comes after the 1.6 s decision and there is no slowdown. The axle check
        // could not look, which is no evidence against it: learned, and confirmed on the next pass.
        for (kmh in listOf(5.0, 6.0, 7.0)) {
            val store = MemoryStore()
            val spec = DriveSpec(bumpsAt = listOf(300.0), bumpKmh = kmh, cruiseKmh = kmh, roadM = 500.0)
            val t1 = Simulator(41).drive(store, spec, tripId = 1, drivingCfg = pocket)
            assertEquals(1, t1.newBumps, "$kmh km/h: ${t1.rejected} ${notes(store, "rejected")}")
            val t2 = Simulator(42).drive(store, spec, tripId = 2, drivingCfg = pocket)
            assertEquals(1, t2.knownHits, "$kmh km/h: ${t2.rejected} ${notes(store, "hit_weak")}")
            assertEquals(Confidence.FULL, store.saved.single().confidence(EngineConfig()), "$kmh km/h")
        }
    }

    @Test fun aHitStillRingingWhereTheRearWouldBeIsAnOrdinaryHit() {
        // The axle check can't see a rear through a hit that is still ringing ("ringing", here forced by a zero decay
        // limit): no evidence against it, so a lone knock at a steady 25 km/h is learned even in a pocket.
        val ringing = EngineConfig().apply { axle.decayFraction = 0.0 }
        val spec = DriveSpec(oneOffJoltsAt = listOf(600.0), cruiseKmh = 25.0, roadM = 900.0)
        val store = MemoryStore()
        val r = Simulator(3).drive(store, spec, ringing, drivingCfg = pocket)
        assertEquals(1, r.newBumps, "${r.rejected} ${notes(store, "rejected")}")
        assertTrue(notes(store, "new_bump").single().contains("ringing"), "${notes(store, "new_bump")}")
        // With the axle check able to look, the same lone knock is no bump in a pocket.
        val plain = Simulator(3).drive(MemoryStore(), spec, drivingCfg = pocket)
        assertEquals(0, plain.newBumps, "${plain.rejected}")
    }

    @Test fun handlingKnocksAWheelbaseApartAreNeverABump() {
        // Braking for a bump that isn't there, the phone picked up: its knocks come a wheelbase apart. Still the phone's.
        val v = 15 / 3.6
        val r = Simulator(5).drive(
            MemoryStore(),
            DriveSpec(
                silentBumpsAt = listOf(800.0), holdsAt = listOf(795.0 to 4.0),
                oneOffJoltsAt = listOf(800.0, 800.0 + 2.6), cruiseKmh = 40.0,
            ),
        )
        assertEquals(0, r.newBumps, "${r.rejected}")
        assertTrue(r.rejected.isNotEmpty() && r.rejected.all { it == "handled" }, "${r.rejected} ($v m/s)")
    }

    @Test fun aClearPairOverridesALaterHandling() {
        // A real bump after braking from 50 to 20 km/h (both axles, nose up then tail up), and the phone lifted without
        // a shake 0.35 s after the rear axle (clear of its pitch), seen before the decision: the car's signature wins.
        // Every override gate is the default; the decision waits 1.6 s (the longest axle wait) so the lift is seen.
        val cfg = EngineConfig().apply { decideAfterMs = 1600L }
        val v = 20 / 3.6
        fun spec(holdAt: Double) =
            DriveSpec(bumpsAt = listOf(1500.0), bumpKmh = 20.0, cruiseKmh = 50.0, holdsAt = listOf(holdAt to 4.0), holdShake = 0.0)
        val store = MemoryStore()
        val r = Simulator(9).drive(store, spec(1500.0 + 2.6 + 0.35 * v), cfg)
        val learned = notes(store, "new_bump")
        assertEquals(1, r.newBumps, "${r.rejected} ${notes(store, "rejected")}")
        assertTrue(learned.single().contains("axle=both") && learned.single().contains("over_handled"), "$learned")
        // Picked up a second before the front axle: the same pair stays the phone's.
        val early = MemoryStore()
        val e = Simulator(9).drive(early, spec(1500.0 - 1.0 * v), cfg)
        assertEquals(0, e.newBumps, "${e.rejected}")
        assertTrue(notes(early, "rejected").any { it.startsWith("handled") }, "${notes(early, "rejected")}")
        // Picked up between the two axles: the phone turned during the pair, never.
        val between = MemoryStore()
        Simulator(9).drive(between, spec(1500.0 + 1.3), cfg)
        assertTrue(notes(between, "new_bump").none { "over_handled" in it }, "${notes(between, "new_bump")} ${notes(between, "rejected")}")
        // Once the car has 3 axle hits, its wheelbase must agree: a car learned at 3.0 m refuses this 2.6 m pair.
        val other = MemoryStore()
        Simulator(9).drive(other, spec(1500.0 + 2.6 + 0.35 * v), cfg, vehicle = VehicleProfile().apply { restore(3.0, 3) })
        assertTrue(notes(other, "new_bump").none { "over_handled" in it }, "${notes(other, "new_bump")} ${notes(other, "rejected")}")
        // No gyroscope: never.
        val g = MemoryStore()
        val n = Simulator(9).drive(g, spec(1500.0 + 2.6 + 0.35 * v).let {
            DriveSpec(bumpsAt = it.bumpsAt, bumpKmh = 20.0, cruiseKmh = 50.0, holdsAt = it.holdsAt, holdShake = 0.0, gyro = false)
        }, cfg)
        assertTrue(notes(g, "new_bump").none { "over_handled" in it }, "${notes(g, "new_bump")} ${notes(g, "rejected")} ${n.rejected}")
    }

    @Test fun theWheelbaseIsLearned() {
        // A 3.0 m car over bumps in slow zones (a steady 25 km/h, so GPS speed is right): within 0.2 m.
        val car = VehicleProfile()
        val store = MemoryStore()
        val spec = DriveSpec(
            bumpsAt = listOf(400.0, 900.0, 1400.0), slowZonesAt = listOf(400.0, 900.0, 1400.0), bumpKmh = 25.0,
            wheelbaseM = 3.0, cruiseKmh = 50.0,
        )
        for (trip in 1L..4L) Simulator(20 + trip).drive(store, spec, tripId = trip, vehicle = car)
        assertTrue(car.hits >= 6, "axle hits learned from: ${car.hits}")
        assertTrue(abs(car.wheelbaseM - 3.0) <= 0.2, "learned ${car.wheelbaseM} m from ${car.hits} hits")
    }
}
