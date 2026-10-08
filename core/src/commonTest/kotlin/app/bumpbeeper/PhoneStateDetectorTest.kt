package app.bumpbeeper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
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
        /** The GPS speed follows [car] (a real stop or start rather than a push). */
        var gpsFollowsCar = false
        private var bearing = 90.0
        /** The phone was HANDLED at some sample since the last [fresh], for this long in all. */
        var everHandled = false
        var handledMsTotal = 0L

        fun fresh(): Rig {
            everHandled = false
            handledMsTotal = 0L
            return this
        }

        /**
         * [seconds] of samples while the phone turns at [rateDegS] about x and at [yawRads] about z, and wobbles
         * ±[wobbleDeg] about x once a second (a hand).
         */
        fun run(seconds: Double, rateDegS: Double = 0.0, yawRads: Double = 0.0, wobbleDeg: Double = 0.0): Rig {
            repeat((seconds * 50).roundToInt()) {
                t += 20
                val rate = rateDegS + wobbleDeg * 2 * PI * cos(2 * PI * t / 1000.0)
                deg += rate * 0.02
                if (gpsFollowsCar) kmh = max(0.0, kmh + car * 0.02 * 3.6)
                val th = degToRad(deg)
                if (gyro) d.onGyro(t, degToRad(rate), 0.0, yawRads)
                d.onAccel(t, 0.0, 9.81 * sin(th) + car, 9.81 * cos(th))
                if (t % 1000 == 0L) {
                    bearing = (bearing + turnDegS) % 360.0
                    d.onFix(Fix(t, 30.0, 31.0, kmh / 3.6, bearing, 5.0))
                }
                if (d.state == PhoneState.HANDLED) {
                    everHandled = true
                    handledMsTotal += 20
                }
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
            // A full emergency stop from 100 km/h, the GPS speed following it.
            val stop = Rig(gyro = gyro).apply { kmh = 100.0; gpsFollowsCar = true }.run(10.0).fresh()
            stop.car = -6.5
            stop.run(4.3)
            stop.car = 0.0
            stop.run(5.0)
            assertFalse(stop.everHandled, "full stop, gyroscope: $gyro")
        }
    }

    @Test fun tripStartingWhileSpeedingUpIsMountedWithin40s() {
        val r = Rig().apply { gpsFollowsCar = true; car = 1.5 }.run(9.0)
        r.car = 0.0
        var mountedAt = -1L
        repeat(60) {
            r.run(1.0)
            if (mountedAt < 0 && r.d.state == PhoneState.STABLE_MOUNTED) mountedAt = r.t
        }
        assertTrue(mountedAt in 0L..42_000L, "mounted at $mountedAt ms")
        assertFalse(r.everHandled)
        assertEquals(PhoneState.STABLE_MOUNTED, r.d.state)
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

    @Test fun withoutALockScreenAnUnlockNeedsMotion() {
        val still = Rig("cupholder").run(5.0).fresh()
        still.signals.keyguardPresent = false
        still.signals.unlockedAtMs = still.t
        still.run(5.5)
        assertFalse(still.everHandled, "the screen came on for a notification")
        val picked = Rig("cupholder").run(5.0).fresh()
        picked.signals.keyguardPresent = false
        picked.signals.unlockedAtMs = picked.t
        picked.run(1.0).run(0.2, rateDegS = 100.0).run(0.5)
        assertTrue(picked.everHandled && (picked.d.causes and PhoneStateDetector.UNLOCK) != 0, "causes ${picked.d.causes}")
    }

    @Test fun causesWhileDrivingAreKeptApart() {
        val stopped = Rig("pocket").run(5.0)
        stopped.signals.unlockedAtMs = stopped.t
        stopped.run(2.0)
        assertEquals(PhoneStateDetector.UNLOCK, stopped.d.causes)
        assertEquals(0, stopped.d.movingCauses, "unlocked while stopped")
        val driving = Rig().apply { kmh = 30.0 }.run(5.0)
        driving.signals.handheldCall = true
        driving.run(3.0)
        assertTrue((driving.d.movingCauses and PhoneStateDetector.CALL) != 0)
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
            // Tilted 24° (less than the 28° that is handling anyway), in a hand that wobbles.
            r.run(0.24, rateDegS = 100.0).run(2.0, wobbleDeg = 4.0)
            assertEquals(handled, r.everHandled, placement)
            if (handled) assertTrue((r.d.causes and PhoneStateDetector.SCREEN) != 0, "causes ${r.d.causes}")
        }
    }

    @Test fun phoneThatSlipsWithTheScreenOnIsNotHandledForLong() {
        for (placement in listOf("mounted", "unknown", "cupholder")) {
            val r = Rig(placement).run(40.0).fresh()
            r.signals.screenOn = true
            r.kmh = 30.0
            r.run(0.3, rateDegS = 20.0 / 0.3).run(60.0)   // slips 20° and stays there, a minute
            assertTrue(r.handledMsTotal <= 3500, "$placement: handled ${r.handledMsTotal} ms")
            assertTrue(r.d.state != PhoneState.HANDLED && r.d.tiltDeg < 2.0, "$placement: ${r.d.state}, tilt ${r.d.tiltDeg}")
            assertTrue(r.d.movingMs < 1500, "$placement: no phone use, ${r.d.movingMs} ms")
            if (placement != "cupholder") assertEquals(PhoneState.STABLE_MOUNTED, r.d.state, "$placement: still in its holder")
        }
    }

    @Test fun stillAtAnAngleWithTheScreenOnStaysHandled() {
        // A hand held still at 70° and a holder that slipped 40° look the same: with the screen on, the hand wins.
        for (deg in listOf(40.0, 70.0)) for (placement in listOf("unknown", "mounted", "cupholder", "pocket")) {
            val r = Rig(placement).apply { kmh = 30.0 }.run(40.0)
            r.signals.screenOn = true
            r.run(0.3, rateDegS = deg / 0.3).fresh().run(60.0)   // turned in 0.3 s, then still for a minute
            assertTrue(r.handledMsTotal >= 59_000, "$deg°, $placement: handled ${r.handledMsTotal} ms of 60 s")
            assertEquals(PhoneState.HANDLED, r.d.state, "$deg°, $placement")
            r.signals.screenOn = false
            r.run(0.1)
            assertTrue(r.d.state != PhoneState.HANDLED && r.d.tiltDeg < 2.0, "$deg°, $placement, screen off: ${r.d.state}")
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

    @Test fun holdLengthWhileDriving() {
        for ((held, counts) in listOf(1.0 to false, 2.0 to true)) {
            val r = Rig().apply { kmh = 30.0 }.run(5.0)
            r.run(0.3, rateDegS = 70 / 0.3).run(held).run(0.3, rateDegS = -70 / 0.3).run(0.5)
            assertEquals(counts, r.d.movingMs >= 1500, "held $held s: ${r.d.movingMs} ms")
        }
    }

    @Test fun tipOverIsNoPhoneUseTime() {
        for (gyro in listOf(true, false)) {
            val r = Rig("cupholder", gyro = gyro).apply { kmh = 30.0 }.run(40.0).fresh()
            r.run(0.3, rateDegS = 70 / 0.3).run(15.0)   // tips 70° and lies there
            assertTrue(r.everHandled, "gyroscope $gyro: handled while it tips")
            assertTrue(r.d.movingMs < 1500, "gyroscope $gyro: lying still is not phone-use time, ${r.d.movingMs} ms")
            assertEquals(PhoneState.STABLE_LOOSE, r.d.state, "gyroscope $gyro: re-learned where it lies")
            assertTrue(r.d.tiltDeg < 2.0, "gyroscope $gyro: tilt ${r.d.tiltDeg}")
        }
    }

    @Test fun holdIsSeenWithoutAGyroscope() {
        val r = Rig(gyro = false).run(5.0)
        r.run(0.5, rateDegS = 120.0).run(3.0)
        assertEquals(PhoneState.HANDLED, r.d.state)
        assertTrue((r.d.causes and PhoneStateDetector.TILT) != 0, "causes ${r.d.causes}")
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
