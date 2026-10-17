package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Self-cleaning map: spots passed clean again and again become a "maybe", then are retired, until felt again. */
class SelfCleaningTest {
    private val cfg = EngineConfig()
    private val store = MemoryStore()
    private var trip = 1L

    private fun pass(spec: DriveSpec): TripResult = Simulator(100L + trip).drive(store, spec, tripId = trip++)
    private fun felt() = pass(DriveSpec(bumpsAt = listOf(700.0)))
    private fun clean() = pass(DriveSpec(silentBumpsAt = listOf(700.0)))
    private fun spot(): Bump = store.saved.single()

    /** Learned and confirmed: felt on two trips. */
    private fun confirmed() {
        felt(); felt()
        assertEquals(Confidence.FULL, spot().confidence(cfg), "felt twice")
        assertEquals(0, spot().missStreak)
    }

    @Test fun threeCleanPassesMakeAMaybeFiveRetire() {
        confirmed()
        repeat(2) { clean() }
        assertEquals(2, spot().missStreak)
        assertEquals(Confidence.FULL, spot().confidence(cfg), "2 clean passes: still confirmed")
        clean()
        assertEquals(Confidence.SOFT, spot().confidence(cfg), "3 clean passes in a row: a maybe")
        assertFalse(spot().isRetired(cfg))
        repeat(2) { clean() }
        assertEquals(5, spot().missStreak)
        assertTrue(spot().isRetired(cfg), "5 clean passes: retired")
        assertEquals(1, store.events.count { it.type == "retired" })
        val after = clean()
        assertTrue(after.warnings.isEmpty(), "a retired spot is silent")
    }

    @Test fun aFeltPassResetsTheCount() {
        confirmed()
        repeat(4) { clean() }
        assertEquals(Confidence.SOFT, spot().confidence(cfg))
        val r = felt()
        assertEquals(0, spot().missStreak)
        assertEquals(Confidence.FULL, spot().confidence(cfg), "felt again: confirmed again")
        assertTrue(r.knownHits >= 1)
        repeat(4) { clean() }
        assertFalse(spot().isRetired(cfg), "the count started again from 0")
    }

    /** Retired, then felt again: back on the map. */
    @Test fun aRetiredSpotFeltAgainComesBack() {
        confirmed()
        repeat(5) { clean() }
        assertTrue(spot().isRetired(cfg))
        felt()
        assertFalse(spot().isRetired(cfg))
        assertEquals(1, store.saved.size, "the same spot, not a new one")
    }

    @Test fun handledOrWrongDirectionPassesDontCount() {
        confirmed()
        repeat(3) { pass(DriveSpec(silentBumpsAt = listOf(700.0), handlingAt = 685.0)) }
        assertEquals(0, spot().missStreak, "phone in the hand over the spot: not counted")
        repeat(3) { pass(DriveSpec(silentBumpsAt = listOf(700.0), westbound = true)) }
        assertEquals(0, spot().missStreak, "the other direction: not counted")
        assertEquals(Confidence.FULL, spot().confidence(cfg))
    }

    /** Each clean pass of a spot of your own also tells the shared map (pass_clear). */
    @Test fun cleanPassesAreObserved() {
        val obs = ArrayList<Observation>()
        felt(); felt()
        repeat(3) {
            Simulator(200L + it).drive(store, DriveSpec(silentBumpsAt = listOf(700.0)), tripId = trip++,
                observationSink = object : ObservationSink { override fun record(o: Observation) { obs.add(o) } })
        }
        assertEquals(3, obs.count { it.kind == "pass_clear" })
    }

    /** An old pothole spot decays the same way. */
    @Test fun legacySpotsDecayToo() {
        confirmed()
        store.saved[0] = spot().copy().also { it.legacy = true }
        repeat(5) { clean() }
        assertTrue(spot().isRetired(cfg))
    }

    // ---------- review: passes that must not count as clean ----------

    /** A confirmed spot of your own at road position [p] (heading east), stored directly. */
    private fun stored(p: Double, store: MemoryStore = this.store): Bump {
        val q = Simulator(1).point(p, false)
        val b = Bump(0, q[0], q[1], 90.0, hits = 3, passes = 3, misses = 0, nPos = 3, firstSeen = 0, lastSeen = 0, sevIndex = 5.0)
        b.id = store.insertBump(b)
        return store.saved.last()
    }

    /** A parallel road 15 m to the side, same direction: its passes never count against the spot. */
    @Test fun aParallelRoadIsNotAPass() {
        confirmed()
        repeat(5) { pass(DriveSpec(sideOffsetM = 15.0)) }
        assertEquals(0, spot().missStreak)
        assertFalse(spot().isRetired(cfg))
        assertTrue(store.events.any { it.type == "miss" && "beside it" in it.note } || store.events.none { it.type == "miss" && it.tripId > 2 },
            "passes beside it are not clean")
    }

    /** A bump taken at 70 km/h every day (its jolt rejected as too fast) is not passed clean. */
    @Test fun aBumpTakenTooFastIsNotPassedClean() {
        stored(700.0)
        repeat(5) { pass(DriveSpec(bumpsAt = listOf(700.0), bumpKmh = 70.0, cruiseKmh = 70.0)) }
        assertTrue(store.events.any { it.type == "rejected" }, "the fast jolts are rejected")
        assertEquals(0, spot().missStreak, "felt, just not taken: ${store.events.filter { it.type == "miss" }.map { it.note }}")
        assertFalse(spot().isRetired(cfg))
    }

    /** Two humps 8 m apart at 30 km/h: the second is swallowed by the first one's refractory, but never retires. */
    @Test fun aHumpInARowIsNotRetired() {
        val first = stored(700.0); val second = stored(708.0)
        repeat(5) { pass(DriveSpec(bumpsAt = listOf(700.0, 708.0), bumpKmh = 30.0)) }
        for (b in store.saved) assertFalse(b.isRetired(cfg), "spot ${b.id} (${first.id}/${second.id}) streak ${b.missStreak}")
    }

    /** Your retired spot doesn't hide the shared map's confirmed one at the same place. */
    @Test fun aRetiredSpotDoesNotHideItsSharedTwin() {
        val own = stored(700.0).also { it.missStreak = cfg.retireAfterMisses }
        store.updateBump(own)
        val q = Simulator(1).point(700.0, false)
        @Suppress("DEPRECATION")
        val shared = RemoteSpot(7, q[0], q[1], 90.0, BumpKind.BUMP, Side.RIGHT, 7.0, 3)
        val r = Simulator(9).drive(store, DriveSpec(silentBumpsAt = listOf(700.0)), tripId = 50, spotSource = ListSpotSource(listOf(shared)))
        assertEquals(1, r.warnings.size, "the shared spot warns")
        assertTrue(r.warnings[0].spot.id < 0, "it is the shared one: ${r.warnings[0].spot.id}")
    }

    /** Only ever felt as a weak, down-first jolt, twice: retired. A real hit afterwards brings it back. */
    @Test fun weakDownFirstOnlyIsRetired() {
        val b = Bump(1, 0.0, 0.0, 90.0, 1, 1, 0, 1, 0, 0, weakDownHits = 1)
        assertFalse(b.isRetired(cfg))
        b.weakDownHits = 2
        assertTrue(b.isRetired(cfg))
        b.weakDownHits = -1
        assertFalse(b.isRetired(cfg), "felt for real: never retired this way")
        // The simulator's dips come out "unclear" (never weak down-first) at 25-33 km/h, in a holder or not, so the
        // engine path is covered by the rule above; real weak dips are counted in registerHit's weak branch.
    }
}
