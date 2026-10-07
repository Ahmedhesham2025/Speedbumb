package app.bumpbeeper

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [PhoneStateDetector] on its own: a phone lying flat (gravity on z) and turned about its x axis, 50 Hz, GPS once a second. */
class PhoneStateDetectorTest {
    private class Rig(placement: String = "unknown", private val gyro: Boolean = true) {
        val d = PhoneStateDetector().also { it.placement = placement }
        val signals: PhoneSignals get() = d.signals
        var t = 0L
        /** How far the phone is turned about its x axis, degrees. */
        var deg = 0.0
        /** The car's own push along the phone's y axis (speeding up, braking, cornering), m/s². */
        var car = 0.0
        var kmh = 0.0
        /** How fast the GPS heading turns, degrees per second. */
        var turnDegS = 0.0
        private var bearing = 90.0
        /** The phone was HANDLED at some sample since the last [fresh]. */
        var everHandled = false

        fun fresh(): Rig {
            everHandled = false
            return this
        }

        /** [seconds] of samples while the phone turns at [rateDegS] about x and at [yawRads] about z. */
        fun run(seconds: Double, rateDegS: Double = 0.0, yawRads: Double = 0.0): Rig {
            repeat((seconds * 50).roundToInt()) {
                t += 20
                deg += rateDegS * 0.02
                val th = degToRad(deg)
                if (gyro) d.onGyro(t, degToRad(rateDegS), 0.0, yawRads)
                d.onAccel(t, 0.0, 9.81 * sin(th) + car, 9.81 * cos(th))
                if (t % 1000 == 0L) {
                    bearing = (bearing + turnDegS) % 360.0
                    d.onFix(Fix(t, 30.0, 31.0, kmh / 3.6, bearing, 5.0))
                }
                if (d.state == PhoneState.HANDLED) everHandled = true
            }
            return this
        }
    }

    @Test fun restingPhoneIsMountedOrLoose() {
        val r = Rig().run(40.0)
        assertFalse(r.everHandled)
        assertEquals(PhoneState.STABLE_MOUNTED, r.d.state, "no placement set, steady for 30 s: mounted")
        assertEquals(PhoneState.STABLE_LOOSE, Rig("pocket").run(40.0).d.state, "a pocket is never mounted")
        assertEquals(PhoneState.STABLE_MOUNTED, Rig("mounted").run(1.0).d.state, "placement mounted: trusted from the start")
    }

    @Test fun tiltHeldIsHandledUntil2sStable() {
        val r = Rig().run(5.0)
        val t0 = r.t
        r.run(0.4, rateDegS = 100.0).run(3.0)
        assertEquals(PhoneState.HANDLED, r.d.state)
        assertEquals(PhoneStateDetector.TILT, r.d.causes)
        val since = r.d.handledSinceMs - t0
        assertTrue(since in 250L..400L, "back-dated to when the tilt passed 28°: $since ms")
        r.run(0.4, rateDegS = -100.0).run(1.5)
        assertEquals(PhoneState.HANDLED, r.d.state, "put back 1.5 s ago")
        r.run(1.0)
        assertEquals(PhoneState.STABLE_LOOSE, r.d.state, "2 s without a sign")
        assertTrue(r.d.lastHandledEndMs > r.t - 1000, "just ended")
        assertTrue(r.d.tiltDeg < 1.0, "the baseline is learned again: ${r.d.tiltDeg}")
    }

    @Test fun tiltShorterThanHalfASecondIsNot() {
        val r = Rig().run(5.0).fresh()
        r.run(0.25, rateDegS = 140.0).run(0.2).run(0.25, rateDegS = -140.0).run(3.0)
        assertFalse(r.everHandled)
    }

    @Test fun carSpeedingUpBrakingOrCorneringIsNotATilt() {
        for (gyro in listOf(true, false)) {
            val r = Rig(gyro = gyro).run(5.0).fresh()
            r.car = 6.5
            r.run(1.5)   // emergency stop: the measured "down" leans 33°
            r.car = 0.0
            r.run(3.0)
            r.car = 4.0
            r.run(10.0)  // a long hard curve
            r.car = 0.0
            r.run(3.0)
            assertFalse(r.everHandled, "gyroscope: $gyro")
        }
    }

