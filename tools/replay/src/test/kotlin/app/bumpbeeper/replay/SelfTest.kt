package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

/**
 * A synthetic drive with known bumps and potholes, labelled the way a passenger would (0.6 s after the wheels hit),
 * goes through CSV → TraceReader → Replayer → Metrics, like a real recording. Every hazard must be found.
 */
class SelfTest {

    /** Eastbound straight road at 30 km/h, phone lying flat, 50 Hz accelerometer, exact 1 Hz GPS, no gyroscope. */
    private fun syntheticDrive(bumpsAt: List<Double>, potholesAt: List<Double>): List<TraceSample> {
        val rnd = Random(7)
        val v = 30 / 3.6
        val hits = bumpsAt.map { Pair((it / v * 1000).toLong(), false) } + potholesAt.map { Pair((it / v * 1000).toLong(), true) }
        val endMs = ((bumpsAt + potholesAt).max() / v * 1000).toLong() + 20_000
        val out = ArrayList<TraceSample>()
        var tMs = 0L
        while (tMs <= endMs) {
            var vert = rnd.nextGaussian() * 0.1
            for ((h, pothole) in hits) {
                val tau = (tMs - h) / 1000.0
                vert += when {
                    tau < 0 -> 0.0
                    pothole && tau < 0.06 -> -4.2 * sin(PI * tau / 0.06)          // wheel drops in…
                    pothole && tau < 0.11 -> 7.0 * sin(PI * (tau - 0.06) / 0.05)  // …and slams the far edge
                    !pothole && tau < 0.1 -> 5.0 * sin(PI * tau / 0.1)            // bump: pushed up…
                    !pothole && tau < 0.2 -> -3.5 * sin(PI * (tau - 0.1) / 0.1)   // …and back down
                    else -> 0.0
                }
            }
            out.add(TraceSample.Accel(tMs, 0.0, 0.0, 9.81 + vert, Double.NaN, Double.NaN, Double.NaN))
            if (tMs % 1000 == 0L) {
                val p = Geo.move(30.0444, 31.2357, 90.0, v * tMs / 1000.0)
                out.add(TraceSample.Gps(tMs, p[0], p[1], 30.0, 90.0, 5.0))
            }
            tMs += 20
        }
        for ((h, pothole) in hits) out.add(TraceSample.Event(h + 600, "label", -1, Double.NaN, if (pothole) "pothole_r" else "bump"))
        // A mistaken tap, undone straight away: must not count.
        out.add(TraceSample.Event(5000, "label", -1, Double.NaN, "bump"))
        out.add(TraceSample.Event(5500, "label", -1, Double.NaN, "undo"))
        return out.sortedBy { it.tMs }
    }

    private fun metricsOf(csv: String): MetricsReport {
        val samples = TraceReader.read(csv.lineSequence())
        val result = Replayer.replay(samples, MemStore())
        val report = Metrics.compute(TraceReader.labels(samples), result, samples)
        println(report.toMarkdown("synthetic"))
        return report
    }

    private val drive = syntheticDrive(bumpsAt = listOf(200.0, 600.0, 1000.0), potholesAt = listOf(400.0, 800.0))

    @Test fun knownHazardsGivePerfectScores() {
        val r = metricsOf(TraceWriterCore.toCsv(drive, startMs = 0))
        assertEquals(5, r.labels)
        assertEquals(5, r.detections)
        assertEquals(1.0, r.precision, 1e-9)
        assertEquals(1.0, r.recall, 1e-9)
        assertEquals(1.0, r.recallFast, 1e-9)
        assertEquals(1.0, r.kindAccuracy, 1e-9)
        assertEquals(0, r.falseWarnings)
        assertEquals(1.2, r.distanceKm, 0.1)
    }

    @Test fun anonymizedDriveScoresTheSame() {
        val raw = "# device=Acme/Phone1\n# gyro=none\n" + TraceWriterCore.toCsv(drive, startMs = 0)
        val anon = Anonymizer.anonymize(raw.lines())
        val r = metricsOf(anon.joinToString("\n"))
        assertEquals(1.0, r.precision, 1e-9)
        assertEquals(1.0, r.recall, 1e-9)
        assertEquals(1.0, r.kindAccuracy, 1e-9)
    }
}
