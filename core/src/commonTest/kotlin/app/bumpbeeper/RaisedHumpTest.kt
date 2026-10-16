package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E4a: Egyptian raised humps go up first; dips go down first; the crossing's V speed profile confirms a hump. */
class RaisedHumpTest {
    private fun notes(store: MemoryStore, type: String) = store.events.filter { it.type == type }.map { it.note }

    @Test fun aRaisedHumpGoesUpFirst() {
        val store = MemoryStore()
        val r = Simulator(51).drive(store, DriveSpec(bumpsAt = listOf(900.0), bumpKmh = 15.0, cruiseKmh = 40.0))
        assertEquals(1, r.newBumps, "${r.rejected}")
        assertTrue(notes(store, "new_bump").single().contains("polarity=up"), "${notes(store, "new_bump")}")
    }

    @Test fun aDipGoesDownFirstAndIsNoBump() {
        val store = MemoryStore()
        // (A slow zone first, so the car's forward direction is known: the frame is then trusted.)
        val r = Simulator(52).drive(store, DriveSpec(dipsAt = listOf(900.0), slowZonesAt = listOf(400.0), cruiseKmh = 40.0))
        assertEquals(0, r.newBumps, "${r.rejected} ${notes(store, "rejected")} ${notes(store, "new_bump")}")
        assertTrue(notes(store, "rejected").any { it.startsWith("down_first") && "polarity=down" in it }, "${notes(store, "rejected")}")
    }

    @Test fun inAPocketThePolarityIsUnclear() {
        // The phone turns in a pocket: its frame can't be trusted, so neither up nor down counts.
        val pocket = DrivingConfig().apply { placement = "pocket" }
        val store = MemoryStore()
        Simulator(53).drive(store, DriveSpec(bumpsAt = listOf(900.0), dipsAt = listOf(1400.0), cruiseKmh = 40.0), drivingCfg = pocket)
        val judged = notes(store, "new_bump") + notes(store, "rejected").filter { "polarity=" in it }
        assertTrue(judged.isNotEmpty() && judged.all { "polarity=unclear" in it }, "$judged")
        assertTrue(notes(store, "rejected").none { it.startsWith("down_first") }, "${notes(store, "rejected")}")
    }

    @Test fun aLoosePhoneHasNoPolarity() {
        // In a cup holder the phone rests loose: its frame isn't trusted, so the same dip is no "down_first".
        val loose = DrivingConfig().apply { placement = "cupholder" }
        val store = MemoryStore()
        Simulator(52).drive(store, DriveSpec(dipsAt = listOf(900.0), slowZonesAt = listOf(400.0), cruiseKmh = 40.0), drivingCfg = loose)
        val judged = notes(store, "new_bump") + notes(store, "rejected")
        assertTrue(judged.none { it.startsWith("down_first") } && judged.filter { "polarity=" in it }.all { "polarity=unclear" in it }, "$judged")
    }

    @Test fun brakingPitchIsNotTheJolts() {
        // The car pitches nose-down while braking (0.05 rad/s at 3.5 m/s², over 10 x the threshold in 0.25 s).
        // A hump crossed while braking still goes UP, and a drop felt with no nose-dip of its own is never DOWN.
        val store = MemoryStore()
        val r = Simulator(55).drive(store, DriveSpec(bumpsAt = listOf(900.0), brakePitchRads = 0.05, cruiseKmh = 40.0))
        assertEquals(1, r.newBumps, "${r.rejected}")
        assertTrue(notes(store, "new_bump").single().contains("polarity=up"), "${notes(store, "new_bump")}")
        val drop = MemoryStore()
        Simulator(56).drive(drop, DriveSpec(dipsAt = listOf(900.0), dipPitch = 0.0, slowZonesAt = listOf(400.0, 900.0), brakePitchRads = 0.05, cruiseKmh = 40.0))
        val judged = notes(drop, "new_bump") + notes(drop, "rejected")
        assertTrue(judged.none { it.startsWith("down_first") }, "$judged")
    }

    @Test fun aStartFromStandstillIsNoV() {
        // Stopped, then off over a ramp: slow, then faster, but no approach: the hump is not confirmed by a V.
        val store = MemoryStore()
        Simulator(57).drive(store, DriveSpec(stopsAt = listOf(800.0 to 10.0), bumpsAt = listOf(806.0), bumpKmh = 8.0, cruiseKmh = 40.0))
        assertTrue(notes(store, "cross_v").isEmpty(), "${store.events.filter { it.type == "cross_v" || it.type == "new_bump" }.map { it.note }}")
        assertTrue(notes(store, "new_bump").all { "profile=none" in it }, "${notes(store, "new_bump")}")
    }

    @Test fun anEgyptianCrossingIsAV() {
        // 40 km/h, down to 8 for the hump, back up past 35: the V and the hump going up first confirm it at once.
        val store = MemoryStore()
        val r = Simulator(54).drive(store, DriveSpec(bumpsAt = listOf(900.0), bumpKmh = 8.0, cruiseKmh = 40.0))
        assertEquals(1, r.newBumps, "${r.rejected}")
        val learned = notes(store, "new_bump").single()
        assertTrue("profile=slowdown" in learned && ("polarity=up" in learned || "axle=both" in learned), learned)
        assertEquals(1, notes(store, "cross_v").size, "${store.events.map { it.type }}")
        val b = store.saved.single()
        assertTrue(b.axleHits >= 2, "axle hits ${b.axleHits}")
        assertEquals(Confidence.FULL, b.confidence(EngineConfig()))
        // At a steady 35 km/h there is no V: the same hump is only a maybe after one hit.
        val steady = MemoryStore()
        Simulator(54).drive(steady, DriveSpec(oneOffJoltsAt = listOf(900.0), cruiseKmh = 35.0))
        assertTrue(notes(steady, "cross_v").isEmpty() && notes(steady, "new_bump").all { "profile=none" in it }, "${notes(steady, "new_bump")}")
    }
}
