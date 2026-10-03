package app.bumpbeeper.auto

import android.content.Context
import app.bumpbeeper.Prefs
import com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER
import com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** play edition (#49): which Google transitions start a recording and which shorten the parked stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehicleActivityReceiverTest {
    private lateinit var ctx: Context
    private val calls = ArrayList<String>()
    private val actions = object : VehicleActivityReceiver.Actions {
        override fun startRecording(ctx: Context) { calls += "start" }
        override fun userWalking(ctx: Context) { calls += "walking" }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        Prefs.sp(ctx).edit().clear().putBoolean(Prefs.AUTO_DETECT, true).commit()
    }

    private fun send(vararg events: Pair<Int, Int>) =
        VehicleActivityReceiver().handle(ctx, events.map { ActivityTransitionEvent(it.first, it.second, 0L) }, actions)

    @Test fun enteringAVehicleStartsRecording() {
        send(DetectedActivity.IN_VEHICLE to ACTIVITY_TRANSITION_ENTER)
        assertEquals(listOf("start"), calls)
    }

    @Test fun vehicleExitAloneChangesNothing() {
        // A car standing at a long red light: Google ends IN_VEHICLE (STILL). The normal parked setting must apply.
        send(DetectedActivity.IN_VEHICLE to ACTIVITY_TRANSITION_EXIT)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun walkingOrRunningMeansTheUserLeftTheCar() {
        send(DetectedActivity.WALKING to ACTIVITY_TRANSITION_ENTER, DetectedActivity.RUNNING to ACTIVITY_TRANSITION_ENTER)
        assertEquals(listOf("walking", "walking"), calls)
    }

    @Test fun eventsAreHandledInOrder() {
        send(
            DetectedActivity.WALKING to ACTIVITY_TRANSITION_ENTER,
            DetectedActivity.IN_VEHICLE to ACTIVITY_TRANSITION_ENTER,
        )
        assertEquals(listOf("walking", "start"), calls)
    }

    @Test fun switchedOffDoesNothing() {
        Prefs.sp(ctx).edit().putBoolean(Prefs.AUTO_DETECT, false).commit()
        send(DetectedActivity.IN_VEHICLE to ACTIVITY_TRANSITION_ENTER)
        assertEquals(emptyList<String>(), calls)
    }
}
