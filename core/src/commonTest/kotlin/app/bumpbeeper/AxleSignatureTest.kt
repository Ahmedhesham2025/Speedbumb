package app.bumpbeeper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two-axle signature on synthetic roads ([Road]): pairs, single knocks, rumble strips, odd spacing, slow crossings. */
class AxleSignatureTest {
    @Test fun bothAxlesAtAnySpeedAndWheelbase() {
        // The detector assumes 2.6 m; the cars are 2.4 and 3.0 m. Δt must come out within 10 % of the true one.
        for (rate in listOf(50.0, 100.0, 200.0)) for (kmh in listOf(10.0, 20.0, 40.0, 70.0)) for (wb in listOf(2.4, 3.0)) {
            for (seed in if (rate == 200.0) 1L..1L else 1L..3L) {
                val r = judge(pair(kmh, wb, seed), kmh, rate)
                val what = "$rate Hz, $kmh km/h, $wb m, seed $seed: ${describe(r)}"
                assertEquals(AxleVerdict.BOTH, r.verdict, what)
                assertTrue(r.score >= 0.6, what)
                assertTrue(abs(r.dtMs / (wb / (kmh / 3.6) * 1000) - 1) <= 0.10, what)
            }
        }
    }

    @Test fun oneKnockIsNoAxle() {
        // Like handling the phone: one sharp impulse on a quiet road, nothing a wheelbase later.
        for (rate in listOf(50.0, 100.0)) for (kmh in listOf(15.0, 30.0, 50.0)) for (seed in 1L..2L) {
            val road = Road(seed).apply { hit(T0, 6.0, 0.06) }
            val r = judge(road, kmh, rate)
            val what = "$rate Hz, $kmh km/h, seed $seed: ${describe(r)}"
            assertEquals(AxleVerdict.ONE, r.verdict, what)
            assertTrue(r.score <= 0.3, what)
        }
    }

    @Test fun rumbleStripIsNoPair() {
        // Ten narrow bars, 0.5–1.6 m apart, under both axles.
        for (rate in listOf(50.0, 100.0)) for (kmh in listOf(20.0, 40.0)) for (gap in listOf(0.5, 0.8, 1.2, 1.6)) {
            for (wb in listOf(2.4, 3.0)) {
                val v = kmh / 3.6
                val road = Road(7)
                for (k in 0 until 10) {
                    val w = maxOf(0.03, 0.15 / v)
                    road.hit(T0 + k * gap / v, 4.5, w)
                    road.hit(T0 + (k * gap + wb) / v, 3.6, w)
                }
                assertNoPair(judge(road, kmh, rate), "$rate Hz, $kmh km/h, bars $gap m, $wb m")
            }
        }
    }

    @Test fun wrongSpacingIsNoPair() {
        // Two impulses, but 0.45, 1.6 or 2.2 × the wheelbase apart.
        for (rate in listOf(50.0, 100.0)) for (kmh in listOf(15.0, 30.0)) for (f in listOf(0.45, 1.6, 2.2)) {
            val v = kmh / 3.6
            val road = Road(5).apply { hit(T0, 5.0, width(v)); hit(T0 + f * 2.6 / v, 4.0, width(v)) }
            assertNoPair(judge(road, kmh, rate, postMs = 1600), "$rate Hz, $kmh km/h, × $f")
        }
    }

    @Test fun slowCrossingIsNeverNoAxle() {
        for (rate in listOf(50.0, 100.0)) {
            // 4–5 km/h: the rear wheels come 1.9–2.3 s later, after the 1.6 s window.
            for (kmh in listOf(4.0, 5.0)) {
                val r = judge(pair(kmh, 2.6, 3), kmh, rate)
                assertEquals(AxleVerdict.UNKNOWN, r.verdict, "$rate Hz, $kmh km/h: ${describe(r)}")
            }
            // 7 km/h: Δt + margin is past the window, but the rear still falls in it; never "no axle".
            val r = judge(pair(7.0, 2.6, 3), 7.0, rate)
            assertNotEquals(AxleVerdict.ONE, r.verdict, "$rate Hz, 7 km/h: ${describe(r)}")
        }
    }

