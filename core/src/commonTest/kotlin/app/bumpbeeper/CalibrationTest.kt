package app.bumpbeeper

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-phone calibration: the noise floor is learned on smooth road and scales the jolt trigger and severity. */
class CalibrationTest {
    /** Learn on [trips] 5 km trips at 50 km/h with this phone (placement "unknown": the holder is detected). */
    private fun learn(
        gain: Double = 1.0, noise: Double = 0.0, trips: Int = 3, c: PhoneCalibration = PhoneCalibration(),
        rough: List<Pair<Double, Double>> = emptyList(),
    ): PhoneCalibration {
        for (seed in 1..trips) {
            Simulator(400L + seed).drive(MemoryStore(), DriveSpec(roadM = 5000.0, phoneGain = gain, phoneNoise = noise, roughAt = rough), calibration = c)
        }
        return c
    }

    private fun copy(c: PhoneCalibration) = PhoneCalibration.decode(c.encode())

    @Test fun learnsTheNoiseFloorPerPhone() {
        val base = learn()
        assertTrue(base.windowWeight("mounted") >= base.cfg.minWindows, "windows ${base.windowWeight("mounted")}")
        assertTrue(base.learnedScale("mounted") in 0.8..1.25, "the simulator's phone is the reference: ${base.describe()}")
        assertEquals(1.5, learn(noise = 0.8).learnedScale("mounted"), 1e-9, "a rattling phone, clamped")
        assertEquals(0.5, learn(gain = 0.4).learnedScale("mounted"), 1e-9, "a stiff, quiet mount, clamped")
        assertEquals(1.0, base.learnedScale("pocket"), "nothing learned for the pocket")
    }

    /** Half the driving on rough road (1.2 m/s² RMS) doesn't move the floor: the scale stays the smooth road's. */
    @Test fun aRoughCommuteDoesNotMoveIt() {
        val smooth = learn().learnedScale("mounted")
        val mixed = learn(rough = listOf(1000.0 to 3500.0)).learnedScale("mounted")
        assertTrue(abs(mixed - smooth) <= 0.1, "smooth only ${formatFixed(smooth, 2)}, half rough ${formatFixed(mixed, 2)}")
    }

    /** Placement "unknown": pocket and holder trips each keep their own scale. */
    @Test fun pocketAndHolderKeepTheirOwnScale() {
        val c = PhoneCalibration()
        repeat(3) { i ->
            // In the pocket: it keeps jostling (pocket mode turns on by itself) and rattles.
            Simulator(700L + i).drive(MemoryStore(), DriveSpec(roadM = 5000.0, phoneNoise = 0.8, jostlesAt = listOf(150.0, 300.0, 450.0, 600.0)), calibration = c)
            Simulator(800L + i).drive(MemoryStore(), DriveSpec(roadM = 5000.0), calibration = c)
        }
        assertEquals(1.5, c.learnedScale("pocket"), 1e-9, c.describe())
        assertTrue(c.learnedScale("mounted") in 0.8..1.25, c.describe())
    }

    @Test fun keys() {
        assertEquals("cupholder", PhoneCalibration.keyFor("cupholder", false, PhoneState.STABLE_MOUNTED))
        assertEquals("pocket", PhoneCalibration.keyFor("unknown", true, PhoneState.STABLE_LOOSE))
        assertEquals("mounted", PhoneCalibration.keyFor("unknown", false, PhoneState.STABLE_MOUNTED))
        assertNull(PhoneCalibration.keyFor("unknown", false, PhoneState.STABLE_LOOSE))
    }

    /** The scale a trip uses is the one learned before it; a lowered trigger stays at 1.8 m/s² or more. */
    @Test fun aTripUsesWhatWasLearnedBefore() {
        val learned = learn(noise = 0.8)
        val eng = BumpEngine(EngineConfig(), MemoryStore(), object : EngineListener {}, { 0L }, calibration = PhoneCalibration())
        eng.phone.placement = "mounted"
        assertEquals(1.0, eng.joltScale)
        val next = BumpEngine(EngineConfig(), MemoryStore(), object : EngineListener {}, { 0L }, calibration = copy(learned))
        next.phone.placement = "mounted"
        assertEquals(1.5, next.joltScale, 1e-9)
        assertEquals(4.5, next.joltTrigger, 1e-9)
        val stiff = PhoneCalibration.decode("mounted:10=200")
        stiff.freeze()
        assertEquals(1.8, stiff.trigger(3.0, "mounted"), 1e-9, "0.5 × 3.0 = 1.5 would be too close to the noise")
    }

    @Test fun encodeDecodeKeepsTheScale() {
        val c = learn(noise = 0.3)
        assertEquals(c.learnedScale("mounted"), copy(c).learnedScale("mounted"), 0.01)
        assertEquals(1.0, PhoneCalibration.decode("garbage;x:;:1,2;mounted:a=b").learnedScale("mounted"))
    }

