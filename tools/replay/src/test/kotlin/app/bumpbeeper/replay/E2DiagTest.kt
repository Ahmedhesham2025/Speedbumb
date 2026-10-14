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
            val ev = run.result.events
            println("E2DIAG $placement types " + ev.groupingBy { it.type }.eachCount())
            println("E2DIAG $placement axle " + ev.filter { "axle=" in it.note }.groupingBy { it.type + ":" + it.note.substringAfter("axle=").substringBefore(' ') }.eachCount())
            println("E2DIAG $placement rejects " + ev.filter { it.type == "rejected" }.groupingBy { it.note.substringBefore(' ') }.eachCount())
        }
    }
}
