package app.bumpbeeper.ui

import app.bumpbeeper.ui.AutoSetup.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order of the questions behind "Start recording when I drive". */
class AutoSetupTest {
    private fun state(fine: Boolean = false, bg: Boolean = false, sdk: Int = 34, notif: Boolean = false,
                      battery: Boolean = false, activity: Boolean = false) =
        AutoSetup.State(fine, bg, sdk, notif, battery, activity)

    /** Walks the questions as if the user said yes to each one. */
    private fun walk(start: AutoSetup.State, grant: (AutoSetup.State, Step) -> AutoSetup.State): List<Step> {
        val asked = HashSet<Step>()
        val out = ArrayList<Step>()
        var s = start
        while (true) {
            val step = AutoSetup.next(s, asked) ?: return out
            asked.add(step); out.add(step)
            s = grant(s, step)
        }
    }

    private fun yes(s: AutoSetup.State, step: Step) = when (step) {
        Step.FINE -> state(true, s.background, s.sdk, s.notifications, s.batteryOk, s.activityNeeded)
        Step.BACKGROUND -> state(s.fine, true, s.sdk, s.notifications, s.batteryOk, s.activityNeeded)
        Step.NOTIFICATIONS -> state(s.fine, s.background, s.sdk, true, s.batteryOk, s.activityNeeded)
        Step.BATTERY -> state(s.fine, s.background, s.sdk, s.notifications, true, s.activityNeeded)
        Step.ACTIVITY -> state(s.fine, s.background, s.sdk, s.notifications, s.batteryOk, false)
    }

    @Test fun fullOrderOnPlayAndroid14() {
        assertEquals(listOf(Step.FINE, Step.BACKGROUND, Step.NOTIFICATIONS, Step.BATTERY, Step.ACTIVITY),
            walk(state(activity = true), ::yes))
    }

    @Test fun fossNeverAsksForPhysicalActivity() {
        assertEquals(listOf(Step.FINE, Step.BACKGROUND, Step.NOTIFICATIONS, Step.BATTERY), walk(state(), ::yes))
    }

    @Test fun noNotificationQuestionBeforeAndroid13() {
        assertEquals(listOf(Step.FINE, Step.BACKGROUND, Step.BATTERY), walk(state(sdk = 32), ::yes))
        assertFalse(AutoSetup.needed(Step.NOTIFICATIONS, state(sdk = 29)))
    }

    @Test fun grantedStepsAreSkipped() {
        assertEquals(listOf(Step.BATTERY), walk(state(fine = true, bg = true, notif = true), ::yes))
        assertNull(AutoSetup.next(state(true, true, 34, true, true, false), emptySet()))
    }

    @Test fun optionalNoMovesOnButEachQuestionOnlyOnce() {
        // The user says no to everything optional: every question is asked once, then the walk ends.
        val asked = walk(state(fine = true, bg = true, activity = true)) { s, _ -> s }
        assertEquals(listOf(Step.NOTIFICATIONS, Step.BATTERY, Step.ACTIVITY), asked)
    }

    @Test fun locationIsRequiredAndNeedsTheDisclosure() {
        assertTrue(AutoSetup.required(Step.FINE))
        assertTrue(AutoSetup.required(Step.BACKGROUND))
        assertFalse(AutoSetup.required(Step.BATTERY))
        assertFalse(AutoSetup.required(Step.ACTIVITY))
        assertTrue(AutoSetup.needsDisclosure(state(fine = true)))
        assertFalse(AutoSetup.needsDisclosure(state(fine = true, bg = true)))
    }
}
