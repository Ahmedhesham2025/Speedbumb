package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.BumpDb
import app.bumpbeeper.Prefs
import app.bumpbeeper.auto.TripHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #93: the first start after a backup restore resets the training state before anything talks to the server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreResetTest {
    private lateinit var ctx: Context

    /** Records every call and answers like the backend for sign-in and registration. */
    private class Recorder : Transport {
        val calls = ArrayList<String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            return when (name) {
                "signup", "token" -> HttpResult(200, """{"access_token":"a","expires_in":3600,"refresh_token":"r","user":{"id":"u"}}""")
                "register_device" -> HttpResult(200, "\"u\"")
                else -> HttpResult(200, "true")
            }
        }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        TripHold.forgetAll(ctx)
        RestoreReset.takeNotice(ctx)
        RestoreReset.marker(ctx).delete()   // AppStart already wrote one for this test's application
    }

    private fun <T> db(block: (BumpDb) -> T): T = Sync.withDb(ctx, block)

    /** The old phone's state as it arrives in settings.xml and bumps.db: training on, samples queued, a held trip. */
    private fun oldPhoneState() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_SHARE)
        Prefs.sp(ctx).edit().putBoolean(Prefs.SPEED_LIMITS, true).commit()
        Prefs.setTrainingState(ctx, true, TrainingConsent.TRAINING_CONSENT_VERSION, wipe = true, sendOn = true, note = "")
        Prefs.setTrainingUploadAfter(ctx, 1L)
        db {
            TrainingStore(it).add(listOf(TrainingStore.Item("s1", TrainingStore.SAMPLE, """{"client_sample_id":"s1"}""")), System.currentTimeMillis())
            SyncStore(it).holdAdd(listOf("o1" to "{}"), 7L, System.currentTimeMillis())
            SyncStore(it).put(TrainingConsent.PAUSED_UNTIL, Long.MAX_VALUE)
        }
    }

    @Test fun freshInstallResetsNothing() {
        assertFalse(RestoreReset.check(ctx))
        assertTrue("install id written", RestoreReset.marker(ctx).exists())
        assertFalse(RestoreReset.takeNotice(ctx))
    }

    @Test fun restoreResetsTrainingBeforeAnySync() {
        oldPhoneState()
        TripHold.hold(ctx, 7L)

        assertTrue(RestoreReset.check(ctx))

        assertFalse(Prefs.trainingConsent(ctx))
        assertEquals(0, Prefs.trainingConsentVersion(ctx))
        assertFalse(Prefs.trainingWipePending(ctx))
        assertFalse(Prefs.trainingOnPending(ctx))
        assertEquals(0L, Prefs.trainingUploadAfter(ctx))
        db {
            assertEquals(0, TrainingStore(it).count())
            assertTrue(SyncStore(it).heldTrips().isEmpty())
            assertEquals(0L, SyncStore(it).getLong(TrainingConsent.PAUSED_UNTIL))
        }
        assertFalse(TripHold.isHeld(ctx, 7L))
        // Kept: the shared-map choice and road speed limits.
        assertEquals(Prefs.SYNC_SHARE, Prefs.syncChoice(ctx))
        assertTrue(Prefs.speedLimits(ctx))
        assertTrue(RestoreReset.marker(ctx).exists())

        // The first sync after it: registers the new device, never sends a training call.
        val net = Recorder()
        TrainingConsent.run(ctx, net)
        assertTrue(net.calls.contains("register_device"))
        assertFalse(net.calls.contains("set_training_consent"))
        assertFalse(net.calls.contains("submit_training_samples"))

        // The notice shows once.
        assertTrue(RestoreReset.takeNotice(ctx))
        assertFalse(RestoreReset.takeNotice(ctx))
    }

    @Test fun normalSecondStartResetsNothing() {
        assertFalse(RestoreReset.check(ctx))   // first start of a fresh install
        oldPhoneState()                         // the user then turned training on

        assertFalse(RestoreReset.check(ctx))

        assertTrue(Prefs.trainingConsent(ctx))
        assertTrue(Prefs.trainingOnPending(ctx))
        db { assertEquals(1, TrainingStore(it).count()) }
        assertFalse(RestoreReset.takeNotice(ctx))
    }
}
