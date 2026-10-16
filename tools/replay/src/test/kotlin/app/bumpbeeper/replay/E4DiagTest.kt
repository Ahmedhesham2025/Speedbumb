package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Test
import java.io.File

class E4DiagTest {
    @Test fun drive01() {
        val f = File(System.getProperty("testdata.dir") ?: "../../testdata", "real/drive01_pocket.csv.gz")
        val samples = TraceReader.read(readLines(f).asSequence())
        val run = replaySamples(listOf(Recording(f.name, samples, "unknown"))).single()
        for (e in run.result.events) {
            if (e.note.contains("down_first") || e.type == "cross_v" || (e.type == "new_bump" && "polarity=" in e.note) || e.type == "hit") {
                println("E4DIAG t=${(e.wallTime - Replayer.WALL_BASE_MS) / 1000.0} ${e.type} ${String.format("%.1f", e.speedKmh)} km/h peak=${String.format("%.1f", e.peak)} ${e.note.substringAfter("axle=", "").let { "axle=$it" }}")
            }
        }
    }
}
