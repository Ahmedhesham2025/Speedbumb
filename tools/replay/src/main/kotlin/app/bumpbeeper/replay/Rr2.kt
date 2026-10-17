package app.bumpbeeper.replay

import app.bumpbeeper.TraceSample
import app.bumpbeeper.research.RateAverager
import app.bumpbeeper.research.ResearchFile
import app.bumpbeeper.research.ResearchFormat
import app.bumpbeeper.research.ResearchReader
import app.bumpbeeper.research.ResearchRecord
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** A phone-state line of a research recording (`scr`, `unl`, `aud`, `px`, `lx`), in the contract's units; NaN = empty. */
class PhoneLine(val tMs: Long, val code: String, val values: List<Double>)

/**
 * One trip of research recordings (rr2, contracts/research-rr2.md), its segments joined, turned into what the engine got
 * on the phone. While research records, BumpService hands the engine ≤ 100 Hz averages of the 200 Hz accelerometer and
 * gyroscope (the app's [RateAverager], bins of at least 10 ms, whole-millisecond times); GPS fixes keep their own time;
 * the driver's `lbl` taps become `label` events, as in the CSV traces. Gravity and the phone's own signals are kept.
 */
class Rr2Trip(
    /** The first segment's header (`placement`, `device`, `sensor.*`, …). */
    val meta: Map<String, String>,
    /** In time order: accelerometer bins carrying the latest gyroscope bin, GPS fixes, labels. For core's Replayer. */
    val samples: List<TraceSample>,
    /** `scr`, `unl`, `aud`, `px`, `lx` in time order. */
    val phone: List<PhoneLine>,
    /** The gravity sensor (`gr`): time ms, x, y, z in m/s². The engine finds gravity itself; kept for comparison. */
    val gravity: List<DoubleArray>,
    val segments: Int,
    /** The last segment carries the footer (`end_lines`): the trip was closed, not cut short. */
    val complete: Boolean,
    /** A segment was cut off mid-write (the app was killed); it was read up to its last flush. */
    val truncated: Boolean,
    /** Segment numbers missing between 0 and the last one given (the upload skips segments wholly in the privacy zone). */
    val missingSegments: List<Int> = emptyList(),
) {
    /** The user's placement setting while recording: mounted, cupholder, pocket or unknown. */
    val placement: String get() = meta["placement"] ?: "unknown"
}

object Rr2 {
    /** Codes of the phone's own state, for E3's PhoneSignals. */
    val PHONE_CODES = setOf("scr", "unl", "aud", "px", "lx")

    /** The engine's default sensor period (Prefs.sensorPeriodUs): 50 Hz asked, ≤ 100 Hz while research records. */
    const val DEFAULT_SENSOR_PERIOD_US = 20_000

    /**
     * A research recording (any format version): a `# format=…` line among the comment lines it starts with. Readers
     * map the header by key (the contract), so not only the first line counts. CSV traces have no `format` key.
     */
    fun isResearch(f: File): Boolean {
        val raw = f.inputStream().buffered()
        raw.mark(2)
        val gzip = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        val text = if (gzip) GZIPInputStream(raw) else raw
        return text.bufferedReader(Charsets.UTF_8).use { r ->
            r.lineSequence().takeWhile { it.startsWith("#") }.any { it.removePrefix("#").trim().substringBefore('=') == "format" }
        }
    }

    fun read(files: List<File>, sensorPeriodUs: Int = DEFAULT_SENSOR_PERIOD_US): Rr2Trip =
        readStreams(files.map { it.inputStream() }, sensorPeriodUs)

    /**
     * The segments of one trip, in any order, fed as the engine got them with [sensorPeriodUs] set
     * ([RateAverager.forPeriod]). Refuses other format versions, segments of different trips and a segment given twice.
     */
    fun readStreams(segments: List<InputStream>, sensorPeriodUs: Int = DEFAULT_SENSOR_PERIOD_US): Rr2Trip {
        val parts = segments.map { ResearchReader.read(it) }
        for (p in parts) require(p.meta["format"] == ResearchFormat.VERSION) { "not an rr2 recording: format=${p.meta["format"]}" }
        val trips = parts.map { listOf(it.meta[ResearchFormat.RESEARCH_ID], it.meta[ResearchFormat.START_UTC_MS]) }.distinct()
        require(trips.size == 1) { "segments of ${trips.size} different trips" }
        val missing = missingSegments(parts.map { segmentOf(it) })
        return convert(parts.sortedBy { segmentOf(it) }, sensorPeriodUs, missing)
    }

