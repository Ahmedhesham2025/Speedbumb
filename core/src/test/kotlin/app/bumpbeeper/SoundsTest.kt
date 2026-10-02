package app.bumpbeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Warning sounds: first-pass tick, a sound per kind, groups of spots announced once, and their phrases. */
class SoundsTest {
    @Test fun firstPassTick() = Scenarios.firstPassTick()
    @Test fun warningSounds() = Scenarios.warningSounds()
    @Test fun groupOfBumps() = Scenarios.groupOfBumps()
    @Test fun mixedGroupNamesHarshPothole() = Scenarios.mixedGroupNamesHarshPothole()

    @Test fun harshThresholdDefaultIs5() {
        val cfg = EngineConfig()
        assertEquals(5.0, cfg.harshPotholeMs2, 0.0)
        fun pothole(peak: Double) = Bump(1, 0.0, 0.0, 0.0, 1, 1, 0, 1, 0, 0, kindScore = 1.0, kindVotes = 1, peakAvg = peak)
        assertTrue(pothole(5.0).isHarsh(cfg))
        assertFalse(pothole(4.9).isHarsh(cfg))
        cfg.harshPotholeMs2 = 6.0   // a user's own setting still wins
        assertFalse(pothole(5.5).isHarsh(cfg))
    }

    @Test fun groupPhrasesEnglish() {
        assertEquals("3 bumps ahead.", Phrases.cluster("en", 3, BumpKind.BUMP, null))
        assertEquals("4 potholes ahead.", Phrases.cluster("en", 4, BumpKind.POTHOLE, null))
        assertEquals("3 hazards ahead.", Phrases.cluster("en", 3, null, null))
        assertEquals("3 hazards ahead, harsh pothole on the right.", Phrases.cluster("en", 3, null, Side.RIGHT))
        assertEquals("5 potholes ahead, harsh pothole on the left.", Phrases.cluster("en", 5, BumpKind.POTHOLE, Side.LEFT))
        assertEquals("3 hazards ahead, one harsh pothole.", Phrases.cluster("en", 3, null, Side.UNKNOWN))
        // Unknown language falls back to English, like the pothole phrase.
        assertEquals("3 bumps ahead.", Phrases.cluster("fr", 3, BumpKind.BUMP, null))
        assertEquals(null, Phrases.cluster("en", 2, BumpKind.BUMP, null))   // fewer than 3: no group line
    }

    @Test fun groupPhrasesArabic() {
        assertEquals("3 مطبات قدام.", Phrases.cluster("ar", 3, BumpKind.BUMP, null))
        assertEquals("4 حفر قدام.", Phrases.cluster("ar", 4, BumpKind.POTHOLE, null))
        assertEquals("3 عقبات قدام، حفرة عنيفة على اليمين.", Phrases.cluster("ar", 3, null, Side.RIGHT))
        assertEquals("3 عقبات قدام، حفرة عنيفة على الشمال.", Phrases.cluster("ar", 3, null, Side.LEFT))
        // 11 and up take the singular in Arabic.
        assertEquals("12 مطب قدام.", Phrases.cluster("ar", 12, BumpKind.BUMP, null))
    }

    @Test fun potholePhraseUnchanged() {
        assertEquals("Pothole on the right. Keep left.", Phrases.pothole("en", Side.RIGHT))
        assertEquals("حفرة على اليمين. خليك شمال.", Phrases.pothole("ar", Side.RIGHT))
    }

    /** A listener that only knows [EngineListener.onBeep] (older code) still hears every warning. */
    @Test fun onWarningFallsBackToOnBeep() {
        var beeps = 0
        val l = object : EngineListener {
            override fun onBeep(b: Bump, distanceM: Double, speedKmh: Double) { beeps++ }
        }
        l.onWarning(Warning(Bump(1, 0.0, 0.0, 0.0, 1, 1, 0, 1, 0, 0), 90.0, 50.0, WarnSound.BUMP, null))
        assertEquals(1, beeps)
    }

    // ---------- groups, driven straight from GPS fixes (no simulator) ----------

    private fun at(p: Double) = Geo.move(30.0444, 31.2357, 90.0, p)
    private fun shared(id: Long, p: Double, kind: BumpKind, heading: Double = 90.0) =
        at(p).let { RemoteSpot(id, it[0], it[1], heading, kind, Side.RIGHT, 7.0, 3) }

    /** Shared bump, harsh pothole and unsure spot 60 m apart on an east-going road. */
    private val three = listOf(shared(1, 800.0, BumpKind.BUMP), shared(2, 860.0, BumpKind.POTHOLE), shared(3, 920.0, BumpKind.UNSURE))

