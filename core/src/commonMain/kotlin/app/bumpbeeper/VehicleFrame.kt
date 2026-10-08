package app.bumpbeeper

import kotlin.math.sqrt

/**
 * The car's axes in phone coordinates: up from gravity, forward from the GPS-learned direction of travel
 * ([BumpEngine.forwardVector]) made square to up, and left = up × forward. Turns phone-axis accelerometer and gyroscope
 * readings into the car's vertical, longitudinal and lateral acceleration and its pitch and roll rate. While forward
 * is unknown only [vertical] works; the others are NaN.
 */
class VehicleFrame private constructor(
    /** Unit vectors in phone axes. [forward] and [left] are null while the forward direction is unknown. */
    val up: DoubleArray,
    val forward: DoubleArray?,
    val left: DoubleArray?,
    /** Strength of the gravity this frame was made from, m/s²; [vertical] subtracts it. */
    val gravity: Double,
) {
    val hasForward: Boolean get() = forward != null

    /** Vertical acceleration with gravity removed, m/s², up positive (the engine's jolt signal). */
    fun vertical(ax: Double, ay: Double, az: Double): Double = dot(up, ax, ay, az) - gravity

    /** Along the car, m/s², speeding up positive. NaN while forward is unknown. */
    fun longitudinal(ax: Double, ay: Double, az: Double): Double = forward?.let { dot(it, ax, ay, az) } ?: Double.NaN

    /** Across the car, m/s², to the left positive. NaN while forward is unknown. */
    fun lateral(ax: Double, ay: Double, az: Double): Double = left?.let { dot(it, ax, ay, az) } ?: Double.NaN

    /** Pitch rate from the gyroscope (rad/s, phone axes), nose up positive. NaN while forward is unknown. */
    fun pitchRate(gx: Double, gy: Double, gz: Double): Double = left?.let { -dot(it, gx, gy, gz) } ?: Double.NaN

    /** Roll rate, left side up positive. NaN while forward is unknown. */
    fun rollRate(gx: Double, gy: Double, gz: Double): Double = forward?.let { dot(it, gx, gy, gz) } ?: Double.NaN

    companion object {
        /**
         * The frame for [gravity] (the accelerometer's slow average, phone axes) and [forward] (any length, null =
         * unknown), or null when gravity is weaker than [VehicleConfig.minGravity] (free fall or garbage). Forward
         * counts only if it leans at least [VehicleConfig.forwardMinSine] away from up; closer to vertical it says
         * nothing about the car's nose, and the frame falls back to vertical only.
         */
        fun of(gravity: DoubleArray, forward: DoubleArray?, cfg: VehicleConfig = VehicleConfig()): VehicleFrame? {
            val g = norm(gravity[0], gravity[1], gravity[2])
            if (!(g >= cfg.minGravity)) return null
            val u = doubleArrayOf(gravity[0] / g, gravity[1] / g, gravity[2] / g)
            if (forward == null) return VehicleFrame(u, null, null, g)
            val fn = norm(forward[0], forward[1], forward[2])
            val d = dot(u, forward[0], forward[1], forward[2])
            val hx = forward[0] - d * u[0]
            val hy = forward[1] - d * u[1]
            val hz = forward[2] - d * u[2]
            val h = norm(hx, hy, hz)
            if (!(fn > 0.0) || !(h >= cfg.forwardMinSine * fn)) return VehicleFrame(u, null, null, g)
            val f = doubleArrayOf(hx / h, hy / h, hz / h)
            val l = doubleArrayOf(u[1] * f[2] - u[2] * f[1], u[2] * f[0] - u[0] * f[2], u[0] * f[1] - u[1] * f[0])
            return VehicleFrame(u, f, l, g)
        }

        private fun dot(a: DoubleArray, x: Double, y: Double, z: Double) = a[0] * x + a[1] * y + a[2] * z
        private fun norm(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
    }
}
