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
        // The driver slows to the bump's speed; both axles are felt and confirm the spot at once when it is strong.
        for (kmh in listOf(10.0, 20.0, 40.0)) {
            val store = MemoryStore()
            val r = Simulator(7).drive(store, DriveSpec(bumpsAt = listOf(800.0), bumpKmh = kmh, cruiseKmh = 50.0))
            val n = notes(store, "new_bump")
            assertEquals(1, r.newBumps, "$kmh km/h: ${r.rejected} $n")
            assertTrue(n.single().contains("axle=both"), "$kmh km/h: $n")
        }
        // At 70 km/h it is still a pair, but too fast for a speed bump.
        val store = MemoryStore()
        val r = Simulator(7).drive(store, DriveSpec(bumpsAt = listOf(800.0), bumpKmh = 70.0, cruiseKmh = 70.0))
        assertEquals(0, r.newBumps)
        assertTrue(notes(store, "rejected").any { it.startsWith("too_fast") && "axle=both" in it }, "${notes(store, "rejected")}")
    }

    @Test fun aLoneJoltAtASteadySpeedIsOnlyAMaybe() {
        // One knock, no rear axle, no slowdown: a maybe in a holder that never confirms, rejected in a pocket.
        val store = MemoryStore()
        val spec = DriveSpec(oneOffJoltsAt = listOf(900.0), cruiseKmh = 40.0)
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
        // A real bump while braking (both axles, pitch up then down), and the phone picked up just after the rear axle,
        // before the decision: the car's signature wins.
        val v = 15 / 3.6
        val store = MemoryStore()
        val r = Simulator(9).drive(store, DriveSpec(bumpsAt = listOf(800.0), holdsAt = listOf(800.0 + 2.6 + 0.7 * v to 4.0), cruiseKmh = 40.0))
        assertEquals(1, r.newBumps, "${r.rejected} ${notes(store, "rejected")}")
        // Without a gyroscope (no pitch order) the same bump stays the phone's.
        val g = Simulator(9).drive(MemoryStore(), DriveSpec(bumpsAt = listOf(800.0), holdsAt = listOf(800.0 + 2.6 + 0.7 * v to 4.0), cruiseKmh = 40.0, gyro = false))
        assertEquals(0, g.newBumps, "${g.rejected}")
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
