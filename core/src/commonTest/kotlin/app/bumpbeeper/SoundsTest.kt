package app.bumpbeeper

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/** Warning sounds: first-pass tick, a sound per severity band (soft for a "maybe"), groups announced once, phrases. */
class SoundsTest {
    @Test fun firstPassTick() = Scenarios.firstPassTick()
    @Test fun warningSounds() = Scenarios.warningSounds()
    @Test fun groupOfBumps() = Scenarios.groupOfBumps()
    @Test fun groupNamesStrongBump() = Scenarios.groupNamesStrongBump()

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
        assertEquals("3 bumps ahead.", Phrases.cluster("en", 3, anyStrong = false))
        assertEquals("4 bumps ahead, one strong.", Phrases.cluster("en", 4, anyStrong = true))
        // Unknown language falls back to English.
        assertEquals("3 bumps ahead.", Phrases.cluster("fr", 3, anyStrong = false))
        assertEquals(null, Phrases.cluster("en", 2, anyStrong = true))   // fewer than 3: no group line
    }

    @Test fun groupPhrasesArabic() {
        assertEquals("3 مطبات قدام.", Phrases.cluster("ar", 3, anyStrong = false))
        assertEquals("4 مطبات قدام، منهم واحد قوي.", Phrases.cluster("ar", 4, anyStrong = true))
        // 11 and up take the singular in Arabic.
        assertEquals("12 مطب قدام.", Phrases.cluster("ar", 12, anyStrong = false))
        assertEquals(null, Phrases.cluster("ar", 2, anyStrong = false))
    }

    @Test fun strongBumpPhrase() {
        assertEquals("Strong bump ahead.", Phrases.strongBump("en"))
        assertEquals("مطب قوي قدام.", Phrases.strongBump("ar"))
        assertEquals("Strong bump ahead.", Phrases.strongBump("fr"))
    }

    /** A listener that only knows [EngineListener.onBeep] (older code) still hears every warning. */
    @Test fun onWarningFallsBackToOnBeep() {
        var beeps = 0
        val l = object : EngineListener {
            override fun onBeep(b: Bump, distanceM: Double, speedKmh: Double) { beeps++ }
        }
        l.onWarning(Warning(Bump(1, 0.0, 0.0, 0.0, 1, 1, 0, 1, 0, 0), 90.0, 50.0, WarnSound.MODERATE, null))
        assertEquals(1, beeps)
    }

    // ---------- groups, driven straight from GPS fixes (no simulator) ----------

    private fun at(p: Double) = Geo.move(30.0444, 31.2357, 90.0, p)
    private fun shared(id: Long, p: Double, kind: BumpKind, heading: Double = 90.0, severity: Double = 7.0, devices: Int = 3) =
        at(p).let { RemoteSpot(id, it[0], it[1], heading, kind, Side.RIGHT, severity, devices) }

    /**
     * Confirmed spots (2+ phones) sound by their band: mild one beep, moderate two, strong the voice. A "maybe" (one
     * phone only, or a shared pothole from before v2) gets the soft beep whatever its jolt.
     */
    @Test fun soundPerBandAndConfidence() {
        val r = Run(listOf(
            shared(11, 300.0, BumpKind.BUMP, severity = 3.0), shared(12, 650.0, BumpKind.BUMP, severity = 4.2),
            shared(13, 1000.0, BumpKind.BUMP, severity = 7.0), shared(14, 1350.0, BumpKind.POTHOLE, severity = 7.0),
            shared(15, 1700.0, BumpKind.BUMP, severity = 7.0, devices = 1),
        ))
        r.drive(0.0, 2000.0)
        assertEquals(
            listOf(WarnSound.MILD, WarnSound.MODERATE, WarnSound.STRONG, WarnSound.SOFT, WarnSound.SOFT),
            r.warnings.map { it.sound }, r.describe(),
        )
        assertTrue(r.warnings.all { it.cluster == null })
        assertEquals(listOf("mild", "moderate", "strong", "soft", "soft"), r.store.events.filter { it.type == "beep" }.map { it.note.removePrefix("remote ") })
    }

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
        assertEquals(1, r.warnings.size, r.describe())
        assertEquals(3, r.warnings[0].cluster?.count)
        assertEquals(2, r.count("beep_grouped"))
    }

    /** No speech: no groups, every spot warns with its own sound. */
    @Test fun groupingOffEverySpotWarns() {
        val r = Run(three, EngineConfig().apply { groupWarnings = false })
        r.drive(0.0, 1200.0)
        assertEquals(3, r.warnings.size, r.describe())
        assertTrue(r.warnings.all { it.cluster == null })
        assertEquals(0, r.count("beep_grouped"))
    }

    /** The group line couldn't be spoken: [BumpEngine.ungroup] lets the silenced spots warn after all. */
    @Test fun ungroupAfterFailedSpeech() {
        val r = Run(three)
        r.onWarn = { if (it.cluster != null) r.e.ungroup() }
        r.drive(0.0, 1200.0)
        assertEquals(3, r.warnings.size, r.describe())
        assertEquals(3, r.warnings[0].cluster?.count)
        assertTrue(r.warnings.drop(1).all { it.cluster == null })
    }

    /** Turn round before the group, drive back and come again: the silenced spots warn again (grouping off now). */
    @Test fun uTurnClearsGroup() {
        val r = Run(three)
        r.drive(0.0, 740.0)
        assertEquals(1, r.warnings.size, r.describe())
        assertEquals(3, r.warnings[0].cluster?.count)
        r.drive(740.0, 200.0)
        r.cfg.clusterMinExtra = 99
        r.drive(200.0, 1200.0)
        assertEquals(listOf(-3L, -3L, -4L, -5L), r.warnings.map { it.spot.id }, r.describe())
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
        assertEquals(1, r.warnings.size, r.describe())
        assertEquals(3, r.warnings[0].cluster?.count)
    }

    /** "Mute last warning" right after a group mutes the spot that warned, not the silenced ones behind it. */
    @Test fun muteAfterGroupMutesOnlyFirst() {
        val r = Run(three)
        r.drive(0.0, 720.0)
        assertEquals(3, r.warnings.single().cluster?.count, r.describe())
        val muted = r.e.muteBump(r.e.lastBeepedId)
        assertTrue(muted != null && muted.userMuted)
        assertEquals(1, r.store.saved.size)
        assertTrue(Geo.distance(muted!!.lat, muted.lon, three[0].lat, three[0].lon) < 1.0)
    }
}
