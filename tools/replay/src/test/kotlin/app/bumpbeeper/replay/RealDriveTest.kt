package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Properties
import kotlin.math.abs
import kotlin.math.max

/**
 * Replays every anonymized real drive in testdata/real (see testdata/README.md) on a fresh map and prints what the
 * engine did, so CI logs show it for every change. Labelled drives also print the accuracy table.
 *
 * A drive with a `<name>.expected.properties` next to it is a regression guard: each count given there must stay
 * within ±25 % (at least ±3), the distance within ±10 %, and none may drop to 0 where more is expected. Empty values
 * are printed but not checked.
 */
class RealDriveTest {
    private val dir = File(System.getProperty("testdata.dir") ?: "../../testdata", "real")
    private val drives = dir.listFiles { f -> f.name.endsWith(".csv.gz") || f.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()

    @Test fun realDrivesReplay() {
        assertTrue("no recordings in ${dir.path}", drives.isNotEmpty())
        for (f in drives) {
            val lines = readLines(f)
            val run = replayRuns(listOf(f.name to lines)).single()
            val summary = DriveSummary.of(run.result)
            if (run.labels.isNotEmpty()) println(Metrics.compute(listOf(run)).toMarkdown(f.name))
            println(summary.toMarkdown(f.name))
            val raw = RealDriveChecks.anonymizationProblems(lines)
            assertTrue("${f.name} still looks raw:\n" + raw.joinToString("\n"), raw.isEmpty())
            val exp = RealDriveChecks.expectedFile(f)
            if (exp.exists()) {
                val want = Properties().apply { exp.reader(Charsets.UTF_8).use { load(it) } }
                val bad = RealDriveChecks.outOfRange(want, summary)
                assertTrue("${f.name} moved outside its expected range:\n" + bad.joinToString("\n"), bad.isEmpty())
            }
        }
    }
}

/** The checks RealDriveTest runs on each drive, kept apart so they can be unit-tested (RealDriveChecksTest). */
object RealDriveChecks {
    private const val FAKE = 0.5
    private const val SPREAD = 0.3

    /**
     * Last line of defence: why [lines] would not pass as an anonymized recording, empty when it does. Positions in
     * the `lat`/`lon` columns of EVERY row (GPS, event and label rows alike) must sit near the fake origin (0.5, 0.5);
     * the clock must start at 0; only [Anonymizer.KEEP_META] metadata. Messages give row numbers, never coordinates.
     */
    fun anonymizationProblems(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var cols: Map<String, Int>? = null
        var minT = Double.POSITIVE_INFINITY
        var gpsWithPosition = 0
        val offRows = ArrayList<Int>()
        val halfRows = ArrayList<Int>()
        for ((n, raw) in lines.withIndex()) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                val key = line.removePrefix("#").trim().substringBefore('=').trim()
                if (key !in Anonymizer.KEEP_META) out.add("line ${n + 1}: metadata '$key' (only ${Anonymizer.KEEP_META})")
                continue
            }
            val parts = line.split(',')
            if (cols == null) {
                val names = parts.map { it.trim() }
                if ("type" in names && "t_s" in names) { cols = names.withIndex().associate { (i, v) -> v to i }; continue }
                cols = TraceReader.HEADER.withIndex().associate { (i, v) -> v to i }
            }
            val c = cols!!
            c["t_s"]?.let { parts.getOrNull(it)?.trim()?.toDoubleOrNull() }?.let { if (it < minT) minT = it }
            val lat = c["lat"]?.let { parts.getOrNull(it)?.trim() }.orEmpty()
            val lon = c["lon"]?.let { parts.getOrNull(it)?.trim() }.orEmpty()
            if (lat.isEmpty() && lon.isEmpty()) continue
            val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
            when {
                la == null || lo == null -> halfRows.add(n + 1)
                abs(la - FAKE) >= SPREAD || abs(lo - FAKE) >= SPREAD -> offRows.add(n + 1)
                c["type"]?.let { parts.getOrNull(it)?.trim() } == "gps" -> gpsWithPosition++
            }
        }
        fun rows(r: List<Int>) = "${r.size} row(s), first at line ${r.first()}"
        if (offRows.isNotEmpty()) out.add("position not near the fake origin (not anonymized?): ${rows(offRows)}")
        if (halfRows.isNotEmpty()) out.add("lat/lon not both numbers: ${rows(halfRows)}")
        if (gpsWithPosition == 0) out.add("no GPS fix with a position")
        if (minT.isInfinite() || abs(minT) > 1e-9) out.add("clock does not start at 0")
        return out
    }

    /** `name.csv.gz` / `name.csv` -> `name.expected.properties` (drive names may contain dots). */
    fun expectedFile(drive: File): File =
        File(drive.parentFile, drive.name.removeSuffix(".gz").removeSuffix(".csv") + ".expected.properties")

    /**
     * Counts outside [want]: each count within ±25 % (at least ±3), distance within ±10 %, and never 0 where more
     * than 0 is expected. Keys that are missing or empty are not checked.
     */
    fun outOfRange(want: Properties, s: DriveSummary): List<String> {
        val actual = mapOf(
            "learned" to s.learned, "warnings" to s.warnings, "hits" to s.hits, "misses" to s.misses,
            "rejected" to s.rejects.values.sum(), "harsh_brakes" to s.harshBrakes, "harsh_accels" to s.harshAccels,
            "harsh_corners" to s.harshCorners, "swerves" to s.swerves,
        )
        val bad = ArrayList<String>()
        for ((k, v) in actual) {
            val w = want.getProperty(k)?.trim()?.toIntOrNull() ?: continue
            if (abs(v - w) > max(3.0, 0.25 * w) || (w > 0 && v == 0)) bad.add("$k $v (expected $w ± max(3, 25 %), not 0)")
        }
        want.getProperty("distance_km")?.trim()?.toDoubleOrNull()?.let { w ->
            if (abs(s.distanceKm - w) > 0.10 * w || (w > 0 && s.distanceKm == 0.0)) bad.add("distance_km ${s.distanceKm} (expected $w ± 10 %)")
        }
        return bad
    }
}
