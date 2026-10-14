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
) {
    /** The user's placement setting while recording: mounted, cupholder, pocket or unknown. */
    val placement: String get() = meta["placement"] ?: "unknown"
}

object Rr2 {
    /** Codes of the phone's own state, for E3's PhoneSignals. */
    val PHONE_CODES = setOf("scr", "unl", "aud", "px", "lx")

    /** A research recording (any format version): its first line is `# format=…`. CSV traces start otherwise. */
    fun isResearch(f: File): Boolean {
        val raw = f.inputStream().buffered()
        raw.mark(2)
        val gzip = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        val text = if (gzip) GZIPInputStream(raw) else raw
        return text.bufferedReader(Charsets.UTF_8).use { it.readLine()?.startsWith("# format=") == true }
    }

    fun read(files: List<File>): Rr2Trip = readStreams(files.map { it.inputStream() })

    /** The segments of one trip, in any order. Refuses other format versions and segments of different trips. */
    fun readStreams(segments: List<InputStream>): Rr2Trip {
        val parts = segments.map { ResearchReader.read(it) }
        for (p in parts) require(p.meta["format"] == ResearchFormat.VERSION) { "not an rr2 recording: format=${p.meta["format"]}" }
        val trips = parts.map { listOf(it.meta[ResearchFormat.RESEARCH_ID], it.meta[ResearchFormat.START_UTC_MS]) }.distinct()
        require(trips.size == 1) { "segments of ${trips.size} different trips" }
        val ordered = parts.sortedBy { it.meta[ResearchFormat.SEGMENT]?.toIntOrNull() ?: 0 }
        return convert(ordered)
    }

    private fun convert(parts: List<ResearchFile>): Rr2Trip {
        // Lines come in delivery order (GPS carries its own older time, batched sensors come late): sort each code by time.
        val byCode = parts.flatMap { it.records }.groupBy { it.code }.mapValues { (_, v) -> v.sortedBy { it.tDms } }
        fun of(code: String) = byCode[code].orEmpty()
        val out = ArrayList<TraceSample>()
        val gyro = averaged(of("g"))
        var gi = -1
        for (a in averaged(of("a"))) {
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
        for (r in of("lbl")) out.add(TraceSample.Event(ms(r.tDms), "label", -1, Double.NaN, r.text(0)))
        val phone = PHONE_CODES.flatMap { c ->
            val n = ResearchFormat.code(c)?.scales?.size ?: 0
            of(c).map { r -> PhoneLine(ms(r.tDms), c, List(n) { r.value(it) }) }
        }.sortedBy { it.tMs }
        val gravity = of("gr").map { doubleArrayOf(ms(it.tDms).toDouble(), it.value(0), it.value(1), it.value(2)) }
        return Rr2Trip(
            parts.first().meta, out.sortedBy { it.tMs }, phone, gravity, parts.size,
            parts.last().meta.containsKey(ResearchFormat.END_LINES), parts.any { it.truncated },
        )
    }

    /** One 3-axis stream through the app's [RateAverager]: (time ms, x, y, z) per finished bin. */
    private fun averaged(lines: List<ResearchRecord>): List<DoubleArray> {
        val avg = RateAverager()
        val out = ArrayList<DoubleArray>()
        for (r in lines) {
            val x = r.value(0)
            val y = r.value(1)
            val z = r.value(2)
            if (x.isNaN() || y.isNaN() || z.isNaN()) continue
            val t = ms(r.tDms)
            if (avg.add(t, x, y, z)) out.add(doubleArrayOf(t.toDouble(), avg.x, avg.y, avg.z))
        }
        return out
    }

    /** Whole milliseconds since the trip start, rounded down like the app's sensor times. */
    private fun ms(tDms: Long): Long = Math.floorDiv(tDms, ResearchFormat.T_PER_MS)

    private val NO_GYRO = doubleArrayOf(0.0, Double.NaN, Double.NaN, Double.NaN)
}