    @Test fun sameAt50And100Hz() {
        for (kmh in listOf(10.0, 20.0, 40.0, 70.0)) for (wb in listOf(2.4, 3.0)) {
            val road = pair(kmh, wb, 4)
            val a = judge(road, kmh, 50.0)
            val b = judge(road, kmh, 100.0)
            val what = "$kmh km/h, $wb m: ${describe(a)} | ${describe(b)}"
            assertEquals(a.verdict, b.verdict, what)
            assertTrue(abs(a.score - b.score) <= 0.1, what)
            assertTrue(abs(a.dtMs / b.dtMs - 1) <= 0.05, what)
        }
    }

    @Test fun pitchOrderIsChecked() {
        for (rate in listOf(50.0, 100.0)) for (kmh in listOf(20.0, 40.0)) {
            val right = judge(pair(kmh, 2.6, 3, pitch = 0.3), kmh, rate)
            assertEquals(1, right.pitchOrder, describe(right))
            assertEquals(AxleVerdict.BOTH, right.verdict, describe(right))
            // Nose down at the front and up at the rear is no car going over a bump: score halved, not a pair.
            val wrong = judge(pair(kmh, 2.6, 3, pitch = -0.3), kmh, rate)
            assertEquals(-1, wrong.pitchOrder, describe(wrong))
            assertTrue(abs(wrong.score - 0.5 * right.score) <= 0.05, describe(wrong))
            assertNotEquals(AxleVerdict.BOTH, wrong.verdict)
            // Without a gyroscope the order is unknown and nothing is held against the pair.
            val none = judge(pair(kmh, 2.6, 3, pitch = 0.3), kmh, rate, gyro = false)
            assertEquals(0, none.pitchOrder)
            assertEquals(AxleVerdict.BOTH, none.verdict, describe(none))
        }
    }

    @Test fun brakingBeforeTheJolt() {
        val road = pair(18.0, 2.6, 1)
        // Slowing from 30 to 20 km/h by GPS (the jolt triggers just after 3 s); 18 km/h at the jolt.
        val fixes = listOf(30.0, 27.0, 23.0, 20.0).mapIndexed { i, kmh -> Fix(600 + i * 800L, 0.5, 0.5, kmh / 3.6, 90.0, 5.0) }
        val r = judge(road, 18.0, 50.0, fixes = fixes)
        assertEquals(12.0, r.brakeDropKmh, 1e-9)
        assertEquals(true, r.braked)
        val steady = judge(road, 18.0, 50.0, fixes = fixes.map { Fix(it.timeMs, 0.5, 0.5, 18.0 / 3.6, 90.0, 5.0) })
        assertEquals(false, steady.braked)
        val none = judge(road, 18.0, 50.0)
        assertTrue(none.brakeDropKmh.isNaN())
        assertNull(none.braked)
    }

    @Test fun decideWindow() {
        assertEquals(1200L, AxleSignature.decideWindowMs(30 / 3.6, 2.6))     // Δt 312 ms
        assertEquals(1336L, AxleSignature.decideWindowMs(10 / 3.6, 2.6))     // Δt 936 ms + 400
        assertEquals(1600L, AxleSignature.decideWindowMs(5 / 3.6, 2.6))      // capped
        assertEquals(1200L, AxleSignature.decideWindowMs(Double.NaN, 2.6))
        assertEquals(1200L, AxleSignature.decideWindowMs(0.0, 2.6))
    }

    @Test fun noSpeedNoJudgement() {
        val s = pair(20.0, 2.6, 1).sample(50.0, 0.5, 4.2)
        for (speed in listOf(Double.NaN, 0.0, 0.5)) {
            val r = AxleSignature.analyze(s.t, s.v, null, 3000L, speed, 2.6)
            assertEquals(AxleVerdict.UNKNOWN, r.verdict)
            assertTrue(r.score.isNaN())
        }
    }

    private fun assertNoPair(r: AxleResult, what: String) {
        assertNotEquals(AxleVerdict.BOTH, r.verdict, "$what: ${describe(r)}")
        assertTrue(r.score.isNaN() || r.score <= 0.3, "$what: ${describe(r)}")
    }

