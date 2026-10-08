package app.bumpbeeper

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The learned wheelbase: from 2.6 m to the car's own within five axle hits, kept in range, stored as text. */
class VehicleProfileTest {
    @Test fun convergesWithinFiveHits() {
        // Measurements scatter ±5 % around the true wheelbase (speed and timing errors), at different speeds.
        val scatter = listOf(1.04, 0.97, 1.02, 0.95, 1.03)
        val speeds = listOf(4.0, 6.0, 9.0, 12.0, 5.0)
        for (truth in listOf(2.2, 2.45, 2.9, 3.2)) {
            val p = VehicleProfile()
            assertEquals(2.6, p.wheelbaseM, 0.0)
            for (k in 0 until 5) assertTrue(p.learn(truth * scatter[k] / speeds[k] * 1000, speeds[k]))
            assertEquals(5, p.hits)
            assertTrue(abs(p.wheelbaseM - truth) <= 0.2, "truth $truth learned ${p.wheelbaseM}")
        }
    }

    @Test fun keepsToItsRange() {
        val p = VehicleProfile()
        repeat(30) { p.learn(3.7 / 5.0 * 1000, 5.0) }        // a van, or a steady speed error
        assertEquals(3.2, p.wheelbaseM, 1e-12)
        repeat(30) { p.learn(1.9 / 5.0 * 1000, 5.0) }
        assertEquals(2.2, p.wheelbaseM, 1e-12)
    }

    @Test fun ignoresImpossibleMeasurements() {
        val p = VehicleProfile()
        assertFalse(p.learn(1000.0, 5.0))       // 5 m: not two axles of one car
        assertFalse(p.learn(300.0, 5.0))        // 1.5 m
        assertFalse(p.learn(Double.NaN, 5.0))
        assertFalse(p.learn(500.0, Double.NaN))
        assertEquals(0, p.hits)
        assertEquals(2.6, p.wheelbaseM, 0.0)
    }

    @Test fun anotherCarIsLearnedToo() {
        // After 50 hits in a 2.4 m car, the phone rides in a 3.0 m one: the newest hit keeps a tenth of the weight.
        val p = VehicleProfile()
        repeat(50) { p.learn(2.4 / 6.0 * 1000, 6.0) }
        repeat(15) { p.learn(3.0 / 6.0 * 1000, 6.0) }
        assertTrue(abs(p.wheelbaseM - 3.0) <= 0.15, "${p.wheelbaseM}")
    }

    @Test fun storedAsText() {
        val p = VehicleProfile()
        p.learn(2.8 / 5.0 * 1000, 5.0)
        p.learn(2.9 / 5.0 * 1000, 5.0)
        val text = p.encode()
        assertEquals("2.7667;2", text)
        val q = VehicleProfile.decode(text)
        assertEquals(2.7667, q.wheelbaseM, 1e-9)
        assertEquals(2, q.hits)
        for (bad in listOf(null, "", "x;3", "9.5;4", "2,7;1")) {
            val r = VehicleProfile.decode(bad)
            assertEquals(2.6, r.wheelbaseM, 0.0, "$bad")
            assertEquals(0, r.hits, "$bad")
        }
        assertEquals(1, VehicleProfile.decode("2.5;-3").let { it.learn(2.5 / 5.0 * 1000, 5.0); it.hits })
    }
}
