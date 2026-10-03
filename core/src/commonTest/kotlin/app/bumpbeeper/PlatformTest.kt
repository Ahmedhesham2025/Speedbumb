package app.bumpbeeper

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The platform helpers give the JVM's answers on every platform. */
class PlatformTest {
    @Test fun formatsLikeJava() {
        for (f in listOf(::formatFixed, ::formatFixedPortable)) {
            assertEquals("1.235", f(1.23456, 3))
            assertEquals("-9.800", f(-9.8, 3))
            assertEquals("1", f(0.5, 0))           // half up, not half even
            assertEquals("0.1", f(0.05, 1))
            assertEquals("10.0", f(9.99, 1))
            assertEquals("0.001", f(0.0006, 3))
            assertEquals("0.000", f(1.0E-5, 3))
            assertEquals("12345678901.00", f(1.2345678901E10, 2))
            assertEquals("-0.00", f(-0.001, 2))
            assertEquals("0.00", f(0.0, 2))
            assertEquals("NaN", f(Double.NaN, 1))
        }
    }

    @Test fun roundsLikeJava() {
        assertEquals(3L, javaRound(2.5))
        assertEquals(-2L, javaRound(-2.5))
        assertEquals(0L, javaRound(0.49999999999999994))
        assertEquals(0L, javaRound(Double.NaN))
        assertEquals(Long.MAX_VALUE, javaRound(1e300))
    }

    @Test fun angles() {
        assertEquals(PI, degToRad(180.0), 1e-15)
        assertEquals(180.0, radToDeg(PI), 1e-12)
    }

    @Test fun uuids() {
        val a = randomUuid()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(a), a)
        assertTrue(a != randomUuid())
    }
}
