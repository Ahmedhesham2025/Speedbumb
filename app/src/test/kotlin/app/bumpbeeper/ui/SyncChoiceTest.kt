package app.bumpbeeper.ui

import app.bumpbeeper.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** When the shared-map question appears, and the words the status lines use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncChoiceTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun asksOnFirstOpenOnly() {
        assertTrue(SyncChoice.shouldAsk(Prefs.SYNC_UNSET, recording = false, answers = 0, tripsSinceLater = 0))
        assertFalse("never while recording", SyncChoice.shouldAsk(Prefs.SYNC_UNSET, recording = true, answers = 0, tripsSinceLater = 0))
        assertFalse("already chosen", SyncChoice.shouldAsk(Prefs.SYNC_SHARE, recording = false, answers = 0, tripsSinceLater = 0))
        assertFalse("already chosen", SyncChoice.shouldAsk(Prefs.SYNC_RECEIVE, recording = false, answers = 0, tripsSinceLater = 0))
    }

    @Test fun decideLaterAsksOnceMoreAfterThreeTrips() {
        assertFalse(SyncChoice.shouldAsk(Prefs.SYNC_UNSET, false, answers = 1, tripsSinceLater = 2))
        assertTrue(SyncChoice.shouldAsk(Prefs.SYNC_UNSET, false, answers = 1, tripsSinceLater = 3))
        assertFalse("then never again", SyncChoice.shouldAsk(Prefs.SYNC_UNSET, false, answers = 2, tripsSinceLater = 50))
    }

    @Test fun statusWords() {
        assertEquals("Last synced: never", SyncChoice.lastSynced(ctx, 0L))
        assertEquals("Last synced just now", SyncChoice.lastSynced(ctx, 1_000_000L, now = 1_010_000L))
        assertEquals("1,240 shared spots near you", SyncChoice.spots(ctx, 1240))
        assertEquals("1 shared spot near you", SyncChoice.spots(ctx, 1))
        assertEquals("", SyncChoice.error(ctx, ""))
        assertEquals("Couldn't reach the server. Will retry automatically.", SyncChoice.error(ctx, "offline or server busy"))
        assertTrue(SyncChoice.error(ctx, "daily upload limit reached").startsWith("Daily upload limit"))
    }
}
