package app.bumpbeeper.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto-stop when parked (#49, #50): never at a red light or before the drive, always once parked long enough. */
class AutoStopTest {
    private val min = 60_000L

    /** One fix a second at [kmh] for [seconds], starting at [fromS]. */
    private fun AutoStop.drive(fromS: Int, seconds: Int, kmh: Double) {
        for (s in fromS until fromS + seconds) onFix(s * 1000L, kmh)
    }

    @Test fun parkedThreeMinutesStops() {
        val a = AutoStop(3 * min)
        a.drive(0, 300, 50.0)
        a.drive(300, 179, 0.0)
        assertFalse(a.shouldStop(478_000))
        a.drive(479, 2, 0.0)
        assertTrue(a.shouldStop(480_000))
    }

    @Test fun longRedLightUnderTheLimitKeepsRecording() {
        val a = AutoStop(3 * min)
        a.drive(0, 120, 40.0)
        a.drive(120, 150, 1.0)        // 2.5 min at a light
        assertFalse(a.shouldStop(270_000))
        a.drive(270, 60, 30.0)
        a.drive(330, 150, 0.0)        // and another one: the clock started again
        assertFalse(a.shouldStop(480_000))
    }

    @Test fun neverBeforeTheCarHasDriven() {
        val a = AutoStop(3 * min)
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

    @Test fun tunnelWhileMovingIsNotParked() {
        val a = AutoStop(3 * min)
        a.drive(0, 120, 60.0)
        // No fix for 5 minutes: the last speed was high, so it is a tunnel, not a car park.
        assertFalse(a.shouldStop(420_000))
    }

    @Test fun noFixForFifteenMinutesStops() {
        val a = AutoStop(3 * min)
        a.drive(0, 120, 20.0)         // drove into an underground car park, GPS gone
        assertFalse(a.shouldStop(120_000 + 14 * min))
        assertTrue(a.shouldStop(120_000 + 15 * min))
    }

    @Test fun leftTheVehicleShortensTheWait() {
        val a = AutoStop(3 * min)
        a.drive(0, 120, 50.0)
        a.drive(120, 30, 0.0)
        a.vehicleExit()
        assertFalse(a.shouldStop(150_000))
        a.drive(150, 31, 0.0)
        assertTrue(a.shouldStop(181_000))   // 1 min stationary after "left the vehicle"
    }

    @Test fun drivingAgainCancelsAStaleExit() {
        val a = AutoStop(3 * min)
        a.drive(0, 120, 50.0)
        a.vehicleExit()
        a.drive(120, 60, 40.0)        // Google was wrong: still driving
        a.drive(180, 90, 0.0)         // 1.5 min at a light
        assertFalse(a.shouldStop(270_000))
    }

    @Test fun backInTheVehicleCancelsTheExit() {
        val a = AutoStop(3 * min)
        a.drive(0, 120, 50.0)
        a.vehicleExit()
        a.vehicleEnter()
        a.drive(120, 90, 0.0)
        assertFalse(a.shouldStop(210_000))
    }
}
