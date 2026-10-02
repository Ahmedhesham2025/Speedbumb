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
 * within ±25 % (at least ±3) and the distance within ±10 %. Empty values are printed but not checked.
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
            assertAnonymized(f.name, lines, run.samples)
            checkExpected(f, summary)
        }
    }

    /** Last line of defence: a raw recording must never pass CI (fake origin at 0.5, 0.5; no device metadata). */
    private fun assertAnonymized(name: String, lines: List<String>, samples: List<TraceSample>) {
        val fixes = samples.filterIsInstance<TraceSample.Gps>()
        assertTrue("$name has no GPS", fixes.isNotEmpty())
        assertTrue("$name: GPS not at the fake origin, not anonymized?", fixes.all { abs(it.lat - 0.5) < 0.3 && abs(it.lon - 0.5) < 0.3 })
        assertEquals("$name: clock not at zero", 0L, samples.minOf { it.tMs })
        val meta = lines.filter { it.startsWith("#") }.map { it.removePrefix("#").trim().substringBefore('=').trim() }
        assertTrue("$name: metadata other than ${Anonymizer.KEEP_META}", meta.all { it in Anonymizer.KEEP_META })
    }

    private fun checkExpected(f: File, s: DriveSummary) {
        val file = File(f.parentFile, f.name.substringBefore('.') + ".expected.properties")
        if (!file.exists()) return
        val want = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
        val actual = mapOf(
            "learned" to s.learned, "warnings" to s.warnings, "hits" to s.hits, "misses" to s.misses,
            "rejected" to s.rejects.values.sum(), "harsh_brakes" to s.harshBrakes, "harsh_accels" to s.harshAccels,
            "harsh_corners" to s.harshCorners, "swerves" to s.swerves,
        )
        val bad = ArrayList<String>()
        for ((k, v) in actual) {
            val w = want.getProperty(k)?.trim()?.toIntOrNull() ?: continue
            if (abs(v - w) > max(3.0, 0.25 * w)) bad.add("$k $v (expected $w ± max(3, 25 %))")
        }
        want.getProperty("distance_km")?.trim()?.toDoubleOrNull()?.let { w ->
            if (abs(s.distanceKm - w) > 0.10 * w) bad.add("distance_km ${s.distanceKm} (expected $w ± 10 %)")
        }
        assertTrue("${f.name} moved outside its expected range:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
