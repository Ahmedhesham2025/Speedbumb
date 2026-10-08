package app.bumpbeeper.replay

import app.bumpbeeper.*
import app.bumpbeeper.research.LineEncoder
import app.bumpbeeper.research.ResearchFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** The passenger's marks (Diagnostics → Mark): handling markers, never labels, and the two release gates around them. */
class MarkReportTest {
    private fun tap(tMs: Long, type: String, note: String = "") = TraceSample.Event(tMs, type, -1, Double.NaN, note)

    private fun logged(tMs: Long, type: String, note: String = "") =
        BumpEvent(Replayer.WALL_BASE_MS + tMs, 1, type, -1, 0.5, 0.5, 40.0, 90.0, 0.0, 0.0, Double.NaN, note)

    @Test fun marksAreNeverLabelsAndNoUndoTakesThemBack() {
        // Debug trace: a mark is its own row; the undo after it takes back the label before it, not the mark.
        val trace = listOf(tap(10_000, "label", "bump"), tap(15_000, "label", "nothing"), tap(20_000, "mark"), tap(21_000, "label", "undo"))
        assertEquals(listOf("bump"), Metrics.labels(trace).map { it.kind })
        assertEquals(listOf(20_000L), MarkReport.marks(trace))
        // A label row reading `mark` is read the same way.
        val asLabel = listOf(tap(10_000, "label", "bump"), tap(20_000, "label", "mark"), tap(21_000, "label", "undo"))
        assertEquals(emptyList<String>(), Metrics.labels(asLabel).map { it.kind })
        assertEquals(listOf(20_000L), MarkReport.marks(asLabel))
        // Research file: `lbl mark` becomes a mark event, never a label.
        val trip = Rr2.readStreams(listOf(ByteArrayInputStream(rr2 { e ->
            e.start(100_000, "lbl").text("bump").end()
            e.start(150_000, "lbl").text("nothing").end()
            e.start(200_000, "lbl").text("mark").end()
            e.start(210_000, "lbl").text("undo").end()
        })))
        assertEquals(listOf("bump"), Metrics.labels(trip.samples).map { it.kind })
        assertEquals(listOf(20_000L), MarkReport.marks(trip.samples))
        assertTrue(trip.samples.any { it is TraceSample.Event && it.type == MarkReport.MARK })
        // A jolt at the mark stays a detection nobody labelled: the mark neither matches it nor takes it from a label.
        val decide = EngineConfig().decideAfterMs
        val events = listOf(logged(20_000 + decide, "new_bump", "bump sev=mild conf=soft"), logged(10_200 + decide, "hit", "hits 2/2"))
        val c = LabelConfusion.of(listOf(Run("r", Metrics.labels(trip.samples), ReplayResult(events, TripStats(), DrivingStats()), trip.samples)))
        assertEquals(1, c.count(LabelConfusion.NO_LABEL, "learned"))
        assertEquals(1, c.count("bump", "hit"))
        assertNull(c.counts[MarkReport.MARK])
    }

