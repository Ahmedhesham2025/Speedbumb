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
}
