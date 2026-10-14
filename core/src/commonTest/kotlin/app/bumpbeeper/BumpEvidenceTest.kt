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
        // A real bump after braking to 20 km/h (both axles, pitch up then down), and the phone lifted (without a shake)
        // 0.25 s after the rear axle, seen before the decision: the car's signature wins.
        val v = 20 / 3.6
        fun spec(holdAt: Double) =
            DriveSpec(bumpsAt = listOf(800.0), bumpKmh = 20.0, holdsAt = listOf(holdAt to 4.0), holdShake = 0.0, cruiseKmh = 40.0)
        val diag = ArrayList<String>()
        for (seed in 9L..12L) for (off in listOf(0.2, 0.3, 0.5)) {
            val st = MemoryStore()
            Simulator(seed).drive(st, spec(800.0 + 2.6 + off * v))
            diag.add("$seed/$off: " + st.events.filter { it.type == "new_bump" || it.type == "rejected" }.map { it.type + " " + it.note })
            val st2 = MemoryStore()
            Simulator(seed).drive(st2, DriveSpec(bumpsAt = listOf(800.0), bumpKmh = 20.0, cruiseKmh = 40.0))
            diag.add("$seed none: " + st2.events.filter { it.type == "new_bump" || it.type == "rejected" }.map { it.type + " " + it.note })
        }
        println("DIAG " + diag.joinToString("
DIAG "))
        val store = MemoryStore()
        val r = Simulator(9).drive(store, spec(800.0 + 2.6 + 0.25 * v))
        val learned = notes(store, "new_bump")
        assertEquals(1, r.newBumps, "${r.rejected} ${notes(store, "rejected")}")
        assertTrue(learned.single().contains("axle=both") && learned.single().contains("over_handled"), "$learned")
        // Picked up a second before the front axle: the same pair stays the phone's.
        val early = Simulator(9).drive(MemoryStore(), spec(800.0 - 1.0 * v))
        assertEquals(0, early.newBumps, "${early.rejected}")
        assertTrue("handled" in early.rejected, "${early.rejected}")
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
