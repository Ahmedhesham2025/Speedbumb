package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory stand-in for the app's OSM cache table: keeps the clean passes and mutes like the app does. */
class ListOsmSource(var bumps: List<OsmBump>) : OsmSource {
    override fun osmBumpsNear(lat: Double, lon: Double, radiusM: Double): List<OsmBump> =
        bumps.filter { Geo.distance(lat, lon, it.lat, it.lon) <= radiusM }
    override fun osmCleanPass(osmId: String) {
        bumps = bumps.map { if (it.osmId == osmId) it.copy(cleanPasses = it.cleanPasses + 1) else it }
    }
    override fun osmMuted(osmId: String) {
        bumps = bumps.map { if (it.osmId == osmId) it.copy(cleanPasses = 99) else it }
    }
}

/** OpenStreetMap bumps as a prior ([EngineConfig.osmWarnings]): evidence for a jolt, a soft "maybe" where we know nothing. */
class OsmPriorTest {
    private fun osmAt(sim: Simulator, p: Double, id: String = "n1", westbound: Boolean = false): OsmBump {
        val q = sim.point(p, westbound)
        return OsmBump(id, q[0], q[1], "hump")
    }

    private fun osmBeeps(store: MemoryStore) = store.events.filter { it.type == "beep" && it.note.startsWith("osm ") }

    @Test fun anUnknownOsmBumpWarnsSoftlyInBothDirections() {
        val sim = Simulator(81)
        val store = MemoryStore()
        val src = ListOsmSource(listOf(osmAt(sim, 1000.0)))
        val east = sim.drive(store, DriveSpec(), tripId = 1, osmSource = src)
        assertEquals(listOf(WarnSound.SOFT), east.warnings.map { it.sound }, "${store.events.map { it.type + " " + it.note }}")
        assertTrue(BumpEngine.isOsmKey(east.warnings.single().spot.id))
        assertEquals("osm n1 soft", osmBeeps(store).single().note)
        assertEquals(-1L, osmBeeps(store).single().bumpId)
        // No direction in OSM: the other way warns too (the westbound lane is 10 m away, within the match).
        val west = sim.drive(store, DriveSpec(westbound = true), tripId = 2, osmSource = src)
        assertEquals(listOf(WarnSound.SOFT), west.warnings.map { it.sound })
        assertTrue(store.saved.isEmpty(), "an OSM bump is never stored as a spot")
    }

    @Test fun aSpotOfYoursOrASharedOneWithin20mReplacesIt() {
        val sim = Simulator(82)
        val store = MemoryStore()
        sim.drive(store, DriveSpec(bumpsAt = listOf(1000.0)), tripId = 1)   // learned: your own spot at 1000 m
        val src = ListOsmSource(listOf(osmAt(sim, 1012.0, "n1"), osmAt(sim, 1600.0, "n2")))
        val shared = ListSpotSource(listOf(sim.point(1610.0, false).let {
            RemoteSpot(7, it[0], it[1], 270.0, BumpKind.BUMP, Side.UNKNOWN, 5.0, 2)   // the other way: any direction matches
        }))
        val r = sim.drive(store, DriveSpec(bumpsAt = listOf(1000.0)), tripId = 2, spotSource = shared, osmSource = src)
        assertEquals(1, r.warnings.size, "${store.events.map { it.type + " " + it.note }}")
        assertTrue(!BumpEngine.isOsmKey(r.warnings.single().spot.id))
        assertTrue(osmBeeps(store).isEmpty())
    }

    @Test fun threeCleanPassesQuietIt() {
        val sim = Simulator(83)
        val store = MemoryStore()
        val src = ListOsmSource(listOf(osmAt(sim, 1000.0)))
        for (trip in 1L..3L) {
            val r = sim.drive(store, DriveSpec(), tripId = trip, osmSource = src)
            assertEquals(1, r.warnings.size, "trip $trip")
            assertEquals(trip.toInt(), src.bumps.single().cleanPasses, "trip $trip")
        }
        assertEquals(3, store.events.count { it.type == "osm_clear" })
        val r = sim.drive(store, DriveSpec(), tripId = 4, osmSource = src)
        assertTrue(r.warnings.isEmpty(), "quiet after 3 clean passes")
    }

