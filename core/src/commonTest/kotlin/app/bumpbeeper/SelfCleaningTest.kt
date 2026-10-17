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
}
