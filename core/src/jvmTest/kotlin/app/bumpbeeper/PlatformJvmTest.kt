package app.bumpbeeper

import java.util.Locale
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What iOS uses instead of a Java API must give Java's answers (checked here, where Java is available). */
class PlatformJvmTest {
    @Test fun portableFormatMatchesStringFormat() {
        val rnd = Random(1)
        val special = listOf(0.0, -0.0, 0.5, 1.5, 2.5, 0.05, 0.15, 0.25, 0.35, 1.005, 2.675, 1e-7, 123456.789, 9.9999, 1e21, 5e-324)
        val values = special + List(20_000) {
            val mag = Math.pow(10.0, rnd.nextInt(14) - 7.0)
            (rnd.nextDouble() - 0.5) * 2 * mag
        }
        for (x in values) for (d in 0..7) {
            assertEquals(String.format(Locale.US, "%.${d}f", x), formatFixedPortable(x, d), "x=$x digits=$d")
        }
    }

    @Test fun javaRandomPortMatchesJavaUtilRandom() {
        for (seed in listOf(0L, 1L, 42L, -7L, 123456789L)) {
            val j = Random(seed)
            val p = JavaRandomPort(seed)
            repeat(50_000) {
                assertEquals(j.nextDouble(), p.nextDouble())
                assertEquals(j.nextBoolean(), p.nextBoolean())
                val g = j.nextGaussian()
                assertTrue(Math.abs(g - p.nextGaussian()) <= 4 * Math.ulp(g), "seed $seed")
            }
        }
    }
}
