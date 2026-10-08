package app.bumpbeeper.replay

import app.bumpbeeper.*
import app.bumpbeeper.research.LineEncoder
import app.bumpbeeper.research.ResearchFormat
import app.bumpbeeper.research.ResearchReader
import app.bumpbeeper.research.ResearchTrim
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.zip.GZIPOutputStream

/** A synthetic research trip in Cairo, two segments, made safe for testdata: never a real recording. */
class Rr2AnonymizerTest {
    private val dir = Files.createTempDirectory("rr2anon").toFile()
    private val segments = trip()
    /** In segment order, as the anonymizer joins them. */
    private val ordered = segments.sortedBy { it.name }
    private val out = Rr2Anonymizer.anonymize(segments)
    private val text = out.joinToString("\n")

    @After fun cleanUp() { dir.deleteRecursively() }

    @Test fun keepsOnlyTheUploadWindow() {
        val w = ResearchTrim.window(ResearchTrim.scan(segments))!!
        val original = ordered.flatMap { ResearchReader.read(it).records }
        val inside = original.filter { it.tDms in w.fromT..w.toT }
        val data = out.filter { !it.startsWith("#") }
        assertEquals(inside.size, data.size)
        assertTrue(inside.size < original.size - 1000)                       // both ends were trimmed
        assertEquals(inside.map { it.tDms - w.fromT }, data.map { it.substringBefore(',').toLong() })
        assertEquals(0L, data.first().substringBefore(',').toLong())        // the clock starts at 0
        assertTrue(text.contains("# end_lines=${inside.size}"))
        assertTrue(text.contains("# segment=0"))
    }

    @Test fun distancesAndHeadingsStay() {
        val w = ResearchTrim.window(ResearchTrim.scan(segments))!!
        val before = ordered.flatMap { ResearchReader.read(it).records }.filter { it.code == "G" && it.tDms in w.fromT..w.toT }
        val after = ResearchReader.read(ByteArrayInputStream(gzip(out))).records.filter { it.code == "G" }
        assertEquals(before.size, after.size)
        assertEquals(Anonymizer.FAKE_LAT, after[0].value(0), 1e-7)
        assertEquals(Anonymizer.FAKE_LON, after[0].value(1), 1e-7)
        for (i in before.indices step 7) for (j in before.indices step 11) {
            val d1 = Geo.distance(before[i].value(0), before[i].value(1), before[j].value(0), before[j].value(1))
            val d2 = Geo.distance(after[i].value(0), after[i].value(1), after[j].value(0), after[j].value(1))
            assertEquals(d1, d2, 0.5)
            if (d1 > 50) {
                val b1 = Geo.bearing(before[i].value(0), before[i].value(1), before[j].value(0), before[j].value(1))
                val b2 = Geo.bearing(after[i].value(0), after[i].value(1), after[j].value(0), after[j].value(1))
                assertTrue("bearing $b1 vs $b2", Geo.angleDiff(b1, b2) < 1.0)
            }
        }
        assertEquals(0.0, after[0].value(2), 1e-9)                           // altitude relative to the first fix
        assertEquals(before[5].value(2) - before[0].value(2), after[5].value(2), 0.011)
    }

    @Test fun nothingIdentifyingIsLeft() {
        for (secret in listOf("3f2a9c1e", "trip_id", "research_id", "samsung", "SM-A546E", "LSM6DSO", "STMicro",
            "1791403000000", "5000123456789", "20261007", "300444", "312357")) {
            assertFalse(secret, text.contains(secret))
        }
        assertTrue(text.contains("# placement=pocket"))
        assertTrue(text.contains("# app_version=1.8.0-beta1"))
        assertTrue(text.contains("# sensor.a=res=0.001;max=78.4;min_delay_us=5000;max_delay_us=200000;wakeup=false;batch_us=0;asked_us=5000"))
        assertTrue(text.contains("# sensor.pa=absent"))
        assertTrue(text.contains("# start_utc_ms=${Rr2Anonymizer.FAKE_START_UTC_MS}"))
        assertTrue(text.contains("# start_elapsed_ns=0"))
        assertEquals(emptyList<String>(), Rr2Anonymizer.problems(out))
        assertTrue(Rr2Anonymizer.problems(readLines(segments[0])).size >= 4)   // the raw file fails the testdata check
    }

    @Test fun labelsAndTheReplayStay() {
        val trip = Rr2.readStreams(listOf(ByteArrayInputStream(gzip(out))))
        assertEquals("pocket", trip.placement)
        assertTrue(trip.complete)
        assertEquals(listOf("bump", "rough", "undo"), trip.samples.filterIsInstance<TraceSample.Event>().map { it.note })
        val result = Replayer.replay(trip.samples, MemStore())
        assertTrue(result.trip.distanceM > 1500)
    }

