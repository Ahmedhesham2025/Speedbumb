package app.bumpbeeper.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto-stop when parked (#49, #50): never at a red light, a jam or before the drive, always once parked long enough. */
class AutoStopTest {
    private val min = 60_000L
    /** Metres per degree of latitude: the test car drives due north. */
    private val mPerDeg = 111_195.0
    private var lat = 30.0

    /** One fix a second at [kmh] for [seconds], starting at [fromS]; the position follows the speed. */
    private fun AutoStop.drive(fromS: Int, seconds: Int, kmh: Double) {
        for (s in fromS until fromS + seconds) {
            lat += kmh / 3.6 / mPerDeg
            onFix(s * 1000L, kmh, lat, 31.0)
        }
    }

    /** Parked with GPS jitter: every third fix shows [kmh] while the position wanders ±[wanderM]. */
    private fun AutoStop.jitter(fromS: Int, seconds: Int, kmh: Double, wanderM: Double) {
        for (s in fromS until fromS + seconds) {
            val off = (if (s % 2 == 0) wanderM else -wanderM) / mPerDeg
            onFix(s * 1000L, if (s % 3 == 0) kmh else 1.0, lat + off, 31.0)
        }
    }

    @Test fun parkedFiveMinutesStops() {
        val a = AutoStop(5 * min)
        a.drive(0, 300, 120.0)        // 33 m a second: every fix is a "moving" fix
        a.drive(300, 299, 0.0)
        assertFalse(a.shouldStop(598_000))
        a.drive(599, 2, 0.0)
        assertTrue(a.shouldStop(600_000))
    }

    @Test fun fourMinuteCheckpointKeepsRecording() {
        val a = AutoStop(5 * min)
        a.drive(0, 120, 40.0)
        a.drive(120, 240, 1.0)        // 4 min standing still
        assertFalse(a.shouldStop(360_000))
        a.drive(360, 60, 30.0)
        a.drive(420, 240, 0.0)        // and another one: the clock started again
        assertFalse(a.shouldStop(660_000))
    }

    @Test fun gpsJitterWhileParkedDoesNotRestartTheClock() {
        val a = AutoStop(5 * min)
        a.drive(0, 120, 120.0)
        a.jitter(120, 301, 8.0, 12.0) // 8 km/h spikes, position ±12 m
        assertTrue(a.shouldStop(421_000))
    }

    @Test fun creepingInAJamOverThirtyMetresIsMoving() {
        val a = AutoStop(5 * min)
        a.drive(0, 120, 50.0)
        a.drive(120, 400, 6.0)        // 6 km/h for almost 7 min: 30 m every ~18 s
        assertFalse(a.shouldStop(520_000))
    }

    @Test fun neverBeforeTheCarHasDriven() {
        val a = AutoStop(5 * min)
        a.drive(0, 1200, 0.0)         // manual start in a car park, 20 min waiting
        a.drive(1200, 60, 4.0)        // walking around with the phone
        assertFalse(a.shouldStop(1_260_000))
    }

    @Test fun zeroMeansNever() {
        val a = AutoStop(0)
        a.drive(0, 60, 50.0)
        a.drive(60, 3600, 0.0)
        assertFalse(a.shouldStop(3_660_000))
    }

    @Test fun settingCanChangeDuringATrip() {
        val a = AutoStop(10 * min)
        a.drive(0, 60, 50.0)
        a.drive(60, 200, 0.0)
        assertFalse(a.shouldStop(260_000))
        a.stopAfterMs = 3 * min
        assertTrue(a.shouldStop(260_000))
    }

    @Test fun carBluetoothTripNeverStopsWhileConnected() {
        val a = AutoStop(5 * min)
        a.carConnected = true
        a.drive(0, 120, 50.0)
        a.drive(120, 1800, 0.0)       // 30 min at a checkpoint, car still connected
        assertFalse(a.shouldStop(1_920_000))
        a.carConnected = false        // disconnected: BumpService's grace ends it; the parked rule applies again too
        assertTrue(a.shouldStop(1_920_000))
    }

    @Test fun tunnelWhileMovingIsNotParked() {
        val a = AutoStop(5 * min)
        a.drive(0, 120, 60.0)
        // No fix for 7 minutes: the last speed was high, so it is a tunnel, not a car park.
        assertFalse(a.shouldStop(540_000))
    }

    @Test fun noFixForFifteenMinutesStops() {
        val a = AutoStop(5 * min)
        a.drive(0, 120, 20.0)         // drove into an underground car park, GPS gone
        assertFalse(a.shouldStop(119_000 + 14 * min))
        assertTrue(a.shouldStop(119_000 + 15 * min))
    }

    @Test fun walkingShortensTheWait() {
        val a = AutoStop(5 * min)
        a.drive(0, 120, 120.0)
        a.drive(120, 30, 0.0)
        a.leftVehicle()
        assertFalse(a.shouldStop(150_000))
        a.drive(150, 31, 0.0)
        assertTrue(a.shouldStop(181_000))   // 1 min stationary after the user was seen walking
    }

    @Test fun drivingAgainCancelsAStaleWalk() {
        val a = AutoStop(5 * min)
        a.drive(0, 120, 50.0)
        a.leftVehicle()
        a.drive(120, 60, 40.0)        // Google was wrong: still driving
        a.drive(180, 90, 0.0)         // 1.5 min at a light
        assertFalse(a.shouldStop(270_000))
    }

    @Test fun backInTheVehicleCancelsTheWalk() {
        val a = AutoStop(5 * min)
        a.drive(0, 120, 50.0)
        a.leftVehicle()
        a.backInVehicle()
        a.drive(120, 90, 0.0)
        assertFalse(a.shouldStop(210_000))
    }
}