    /** Jolts that set the trigger off (learned or rejected) after the first minute, on 5 bump-free trips. */
    private fun falseJolts(noise: Double, c: PhoneCalibration): Int = (1..5).sumOf { seed ->
        val store = MemoryStore()
        Simulator(500L + seed).drive(store, DriveSpec(phoneNoise = noise), calibration = copy(c))
        store.events.count { (it.type == "new_bump" || it.type == "rejected") && it.wallTime - 1_700_000_000_000L > 60_000 }
    }

    /** A noisy phone, calibrated, sets the trigger off no more often than the reference phone; uncalibrated it would. */
    @Test fun noisyPhoneDoesNotOverTrigger() {
        val baseline = falseJolts(0.0, learn())
        val noisyRaw = falseJolts(0.8, PhoneCalibration())
        val noisyCal = falseJolts(0.8, learn(noise = 0.8))
        assertTrue(noisyRaw > baseline, "uncalibrated, the rattle triggers: $noisyRaw vs $baseline")
        assertTrue(noisyCal <= baseline, "calibrated: $noisyCal false jolts, reference phone $baseline")
    }

    /** Real bumps are still caught by the calibrated noisy phone. */
    @Test fun noisyPhoneStillCatchesBumps() {
        val noisy = Simulator(601).drive(MemoryStore(), DriveSpec(bumpsAt = listOf(1000.0, 1500.0, 1900.0), bumpKmh = 25.0, phoneNoise = 0.8),
            calibration = copy(learn(noise = 0.8)))
        assertTrue(noisy.newBumps >= 3, "noisy phone, calibrated: ${noisy.newBumps} bumps, rejected ${noisy.rejected}")
    }

    /**
     * A stiff mount (gain 0.4, scale 0.5, trigger 1.8 m/s²) over gentle 15 km/h bumps (5 m/s² × 0.4 = 2.0 front,
     * 1.6 rear): with its calibration, learned on earlier trips, the front axle sets the trigger off.
     */
    @Test fun stiffMountCatchesGentleBumps() {
        val c = learn(gain = 0.4)
        val spec = DriveSpec(bumpsAt = listOf(1000.0, 1500.0, 1900.0), bumpKmh = 15.0, phoneGain = 0.4)
        val rec = ArrayList<TraceSample>()
        val raw = Simulator(602).drive(MemoryStore(), spec).newBumps
        val cal = Simulator(602).drive(MemoryStore(), spec, recorder = rec, calibration = copy(c))
        // What the engine saw: the largest verticals, one per stretch of 3 s.
        val peaks = rec.filterIsInstance<TraceSample.Accel>().let { a ->
            val tops = ArrayList<Pair<Long, Double>>()
            for (s in a.sortedByDescending { abs(it.vertical) }) {
                if (tops.size >= 6) break
                if (tops.none { abs(it.first - s.tMs) < 3000 }) tops.add(s.tMs to s.vertical)
            }
            tops.sortedBy { it.first }.joinToString { "${formatFixed(it.first / 1000.0, 1)} s ${formatFixed(it.second, 2)}" }
        }
        assertTrue(cal.newBumps >= 3 && cal.newBumps >= raw,
            "stiff mount: ${cal.newBumps} bumps calibrated (rejected ${cal.rejected}), $raw without; calibration ${c.describe()}; peaks $peaks")
    }

    /** The same, fed straight to the engine: three 2.5 m/s² jolts set the stiff mount's trigger off, not the default one. */
    @Test fun stiffMountTriggerDirect() {
        val stiff = PhoneCalibration.decode("mounted:10=200")
        assertEquals(0.5, stiff.learnedScale("mounted"), 1e-9)
        assertEquals(0, gentleJolts(PhoneCalibration()), "uncalibrated")
        assertEquals(3, gentleJolts(stiff), "calibrated stiff mount")
    }

    /** Jolts that set the trigger off on a flat phone in a holder at 30 km/h with three 2.5 m/s² jolts. */
    private fun gentleJolts(c: PhoneCalibration): Int {
        var tMs = 0L
        var triggers = 0
        val eng = BumpEngine(EngineConfig(), MemoryStore(), object : EngineListener {
            override fun onNewBump(b: Bump) { triggers++ }
            override fun onKnownBumpHit(b: Bump) { triggers++ }
            override fun onJoltRejected(peak: Double, reason: String) { triggers++ }
        }, { 1_700_000_000_000L + tMs }, calibration = c)
        eng.phone.placement = "mounted"
        val jolts = listOf(20.0, 40.0, 60.0)
        while (tMs <= 70_000L) {
            val ts = tMs / 1000.0
            var z = 9.81
            for (j in jolts) {
                val tau = ts - j
                if (tau in 0.0..0.1) z += 2.5 else if (tau > 0.1 && tau <= 0.2) z -= 1.8
            }
            eng.onAccel(tMs, 0.0, 0.0, z)
            if (tMs % 1000 == 0L) {
                val p = Geo.move(30.0444, 31.2357, 90.0, 8.33 * ts)
                eng.onFix(Fix(tMs, p[0], p[1], 8.33, 90.0, 5.0))
            }
            tMs += 20
        }
        return triggers
    }
}
