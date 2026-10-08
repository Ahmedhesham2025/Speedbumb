package app.bumpbeeper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The car's axes in the phone: orthonormal whatever the tilt, vertical-only when forward says nothing. */
class VehicleFrameTest {
    @Test fun orthonormalAtAnyTilt() {
        // Gravity and a forward guess in many directions, forward 15° to 90° off up (and not square to it).
        for (a in 0 until 12) for (b in 0 until 6) for (lean in listOf(15.0, 40.0, 75.0, 90.0)) {
            val up = dir(a * 30.0, b * 30.0 - 75.0)
            val f = tilted(up, lean, a * 17.0)
            val frame = assertNotNull(VehicleFrame.of(scale(up, 9.81), scale(f, 0.7)))
            assertTrue(frame.hasForward)
            assertOrthonormal(frame)
        }
    }

    @Test fun forwardAlmostAlongUpFallsBackToVertical() {
        val up = dir(20.0, 35.0)
        for (lean in listOf(0.0, 1e-7, 1.0, 10.0)) {
            val frame = assertNotNull(VehicleFrame.of(scale(up, 9.81), tilted(up, lean, 50.0)))
            assertFalse(frame.hasForward, "$lean°")
            assertEquals(1.0, norm(frame.up), 1e-12)
            assertTrue(frame.longitudinal(1.0, 2.0, 3.0).isNaN())
            assertTrue(frame.pitchRate(0.1, 0.2, 0.3).isNaN())
            assertEquals(0.0, frame.vertical(up[0] * 9.81, up[1] * 9.81, up[2] * 9.81), 1e-9)
        }
        // Just past the limit (sine 0.25 ≈ 14.5°) forward counts again, still square.
        val frame = assertNotNull(VehicleFrame.of(scale(up, 9.81), tilted(up, 15.0, 50.0)))
        assertTrue(frame.hasForward)
        assertOrthonormal(frame)
        assertTrue(VehicleFrame.of(scale(up, 9.81), null)?.hasForward == false)
        assertTrue(VehicleFrame.of(scale(up, 9.81), doubleArrayOf(Double.NaN, 0.0, 0.0))?.hasForward == false)
    }

    @Test fun noGravityNoFrame() {
        assertNull(VehicleFrame.of(doubleArrayOf(0.0, 0.0, 0.0), null))
        assertNull(VehicleFrame.of(doubleArrayOf(1.0, 1.0, 1.0), null))       // free fall
        assertNull(VehicleFrame.of(doubleArrayOf(Double.NaN, 9.8, 0.0), null))
    }

    @Test fun carAxesFromPhoneAxes() {
        // Phone lying flat, top towards the nose: phone x = right, y = forward, z = up.
        val frame = assertNotNull(VehicleFrame.of(doubleArrayOf(0.0, 0.0, 9.81), doubleArrayOf(0.0, 1.0, 0.0)))
        assertEquals(2.0, frame.vertical(0.0, 0.0, 11.81), 1e-12)
        assertEquals(1.5, frame.longitudinal(0.0, 1.5, 9.81), 1e-12)     // speeding up
        assertEquals(-0.7, frame.lateral(0.7, 0.0, 9.81), 1e-12)         // pushed right
        // Nose up = turning about the car's right side (phone +x) counter-clockwise.
        assertEquals(0.3, frame.pitchRate(0.3, 0.0, 0.0), 1e-12)
        assertEquals(0.2, frame.rollRate(0.0, 0.2, 0.0), 1e-12)          // left side up
    }

    private fun assertOrthonormal(fr: VehicleFrame) {
        val u = fr.up
        val f = fr.forward!!
        val l = fr.left!!
        for (v in listOf(u, f, l)) assertEquals(1.0, norm(v), 1e-9)
        assertEquals(0.0, dot(u, f), 1e-9)
        assertEquals(0.0, dot(u, l), 1e-9)
        assertEquals(0.0, dot(f, l), 1e-9)
        // Right-handed: forward × left = up.
        val c = doubleArrayOf(f[1] * l[2] - f[2] * l[1], f[2] * l[0] - f[0] * l[2], f[0] * l[1] - f[1] * l[0])
        for (k in 0..2) assertEquals(u[k], c[k], 1e-9)
    }

    /** Unit vector from two angles (degrees). */
    private fun dir(azDeg: Double, elDeg: Double): DoubleArray {
        val az = azDeg * PI / 180
        val el = elDeg * PI / 180
        return doubleArrayOf(cos(el) * cos(az), cos(el) * sin(az), sin(el))
    }

    /** A unit vector [leanDeg] away from [up], turned [spinDeg] about it. */
    private fun tilted(up: DoubleArray, leanDeg: Double, spinDeg: Double): DoubleArray {
        // Any vector square to up, then turn it about up and lean away from up.
        val seed = if (abs(up[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val d = dot(seed, up)
        val p1 = DoubleArray(3) { seed[it] - d * up[it] }.let { scale(it, 1 / norm(it)) }
        val p2 = doubleArrayOf(up[1] * p1[2] - up[2] * p1[1], up[2] * p1[0] - up[0] * p1[2], up[0] * p1[1] - up[1] * p1[0])
        val s = spinDeg * PI / 180
        val l = leanDeg * PI / 180
        return DoubleArray(3) { cos(l) * up[it] + sin(l) * (cos(s) * p1[it] + sin(s) * p2[it]) }
    }

    private fun scale(v: DoubleArray, k: Double) = DoubleArray(3) { v[it] * k }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(v: DoubleArray) = sqrt(dot(v, v))
}
