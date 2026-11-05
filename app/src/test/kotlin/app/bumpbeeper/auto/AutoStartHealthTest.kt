package app.bumpbeeper.auto

import app.bumpbeeper.auto.AutoStartHealth.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Why auto start can't start a trip, in the order to fix it, and when the "paused" notification repeats. */
class AutoStartHealthTest {
    private fun inputs(
        enabled: Boolean = true, fine: Boolean = true, background: Boolean = true, restricted: Boolean = false,
        notifications: Boolean = true, battery: Boolean = true, detecting: Boolean = true,
    ) = AutoStartHealth.Inputs(enabled, fine, background, restricted, notifications, battery, detecting)

    @Test fun offSaysNothing() = assertNull(AutoStartHealth.decide(inputs(enabled = false, fine = false)))

    @Test fun everythingInPlaceIsReady() = assertEquals(emptyList<Problem>(), AutoStartHealth.decide(inputs()))

    @Test fun theFirstBlockerIsTheOneToFixFirst() {
        // Without precise location nothing else matters; then "Allow all the time"; then Android's restriction.
        assertEquals(listOf(Problem.NO_LOCATION), AutoStartHealth.decide(inputs(fine = false, background = false, detecting = false)))
        assertEquals(listOf(Problem.NO_BACKGROUND_LOCATION), AutoStartHealth.decide(inputs(background = false, restricted = true)))
        assertEquals(listOf(Problem.BACKGROUND_RESTRICTED), AutoStartHealth.decide(inputs(restricted = true, detecting = false)))
        assertEquals(listOf(Problem.NOT_RUNNING), AutoStartHealth.decide(inputs(detecting = false)))
    }

    @Test fun warningsComeAfterTheBlockerAndDontBlock() {
        val p = AutoStartHealth.decide(inputs(background = false, notifications = false, battery = false))!!
        assertEquals(listOf(Problem.NO_BACKGROUND_LOCATION, Problem.NO_NOTIFICATIONS, Problem.BATTERY_OPTIMISED), p)
        val warnOnly = AutoStartHealth.decide(inputs(notifications = false, battery = false))!!
        assertTrue(warnOnly.none { it.blocks })
        assertTrue(Problem.NOT_RUNNING.blocks)
        assertFalse(Problem.NO_NOTIFICATIONS.blocks)
    }

    @Test fun thePausedNotificationRepeatsOnlyForANewProblemOrAfter12h() {
        val h = 60 * 60 * 1000L
        assertTrue(AutoStartCheck.due(null, 0L, "NOT_RUNNING", 10 * h))
        assertFalse(AutoStartCheck.due("NOT_RUNNING", 10 * h, "NOT_RUNNING", 11 * h))
        assertTrue(AutoStartCheck.due("NOT_RUNNING", 10 * h, "NO_BACKGROUND_LOCATION", 11 * h))
        assertTrue(AutoStartCheck.due("NOT_RUNNING", 10 * h, "NOT_RUNNING", 22 * h))
        assertTrue("the phone's clock moved back", AutoStartCheck.due("NOT_RUNNING", 10 * h, "NOT_RUNNING", 5 * h))
    }

    @Test fun theNewStartLogReasonsSurviveARestart() {
        val e = StartLog.Entries().plus(1L, StartLog.Reason.BLOCKED_PERMISSION).plus(2L, StartLog.Reason.START_REFUSED)
            .plus(3L, StartLog.Reason.HELD_DELETED)
        assertEquals(e.list, StartLog.Entries.decode(e.encode()).list)
    }
}
