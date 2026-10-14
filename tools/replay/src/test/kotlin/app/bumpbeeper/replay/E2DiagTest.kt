package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Test
import java.io.File

class E2DiagTest {
    @Test fun drive01() {
        val f = File(System.getProperty("testdata.dir") ?: "../../testdata", "real/drive01_pocket.csv.gz")
        val samples = TraceReader.read(readLines(f).asSequence())
        for (placement in listOf("unknown", "pocket")) {
            val run = replaySamples(listOf(Recording(f.name, samples, placement))).single()
            println("E2DIAG $placement " + DriveSummary.of(run.result, placement).toMarkdown(placement).replace("\n", " "))
            for (e in run.result.events) if (e.type == "rejected" && !e.note.startsWith("handled") && !e.note.startsWith("phone_moving") || e.type == "hit_weak") {
                println("E2DIAG $placement t=${(e.wallTime - Replayer.WALL_BASE_MS) / 1000.0} ${e.type} ${String.format("%.1f", e.speedKmh)} km/h peak=${String.format("%.1f", e.peak)} ${e.note}")
            }
        }
    }
}