    fun segmentOf(p: ResearchFile): Int = p.meta[ResearchFormat.SEGMENT]?.toIntOrNull() ?: 0

    /** The numbers missing from 0 to the highest of [numbers]; a number given twice is refused. */
    fun missingSegments(numbers: List<Int>): List<Int> {
        val twice = numbers.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(twice.isEmpty()) { "segment ${twice.sorted().joinToString()} given twice" }
        return (0..(numbers.maxOrNull() ?: 0)).filter { it !in numbers }
    }

    private fun convert(parts: List<ResearchFile>, sensorPeriodUs: Int, missing: List<Int>): Rr2Trip {
        // Milliseconds as the phone's: the absolute monotonic time (start_elapsed_ns + t) rounded down, from the start's.
        val startNs = parts.first().meta[ResearchFormat.START_ELAPSED_NS]?.toLongOrNull()
        fun ms(t: Long): Long = if (startNs == null) Math.floorDiv(t, ResearchFormat.T_PER_MS)
            else Math.floorDiv(startNs + t * ResearchFormat.NS_PER_T, NS_PER_MS) - Math.floorDiv(startNs, NS_PER_MS)
        // Lines come in delivery order (GPS carries its own older time, batched sensors come late): sort each code by time.
        val byCode = parts.flatMap { it.records }.groupBy { it.code }.mapValues { (_, v) -> v.sortedBy { it.tDms } }
        fun of(code: String) = byCode[code].orEmpty()
        val out = ArrayList<TraceSample>()
        val gyro = averaged(of("g"), sensorPeriodUs, ::ms)
        var gi = -1
        for (a in averaged(of("a"), sensorPeriodUs, ::ms)) {
            // The engine keeps the latest gyroscope value; the CSV traces carry it on every accelerometer row the same way.
            while (gi + 1 < gyro.size && gyro[gi + 1][0] <= a[0]) gi++
            val g = if (gi >= 0) gyro[gi] else NO_GYRO
            out.add(TraceSample.Accel(a[0].toLong(), a[1], a[2], a[3], g[1], g[2], g[3]))
        }
        for (r in of("G")) {
            val lat = r.value(0)
            val lon = r.value(1)
            if (lat.isNaN() || lon.isNaN()) continue
            // speed m/s → km/h like the CSV traces; bearing degrees; horizontal accuracy m (empty → NaN → the service's 99).
            out.add(TraceSample.Gps(ms(r.tDms), lat, lon, r.value(3) * 3.6, r.value(4), r.value(5)))
        }
        // A `mark` (Diagnostics, the phone picked up) is its own event like in the debug trace: never a label, never undone.
        for (r in of("lbl")) out.add(
            if (r.text(0).trim() == MarkReport.MARK) TraceSample.Event(ms(r.tDms), MarkReport.MARK, -1, Double.NaN, "")
            else TraceSample.Event(ms(r.tDms), "label", -1, Double.NaN, r.text(0)),
        )
        val phone = PHONE_CODES.flatMap { c ->
            val n = ResearchFormat.code(c)?.scales?.size ?: 0
            of(c).map { r -> PhoneLine(ms(r.tDms), c, List(n) { r.value(it) }) }
        }.sortedBy { it.tMs }
        val gravity = of("gr").map { doubleArrayOf(ms(it.tDms).toDouble(), it.value(0), it.value(1), it.value(2)) }
        return Rr2Trip(
            parts.first().meta, out.sortedBy { it.tMs }, phone, gravity, parts.size,
            parts.last().meta.containsKey(ResearchFormat.END_LINES), parts.any { it.truncated }, missing,
        )
    }

    /** One 3-axis stream through the app's [RateAverager] for [periodUs]: (time ms, x, y, z) per bin, or per sample. */
    private fun averaged(lines: List<ResearchRecord>, periodUs: Int, ms: (Long) -> Long): List<DoubleArray> {
        val avg = RateAverager.forPeriod(periodUs)
        val out = ArrayList<DoubleArray>()
        for (r in lines) {
            val x = r.value(0)
            val y = r.value(1)
            val z = r.value(2)
            if (x.isNaN() || y.isNaN() || z.isNaN()) continue
            val t = ms(r.tDms)
            if (avg == null) out.add(doubleArrayOf(t.toDouble(), x, y, z))
            else if (avg.add(t, x, y, z)) out.add(doubleArrayOf(t.toDouble(), avg.x, avg.y, avg.z))
        }
        return out
    }

    private const val NS_PER_MS = 1_000_000L

    private val NO_GYRO = doubleArrayOf(0.0, Double.NaN, Double.NaN, Double.NaN)
}