    private class Run(spots: List<RemoteSpot>, val cfg: EngineConfig = EngineConfig(), val store: MemoryStore = MemoryStore()) {
        val warnings = ArrayList<Warning>()
        var onWarn: (Warning) -> Unit = {}
        val e = BumpEngine(cfg, store, object : EngineListener {
            override fun onWarning(w: Warning) { warnings.add(w); onWarn(w) }
        }, { 0L }, spotSource = ListSpotSource(spots))
        private var t = 0L

        /** Drive along the road from position [from] to [to] at 14 m/s (east if [to] is further, else west). */
        fun drive(from: Double, to: Double) {
            val east = to > from
            var p = from
            while (if (east) p <= to else p >= to) {
                val q = Geo.move(30.0444, 31.2357, 90.0, p)
                e.onFix(Fix(t, q[0], q[1], 14.0, if (east) 90.0 else 270.0, 5.0))
                p += if (east) 14.0 else -14.0
                t += 1000
            }
        }

        fun count(type: String) = store.events.count { it.type == type }
        fun describe() = "warnings ${warnings.map { "${it.spot.id}:${it.cluster?.count}" }} events " +
            store.events.map { "${it.type} ${it.bumpId} ${it.note}" }
    }

    @Test fun groupFromFixes() {
        val r = Run(three)
        r.drive(0.0, 1200.0)
        assertEquals(r.describe(), 1, r.warnings.size)
        assertEquals(3, r.warnings[0].cluster?.count)
        assertEquals(2, r.count("beep_grouped"))
    }

    /** No speech: no groups, every spot warns with its own sound. */
    @Test fun groupingOffEverySpotWarns() {
        val r = Run(three, EngineConfig().apply { groupWarnings = false })
        r.drive(0.0, 1200.0)
        assertEquals(r.describe(), 3, r.warnings.size)
        assertTrue(r.warnings.all { it.cluster == null })
        assertEquals(0, r.count("beep_grouped"))
    }

    /** The group line couldn't be spoken: [BumpEngine.ungroup] lets the silenced spots warn after all. */
    @Test fun ungroupAfterFailedSpeech() {
        val r = Run(three)
        r.onWarn = { if (it.cluster != null) r.e.ungroup() }
        r.drive(0.0, 1200.0)
        assertEquals(r.describe(), 3, r.warnings.size)
        assertEquals(3, r.warnings[0].cluster?.count)
        assertTrue(r.warnings.drop(1).all { it.cluster == null })
    }

    /** Turn round before the group, drive back and come again: the silenced spots warn again (grouping off now). */
    @Test fun uTurnClearsGroup() {
        val r = Run(three)
        r.drive(0.0, 740.0)
        assertEquals(r.describe(), 1, r.warnings.size)
        assertEquals(3, r.warnings[0].cluster?.count)
        r.drive(740.0, 200.0)
        r.cfg.clusterMinExtra = 99
        r.drive(200.0, 1200.0)
        assertEquals(r.describe(), listOf(-3L, -3L, -4L, -5L), r.warnings.map { it.spot.id })
        assertEquals(0, r.count("beep_grouped"))
    }

    /** Your own spot and its shared twin count once; a spot for the other direction doesn't count. */
    @Test fun ownSpotAndSharedTwinCountOnce() {
        val store = MemoryStore()
        val q = at(862.0)
        store.insertBump(Bump(0, q[0], q[1], 90.0, hits = 2, passes = 2, misses = 0, nPos = 2, firstSeen = 0, lastSeen = 0,
            kindScore = 1.0, kindVotes = 2, peakAvg = 7.0))
        val r = Run(three + shared(4, 890.0, BumpKind.BUMP, heading = 270.0), store = store)
        r.drive(0.0, 1200.0)
        assertEquals(r.describe(), 1, r.warnings.size)
        assertEquals(3, r.warnings[0].cluster?.count)
    }

    /** "Mute last warning" right after a group mutes the spot that warned, not the silenced ones behind it. */
    @Test fun muteAfterGroupMutesOnlyFirst() {
        val r = Run(three)
        r.drive(0.0, 720.0)
        assertEquals(r.describe(), 3, r.warnings.single().cluster?.count)
        val muted = r.e.muteBump(r.e.lastBeepedId)
        assertTrue(muted != null && muted.userMuted)
        assertEquals(1, r.store.saved.size)
        assertTrue(Geo.distance(muted!!.lat, muted.lon, three[0].lat, three[0].lon) < 1.0)
    }
}
