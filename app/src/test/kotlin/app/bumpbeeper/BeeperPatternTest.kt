package app.bumpbeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The beeps each warning plays ([Beeper.patternFor]) and the approach blip. */
class BeeperPatternTest {
    @Test fun warningBeeps() {
        assertEquals(1, Beeper.patternFor(WarnSound.MILD).count)
        assertEquals(2, Beeper.patternFor(WarnSound.MODERATE).count)
        assertEquals(3, Beeper.patternFor(WarnSound.STRONG).count)
        val soft = Beeper.patternFor(WarnSound.SOFT)
        assertEquals(1, soft.count)
        assertTrue("the maybe beep stays quieter", soft.volume < Beeper.patternFor(WarnSound.MILD).volume)
        // The same tone for every confirmed band: only the count tells them apart.
        val strong = Beeper.patternFor(WarnSound.STRONG)
        assertEquals(Beeper.patternFor(WarnSound.MILD).freqsHz[0], strong.freqsHz[2], 0.0)
    }

    @Test fun strongVoiceAfterTheThreeBeeps() {
        val p = Beeper.patternFor(WarnSound.STRONG)
        val beepsMs = 250 + p.count * p.onMs + (p.count - 1) * p.gapMs + 30
        assertTrue("voice at ${Beeper.STRONG_VOICE_MS} ms, beeps end at $beepsMs ms", Beeper.STRONG_VOICE_MS >= beepsMs)
    }

    @Test fun approachBlipFitsTheShortestGap() {
        val a = Beeper.APPROACH
        assertEquals(1, a.count)
        assertTrue("shorter than a warning beep", a.onMs < Beeper.patternFor(WarnSound.MILD).onMs)
        assertTrue("fits between the fastest approach beeps", a.onMs < EngineConfig().approachEndMs)
    }
}
