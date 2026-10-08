package app.bumpbeeper.replay

import app.bumpbeeper.TripPrivacy
import app.bumpbeeper.research.ResearchFile
import app.bumpbeeper.research.ResearchFormat
import app.bumpbeeper.research.ResearchReader
import app.bumpbeeper.research.ResearchRecord
import app.bumpbeeper.research.ResearchTrim
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Makes a research trip (rr2, contracts/research-rr2.md) safe to commit, the way [Anonymizer] does for CSV traces:
 *  - **trim:** every line outside the app's own upload window ([ResearchTrim.window], #115: the first and last 300 m
 *    driven and 300 m around where the trip started and ended; never less);
 *  - **place:** the first kept fix moves to the fake origin (0.5, 0.5); every other one keeps its distance and bearing
 *    from it ([Anonymizer.shift]), so headings and distances stay true. Altitude becomes relative to that fix;
 *  - **time:** the kept part starts at t = 0; the wall clock starts at [FAKE_START_UTC_MS], the monotonic one at 0;
 *  - **header:** only [KEEP_META], the code table, and of each sensor its rates and ranges ([KEEP_SENSOR]); never the
 *    research id, trip id, device or the sensor chips' names;
 *  - **labels** (`lbl`) and every other line are copied as they are.
 * The segments of a trip become one file. [leaks] then checks that no original position, clock, id or date is left;
 * a trip with a leak, or with nothing outside the privacy zone, is refused.
 */
object Rr2Anonymizer {
    /** 2000-01-01T00:00:00Z. */
    const val FAKE_START_UTC_MS = 946_684_800_000L
    val KEEP_META = setOf("app_version", "android_sdk", "os", "placement", "start_source")
    /** Of a sensor's description (`name;vendor;vN;res=…;…`), the fields kept: how fast and how fine, not which chip. */
    val KEEP_SENSOR = setOf("res", "max", "min_delay_us", "max_delay_us", "batch_us", "asked_us", "wakeup", "registered")
    /** Keys that, when present in an output file, mean it was not anonymized here. */
    private val NEVER = setOf(ResearchFormat.RESEARCH_ID, ResearchFormat.TRIP_ID, "device")
    private const val SPREAD = 0.3

    /** One anonymized trip (all [segments], any order) as text lines; refused (IllegalArgumentException) if unsafe. */
    fun anonymize(segments: List<File>, radiusM: Double = TripPrivacy.RADIUS_M): List<String> {
        require(radiusM >= TripPrivacy.RADIUS_M) { "the trim is at least ${TripPrivacy.RADIUS_M.toInt()} m" }
        val parts = segments.map { it to ResearchReader.read(it) }.sortedBy { Rr2.segmentOf(it.second) }
        for ((_, p) in parts) require(p.meta["format"] == ResearchFormat.VERSION) { "not rr2: format=${p.meta["format"]}" }
        val trips = parts.map { listOf(it.second.meta[ResearchFormat.RESEARCH_ID], it.second.meta[ResearchFormat.START_UTC_MS]) }
        require(trips.distinct().size == 1) { "segments of different trips" }
        Rr2.missingSegments(parts.map { Rr2.segmentOf(it.second) })   // refuses a segment given twice
        val w = ResearchTrim.window(ResearchTrim.scan(parts.map { it.first }), radiusM)
            ?: throw IllegalArgumentException("nothing to keep: under ${(2 * radiusM).toInt()} m driven, never out of the zone, or no GPS")
        val kept = parts.flatMap { it.second.records }.filter { it.tDms in w.fromT..w.toT }
        val fixes = kept.filter { it.code == ResearchFormat.GPS.code && !it.value(0).isNaN() && !it.value(1).isNaN() }
        val origin = fixes.minByOrNull { it.tDms } ?: throw IllegalArgumentException("no GPS fix in the kept part")
        val alt0 = fixes.sortedBy { it.tDms }.map { it.value(2) }.firstOrNull { !it.isNaN() } ?: Double.NaN

        val out = ArrayList<String>()
        for ((k, v) in parts.first().second.meta) {
            val value = when {
                k == "format" || k == "line" || k.startsWith("code.") || k in KEEP_META -> v
                k.startsWith("sensor.") -> sensor(v)
                k == ResearchFormat.SEGMENT -> "0"
                k == ResearchFormat.START_UTC_MS -> FAKE_START_UTC_MS.toString()
                k == ResearchFormat.START_ELAPSED_NS -> "0"
                else -> null   // research id, trip id, device, the footer, anything unknown
            } ?: continue
            out.add("# $k=$value")
        }
        out.add("# trim=first_and_last_${radiusM.toInt()}m_driven")
        out.add("# anonymized=fake_origin_0.5_0.5_clock_from_0")
        for (r in kept) {
            val f = r.fields.toMutableList()
            if (r.code == ResearchFormat.GPS.code && f.size >= 2) {
                val lat = r.value(0)
                val lon = r.value(1)
                if (lat.isNaN() || lon.isNaN()) { f[0] = ""; f[1] = "" } else {
                    val p = Anonymizer.shift(origin.value(0), origin.value(1), lat, lon)
                    f[0] = (p[0] * 1e7).roundToLong().toString()
                    f[1] = (p[1] * 1e7).roundToLong().toString()
                }
                if (f.size >= 3) f[2] = r.value(2).let { if (it.isNaN() || alt0.isNaN()) "" else ((it - alt0) * 100).roundToLong().toString() }
            }
            out.add((listOf((r.tDms - w.fromT).toString(), r.code) + f).joinToString(","))
        }
        val last = parts.last().second.meta
        if (ResearchFormat.END_LINES in last) {
            out.add("# ${ResearchFormat.END_LINES}=${kept.size}")
            out.add("# ${ResearchFormat.END_DROPPED}=${last[ResearchFormat.END_DROPPED] ?: "0"}")
        }
        val leaked = leaks(parts.map { it.second }, out)
        require(leaked.isEmpty()) { "refused, something original is left:\n" + leaked.joinToString("\n") }
        return out
    }