    @Test fun passesThatSayNothingAreNotClean() {
        val sim = Simulator(84)
        val src = ListOsmSource(listOf(osmAt(sim, 1000.0)))
        // Crawled over (too slow to feel anything), felt, or with the phone in a hand: none counts.
        sim.drive(MemoryStore(), DriveSpec(crawlAt = listOf(1000.0)), tripId = 1, osmSource = src)
        sim.drive(MemoryStore(), DriveSpec(oneOffJoltsAt = listOf(1000.0)), tripId = 2, osmSource = src)
        sim.drive(MemoryStore(), DriveSpec(holdsAt = listOf(990.0 to 6.0)), tripId = 3, osmSource = src)
        assertEquals(0, src.bumps.single().cleanPasses)
    }

    @Test fun theSettingOffSilencesIt() {
        val sim = Simulator(85)
        val src = ListOsmSource(listOf(osmAt(sim, 1000.0)))
        val off = EngineConfig().apply { osmWarnings = false }
        val store = MemoryStore()
        val r = sim.drive(store, DriveSpec(), cfg = off, tripId = 1, osmSource = src)
        assertTrue(r.warnings.isEmpty())
        assertEquals(0, src.bumps.single().cleanPasses, "not warned, not counted")
    }

    @Test fun anOsmBumpNeverConfirmsASpot() {
        // Two strong knocks with only a weak axle reading, no slowdown, right on an OSM node: still a "maybe".
        val store = MemoryStore()
        val sim = Simulator(3)
        val src = ListOsmSource(listOf(osmAt(sim, 900.0)))
        val spec = DriveSpec(oneOffJoltsAt = listOf(900.0), cruiseKmh = 40.0)
        sim.drive(store, spec, tripId = 1, osmSource = src)
        Simulator(4).drive(store, spec, tripId = 2, osmSource = src)
        val b = store.saved.single()
        assertEquals(Confidence.SOFT, b.confidence(EngineConfig()), "${store.events.map { it.type + " " + it.note }}")
        assertEquals(0, b.axleHits)
        assertTrue(store.events.any { " osm" in it.note }, "the OSM node was seen")
    }

    @Test fun inAPocketAnOsmBumpLetsAJoltBeAMaybe() {
        // Without OSM a lone knock in a pocket is rejected (BumpEvidenceTest); next to an OSM node it is at most a maybe.
        val sim = Simulator(3)
        val store = MemoryStore()
        val r = sim.drive(store, DriveSpec(oneOffJoltsAt = listOf(900.0), cruiseKmh = 40.0),
            drivingCfg = DrivingConfig().apply { placement = "pocket" }, osmSource = ListOsmSource(listOf(osmAt(sim, 900.0))))
        assertTrue("no_evidence" !in r.rejected, "${r.rejected}")
        store.saved.forEach { assertEquals(Confidence.SOFT, it.confidence(EngineConfig())) }
    }

    @Test fun aRoad13mAsideNeverQuietsIt() {
        // The same clean-pass rule as your own spots: passing 13 m beside it says nothing about it.
        val sim = Simulator(87)
        val p = sim.point(1000.0, false)
        val aside = Geo.move(p[0], p[1], 0.0, 13.0)
        val src = ListOsmSource(listOf(OsmBump("n1", aside[0], aside[1], "hump")))
        for (trip in 1L..4L) sim.drive(MemoryStore(), DriveSpec(), tripId = trip, osmSource = src)
        assertEquals(0, src.bumps.single().cleanPasses)
    }

    @Test fun muteLastWarningQuietsItForGood() {
        val sim = Simulator(86)
        val src = ListOsmSource(listOf(osmAt(sim, 1000.0)))
        val r = sim.drive(MemoryStore(), DriveSpec(), tripId = 1, osmSource = src, muteEveryBeep = true)
        assertEquals(1, r.warnings.size)
        assertTrue(src.bumps.single().cleanPasses >= EngineConfig().osmQuietAfterPasses)
        assertTrue(sim.drive(MemoryStore(), DriveSpec(), tripId = 2, osmSource = src).warnings.isEmpty())
    }
}
