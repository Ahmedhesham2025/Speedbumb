package app.bumpbeeper.auto

import android.content.Context
import app.bumpbeeper.Prefs
import app.bumpbeeper.sync.Sync
import app.bumpbeeper.sync.TrainingSink
import app.bumpbeeper.sync.TrainingStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Training samples of a trip that started by itself (#49) follow TripHold: held, released on Yes, deleted on No. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripHoldTrainingTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
        Prefs.sp(ctx).edit().clear().commit()
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        Prefs.sp(ctx).edit().putBoolean(Prefs.TRAINING_CONSENT, true).commit()
        install()
    }

    @After fun tearDown() {
        install()
        Prefs.sp(ctx).edit().clear().commit()
    }

    private fun install() {
        TripHold.reset()
        TripHold.installBuiltIns()
        TripHold.installTraining(ctx)
    }

    private fun queue(tripId: Long, id: String) =
        TrainingSink.queue(ctx, listOf(TrainingStore.Item(id, TrainingStore.SAMPLE, "{}")), tripId, System.currentTimeMillis())

    private fun uploadable(): List<String> = Sync.withDb(ctx) { TrainingStore(it).batch().map { i -> i.id } }
    private fun stored(): Int = Sync.withDb(ctx) { TrainingStore(it).count() }

    @Test fun unconfirmedTripsSamplesStayHeldUntilYes() {
        TripHold.hold(ctx, 5)
        queue(5, "a")
        queue(4, "b")                         // a trip that was never held uploads as before
        assertEquals(listOf("b"), uploadable())
        TripHold.confirm(ctx, 5)
        assertEquals(setOf("a", "b"), uploadable().toSet())
    }

    @Test fun noDeletesTheSamples() {
        TripHold.hold(ctx, 6)
        queue(6, "a")
        TripHold.reject(ctx, 6)
        assertEquals(0, stored())
    }

    @Test fun unansweredForADayDeletesTheSamples() {
        TripHold.hold(ctx, 7, System.currentTimeMillis() - TripHold.MAX_AGE_MS)
        queue(7, "a")
        TripHold.expire(ctx)
        assertEquals(0, stored())
    }

    @Test fun samplesLandingAfterAQuickNoAreHeldNotSent() {
        // TrainingSink writes on its own thread after trip end; a "No" may get there first.
        TripHold.hold(ctx, 8)
        TripHold.reject(ctx, 8)
        queue(8, "late")
        assertTrue(uploadable().isEmpty())    // held, and TrainingStore's prune drops held rows later
    }

    @Test fun samplesLandingAfterAYesUploadNormally() {
        TripHold.hold(ctx, 9)
        TripHold.confirm(ctx, 9)
        queue(9, "late")
        assertEquals(listOf("late"), uploadable())
    }
}
