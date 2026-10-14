package app.bumpbeeper.auto

import app.bumpbeeper.auto.DriveDetector.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto-detect driving (#49): the speed hysteresis and the start / give-up / back-off state machine. */
class DriveDetectorTest {

    // ---------------------------------------------------------------- SpeedGate

    /** One fix every [stepS] seconds at [kmh] from [fromS] for [seconds]; true if the gate opened on any of them. */
    private fun SpeedGate.feed(fromS: Int, seconds: Int, kmh: Double, stepS: Int = 2): Boolean =
        (fromS until fromS + seconds step stepS).map { add(it * 1000L, kmh) }.any { it }

    @Test fun moreThanThirtySecondsAtTwentyIsADrive() {
        val g = SpeedGate()
        assertFalse(g.feed(0, 30, 25.0))      // fixes at 0..28 s
        assertFalse(g.add(30_000, 25.0))      // exactly 30 s: not yet
        assertTrue(g.add(31_000, 25.0))
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
        g.feed(0, 26, 30.0)
        g.add(26_000, 10.0)
        assertFalse(g.feed(28, 30, 30.0))     // 28..56 s: only 28 s fast so far
        assertTrue(g.feed(58, 4, 30.0))
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

    @Test fun twoFixesAtDrivingSpeedStartRecordingAtOnce() {
        val d = DriveDetector()
        d.onMotion(0)
        assertEquals(Action.NONE, d.onFix(2_000, 30.0))
        assertEquals(Action.DRIVING, d.onFix(4_000, 30.0))    // silent; BumpService confirms after 30 s (TripConfirm)
    }

    @Test fun walkingEndsTheCheckAndBacksOff() {
        val d = DriveDetector(checkMs = 150_000L)
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
        val d = DriveDetector(missesBeforeBackoff = Int.MAX_VALUE)   // the pause alone, without the back-off
        var end = d.walkingMiss(0)
        for (pause in listOf(3, 6, 10, 10, 10)) {
            d.assertPause(end, pause)                                  // also starts the next check
            val t = end + pause * 60_000L
            d.onFix(t + 2_000, 5.0)
            assertEquals(Action.END_CHECK, d.onTimer(t + 150_000))
            end = t + 150_000
        }
    }

    @Test fun motionThatIsNotWalkingChecksEvenInAPause() {
        val log = ArrayList<StartLog.Reason>()
        val d = DriveDetector(log = { log += it })
        val end = d.walkingMiss(0)                                     // walked to the car: a 3 min pause
        assertTrue(d.wantsMotionKind(end + 30_000))
        assertEquals(Action.RETRY_LATER, d.onMotion(end + 30_000, MotionKind.Kind.VEHICLE))   // the last check only just ended
        assertEquals(end + 60_000, d.retryAtMs)
        assertEquals(Action.NONE, d.onMotion(end + 60_000, MotionKind.Kind.WALKING))           // still walking: the pause holds
        assertEquals(Action.START_CHECK, d.onMotion(end + 60_000, MotionKind.Kind.VEHICLE))   // the car drives off
        assertEquals(Action.DRIVING, d.run(((end + 60_000) / 1000).toInt(), 20, 25.0))
        assertEquals(listOf(StartLog.Reason.MOTION_CHECK, StartLog.Reason.SKIP_SLOW, StartLog.Reason.MOTION_WALKING,
            StartLog.Reason.MOTION_CHECK, StartLog.Reason.WAITING_FOR_SPEED), log)
    }

    @Test fun motionAtHomeWithGpsWanderUnderTwentyNeverStarts() {
        val log = ArrayList<StartLog.Reason>()
        val d = DriveDetector(log = { log += it })
        var gpsOnS = 0
        for (s in 0 until 3600 step 2) {                               // an hour; the phone is moved every 2 min
            val t = s * 1000L
            // Carried around, picked up and put down, walked with: household motion, never a car's vibration.
            val house = listOf(MotionSignals.carried, MotionSignals.putDown, MotionSignals.walking)
            if (s % 120 == 0) d.onMotion(t, MotionKind.of(house[s / 120 % 3]))
            if (d.checking) {
                gpsOnS += 2
                assertNotEquals(Action.DRIVING, d.onFix(t, if (s % 6 == 0) 18.0 else 4.0))   // wander: jumps, never 20
            }
            if (s % 10 == 0) d.onTimer(t)
        }
        assertTrue(StartLog.Reason.SKIP_SLOW in log)
        assertTrue("GPS on $gpsOnS s of 3600", gpsOnS <= 300)       // about 5 min an hour at most
    }

    /** A quick check at [t] after vehicle-like motion that sees no vehicle. Returns its end. */
    private fun DriveDetector.quickMiss(t: Long): Long {
        assertEquals(Action.START_CHECK, onMotion(t, MotionKind.Kind.VEHICLE))
        onFix(t + 2_000, 4.0)
        assertEquals(Action.END_CHECK, onTimer(t + 30_000))   // a slow fix: 30 s
        return t + 30_000
    }

    @Test fun threeChecksWithoutAVehicleBackOffTenTwentyThirtyMinutes() {
        val log = ArrayList<StartLog.Reason>()
        val d = DriveDetector(log = { log += it })
        var end = d.walkingMiss(0)
        end = d.quickMiss(end + 60_000)
        end = d.quickMiss(end + 60_000)                                // the third: 10 min of rest
        for (min in listOf(10, 20, 30, 30)) {
            val t = end + min * 60_000L
            assertEquals(Action.NONE, d.onMotion(t - 1_000, MotionKind.Kind.MOVED))
            assertEquals(StartLog.Reason.SKIP_BACKOFF, log.last())     // still resting
            assertEquals(Action.NONE, d.onMotion(t, MotionKind.Kind.MOVED))
            assertEquals(StartLog.Reason.MOTION_WALKING, log.last())   // rest over, but still not a vehicle
            assertEquals(Action.NONE, d.onMotion(t, MotionKind.Kind.WALKING))
            end = d.quickMiss(t)                                       // car-like vibration: a quick check only
        }
        // In the car after all: vehicle speed resets the back-off, and the drive starts.
        val t = end + 30 * 60_000L
        assertEquals(Action.START_CHECK, d.onMotion(t, MotionKind.Kind.VEHICLE))
        assertEquals(Action.NONE, d.onFix(t + 2_000, 25.0))
        assertEquals(Action.DRIVING, d.onFix(t + 4_000, 25.0))
        assertTrue(d.wantsMotionKind(t + 5_000))
        assertEquals(Action.START_CHECK, d.onMotion(t + 5_000, MotionKind.Kind.WALKING))   // full checks again
    }

    @Test fun aDriveStartingFiveMinutesIntoABackOffStarts() {
        val d = DriveDetector()
        var end = d.walkingMiss(0)
        end = d.quickMiss(end + 60_000)
        end = d.quickMiss(end + 60_000)                                // 10 min of rest from here
        val t = end + 5 * 60_000L
        assertEquals(Action.NONE, d.onMotion(t, MotionKind.Kind.WALKING))    // walking to the car: resting
        assertEquals(Action.START_CHECK, d.onMotion(t + 30_000, MotionKind.Kind.VEHICLE))   // the car moves
        assertEquals(Action.NONE, d.onFix(t + 32_000, 25.0))
        assertEquals(Action.DRIVING, d.onFix(t + 34_000, 25.0))
    }

    @Test fun aColdGpsInTheCarGetsNinetySecondsASlowFixStillEndsAtThirty() {
        val d = DriveDetector()
        val t = d.walkingMiss(0) + 60_000
        assertEquals(Action.START_CHECK, d.onMotion(t, MotionKind.Kind.VEHICLE))
        for (s in 10..80 step 10) assertEquals(Action.NONE, d.onTimer(t + s * 1000L))   // no fix yet: keeps going
        assertEquals(Action.NONE, d.onFix(t + 82_000, 25.0))           // the first fix after 80 s
        assertEquals(Action.DRIVING, d.onFix(t + 84_000, 25.0))
        val slow = DriveDetector()
        val u = slow.walkingMiss(0) + 60_000
        slow.onMotion(u, MotionKind.Kind.VEHICLE)
        slow.onFix(u + 2_000, 4.0)
        assertEquals(Action.NONE, slow.onTimer(u + 20_000))
        assertEquals(Action.END_CHECK, slow.onTimer(u + 30_000))
    }

    @Test fun householdMotionIsNeverCarLike() {
        assertEquals(MotionKind.Kind.VEHICLE, MotionKind.of(MotionSignals.car))
        assertEquals(MotionKind.Kind.MOVED, MotionKind.of(MotionSignals.carried))
        assertEquals(MotionKind.Kind.MOVED, MotionKind.of(MotionSignals.putDown))
        assertEquals(MotionKind.Kind.WALKING, MotionKind.of(MotionSignals.walking))
        assertEquals(MotionKind.Kind.STILL, MotionKind.of(MotionSignals.still))
        assertEquals(MotionKind.Kind.MOVED, MotionKind.of(emptyList()))   // no accelerometer
    }

    @Test fun aPhoneLyingStillNeverChecks() {
        val log = ArrayList<StartLog.Reason>()
        val d = DriveDetector(log = { log += it })
        for (s in 0 until 3600 step 60) assertEquals(Action.NONE, d.onMotion(s * 1000L, MotionKind.of(MotionSignals.still)))
        assertFalse(d.checking)
        assertEquals(setOf(StartLog.Reason.SKIP_STILL), log.toSet())
        assertEquals(MotionKind.Kind.VEHICLE, MotionKind.of(MotionSignals.car))
        assertEquals(MotionKind.Kind.WALKING, MotionKind.of(MotionSignals.walking))
    }

    @Test fun noFixIndoorsGivesUpAfterNinetySeconds() {
        val d = DriveDetector()
        d.onMotion(0)
        assertEquals(Action.NONE, d.onTimer(80_000))                   // a cold GPS in a car park may need a minute
        assertEquals(Action.END_CHECK, d.onTimer(90_000))
    }

    @Test fun checksWithoutAFixDontGrowThePause() {
        val d = DriveDetector()
        d.onMotion(0)
        d.onTimer(90_000)                                              // no fix: 3 min pause
        d.assertPause(90_000, 3)
        assertEquals(Action.END_CHECK, d.onTimer(270_000 + 90_000))    // no fix again
        d.assertPause(360_000, 3)                                      // still 3 min, not 6
    }

    @Test fun vehicleSpeedChecksAgainAtOnce() {
        val d = DriveDetector()
        d.onMotion(0)
        assertEquals(Action.NONE, d.run(0, 160, 17.0))                 // a slow car-park exit: 15–20 km/h
        assertTrue(d.checking)                                         // no pause: the check went on
        assertEquals(Action.DRIVING, d.run(160, 70, 30.0))
    }

    @Test fun vehicleSpeedRechecksAreCappedWithoutGrowth() {
        val d = DriveDetector(checkMs = 150_000L)
        d.onMotion(0)
        assertEquals(Action.END_CHECK, d.run(0, 800, 17.0))            // a bus crawling: 5 windows of 150 s
        d.assertPause(750_000, 3)
        d.onTimer(1_020_000)                                           // that check: no fix
        d.assertPause(1_020_000, 3)
    }

    @Test fun pauseDecaysAfterThirtyMinutesWithoutAMiss() {
        val d = DriveDetector(missesBeforeBackoff = Int.MAX_VALUE)
        var end = d.walkingMiss(0)
        d.assertPause(end, 3)
        d.onFix(end + 182_000, 5.0)
        assertEquals(Action.END_CHECK, d.onTimer(end + 330_000))       // second miss: next pause would be 6
        end += 330_000
        d.assertPause(end, 6)
        d.onTimer(end + 6 * 60_000L + 90_000)                          // no-fix check, no growth
        end = d.walkingMiss(end + 40 * 60_000L)                        // 40 min later, walking again
        d.assertPause(end, 3)                                          // decayed to 3 min
    }

    @Test fun aDriveStartingLateInTheCheckMayFinish() {
        val d = DriveDetector(checkMs = 150_000L)
        d.onMotion(0)
        assertEquals(Action.NONE, d.run(0, 120, 5.0))                 // walking to the car
        assertEquals(Action.DRIVING, d.run(120, 90, 30.0))            // past 150 s, but the fast stretch finishes
    }

    @Test fun passiveFixAtSpeedChecksEvenInAPause() {
        val d = DriveDetector()
        d.onMotion(0)
        d.onTimer(90_000)                                              // gave up: no fix
        assertEquals(Action.NONE, d.onPassiveFix(100_000, 100_000, 10.0))
        assertEquals(Action.START_CHECK, d.onPassiveFix(110_000, 110_000, 50.0))
        assertTrue(d.checking)
        assertEquals(Action.DRIVING, d.run(112, 60, 50.0))
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