    @Test fun harshEventsAndPhoneUseAroundMarks() {
        val samples = ArrayList<TraceSample>()
        for (s in 0..60) samples.add(TraceSample.Gps(s * 1000L, 0.5, 0.5, if (s in 40..50) 0.0 else 40.0, 90.0, 4.0))
        fun rotation(t: Long): Double = when (t) {
            in 9_000L..9_500L, in 12_000L..12_500L -> 2.0    // lifted and put down: a 3.6 s pick-up around the mark at 10 s
            in 20_000L..25_000L -> 0.4                        // a tight turn: not a hand
            in 30_000L..30_400L -> 2.0                        // a flick: shorter than 1.5 s
            in 44_000L..44_500L, in 47_000L..47_500L -> 2.0   // picked up at a red light
            else -> 0.05
        }
        for (k in 0..6000) samples.add(TraceSample.Accel(k * 10L, 0.0, 0.0, 9.81, 0.0, 0.0, rotation(k * 10L)))
        samples += listOf(tap(10_000, "mark"), tap(30_000, "mark"), tap(45_000, "mark"))
        val events = listOf(
            logged(11_500, "phone_use", "phone held while driving"),
            logged(13_000, "harsh_brake"),    // 3 s after a mark
            logged(24_000, "harsh_corner"),   // 6 s before one: not near
            logged(46_000, "swerve"),         // near the mark at the red light
        )
        val sorted = samples.sortedBy { it.tMs }
        val r = MarkReport.of(listOf(Run("r", Metrics.labels(sorted), ReplayResult(events, TripStats(), DrivingStats()), sorted)))
        assertEquals(3, r.marks)
        assertEquals(mapOf("harsh_brake" to 1, "swerve" to 1), r.harshNear)
        assertEquals(1, r.withPhoneUse)
        assertEquals(1, r.gated)
        assertEquals(1, r.gatedWithPhoneUse)
        assertEquals(1, r.short)
        assertEquals(1, r.stopped)
        assertEquals(0, r.unknownLength)
        assertEquals(1.0, r.phoneUseShare, 1e-9)
        val accel = sorted.filterIsInstance<TraceSample.Accel>()
        assertEquals(3590L, MarkReport.pickupMs(accel, 10_000))   // 9.05 s (first 200 ms above 0.6 rad/s) to 12.64 s
        assertEquals(540L, MarkReport.pickupMs(accel, 30_000))
        assertEquals(0L, MarkReport.pickupMs(accel.filter { it.tMs in 15_000L..28_000L }, 22_000))   // the turn alone
        assertNull(MarkReport.pickupMs(listOf(TraceSample.Accel(10_000, 0.0, 0.0, 9.81, Double.NaN, Double.NaN, Double.NaN)), 10_000))
        val md = r.toMarkdown("r")
        assertTrue(md, md.contains("| Harsh events near a mark (brake / accel / corner / swerve) | 2 (1 / 0 / 0 / 1) | 0: **FAIL** |"))
        assertTrue(md, md.contains("| Pick-ups ≥ 1.5 s while driving, with phone use | 1 / 1 (100 %) | ≥ 90 %: pass |"))
        assertTrue(md, md.contains("| Left out: shorter / car stopped | 1 / 1 | |"))
    }

    @Test fun aMissedPickUpFailsTheGate() {
        val samples = ArrayList<TraceSample>()
        for (s in 0..30) samples.add(TraceSample.Gps(s * 1000L, 0.5, 0.5, 40.0, 90.0, 4.0))
        // No gyroscope: lengths unknown, so every mark while driving counts.
        for (k in 0..3000) samples.add(TraceSample.Accel(k * 10L, 0.0, 0.0, 9.81, Double.NaN, Double.NaN, Double.NaN))
        samples += listOf(tap(5_000, "mark"), tap(20_000, "mark"))
        val sorted = samples.sortedBy { it.tMs }
        val events = listOf(logged(23_000, "phone_use", "phone unlocked while driving"))
        val r = MarkReport.of(listOf(Run("r", emptyList(), ReplayResult(events, TripStats(), DrivingStats()), sorted)))
        assertEquals(2, r.gated)
        assertEquals(2, r.unknownLength)
        assertEquals(0.5, r.phoneUseShare, 1e-9)
        assertTrue(r.toMarkdown("r").contains("| 1 / 2 (50 %) | ≥ 90 %: **FAIL** |"))
        assertEquals(0, r.harsh)
        assertTrue(r.toMarkdown("r").contains("| 0 (0 / 0 / 0 / 0) | 0: pass |"))
    }

    /** One research file, as the app writes it, with [body]'s lines. */
    private fun rr2(body: (LineEncoder) -> Unit): ByteArray {
        val meta = listOf("placement=mounted", "start_utc_ms=946684800000", "start_elapsed_ns=0", "research_id=0a0b0c0d", "segment=0")
        val enc = LineEncoder(1 shl 16)
        body(enc)
        val lines = enc.lines
        enc.comment("end_lines=$lines")
        enc.comment("end_dropped=0")
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            gz.write(ResearchFormat.header(meta).joinToString("") { "$it\n" }.toByteArray())
            gz.write(enc.bytes, 0, enc.size)
        }
        return bos.toByteArray()
    }
}