    @Test fun unlockIsHandledUnlessMounted() {
        val pocket = Rig("pocket").run(5.0)
        pocket.signals.unlockedAtMs = pocket.t
        pocket.run(1.0)
        assertEquals(PhoneState.HANDLED, pocket.d.state)
        assertEquals(PhoneStateDetector.UNLOCK, pocket.d.causes)
        pocket.run(4.5)
        assertEquals(PhoneState.STABLE_LOOSE, pocket.d.state, "3 s after the unlock, then 2 s calm")
        val mounted = Rig("mounted").run(5.0).fresh()
        mounted.signals.unlockedAtMs = mounted.t
        mounted.run(5.5)
        assertFalse(mounted.everHandled, "unlocking a mounted phone (navigation)")
    }

    @Test fun handHeldCallIsHandled() {
        val r = Rig().run(5.0)
        r.signals.handheldCall = true
        r.run(10.0)
        assertEquals(PhoneState.HANDLED, r.d.state)
        assertEquals(PhoneStateDetector.CALL, r.d.causes)
        r.signals.handheldCall = false
        r.run(2.5)
        assertEquals(PhoneState.STABLE_LOOSE, r.d.state)
    }

    @Test fun screenOnAndTiltedOnlyCountsWhenNotMounted() {
        for ((placement, handled) in listOf("cupholder" to true, "mounted" to false)) {
            val r = Rig(placement).run(40.0).fresh()
            r.signals.screenOn = true
            r.run(0.2, rateDegS = 100.0).run(1.0)   // tilted 20°: less than the 28° that is handling anyway
            assertEquals(handled, r.everHandled, placement)
            if (handled) assertEquals(PhoneStateDetector.SCREEN, r.d.causes)
        }
    }

    @Test fun takenOutOfAPocketIsHandled() {
        val r = Rig("pocket")
        r.signals.proximityNear = true
        r.signals.lux = 0.0
        r.run(5.0)
        assertEquals(PhoneState.STABLE_LOOSE, r.d.state)
        r.signals.proximityNear = false
        r.run(0.3)
        r.signals.lux = 250.0
        r.run(0.1)
        assertEquals(PhoneState.HANDLED, r.d.state)
        assertEquals(PhoneStateDetector.POCKET_EXIT, r.d.causes)
    }

    @Test fun rotationTheGpsHeadingDoesNotExplain() {
        val straight = Rig().apply { kmh = 30.0 }.run(5.0).fresh()
        straight.run(1.0, yawRads = 1.3)
        assertTrue(straight.everHandled, "turning 1.3 rad/s while the car drives straight")
        assertEquals(PhoneStateDetector.GYRO, straight.d.causes)
        val turning = Rig().apply { kmh = 30.0; turnDegS = 75.0 }.run(5.0).fresh()
        turning.run(1.0, yawRads = 1.3)
        assertFalse(turning.everHandled, "the car itself turns that fast")
    }

    @Test fun putDownSomewhereNewIsLearnedAfterAWhile() {
        val r = Rig().run(5.0)
        r.run(0.5, rateDegS = 120.0).run(4.0)
        assertEquals(PhoneState.HANDLED, r.d.state)
        r.run(8.0)
        assertEquals(PhoneState.STABLE_LOOSE, r.d.state, "resting at 60° for 10 s: its new place")
        assertTrue(r.d.tiltDeg < 1.0, "measured from the new place: ${r.d.tiltDeg}")
        assertTrue(r.d.handledDuring(r.t - 9000, r.t - 9000) && !r.d.handledDuring(r.t - 100, r.t))
    }

    @Test fun jostlesWhileDrivingTurnPocketModeOn() {
        val r = Rig().apply { kmh = 30.0 }.run(5.0)
        repeat(4) {
            assertFalse(r.d.pocketMode)
            r.run(0.2, rateDegS = 250.0).run(0.3).run(0.2, rateDegS = -250.0).run(6.0)
            assertTrue(r.d.jostle, "a short jostle: ${r.d.handledMs} ms")
        }
        assertTrue(r.d.pocketMode)
    }
}
