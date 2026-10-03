package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.Prefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Trips not confirmed yet (auto-started) are held on the phone until released or discarded; consent is re-checked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrainingHoldTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
    }

    @Test fun releaseRechecksConsentAndQueueHoldsUnconfirmedTrips() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        Prefs.sp(ctx).edit().putBoolean(Prefs.TRAINING_CONSENT, true).commit()
        TrainingSink.isHeld = { it == 5L }
        try {
            val items = listOf(TrainingStore.Item("a", TrainingStore.SAMPLE, "{}"))
            assertEquals(1, TrainingSink.queue(ctx, items, 5L, 1000))
            assertEquals(1, TrainingSink.queue(ctx, listOf(TrainingStore.Item("b", TrainingStore.SAMPLE, "{}")), 4L, 1000))
            assertEquals(listOf("b"), Sync.withDb(ctx) { TrainingStore(it).batch().map { i -> i.id } })
            // Consent withdrawn before the user confirmed: release deletes instead.
            Prefs.sp(ctx).edit().putBoolean(Prefs.TRAINING_CONSENT, false).commit()
            TrainingSink.release(ctx, "5")
            assertEquals(1, Sync.withDb(ctx) { TrainingStore(it).count() })
            assertEquals(0, TrainingSink.queue(ctx, items, 9L, 1000))   // no consent: nothing queued
            Prefs.sp(ctx).edit().putBoolean(Prefs.TRAINING_CONSENT, true).commit()
            TrainingSink.queue(ctx, items, 5L, 1000)
            TrainingSink.release(ctx, "5")
            assertEquals(setOf("a", "b"), Sync.withDb(ctx) { TrainingStore(it).batch().map { i -> i.id }.toSet() })
            TrainingSink.discard(ctx, "5")
            assertEquals(listOf("b"), Sync.withDb(ctx) { TrainingStore(it).batch().map { i -> i.id } })
        } finally {
            TrainingSink.isHeld = { false }
            Prefs.sp(ctx).edit().clear().commit()
        }
    }
}
