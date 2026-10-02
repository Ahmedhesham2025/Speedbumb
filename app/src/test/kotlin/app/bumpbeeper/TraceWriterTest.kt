package app.bumpbeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

/**
 * The recording file format. Readers (tools/replay, pandas) skip `#` lines and map columns by the header,
 * so the header must never change and every row must have exactly as many columns as the header.
 */
class TraceWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val header = "t_s,type,ax,ay,az,gx,gy,gz,vertical,lat,lon,speed_kmh,bearing,accuracy_m,event,bump_id,peak,note"
    private val cols = header.split(',')

    private fun write(meta: List<String> = emptyList(), block: TraceWriter.() -> Unit): List<String> {
        val w = TraceWriter(tmp.newFolder(), meta)
        try { w.block() } finally { w.close() }
        return w.file.readLines()
    }

    /** Data rows as column-name → value maps; fails if a row has the wrong column count. */
    private fun rows(lines: List<String>): List<Map<String, String>> =
        lines.filter { !it.startsWith("#") }.drop(1).map { line ->
            val f = line.split(',')
            assertEquals("columns in: $line", cols.size, f.size)
            cols.zip(f).toMap()
        }

    @Test fun metadataLinesComeBeforeTheUnchangedHeader() {
        val lines = write(listOf("app_version=1.2.0", "device=Acme/Phone\nX", "placement=dash")) {}
        assertEquals("# app_version=1.2.0", lines[0])
        assertEquals("a newline in a value must not start a new line", "# device=Acme/Phone X", lines[1])
        assertEquals("# placement=dash", lines[2])
        assertEquals(header, lines[3])
        assertEquals(4, lines.size)
    }

    @Test fun noMetadataMeansTheHeaderIsFirst() {
        assertEquals(header, write {}.first())
    }

    @Test fun gyroColumnsAreEmptyUntilTheFirstGyroReading() {
        val r = rows(write {
            accel(1_000, 0.1, -0.2, 9.81, Double.NaN, Double.NaN, Double.NaN, 0.5)
            accel(1_010, 0.1, -0.2, 9.81, 0.01, -0.02, 0.5, 0.6)
        })
        assertEquals("accel", r[0]["type"])
        assertEquals("0.100", r[0]["ax"]); assertEquals("-0.200", r[0]["ay"]); assertEquals("9.810", r[0]["az"])
        assertEquals("", r[0]["gx"]); assertEquals("", r[0]["gy"]); assertEquals("", r[0]["gz"])
        assertEquals("0.500", r[0]["vertical"])
        assertEquals("0.0100", r[1]["gx"]); assertEquals("-0.0200", r[1]["gy"]); assertEquals("0.5000", r[1]["gz"])
        assertEquals("0.010", r[1]["t_s"])
    }

    @Test fun gpsRowsFillOnlyThePositionColumns() {
        val r = rows(write { gps(5_000, 30.0444123, 31.2357456, 42.34, 87.6, 4.25) }).single()
        assertEquals("gps", r["type"]); assertEquals("0.000", r["t_s"])
        assertEquals("30.0444123", r["lat"]); assertEquals("31.2357456", r["lon"])
        assertEquals("42.3", r["speed_kmh"]); assertEquals("88", r["bearing"]); assertEquals("4.3", r["accuracy_m"])
        assertEquals("", r["ax"]); assertEquals("", r["event"]); assertEquals("", r["note"])
    }

    @Test fun labelAndBatteryRowsAreStampedWithTheLatestSampleTime() {
        val r = rows(write {
            battery(91)                                         // before any sample: t = 0
            accel(10_000, 0.0, 0.0, 9.8, Double.NaN, Double.NaN, Double.NaN, 0.0)
            accel(12_500, 0.0, 0.0, 9.8, Double.NaN, Double.NaN, Double.NaN, 0.0)
            label(Labels.POTHOLE_LEFT, 30.0444, 31.2357, 36.0)
            battery(87)
        })
        val first = r[0]
        assertEquals("0.000", first["t_s"]); assertEquals("battery", first["event"]); assertEquals("91.00", first["peak"])
        val label = r[3]
        assertEquals("event", label["type"]); assertEquals("label", label["event"]); assertEquals("pothole_l", label["note"])
        assertEquals("2.500", label["t_s"])
        assertEquals("30.0444000", label["lat"]); assertEquals("36.0", label["speed_kmh"])
        assertEquals("", label["bearing"]); assertEquals("", label["peak"]); assertEquals("", label["bump_id"])
        val battery = r[4]
        assertEquals("battery", battery["event"]); assertEquals("87.00", battery["peak"]); assertEquals("2.500", battery["t_s"])
        assertEquals("", battery["lat"]); assertEquals("", battery["note"])
    }

    @Test fun labelIsOnDiskBeforeTheFileIsClosed() {
        val w = TraceWriter(tmp.newFolder())
        try {
            w.accel(0, 0.0, 0.0, 9.8, Double.NaN, Double.NaN, Double.NaN, 0.0)
            w.label(Labels.BUMP, Double.NaN, Double.NaN, Double.NaN)
            // The app may be killed any time: a label must already be in the file.
            assertTrue(w.file.readLines().any { it.endsWith(",label,,,bump") })
        } finally {
            w.close()
        }
    }

    @Test fun commasAndNewlinesInNotesCannotBreakTheCsv() {
        val lines = write {
            event(BumpEvent(0, 1, "rejected", 12, 30.0, 31.0, 50.0, 90.0, 3.456, Double.NaN, Double.NaN, "too slow, braking\nhard"))
        }
        val r = rows(lines).single()
        assertEquals("rejected", r["event"]); assertEquals("12", r["bump_id"]); assertEquals("3.46", r["peak"])
        assertEquals("too slow; braking hard", r["note"])
        assertEquals(2, lines.size)
    }

    @Test fun eventWithoutBumpLeavesBumpIdEmpty() {
        val r = rows(write { event(BumpEvent(0, 1, "harsh_brake", -1, Double.NaN, Double.NaN, 40.0, Double.NaN, 4.0, Double.NaN, Double.NaN, "")) }).single()
        assertEquals("", r["bump_id"]); assertEquals("harsh_brake", r["event"]); assertEquals("", r["lat"])
    }

    @Test fun numbersAreWrittenWithUsDigitsOnAnArabicPhone() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            val r = rows(write { gps(0, 30.5, 31.25, 60.0, 180.0, 3.0) }).single()
            assertEquals("30.5000000", r["lat"]); assertEquals("60.0", r["speed_kmh"])
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun tracingStoreCopiesEventsIntoTheRecordingWhenThereIsOne() {
        val logged = ArrayList<BumpEvent>()
        val inner = object : BumpStore {
            override fun loadBumps(): List<Bump> = emptyList()
            override fun insertBump(b: Bump): Long = 1
            override fun updateBump(b: Bump) {}
            override fun logEvent(e: BumpEvent) { logged.add(e) }
        }
        val e = BumpEvent(0, 1, "hit", 3, 30.0, 31.0, 40.0, 90.0, 5.0, 2.0, 1.0, "")
        val store = TracingStore(inner, null)
        store.logEvent(e)                       // no recording yet: only the database gets it
        val w = TraceWriter(tmp.newFolder())
        store.trace = w                         // label mode switched on mid-trip
        store.logEvent(e)
        w.close()
        assertEquals(2, logged.size)
        assertEquals(listOf("hit"), rows(w.file.readLines()).map { it["event"] })
    }
}
