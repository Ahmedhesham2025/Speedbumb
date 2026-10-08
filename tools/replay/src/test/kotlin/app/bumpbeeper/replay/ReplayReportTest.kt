package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The replay report's newer parts: the placement a drive is replayed with, the severity histogram, labels vs decisions. */
class ReplayReportTest {
    private fun event(tMs: Long, type: String, note: String) =
        BumpEvent(Replayer.WALL_BASE_MS + tMs, 1, type, 1, 0.5, 0.5, 30.0, 90.0, 4.0, 0.0, Double.NaN, note)

    @Test fun placementFromTheArgumentOrTheRecording() {
        val dir = Files.createTempDirectory("placement").toFile()
        try {
            val csv = File(dir, "a.csv").apply {
                writeText("# placement=pocket\n" + TraceWriterCore.toCsv(listOf(TraceSample.Gps(0, 0.5, 0.5, 30.0, 90.0, 5.0))))
            }
            assertEquals("unknown", loadRecordings(listOf(csv)).single().placement)          // as before: unknown
            assertEquals("mounted", loadRecordings(listOf(csv), "mounted").single().placement)
            assertEquals("pocket", loadRecordings(listOf(csv), "recording").single().placement)
            val run = replaySamples(loadRecordings(listOf(csv), "recording")).single()
            assertEquals("pocket", run.placement)
            assertTrue(DriveSummary.of(run.result, run.placement).toMarkdown("a").contains("| Placement | pocket |"))
            assertEquals("cupholder", replayRuns(listOf("b" to csv.readLines()), "cupholder").single().placement)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun severityHistogram() {
        val events = listOf(
            event(10_000, "new_bump", "bump sev=mild conf=soft score=-1.00 first=up no-gyro"),
            event(20_000, "new_bump", "bump sev=strong conf=soft score=-0.30 first=up no-gyro"),
            event(30_000, "hit", "hits 2/2 now=bump sev=moderate conf=full score=-0.40 first=up no-gyro"),
            event(30_500, "hit_repeat", "same pass"),
            event(40_000, "rejected", "phone_moving"),
        )
        val s = DriveSummary.of(ReplayResult(events, TripStats(), DrivingStats()))
        assertEquals(mapOf("mild" to 1, "strong" to 1, "moderate" to 1), s.bands)
        assertEquals(mapOf("soft" to 2, "full" to 1), s.confidence)
        val md = s.toMarkdown("t")
        assertTrue(md, md.contains("| Severity mild / moderate / strong | 1 / 1 / 1 |"))
        assertTrue(md, md.contains("| Confidence soft / full | 2 / 1 |"))
    }

    @Test fun labelsAgainstDecisions() {
        val decide = EngineConfig().decideAfterMs
        val events = listOf(
            event(10_000 + decide, "new_bump", "bump sev=mild conf=soft"),  // jolt at 10 s
            event(20_000 + decide, "rejected", "phone_moving"),              // jolt at 20 s
            event(31_000 + decide, "hit_repeat", "same pass"),
            event(71_000 + decide, "hit", "hits 2/2 now=bump sev=mild conf=full"),
            event(72_000, "beep", "mild"),                                    // not a jolt
        )
        val labels = listOf(
            Label(10_600, "bump", 0.5, 0.5), Label(19_000, "nothing", 0.5, 0.5), Label(31_500, "rough", 0.5, 0.5),
            Label(50_000, "bump_strong", 0.5, 0.5),                           // nothing within 3 s: a miss
            Label(74_500, "bump", 0.5, 0.5),                                  // 3.5 s after the jolt: too far
        )
        val run = Run("r", labels, ReplayResult(events, TripStats(), DrivingStats()), emptyList())
        val c = LabelConfusion.of(listOf(run))
        assertEquals(1, c.count("bump", "learned"))
        assertEquals(1, c.count("nothing", "rejected phone_moving"))
        assertEquals(1, c.count("rough", "same_pass"))
        assertEquals(1, c.count("bump_strong", LabelConfusion.NO_JOLT))
        assertEquals(1, c.count("bump", LabelConfusion.NO_JOLT))
        assertEquals(1, c.count(LabelConfusion.NO_LABEL, "hit"))
        val md = c.toMarkdown("r")
        assertTrue(md, md.contains("| Label \\ decision | hit | learned | rejected phone_moving | same_pass | (no jolt) |"))
        assertTrue(md, md.lines().last { it.isNotBlank() }.startsWith("| (no label) | 1 |"))
    }
}
