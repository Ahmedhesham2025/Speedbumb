package app.bumpbeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.zip.ZipInputStream

/** Settings → Export / Share recordings: the file names and the one-zip bundle sent to Google Drive. */
class RecordingsExportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun firstTryKeepsTheName() {
        assertEquals("trace_2026-10-02_143000.csv", CsvExport.altName("trace_2026-10-02_143000.csv", 0))
    }

    @Test fun retriesNumberTheNameBeforeTheExtension() {
        assertEquals("trace_x_2.csv", CsvExport.altName("trace_x.csv", 1))
        assertEquals("trace_x_4.csv", CsvExport.altName("trace_x.csv", 3))
        assertEquals("recordings_2.zip", CsvExport.altName("recordings.zip", 1))
    }

    @Test fun retriesWorkWithoutAnExtension() {
        assertEquals("notes_2", CsvExport.altName("notes", 1))
        assertEquals(".hidden_2", CsvExport.altName(".hidden", 1))
    }

    @Test fun zipNameIsStampedAndEndsInZip() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 2, 14, 30, 0) }.time
        assertEquals("recordings_2026-10-02_1430.zip", TraceWriter.zipName(now))
    }

    @Test fun zipHoldsEveryRecordingByteForByte() {
        val a = tmp.newFile("trace_a.csv").apply { writeText("# app_version=1.4.0\nt_s,type\n0.000,accel\n") }
        val big = buildString { repeat(20_000) { append(it).append(",accel,0.1,0.2,9.8\n") } }
        val b = tmp.newFile("trace_b.csv").apply { writeText(big) }

        val out = ByteArrayOutputStream()
        TraceWriter.zipTo(listOf(a, b), out)

        val got = LinkedHashMap<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                got[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(listOf("trace_a.csv", "trace_b.csv"), got.keys.toList())
        assertEquals(a.readText(), got["trace_a.csv"])
        assertEquals(big, got["trace_b.csv"])
        assertTrue("CSV text should compress", out.size() < big.length / 2)
    }

    @Test fun zipOfNothingIsStillAValidZip() {
        val out = ByteArrayOutputStream()
        TraceWriter.zipTo(emptyList(), out)
        ZipInputStream(out.toByteArray().inputStream()).use { assertEquals(null, it.nextEntry) }
    }
}
