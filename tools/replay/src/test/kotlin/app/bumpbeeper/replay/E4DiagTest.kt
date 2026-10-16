package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Test
import java.io.File

class E4DiagTest {
    @Test fun drive01() {
        val f = File(System.getProperty("testdata.dir") ?: "../../testdata", "real/drive01_pocket.csv.gz")
        val samples = TraceReader.read(readLines(f).asSequence())
        fun run(cfg: EngineConfig): List<String> = Replayer.replay(samples, MemStore(), cfg).events
            .filter { it.type in setOf("new_bump", "rejected", "beep", "hit", "hit_repeat", "beep_grouped", "miss") }
            .map { "t=${(it.wallTime - Replayer.WALL_BASE_MS) / 1000.0} ${it.type} #${it.bumpId} ${String.format("%.1f", it.speedKmh)} km/h ${it.note.take(60)}" }
        val now = run(EngineConfig())
        val old = run(EngineConfig().apply { rowMaxKmh = -1.0; rowMaxGapM = -1.0 })
        println("E4DIAG counts now new=${now.count { " new_bump " in it }} beep=${now.count { " beep " in it }} rej=${now.count { " rejected " in it }}; old new=${old.count { " new_bump " in it }} beep=${old.count { " beep " in it }} rej=${old.count { " rejected " in it }}")
        val strip = { s: String -> s.replace(Regex("#-?\d+ "), "") }
        val o = old.map(strip).toSet()
        val n = now.map(strip).toSet()
        for (e in now) if (strip(e) !in o) println("E4DIAG + $e")
        for (e in old) if (strip(e) !in n) println("E4DIAG - $e")
    }
}