    /** A sensor description with only its rates and ranges: `res=…;max=…;min_delay_us=…;…` (or `absent`). */
    private fun sensor(v: String): String =
        if (v == "absent") v else v.split(';').filter { it.substringBefore('=') in KEEP_SENSOR && '=' in it }.joinToString(";")

    /**
     * What of the original [parts] is still in [out]: any field equal to an original latitude or longitude, the
     * research id, trip id, device, the start clocks or the start date. Empty when nothing is. Also every check of
     * [problems] (positions near the fake origin, fake clocks, no identifying keys).
     */
    fun leaks(parts: List<ResearchFile>, out: List<String>): List<String> {
        val bad = ArrayList(problems(out))
        val coords = HashSet<String>()
        for (p in parts) for (r in p.records) if (r.code == ResearchFormat.GPS.code) {
            r.fields.take(2).filter { it.trimStart('-').length >= 6 }.forEach { coords.add(it) }
        }
        val fields = out.filter { !it.startsWith("#") }.flatMap { it.split(',').drop(2) }
        val n = fields.count { it in coords }
        if (n > 0) bad.add("$n field(s) equal to an original latitude or longitude")
        val text = out.joinToString("\n")
        val meta = parts.first().meta
        val secrets = listOf(ResearchFormat.RESEARCH_ID, ResearchFormat.TRIP_ID, "device", ResearchFormat.START_UTC_MS, ResearchFormat.START_ELAPSED_NS)
            .mapNotNull { k -> meta[k]?.takeIf { it.length >= 4 && it != "0" && it != FAKE_START_UTC_MS.toString() }?.let { k to it } }
        for ((k, v) in secrets) if (text.contains(v)) bad.add("the original $k is still there")
        meta[ResearchFormat.START_UTC_MS]?.toLongOrNull()?.let { ms ->
            val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))
            if (ms != FAKE_START_UTC_MS && text.contains(day)) bad.add("the trip's date ($day) is still there")
        }
        return bad
    }

    /**
     * Why lines of an rr2 file would not pass as anonymized (for testdata, like [RealDriveChecks.anonymizationProblems]):
     * a position away from the fake origin, a real clock, an identifying key or a sensor chip's name, data before t = 0.
     * Messages give line numbers, never values.
     */
    fun problems(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var minT = Long.MAX_VALUE
        for ((n, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                val kv = line.removePrefix("#").trim()
                val k = kv.substringBefore('=')
                val v = kv.substringAfter('=', "")
                when {
                    k in NEVER -> out.add("line ${n + 1}: $k")
                    k == ResearchFormat.START_UTC_MS && v != FAKE_START_UTC_MS.toString() -> out.add("line ${n + 1}: a real wall clock")
                    k == ResearchFormat.START_ELAPSED_NS && v != "0" -> out.add("line ${n + 1}: a real monotonic clock")
                    k.startsWith("sensor.") && v != "absent" && v.split(';').any { it.substringBefore('=') !in KEEP_SENSOR } ->
                        out.add("line ${n + 1}: a sensor's name or chip details")
                }
                continue
            }
            val p = line.split(',')
            val t = p.getOrNull(0)?.toLongOrNull() ?: continue
            if (t < minT) minT = t
            if (p.getOrNull(1) != ResearchFormat.GPS.code) continue
            val lat = p.getOrNull(2)?.toLongOrNull()?.div(1e7)
            val lon = p.getOrNull(3)?.toLongOrNull()?.div(1e7)
            if (lat != null && lon != null && (abs(lat - Anonymizer.FAKE_LAT) >= SPREAD || abs(lon - Anonymizer.FAKE_LON) >= SPREAD)) {
                out.add("line ${n + 1}: a position away from the fake origin")
            }
        }
        if (minT != Long.MAX_VALUE && minT != 0L) out.add("the clock does not start at 0")
        return out
    }
}
