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
        val report = Metrics.compute(Metrics.labels(samples), result, samples)
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
        assertEquals(0, r.falseWarnings)
        assertEquals(1.2, r.distanceKm, 0.1)
    }

    @Test fun anonymizedDriveScoresTheSame() {
        val raw = "# device=Acme/Phone1\n# gyro=none\n" + TraceWriterCore.toCsv(drive, startMs = 0)
        val anon = Anonymizer.anonymize(raw.lines())
        val r = metricsOf(anon.joinToString("\n"))
        assertEquals(1.0, r.precision, 1e-9)
        assertEquals(1.0, r.recall, 1e-9)
    }

    // ---------- core's simulator: gyroscope, phone tilted in a holder, GPS 0.8 s late with ±3 m noise ----------

    /** Bump at 300 m (driver slows to 15 km/h), harsh potholes at 700 m (right) and 1100 m (left) hit at 40 km/h. */
    private val spec = DriveSpec(bumpsAt = listOf(300.0), potholesAt = listOf(700.0), potholesLeftAt = listOf(1100.0), cruiseKmh = 40.0)
    private val truth = listOf(300.0 to "bump", 700.0 to "pothole_r", 1100.0 to "pothole_l")

    /**
     * Records one simulated drive (sensor rows only, like the app's recording) and adds the passenger's taps
     * [tapDelayMs] after the front wheels hit each hazard. 0.9 s at 40 km/h puts the tap 10 m past the spot.
     */
    private fun simulatedRun(sim: Simulator, tapDelayMs: Long = 900): List<String> {
        val rec = ArrayList<TraceSample>()
        sim.drive(MemoryStore(), spec, recorder = rec)
        val sensors = rec.filter { it !is TraceSample.Event }   // the simulator's own engine events are not replayed
        val fixes = sensors.filterIsInstance<TraceSample.Gps>()
        val accel = sensors.filterIsInstance<TraceSample.Accel>()
        val start = sim.point(0.0, false)
        val taps = truth.map { (pos, kind) ->
            // The first fix past the spot comes 0.8–1.8 s after the wheels hit it; the hit is the strongest jolt around then.
            val f = fixes.first { Geo.distance(start[0], start[1], it.lat, it.lon) >= pos }
            val hit = accel.filter { it.tMs in f.tMs - 3000..f.tMs + 1000 }
                .maxBy { abs(sqrt(it.ax * it.ax + it.ay * it.ay + it.az * it.az) - 9.81) }
            TraceSample.Event(hit.tMs + tapDelayMs, "label", -1, Double.NaN, kind)
        }
        return TraceWriterCore.toCsv((sensors + taps).sortedBy { it.tMs }, startMs = 0).lines()
    }

    @Test fun gyroscopeAndFastHits() {
        val runs = replayRuns(listOf("sim-gyro" to simulatedRun(Simulator(41))))
        val r = Metrics.compute(runs)
        println(r.toMarkdown("simulated, gyroscope, potholes at 40 km/h"))
        val dets = Metrics.detections(runs[0].result.events)
        for (l in runs[0].labels) for (d in dets) println(String.format(java.util.Locale.US, "  label %s t=%d  det %s t=%d  %.1f m", l.kind, l.tMs, d.band, d.tMs, Geo.distance(l.lat, l.lon, d.lat, d.lon)))
        assertEquals(3, r.labels)
        assertEquals(3, r.detections)
        assertEquals(1.0, r.precision, 1e-9)
        assertEquals(1.0, r.recall, 1e-9)
        assertEquals(2, r.labelsFast)                  // both potholes, hit at 40 km/h
        assertEquals(1.0, r.recallFast, 1e-9)
    }

    @Test fun twoRunsOnOneMap() {
        val sim = Simulator(41)
        val runs = replayRuns(listOf("run1" to simulatedRun(sim), "run2" to simulatedRun(sim)))
        val r = Metrics.compute(runs)
        println(r.toMarkdown("simulated, 2 runs"))
        println(r.toJson())
        assertEquals(2, r.runs.size)
        assertEquals(0, r.runs[0].beeps)               // empty map: nothing to warn about on the first run
        assertTrue("run 2 should warn", r.runs[1].beeps > 0)
        assertEquals(0, r.runs[1].falseWarnings)
        assertEquals(0, r.falseWarnings)
        assertEquals(3, r.spots)
        assertEquals(1.0, r.learnedRate, 1e-9)
        assertEquals(6, r.labels)
        assertEquals(1.0, r.recall, 1e-9)
        assertTrue(r.toJson().contains("\"runs\": ["))
    }
}
