package app.bumpbeeper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The severity index on one bump model: a quarter car (body, wheel, spring, damper, tyre) per axle. */
class SeverityIndexTest {
    @Test fun sameBumpSameIndexFrom20To60Kmh() {
        for (rate in listOf(50.0, 100.0, 200.0)) {
            val idx = listOf(20.0, 30.0, 40.0, 50.0, 60.0).map { index(0.08, 1.0, it, rate).index }
            val mean = idx.average()
            for (i in idx) assertTrue(abs(i / mean - 1) <= 0.20, "$rate Hz: $idx")
        }
    }

    @Test fun growsWithTheBump() {
        val idx = listOf(0.02, 0.04, 0.06, 0.08, 0.10).map { index(it, 1.0, 30.0, 50.0).index }
        for (k in 1 until idx.size) assertTrue(idx[k] > idx[k - 1], "$idx")
    }

    @Test fun sameAtAnySampleRate() {
        for (kmh in listOf(20.0, 30.0, 40.0)) {
            val at100 = index(0.08, 1.0, kmh, 100.0).index
            for (rate in listOf(50.0, 200.0)) {
                val other = index(0.08, 1.0, kmh, rate).index
                assertTrue(abs(other / at100 - 1) <= 0.05, "$kmh km/h: $other at $rate Hz, $at100 at 100 Hz")
            }
        }
    }

    @Test fun peakScaleAndDiagnostics() {
        val cfg = SeverityConfig()
        val r = index(0.06, 1.0, cfg.refSpeedKmh, 100.0)
        // At the reference speed nothing is normalised: index = raw dose ÷ the mapping.
        assertEquals(r.vdv, r.vdvNorm, 1e-12)
        assertEquals(r.vdv / cfg.vdvPerPeak, r.index, 1e-12)
        assertTrue(r.bandPeak > 0.0)
        // The same jolt, called twice as fast: the dose is divided by √2.
        val (t, v, trig) = bodyAcceleration(0.06, 1.0, cfg.refSpeedKmh, 100.0)
        val fast = SeverityIndex.of(t, v, trig, 2 * cfg.refSpeedKmh / 3.6)
        assertEquals(r.vdvNorm / sqrt(2.0), fast.vdvNorm, 1e-9)
        // Unknown speed: no index, but the raw dose is still there.
        val unknown = SeverityIndex.of(t, v, trig, Double.NaN)
        assertTrue(unknown.index.isNaN())
        assertEquals(r.vdv, unknown.vdv, 1e-12)
        // Never past the server's 0..100.
        val huge = SeverityIndex.of(t, DoubleArray(v.size) { v[it] * 1000 }, trig, cfg.refSpeedKmh / 3.6)
        assertEquals(cfg.maxIndex, huge.index, 0.0)
    }

    @Test fun nothingToMeasure() {
        assertTrue(SeverityIndex.of(LongArray(0), DoubleArray(0), 0L, 10.0).index.isNaN())
        val t = LongArray(100) { it * 20L }
        // A window entirely after the samples.
        assertTrue(SeverityIndex.of(t, DoubleArray(100), 10_000L, 10.0).vdv.isNaN())
    }

    private fun index(heightM: Double, lengthM: Double, kmh: Double, rateHz: Double): SeverityResult {
        val (t, v, trig) = bodyAcceleration(heightM, lengthM, kmh, rateHz)
        return SeverityIndex.of(t, v, trig, kmh / 3.6)
    }
}

/**
 * What a body-mounted phone feels when a car crosses a sin² bump ([heightM] high, [lengthM] long, 2 m ahead) at [kmh]:
 * two quarter cars (300 kg body, 40 kg wheel, 20 kN/m spring, 1.5 kNs/m damper, 200 kN/m tyre), the rear one a 2.6 m
 * wheelbase later and felt at 0.8. Integrated at 2 kHz, sampled at [rateHz]. Returns times (ms), body vertical
 * acceleration (m/s²) and the trigger: the first sample at 30 % of the largest.
 */
private fun bodyAcceleration(heightM: Double, lengthM: Double, kmh: Double, rateHz: Double): Triple<LongArray, DoubleArray, Long> {
    val v = kmh / 3.6
    val dt = 0.0005
    fun road(x: Double): Double {
        val u = x - 2.0
        return if (u in 0.0..lengthM) heightM * sin(PI * u / lengthM).let { it * it } else 0.0
    }
    val state = Array(2) { DoubleArray(4) }   // body height, wheel height, body speed, wheel speed
    val every = (1.0 / (dt * rateHz)).roundToInt()
    val t = ArrayList<Long>()
    val acc = ArrayList<Double>()
    for (i in 0 until (5.0 / dt).toInt()) {
        var felt = 0.0
        for (axle in 0..1) {
            val s = state[axle]
            val spring = 20_000.0 * (s[1] - s[0]) + 1_500.0 * (s[3] - s[2])
            val tyre = 200_000.0 * (road(v * i * dt - axle * 2.6) - s[1])
            val aBody = spring / 300.0
            s[2] += aBody * dt
            s[3] += (tyre - spring) / 40.0 * dt
            s[0] += s[2] * dt
            s[1] += s[3] * dt
            felt += aBody * (if (axle == 0) 1.0 else 0.8)
        }
        if (i % every == 0) { t.add((i * dt * 1000).roundToLong()); acc.add(felt) }
    }
    val top = acc.maxOf { abs(it) }
    val trig = t[acc.indexOfFirst { abs(it) >= 0.3 * top }]
    return Triple(t.toLongArray(), acc.toDoubleArray(), trig)
}
