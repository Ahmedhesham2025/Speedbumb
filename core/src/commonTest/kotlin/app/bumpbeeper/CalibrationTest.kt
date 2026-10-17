package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Per-phone calibration: the noise floor is learned on smooth road and scales the jolt trigger and severity. */
class CalibrationTest {
    /** Learn on three 5 km trips of smooth road at 50 km/h with this phone. */
    private fun learn(gain: Double = 1.0, noise: Double = 0.0): PhoneCalibration {
        val c = PhoneCalibration()
        for (seed in 1..3) {
            Simulator(400L + seed).drive(MemoryStore(), DriveSpec(roadM = 5000.0, phoneGain = gain, phoneNoise = noise), calibration = c)
        }
        return c
    }

    /** Jolts that set the trigger off on a road with no bump (learned or rejected), over five trips. */
    private fun falseJolts(noise: Double, c: PhoneCalibration): Int = (1..5).sumOf { seed ->
        val r = Simulator(500L + seed).drive(MemoryStore(), DriveSpec(phoneNoise = noise), calibration = PhoneCalibration.decode(c.encode()))
        r.newBumps + r.rejected.size
    }

    @Test fun learnsTheNoiseFloorPerPhone() {
        val base = learn()
        assertTrue(base.windowCount("unknown") >= base.cfg.minWindows, "windows ${base.windowCount("unknown")}")
        assertTrue(base.learnedScale("unknown") in 0.8..1.25, "the simulator's phone is the reference: ${base.learnedScale("unknown")}")
        assertEquals(1.5, learn(noise = 0.8).learnedScale("unknown"), 1e-9, "a rattling pocket phone, clamped")
        assertEquals(0.7, learn(gain = 0.5).learnedScale("unknown"), 1e-9, "a stiff, quiet mount, clamped")
        assertEquals(1.0, base.learnedScale("pocket"), "nothing learned for the pocket yet")
    }

    /** The scale a trip uses is the one learned before it. */
    @Test fun aTripUsesWhatWasLearnedBefore() {
        val c = PhoneCalibration()
        val eng = BumpEngine(EngineConfig(), MemoryStore(), object : EngineListener {}, { 0L }, calibration = c)
        assertEquals(1.0, eng.joltScale)
        val learned = learn(noise = 0.8)
        val next = BumpEngine(EngineConfig(), MemoryStore(), object : EngineListener {}, { 0L }, calibration = PhoneCalibration.decode(learned.encode()))
        assertEquals(1.5, next.joltScale, 1e-9)
    }

    @Test fun encodeDecodeKeepsTheScale() {
        val c = learn(noise = 0.3)
        val d = PhoneCalibration.decode(c.encode())
        assertEquals(c.learnedScale("unknown"), d.learnedScale("unknown"), 0.01)
        assertEquals(1.0, PhoneCalibration.decode("garbage;x:;:1,2").learnedScale("unknown"))
    }

    /** A noisy phone, calibrated, sets the trigger off no more often than the reference phone; uncalibrated it would. */
    @Test fun noisyPhoneDoesNotOverTrigger() {
        val baseline = falseJolts(0.0, learn())
        val noisyRaw = falseJolts(0.8, PhoneCalibration())
        val noisyCal = falseJolts(0.8, learn(noise = 0.8))
        assertTrue(noisyRaw > baseline, "uncalibrated, the rattle triggers: $noisyRaw vs $baseline")
        assertTrue(noisyCal <= baseline, "calibrated: $noisyCal false jolts, reference phone $baseline")
    }

    /** Real bumps are still caught by the calibrated noisy phone, and the stiff mount catches its gentle ones. */
    @Test fun realBumpsStillCaught() {
        val bumps = listOf(500.0, 1000.0, 1500.0)
        val noisy = Simulator(601).drive(MemoryStore(), DriveSpec(bumpsAt = bumps, bumpKmh = 25.0, phoneNoise = 0.8),
            calibration = PhoneCalibration.decode(learn(noise = 0.8).encode()))
        assertTrue(noisy.newBumps >= 3, "noisy phone, calibrated: ${noisy.newBumps} bumps, rejected ${noisy.rejected}")
        val stiff = DriveSpec(bumpsAt = bumps, bumpKmh = 15.0, phoneGain = 0.5)
        val raw = Simulator(602).drive(MemoryStore(), stiff).newBumps
        val cal = Simulator(602).drive(MemoryStore(), stiff, calibration = PhoneCalibration.decode(learn(gain = 0.5).encode())).newBumps
        assertTrue(cal >= raw && cal >= 3, "stiff mount: $cal bumps calibrated, $raw without")
    }
}