    /** Sample [road] like the engine would see it at [rateHz] and judge the jolt at [T0], cut at the decision. */
    private fun judge(
        road: Road, kmh: Double, rateHz: Double, postMs: Long? = null, gyro: Boolean = true, fixes: List<Fix> = emptyList(),
    ): AxleResult {
        val v = kmh / 3.6
        val post = postMs ?: AxleSignature.decideWindowMs(v, 2.6)
        val s = road.sample(rateHz, T0 - 2.5, T0 + post / 1000.0 + 0.05)
        val trigger = s.t[s.t.indices.first { s.t[it] >= (T0 - 0.1) * 1000 && abs(s.v[it]) >= 3.0 }]
        val n = s.t.indices.last { s.t[it] <= trigger + post } + 1
        return AxleSignature.analyze(s.t.copyOf(n), s.v.copyOf(n), if (gyro) s.p.copyOf(n) else null, trigger, v, 2.6, fixes)
    }

    private fun describe(r: AxleResult) =
        "${r.verdict} score=${formatFixed(r.score, 2)} dt=${formatFixed(r.dtMs, 0)}/${formatFixed(r.expectedDtMs, 0)} " +
            "front=${formatFixed(r.frontPeak, 2)} rear=${formatFixed(r.rearPeak, 2)} pitch=${r.pitchOrder} ${r.reason}"
}

private const val T0 = 3.0

/** A bump hit by both axles: 3.5 + 0.1 × km/h m/s² at the front, 0.8 of it at the rear, pitching the car. */
private fun pair(kmh: Double, wheelbase: Double, seed: Long, pitch: Double = 0.3): Road {
    val v = kmh / 3.6
    val a = 3.5 + 0.1 * kmh
    return Road(seed).apply {
        hit(T0, a, width(v), pitch)
        hit(T0 + wheelbase / v, 0.8 * a, width(v), -pitch)
    }
}

/** One wheel's impulse: a 0.5 m bump plus the tyre, but never shorter than the suspension's 40 ms. */
private fun width(v: Double) = (0.5 / v).coerceIn(0.04, 0.12)

/**
 * A synthetic road as the phone feels it, a continuous signal so any sample rate sees the same thing: half-sine
 * impulses (each followed by a rebound of half its size), 16 random tones from 2 to 20 Hz of road noise, a slow
 * 0.7 Hz sway, and the car's nose-up pitch rate (a half-sine of 150 ms per impulse).
 */
private class Road(seed: Long, noise: Double = 0.25, private val sway: Double = 0.4) {
    private var s = seed * MUL + INC
    private fun next(): Double {
        s = s * MUL + INC
        return (s ushr 11).toDouble() / TWO_53
    }
    private val tones = List(16) { doubleArrayOf(2.0 + 18.0 * next(), 2 * PI * next()) }
    private val toneAmp = noise * sqrt(2.0 / 16)
    private val swayPhase = 2 * PI * next()
    private val hits = ArrayList<DoubleArray>()      // time s, amplitude m/s², width s
    private val pitches = ArrayList<DoubleArray>()   // time s, peak nose-up rate rad/s

    fun hit(atS: Double, amp: Double, widthS: Double, pitch: Double = 0.0) {
        hits.add(doubleArrayOf(atS, amp, widthS))
        if (pitch != 0.0) pitches.add(doubleArrayOf(atS, pitch))
    }

    fun v(t: Double): Double {
        var x = tones.sumOf { sin(2 * PI * it[0] * t + it[1]) } * toneAmp + sway * sin(2 * PI * 0.7 * t + swayPhase)
        for (h in hits) {
            val k = t - h[0]
            val w = h[2]
            if (k >= 0 && k < w) x += h[1] * sin(PI * k / w)
            else if (k >= w && k < 2 * w) x -= 0.5 * h[1] * sin(PI * (k - w) / w)
        }
        return x
    }

    fun p(t: Double): Double {
        var x = 0.01 * sin(2 * PI * 3.1 * t)
        for (h in pitches) {
            val k = t - h[0]
            if (k >= 0 && k < 0.15) x += h[1] * sin(PI * k / 0.15)
        }
        return x
    }

    fun sample(rateHz: Double, fromS: Double, toS: Double): Sampled {
        val n = ((toS - fromS) * rateHz).roundToLong().toInt() + 1
        val t = LongArray(n) { ((fromS + it / rateHz) * 1000.0).roundToLong() }
        return Sampled(t, DoubleArray(n) { v(t[it] / 1000.0) }, DoubleArray(n) { p(t[it] / 1000.0) })
    }

    class Sampled(val t: LongArray, val v: DoubleArray, val p: DoubleArray)

    private companion object {
        const val MUL = 6364136223846793005L
        const val INC = 1442695040888963407L
        const val TWO_53 = 9007199254740992.0
    }
}
