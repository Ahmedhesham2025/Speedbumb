package app.bumpbeeper.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The engine's view while research asks for 200 Hz: bins of at least 10 ms, the way BumpService feeds it. */
class RateAveragerTest {
    @Test fun twoHundredHertzBecomesOneHundredOfPairAverages() {
        val a = RateAverager()
        val out = ArrayList<Triple<Long, Double, Double>>()
        for (i in 0 until 9) {
            val t = 5L * i
            if (a.add(t, i.toDouble(), -i.toDouble(), 9.8)) out.add(Triple(t, a.x, a.y))
        }
        // The first sample on its own, then every 10 ms the mean of the two samples since.
        assertEquals(listOf(0L, 10L, 20L, 30L, 40L), out.map { it.first })
        assertEquals(listOf(0.0, 1.5, 3.5, 5.5, 7.5), out.map { it.second })
        assertEquals(-1.5, out[1].third, 1e-12)
        assertEquals(9.8, a.z, 1e-12)
    }

    @Test fun ratesUpToOneHundredHertzPassUnchanged() {
        for (step in listOf(10L, 20L)) {   // 100 Hz and 50 Hz: what the engine gets without research
            val a = RateAverager()
            for (i in 0 until 20) {
                assertTrue("every sample at $step ms", a.add(step * i, i.toDouble(), 2.0 * i, 3.0))
                assertEquals(i.toDouble(), a.x, 0.0)
                assertEquals(2.0 * i, a.y, 0.0)
                assertEquals(3.0, a.z, 0.0)
            }
        }
    }

    @Test fun jitteryInputNeverComesOutFasterThanOneHundredHertz() {
        val a = RateAverager()
        val rnd = Random(3)
        var last = Long.MIN_VALUE / 2
        var bins = 0
        for (i in 0 until 2000) {
            val t = 5L * i + rnd.nextInt(3) - 1   // 200 Hz, ±1 ms
            if (a.add(t, 1.0, 2.0, 3.0)) {
                assertTrue("${t - last} ms apart", t - last >= 10)
                assertEquals("the mean of equal values is that value", 2.0, a.y, 1e-12)
                last = t
                bins++
            }
        }
        assertTrue("$bins bins of 2000 samples", bins in 600..1000)
    }
}
