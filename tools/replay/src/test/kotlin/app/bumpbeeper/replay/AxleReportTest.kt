package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** The axle verdict histogram: a synthetic drive with known pairs and a knock, then the real drives in testdata (printed). */
class AxleReportTest {
    @Test fun pairsAndAKnock() {
        val jolts = AxleReport.of(drive())
        val md = AxleReport.toMarkdown(jolts, "synthetic")
        println(md)
        assertEquals(listOf("learned", "learned", "learned"), jolts.map { it.decision })
        for (j in jolts.take(2)) {
            assertEquals(AxleVerdict.BOTH, j.result.verdict)
            assertEquals(2.6 / (25 / 3.6) * 1000, j.result.dtMs, 37.0)    // Δt within 10 %
        }
        assertEquals(AxleVerdict.ONE, jolts[2].result.verdict)             // the knock, at a steady 25 km/h
        assertTrue(md, md.contains("| learned | 3 | 2 | 1 | 0 |"))
    }

    @Test fun realDrives() {
        val dir = File(System.getProperty("testdata.dir") ?: "../../testdata", "real")
        val drives = dir.listFiles { f -> f.name.endsWith(".csv.gz") || f.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()
        for (f in drives) {
            val jolts = AxleReport.of(TraceReader.read(readLines(f).asSequence()))
            println(AxleReport.toMarkdown(jolts, f.name))
            assertTrue(f.name, jolts.isNotEmpty())
        }
    }

    /**
     * Eastbound at a steady 25 km/h, phone lying flat, 100 Hz, 1 Hz GPS: bumps at 200 and 600 m hit by both axles of a
     * 2.6 m car (front 5 m/s², rear 4), and a knock at 1000 m with nothing after it.
     */
    private fun drive(): List<TraceSample> {
        val rnd = Random(11)
        val v = 25 / 3.6
        val bumps = listOf(200.0, 600.0).map { it / v }
        val knock = 1000.0 / v
        val gap = 2.6 / v
        val out = ArrayList<TraceSample>()
        var tMs = 0L
        while (tMs <= ((knock + 20) * 1000).toLong()) {
            val s = tMs / 1000.0
            var vert = rnd.nextGaussian() * 0.1 + pulse(s - knock, 6.0, 0.06)
            for (h in bumps) vert += pulse(s - h, 5.0, 0.1) + pulse(s - h - gap, 4.0, 0.1)
            out.add(TraceSample.Accel(tMs, 0.0, 0.0, 9.81 + vert, Double.NaN, Double.NaN, Double.NaN))
            if (tMs % 1000 == 0L) {
                val p = Geo.move(30.0444, 31.2357, 90.0, v * s)
                out.add(TraceSample.Gps(tMs, p[0], p[1], 25.0, 90.0, 5.0))
            }
            tMs += 10
        }
        return out
    }

    /** A half-sine of [amp] lasting [w] s, then a rebound of half its size. */
    private fun pulse(k: Double, amp: Double, w: Double) = when {
        k < 0 -> 0.0
        k < w -> amp * sin(PI * k / w)
        k < 2 * w -> -0.5 * amp * sin(PI * (k - w) / w)
        else -> 0.0
    }
}
