package app.bumpbeeper.auto

import app.bumpbeeper.auto.DriveDetector.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto-detect driving (#49): the speed hysteresis and the start / give-up / back-off state machine. */
class DriveDetectorTest {

    // ---------------------------------------------------------------- SpeedGate

    /** One fix every [stepS] seconds at [kmh] from [fromS] for [seconds]; true if the gate opened on any of them. */
    private fun SpeedGate.feed(fromS: Int, seconds: Int, kmh: Double, stepS: Int = 2): Boolean =
        (fromS until fromS + seconds step stepS).map { add(it * 1000L, kmh) }.any { it }

    @Test fun sixtySecondsAtTwentyIsADrive() {
        val g = SpeedGate()
        assertFalse(g.feed(0, 60, 25.0))      // fixes at 0..58 s
        assertTrue(g.add(60_000, 25.0))
    }

    @Test fun cyclingAtEighteenNeverOpens() {
        assertFalse(SpeedGate().feed(0, 600, 18.0))
    }

    @Test fun slowingForATurnDoesNotStartOver() {
        val g = SpeedGate()
        g.feed(0, 30, 30.0)
        g.feed(30, 10, 16.0)                  // between 15 and 20: neither counts nor resets
        assertTrue(g.feed(40, 22, 30.0))
    }

    @Test fun droppingBelowFifteenStartsOver() {
        val g = SpeedGate()
        g.feed(0, 50, 30.0)
        g.add(50_000, 10.0)
        assertFalse(g.feed(52, 50, 30.0))
        assertTrue(g.feed(102, 12, 30.0))
    }

    @Test fun aLongGapStartsOver() {
        val g = SpeedGate()
        g.add(0, 40.0)
        assertFalse(g.add(70_000, 40.0))      // two fast fixes 70 s apart are not a drive
        assertTrue(g.pending)
    }

    @Test fun fixesWithoutSpeedAreIgnored() {
        val g = SpeedGate()
        g.feed(0, 30, 30.0)
        g.add(31_000, Double.NaN)
        assertTrue(g.feed(32, 30, 30.0))
    }

    // ---------------------------------------------------------------- DriveDetector

    /** GPS fixes every 2 s with timer ticks every 10 s; returns the first non-NONE action other than START_CHECK. */
    private fun DriveDetector.run(fromS: Int, seconds: Int, kmh: Double?): Action {
        for (s in fromS until fromS + seconds step 2) {
            if (kmh != null) onFix(s * 1000L, kmh).let { if (it != Action.NONE) return it }
            if (s % 10 == 0) onTimer(s * 1000L).let { if (it != Action.NONE) return it }
        }
        return Action.NONE
    }

    @Test fun motionThenDrivingStartsRecording() {
        val d = DriveDetector()
        assertEquals(Action.START_CHECK, d.onMotion(0))
        assertEquals(Action.NONE, d.run(0, 10, null))           // GPS warming up
        assertEquals(Action.DRIVING, d.run(10, 120, 35.0))
        assertFalse(d.checking)
    }

    @Test fun walkingEndsTheCheckAndBacksOff() {
        val d = DriveDetector()
        d.onMotion(0)
        assertEquals(Action.END_CHECK, d.run(0, 200, 5.0))
        val end = 150_000L
        assertEquals(Action.NONE, d.onMotion(end + 60_000))           // within the 3 min cooldown
        assertEquals(Action.START_CHECK, d.onMotion(end + 3 * 60_000))
        assertEquals(Action.END_CHECK, d.run(330, 200, 5.0))
        // Second miss: 6 min cooldown.
        assertEquals(Action.NONE, d.onMotion(480_000 + 5 * 60_000))
        assertEquals(Action.START_CHECK, d.onMotion(480_000 + 6 * 60_000))
    }

    /** A check at [t] that sees only walking pace and ends after 150 s. Returns the end time. */
    private fun DriveDetector.walkingMiss(t: Long): Long {
        assertEquals(Action.START_CHECK, onMotion(t))
        onFix(t + 2_000, 5.0)
        assertEquals(Action.END_CHECK, onTimer(t + 150_000))
        return t + 150_000
    }

    /** Asserts motion is ignored just before [end] + [pauseMin] and starts a check right at it. */
    private fun DriveDetector.assertPause(end: Long, pauseMin: Int) {
        assertEquals(Action.NONE, onMotion(end + pauseMin * 60_000L - 1_000))
        assertEquals(Action.START_CHECK, onMotion(end + pauseMin * 60_000L))
    }

    @Test fun pauseGrowsThreeSixTenThenStays() {
        val d = DriveDetector()
        var end = d.walkingMiss(0)
        for (pause in listOf(3, 6, 10, 10, 10)) {
            d.assertPause(end, pause)                                  // also starts the next check
            val t = end + pause * 60_000L
            d.onFix(t + 2_000, 5.0)
            assertEquals(Action.END_CHECK, d.onTimer(t + 150_000))
            end = t + 150_000
        }
    }

    @Test fun noFixIndoorsGivesUpAfterAMinute() {
        val d = DriveDetector()
        d.onMotion(0)
        assertEquals(Action.NONE, d.onTimer(50_000))
        assertEquals(Action.END_CHECK, d.onTimer(60_000))
    }

    @Test fun checksWithoutAFixDontGrowThePause() {
        val d = DriveDetector()
        d.onMotion(0)
        d.onTimer(60_000)                                              // no fix: 3 min pause
        d.assertPause(60_000, 3)
        assertEquals(Action.END_CHECK, d.onTimer(240_000 + 60_000))    // no fix again
        d.assertPause(300_000, 3)                                      // still 3 min, not 6
    }

    @Test fun vehicleSpeedChecksAgainAtOnce() {
        val d = DriveDetector()
        d.onMotion(0)
        assertEquals(Action.NONE, d.run(0, 160, 17.0))                 // a slow car-park exit: 15–20 km/h
        assertTrue(d.checking)                                         // no pause: the check went on
        assertEquals(Action.DRIVING, d.run(160, 70, 30.0))
    }

    @Test fun vehicleSpeedRechecksAreCappedWithoutGrowth() {
        val d = DriveDetector()
        d.onMotion(0)
        assertEquals(Action.END_CHECK, d.run(0, 800, 17.0))            // a bus crawling: 5 windows of 150 s
        d.assertPause(750_000, 3)
        d.onTimer(990_000)                                             // that check: no fix
        d.assertPause(990_000, 3)
    }

    @Test fun pauseDecaysAfterThirtyMinutesWithoutAMiss() {
        val d = DriveDetector()
        var end = d.walkingMiss(0)
        d.assertPause(end, 3)
        d.onFix(end + 182_000, 5.0)
        assertEquals(Action.END_CHECK, d.onTimer(end + 330_000))       // second miss: next pause would be 6
        end += 330_000
        d.assertPause(end, 6)
        d.onTimer(end + 6 * 60_000L + 60_000)                          // no-fix check, no growth
        end = d.walkingMiss(end + 40 * 60_000L)                        // 40 min later, walking again
        d.assertPause(end, 3)                                          // decayed to 3 min
    }

    @Test fun aDriveStartingLateInTheCheckMayFinish() {
        val d = DriveDetector()
        d.onMotion(0)
        assertEquals(Action.NONE, d.run(0, 120, 5.0))                 // walking to the car
        assertEquals(Action.DRIVING, d.run(120, 90, 30.0))            // past 150 s, but the fast stretch finishes
    }

    @Test fun passiveFixAtSpeedChecksEvenInAPause() {
        val d = DriveDetector()
        d.onMotion(0)
        d.onTimer(60_000)                                              // gave up: no fix
        assertEquals(Action.NONE, d.onPassiveFix(70_000, 70_000, 10.0))
        assertEquals(Action.START_CHECK, d.onPassiveFix(80_000, 80_000, 50.0))
        assertTrue(d.checking)
        assertEquals(Action.DRIVING, d.run(82, 60, 50.0))
    }

    @Test fun stalePassiveFixesAreIgnored() {
        val d = DriveDetector()
        assertEquals(Action.NONE, d.onPassiveFix(100_000, 60_000, 50.0))   // 40 s old
        assertFalse(d.checking)
        assertEquals(Action.START_CHECK, d.onPassiveFix(100_000, 75_000, 50.0))
    }

    @Test fun snoozeAfterStopIgnoresEverything() {
        val d = DriveDetector()
        d.snooze(0, 15 * 60_000)
        assertEquals(Action.NONE, d.onMotion(60_000))
        assertEquals(Action.NONE, d.onPassiveFix(60_000, 60_000, 80.0))
        assertEquals(Action.START_CHECK, d.onMotion(15 * 60_000))
    }

    @Test fun aDriveResetsTheBackOff() {
        val d = DriveDetector()
        var end = d.walkingMiss(0)                                     // next pause 3, then 6
        d.assertPause(end, 3)
        d.onFix(end + 182_000, 5.0)
        d.onTimer(end + 330_000)
        end += 330_000
        d.assertPause(end, 6)
        val t = end + 6 * 60_000L
        assertEquals(Action.DRIVING, d.run((t / 1000).toInt(), 80, 40.0))
        end = d.walkingMiss(t + 20 * 60_000L)
        d.assertPause(end, 3)                                          // back to 3 min
    }

    // ---------------------------------------------------------------- AutoStop for automatic starts

    @Test fun autoStartWithoutDrivingGivesUpAfterTenMinutes() {
        val a = AutoStop(5 * 60_000L)
        a.autoStarted(0)
        for (s in 0 until 599) a.onFix(s * 1000L, 3.0, 30.0, 31.0)    // sat in a parked car / a wrong guess
        assertFalse(a.shouldStop(599_000))
        assertTrue(a.shouldStop(600_000))
    }

    @Test fun giveUpAppliesEvenWhenParkedStopIsOff() {
        val a = AutoStop(0)                                            // "never stop when parked"
        a.autoStarted(0)
        assertTrue(a.shouldStop(600_000))
    }

    @Test fun manualStartNeverGivesUpBeforeDriving() {
        val a = AutoStop(5 * 60_000L)
        for (s in 0 until 1800) a.onFix(s * 1000L, 3.0, 30.0, 31.0)
        assertFalse(a.shouldStop(1_800_000))
    }
}
