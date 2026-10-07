package app.bumpbeeper.ui

import app.bumpbeeper.Prefs
import app.bumpbeeper.research.ResearchConsent
import app.bumpbeeper.research.ResearchUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** When the research question appears, and the words Settings uses for research recordings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchChoiceTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val now = 1_800_000_000_000L
    private val hour = 3600_000L

    @Before fun setUp() {
        Prefs.sp(ctx).edit().clear().commit()
    }

    private fun status(files: Int = 0, bytes: Long = 0, last: Long = 0, until: Long = 0, why: String = "") =
        ResearchChoice.status(ctx, ResearchUploader.Status(files, bytes, last, until, why, emptyList()), now)

    @Test fun askedOnceAfterTheMapQuestionNeverWhileRecording() {
        val v = ResearchConsent.RESEARCH_CONSENT_VERSION
        assertTrue(ResearchChoice.shouldAsk(asked = 0, on = false, recording = false, mapQuestionOpen = false))
        assertFalse("the shared-map question first", ResearchChoice.shouldAsk(0, false, false, mapQuestionOpen = true))
        assertFalse("never while recording", ResearchChoice.shouldAsk(0, false, recording = true, mapQuestionOpen = false))
        assertFalse("answered", ResearchChoice.shouldAsk(asked = v, on = false, recording = false, mapQuestionOpen = false))
        assertFalse("already on", ResearchChoice.shouldAsk(asked = 0, on = true, recording = false, mapQuestionOpen = false))
    }

    @Test fun statusWords() {
        assertEquals("off and never on: nothing to say", "" to "", status())
        Prefs.setResearchState(ctx, true, 1, offPending = false, onPending = true, serverOn = false, note = "")
        assertEquals("Telling the server you turned this on (when online)\nNothing uploaded yet", status().first)
        Prefs.setResearchState(ctx, true, 1, offPending = false, onPending = false, serverOn = true, note = "")
        assertEquals("Waiting for Wi-Fi: 3 files (12.5 MB)\nLast upload: 2 hours ago",
            status(files = 3, bytes = 12_500_000, last = now - 2 * hour).first)
        assertEquals("Paused (server storage full): tries again in 5 hours", status(until = now + 5 * hour, why = "full").second)
        assertEquals("Paused (daily upload limit): tries again in 5 hours", status(until = now + 5 * hour, why = "device_daily").second)
        assertEquals("a pause that is over says nothing", "", status(until = now - 1, why = "full").second)
        ResearchConsent.sessionReset(ctx)
        assertTrue(status().second.startsWith("Your anonymous ID was reset"))
    }

    @Test fun theResearchIdAndEarlierOnes() {
        assertEquals("Research ID: none yet (made with the first upload)", ResearchChoice.idText(ctx, null, emptyList()))
        assertEquals("Research ID: abc", ResearchChoice.idText(ctx, "abc", listOf("abc")))
        assertEquals("Research ID: abc\nEarlier IDs: old1, old2", ResearchChoice.idText(ctx, "abc", listOf("old1", "abc", "old2")))
    }
}
