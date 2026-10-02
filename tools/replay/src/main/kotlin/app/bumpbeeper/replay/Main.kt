package app.bumpbeeper.replay

import app.bumpbeeper.*
import java.io.File
import java.util.zip.*
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  replay --trace drive.csv[.gz] [--out metrics.json]   replay a labelled recording, print the accuracy table
  anonymize --in raw.csv[.gz] --out anon.csv[.gz]      fake origin + clock at zero, before anything enters testdata/"""

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 1
    while (i < args.size) {
        if (args[i].startsWith("--") && i + 1 < args.size) { opts[args[i].removePrefix("--")] = args[i + 1]; i += 2 } else usage()
    }
    when (args.firstOrNull()) {
        "replay" -> {
            val trace = File(opts["trace"] ?: usage())
            val samples = TraceReader.read(readLines(trace).asSequence())
            val result = Replayer.replay(samples, MemStore())
            val report = Metrics.compute(TraceReader.labels(samples), result, samples)
            opts["out"]?.let { File(it).writeText(report.toJson()) }
            println(report.toMarkdown(trace.name))
        }
        "anonymize" -> {
            val out = File(opts["out"] ?: usage())
            val lines = Anonymizer.anonymize(readLines(File(opts["in"] ?: usage())))
            val stream = out.outputStream().let { if (out.name.endsWith(".gz")) GZIPOutputStream(it) else it }
            stream.bufferedWriter(Charsets.UTF_8).use { w -> lines.forEach { w.write(it); w.write("\n") } }
            println("Wrote ${lines.size} lines to ${out.path}")
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}

fun readLines(f: File): List<String> {
    val stream = f.inputStream().let { if (f.name.endsWith(".gz")) GZIPInputStream(it) else it }
    return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
}

/** In-memory bump map for one replay (the engine starts with an empty map, like a fresh install). */
class MemStore : BumpStore {
    private val bumps = ArrayList<Bump>()
    private var nextId = 1L

    override fun loadBumps(): List<Bump> = bumps.map { it.copy() }
    override fun insertBump(b: Bump): Long {
        val c = b.copy()
        c.id = nextId++
        bumps.add(c)
        return c.id
    }
    override fun updateBump(b: Bump) {
        val i = bumps.indexOfFirst { it.id == b.id }
        if (i >= 0) bumps[i] = b.copy()
    }
    override fun logEvent(e: BumpEvent) {}   // Replayer already collects the events
}
