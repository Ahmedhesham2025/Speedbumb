package app.bumpbeeper.replay

import app.bumpbeeper.*
import java.io.File
import java.util.zip.*
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  replay --trace run1.csv[.gz] [--trace run2.csv …] [--out metrics.json]
      replay labelled recordings (the runs of one route, oldest first) on one shared map, print the accuracy table;
      research recordings (rr2, rr_<id>_<start>_<segment>.csv.gz) are read too, the segments of a trip as one run;
      --placement mounted|cupholder|pocket|unknown (default) for every run, or "recording": each file's own setting
  anonymize --in raw.csv[.gz] --out anon.csv[.gz]      fake origin + clock at zero, before anything enters testdata/
      a research trip: --in rr_…_000.csv.gz [--in rr_…_001.csv.gz …]: also trimmed like the upload, ids and chips dropped"""

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    val traces = ArrayList<File>()
    val inputs = ArrayList<File>()
    var i = 1
    while (i < args.size) {
        if (args[i].startsWith("--") && i + 1 < args.size) {
            val key = args[i].removePrefix("--")
            when (key) {
                "trace" -> traces.add(File(args[i + 1]))
                "in" -> inputs.add(File(args[i + 1]))
                else -> opts[key] = args[i + 1]
            }
            i += 2
        } else usage()
    }
    when (args.firstOrNull()) {
        "replay" -> {
            if (traces.isEmpty()) usage()
            val runs = replaySamples(loadRecordings(traces, opts["placement"] ?: "unknown"))
            val report = Metrics.compute(runs)
            opts["out"]?.let { File(it).writeText(report.toJson()) }
            val title = if (traces.size == 1) traces[0].name else "${traces.size} runs (${traces.first().name} … ${traces.last().name})"
            println(report.toMarkdown(title))
            // Unlabelled drives score nothing above; these counts still show what the engine did.
            for (r in runs) println(DriveSummary.of(r.result, r.placement).toMarkdown(r.name))
            if (runs.any { it.labels.isNotEmpty() }) println(LabelConfusion.of(runs).toMarkdown(title))
        }
        "anonymize" -> {
            val out = File(opts["out"] ?: usage())
            if (inputs.isEmpty()) usage()
            val lines = if (Rr2.isResearch(inputs[0])) Rr2Anonymizer.anonymize(inputs) else Anonymizer.anonymize(readLines(inputs[0]))
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

/** One drive to replay: its samples and the phone placement to replay it with (mounted, cupholder, pocket, unknown). */
class Recording(val name: String, val samples: List<TraceSample>, val placement: String = "unknown")

/**
 * Recordings in the order given: a CSV trace is one run; research (rr2) files of one trip (same `rr_<id>_<start>`
 * name) are joined into one run, named after the trip. [placement] for all of them, or "recording": each file's own
 * setting (rr2 header `placement`, CSV `# placement=`).
 */
fun loadRecordings(files: List<File>, placement: String = "unknown"): List<Recording> {
    val out = ArrayList<Recording>()
    val trips = LinkedHashMap<String, MutableList<File>>()
    fun pick(own: String?) = if (placement == "recording") own ?: "unknown" else placement
    for (f in files) {
        if (Rr2.isResearch(f)) {
            val key = f.name.removeSuffix(".gz").removeSuffix(".csv").substringBeforeLast('_')
            if (key !in trips) out.add(Recording(key, emptyList()))   // keeps its place among the runs
            trips.getOrPut(key) { ArrayList() }.add(f)
        } else {
            val lines = readLines(f)
            val own = lines.firstOrNull { it.startsWith("# placement=") }?.substringAfter('=')?.trim()
            out.add(Recording(f.name, TraceReader.read(lines.asSequence()), pick(own)))
        }
    }
    return out.map { r ->
        val segments = trips[r.name] ?: return@map r
        val trip = Rr2.read(segments)
        if (!trip.complete || trip.truncated) System.err.println("${r.name}: cut short (no footer or a truncated segment)")
        Recording(r.name, trip.samples, pick(trip.meta["placement"]))
    }
}

/** [replaySamples] for CSV traces given as lines, all replayed with [placement]. */
fun replayRuns(recordings: List<Pair<String, List<String>>>, placement: String = "unknown"): List<Run> =
    replaySamples(recordings.map { (name, lines) -> Recording(name, TraceReader.read(lines.asSequence()), placement) })

/**
 * Replays recordings (name, samples) one after another on ONE map, like the same phone driving the route again:
 * a fresh engine per recording (one trip each), sharing a store that starts empty, like a fresh install.
 */
fun replaySamples(recordings: List<Recording>): List<Run> {
    val store = MemStore()
    return recordings.mapIndexed { k, rec ->
        val samples = rec.samples
        // TODO(E3 #123/#125): replay research trips' phone signals (Rr2Trip.phone) into engine.phone.signals once E3 has
        //  merged: scr → screenOn, unl → unlockedAtMs, px → proximityNear (< min(sensor max, 5 cm)), lx → lux,
        //  aud → handheldCall (mode 2 or 3 with call_device 1, the earpiece). Needs a per-sample hook in Replayer.
        val driving = DrivingConfig().apply { placement = rec.placement }
        val result = Replayer.replay(samples, store, drivingCfg = driving, tripId = k + 1L)
        Run(rec.name, Metrics.labels(samples), result, samples, rec.placement)
    }
}

/** In-memory bump map shared by the runs of one replay (starts empty, like a fresh install). */
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
