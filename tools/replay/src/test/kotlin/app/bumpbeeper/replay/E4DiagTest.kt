package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Test
import java.io.File

/** TEMPORARY: drive01 with the row rules on and off. Removed before review. */
class E4DiagTest {
    @Test fun drive01() {
        val f = File(System.getProperty("testdata.dir") ?: "../../testdata", "real/drive01_pocket.csv.gz")
        val samples = TraceReader.read(readLines(f).asSequence())
        val keep = setOf("new_bump", "rejected", "beep", "hit", "hit_repeat", "beep_grouped", "miss", "hit_weak")
        fun run(cfg: EngineConfig): List<String> = Replayer.replay(samples, MemStore(), cfg).events
            .filter { it.type in keep }
            .map { "t=" + (it.wallTime - Replayer.WALL_BASE_MS) / 1000.0 + " " + it.type + " " + String.format("%.1f", it.speedKmh) + "km/h " + it.note.take(70) }
        val now = run(EngineConfig())
        val old = run(EngineConfig().apply { rowMaxKmh = -1.0; rowMaxGapM = -1.0 })
        fun count(l: List<String>, type: String) = l.count { it.split(' ')[1] == type }
        println("E4DIAG now new=" + count(now, "new_bump") + " beep=" + count(now, "beep") + " rej=" + count(now, "rejected") +
            " | old new=" + count(old, "new_bump") + " beep=" + count(old, "beep") + " rej=" + count(old, "rejected"))
        val o = old.toSet()
        val n = now.toSet()
        for (e in now) if (e !in o) println("E4DIAG + $e")
        for (e in old) if (e !in n) println("E4DIAG - $e")
    }
}
