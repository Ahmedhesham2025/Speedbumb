package app.bumpbeeper.ui

import app.bumpbeeper.Prefs
import app.bumpbeeper.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live speed-limit sign's text, the speed colour, and when the switch may be turned on. */
class LiveLimitTextTest {

    @Test fun signShowsFreshLimit() {
        assertEquals("60", LiveLimitText.signText(60, 0L))
        assertEquals("120", LiveLimitText.signText(120, LiveLimitText.STALE_MS))
    }

    @Test fun signUnknownWhenMissingOrStale() {
        assertEquals(LiveLimitText.UNKNOWN, LiveLimitText.signText(null, 1_000L))
        assertEquals(LiveLimitText.UNKNOWN, LiveLimitText.signText(60, -1L))          // no age = no answer
        assertEquals(LiveLimitText.UNKNOWN, LiveLimitText.signText(60, LiveLimitText.STALE_MS + 1))
        assertEquals(LiveLimitText.UNKNOWN, LiveLimitText.signText(0, 0L))
        assertEquals("– –", LiveLimitText.UNKNOWN)
    }

    @Test fun threeDigitsGetSmallerText() {
        assertTrue(LiveLimitText.textScale("120") < LiveLimitText.textScale("60"))
        assertTrue(LiveLimitText.textScale(LiveLimitText.UNKNOWN) < LiveLimitText.textScale("60"))
    }

    @Test fun speedColourFollowsOverLimit() {
        assertEquals(Ui.TEXT, LiveLimitText.speedColor(0))
        assertEquals(Ui.ORANGE, LiveLimitText.speedColor(1))
        assertEquals(Ui.RED, LiveLimitText.speedColor(2))
        assertEquals(Ui.TEXT, LiveLimitText.speedColor(-1))
    }

    @Test fun signNeedsRecordingSwitchAndConsent() {
        assertTrue(LiveLimitText.showSign(recording = true, on = true, consentVersion = 1))
        assertTrue(LiveLimitText.showSign(recording = true, on = true, consentVersion = 2))
        assertFalse(LiveLimitText.showSign(recording = false, on = true, consentVersion = 1))
        assertFalse(LiveLimitText.showSign(recording = true, on = false, consentVersion = 1))
        assertFalse(LiveLimitText.showSign(recording = true, on = true, consentVersion = 0))
    }

    @Test fun sharedMapChoiceComesBeforeConsent() {
        assertEquals(LiveLimitText.TurnOn.NEED_MAP, LiveLimitText.turnOnStep(Prefs.SYNC_UNSET))
        assertEquals(LiveLimitText.TurnOn.ASK_CONSENT, LiveLimitText.turnOnStep(Prefs.SYNC_RECEIVE))
        assertEquals(LiveLimitText.TurnOn.ASK_CONSENT, LiveLimitText.turnOnStep(Prefs.SYNC_SHARE))
    }

    @Test fun marginFallsBackToTen() {
        for (m in listOf(5, 10, 20)) assertEquals(m, LiveLimitText.margin(m))
        assertEquals(10, LiveLimitText.margin(15))
        assertEquals(10, LiveLimitText.margin(0))
        assertEquals(1, LiveLimitText.CONSENT_VERSION)
    }
}
