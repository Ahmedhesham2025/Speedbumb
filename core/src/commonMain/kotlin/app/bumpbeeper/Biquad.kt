package app.bumpbeeper

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * One second-order section (transposed direct form II): a Butterworth low- or high-pass made with the bilinear
 * transform. The corner is prewarped, so it lands where asked at any sample rate (50, 100 or 200 Hz), and it is kept
 * below [MAX_CORNER] × the rate: 20 Hz is fine at 50 Hz, but at 40 Hz it becomes 18 Hz instead of folding over Nyquist.
 */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    /** Start as if the input had been [x0] for ever, so a signal that doesn't start at 0 makes no start-up jolt. */
    fun reset(x0: Double) {
        val y0 = x0 * (b0 + b1 + b2) / (1.0 + a1 + a2)
        z2 = b2 * x0 - a2 * y0
        z1 = b1 * x0 - a1 * y0 + z2
    }

    fun step(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        /** The highest corner, as a fraction of the sample rate. */
        const val MAX_CORNER = 0.45
        private val Q = 1.0 / sqrt(2.0)

        fun lowPass(cornerHz: Double, rateHz: Double): Biquad {
            val k = tan(PI * min(cornerHz, MAX_CORNER * rateHz) / rateHz)
            val n = 1.0 / (1.0 + k / Q + k * k)
            val b0 = k * k * n
            return Biquad(b0, 2 * b0, b0, 2 * (k * k - 1) * n, (1 - k / Q + k * k) * n)
        }

        fun highPass(cornerHz: Double, rateHz: Double): Biquad {
            val k = tan(PI * min(cornerHz, MAX_CORNER * rateHz) / rateHz)
            val n = 1.0 / (1.0 + k / Q + k * k)
            return Biquad(n, -2 * n, n, 2 * (k * k - 1) * n, (1 - k / Q + k * k) * n)
        }

        /** [v] high-passed at [lowHz], then low-passed at [highHz], starting steady on its first value. */
        fun bandPass(v: DoubleArray, lowHz: Double, highHz: Double, rateHz: Double): DoubleArray {
            if (v.isEmpty()) return v
            val hp = highPass(lowHz, rateHz)
            val lp = lowPass(highHz, rateHz)
            hp.reset(v[0])
            lp.reset(0.0)
            return DoubleArray(v.size) { lp.step(hp.step(v[it])) }
        }

        /** Average sample rate of [t] (milliseconds), Hz; 0 when it can't be told. */
        fun rateHz(t: LongArray): Double =
            if (t.size < 2 || t[t.size - 1] <= t[0]) 0.0 else (t.size - 1) * 1000.0 / (t[t.size - 1] - t[0])
    }
}
