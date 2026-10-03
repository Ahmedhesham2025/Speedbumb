package app.bumpbeeper

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * `java.util.Random` (48-bit LCG, polar Gaussian) in plain Kotlin, for platforms without it. Doubles and booleans
 * match Java bit for bit; Gaussians can differ in the last bit, as Java uses `StrictMath.log` (checked in jvmTest).
 */
class JavaRandomPort(seed: Long) {
    private var seed = (seed xor MULTIPLIER) and MASK
    private var nextNextGaussian = 0.0
    private var haveNextNextGaussian = false
    private fun next(bits: Int): Int {
        seed = (seed * MULTIPLIER + 0xBL) and MASK
        return (seed ushr (48 - bits)).toInt()
    }

    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * DOUBLE_UNIT
    fun nextBoolean(): Boolean = next(1) != 0
    fun nextGaussian(): Double {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false
            return nextNextGaussian
        }
        var v1: Double; var v2: Double; var s: Double
        do {
            v1 = 2 * nextDouble() - 1
            v2 = 2 * nextDouble() - 1
            s = v1 * v1 + v2 * v2
        } while (s >= 1 || s == 0.0)
        val multiplier = sqrt(-2 * ln(s) / s)
        nextNextGaussian = v2 * multiplier
        haveNextNextGaussian = true
        return v1 * multiplier
    }

    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val MASK = (1L shl 48) - 1
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}