    @Test fun refusesWhatCannotBeMadeSafe() {
        val short = File(dir, "short.csv.gz").apply { writeBytes(segment(0, last = true) { drive(it, 0, 50) }) }  // 500 m
        assertTrue(runCatching { Rr2Anonymizer.anonymize(listOf(short)) }.exceptionOrNull()?.message!!.contains("nothing to keep"))
        assertTrue(runCatching { Rr2Anonymizer.anonymize(segments, radiusM = 100.0) }.isFailure)    // never less than 300 m
        val leaky = out + "# device=samsung/SM-A546E"
        assertTrue(Rr2Anonymizer.leaks(segments.map { ResearchReader.read(it) }, leaky).isNotEmpty())
        val original = segments.flatMap { ResearchReader.read(it).records }.first { it.code == "G" }
        val coordinate = out + "100,G,${original.fields[0]},${original.fields[1]}"
        assertTrue(Rr2Anonymizer.leaks(segments.map { ResearchReader.read(it) }, coordinate).isNotEmpty())
    }

    /** 3.5 km wandering through Cairo at 36 km/h, 20 Hz accelerometer, 1 Hz GPS with altitude, labels and phone lines. */
    private fun trip(): List<File> {
        val a = File(dir, "rr_3f2a9c1e_20261007T201530_000.csv.gz").apply { writeBytes(segment(0, last = false) { drive(it, 0, 180) }) }
        val b = File(dir, "rr_3f2a9c1e_20261007T201530_001.csv.gz").apply { writeBytes(segment(1, last = true) { drive(it, 180, 350) }) }
        return listOf(b, a)   // any order
    }

    private fun drive(e: LineEncoder, fromS: Int, toS: Int) {
        val rnd = Random(3)
        val noise = Random(5L + fromS)   // apart from the path, so both segments drive the same road
        var p = doubleArrayOf(30.0444, 31.2357)
        var brg = 40.0
        for (s in 0 until toS) {
            if (s >= fromS) {
                val t = s * 10_000L
                for (k in 0 until 20) e.sample(t + k * 500, ResearchFormat.ACCEL, doubleArrayOf(0.1, 0.2, 9.8 + noise.nextGaussian() * 0.2))
                e.sample(t, ResearchFormat.GPS, doubleArrayOf(p[0], p[1], 70.0 + s * 0.05, 10.0, brg, 4.0, 0.5, 2.0, 3.0, 400.0))
                if (s == 0) e.sample(-30_000, ResearchFormat.SCREEN, doubleArrayOf(0.0))
                // 20 s and 340 s fall in the trimmed ends (first and last 300 m driven); 60–125 s are kept.
                val label = mapOf(20 to "bump", 60 to "bump", 120 to "rough", 125 to "undo", 340 to "bump")[s]
                if (label != null) e.start(t + 3000, "lbl").text(label).end()
            }
            brg = (brg + rnd.nextGaussian() * 10 + 360) % 360
            p = Geo.move(p[0], p[1], brg, 10.0)
        }
    }

    private fun segment(seg: Int, last: Boolean, body: (LineEncoder) -> Unit): ByteArray {
        val meta = listOf(
            "app_version=1.8.0-beta1", "android_sdk=34", "device=samsung/SM-A546E", "placement=pocket", "start_source=car",
            "trip_id=7", "start_utc_ms=1791403000000", "start_elapsed_ns=5000123456789",
            "sensor.a=LSM6DSO;STMicroelectronics;v1;res=0.001;max=78.4;min_delay_us=5000;max_delay_us=200000;power_ma=0.2;fifo=0;wakeup=false;batch_us=0;asked_us=5000",
            "sensor.pa=absent", "research_id=3f2a9c1e", "segment=$seg",
        )
        val enc = LineEncoder(1 shl 23)
        body(enc)
        val lines = enc.lines
        if (last) { enc.comment("end_lines=$lines"); enc.comment("end_dropped=0") }
        return gzipBytes(ResearchFormat.header(meta).joinToString("") { "$it\n" }.toByteArray() + enc.bytes.copyOf(enc.size))
    }

    private fun gzip(lines: List<String>): ByteArray = gzipBytes(lines.joinToString("") { "$it\n" }.toByteArray())

    private fun gzipBytes(b: ByteArray): ByteArray = ByteArrayOutputStream().also { bos -> GZIPOutputStream(bos).use { it.write(b) } }.toByteArray()
}
