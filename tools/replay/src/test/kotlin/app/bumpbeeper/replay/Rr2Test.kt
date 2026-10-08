package app.bumpbeeper.replay

import app.bumpbeeper.*
import app.bumpbeeper.research.LineEncoder
import app.bumpbeeper.research.ResearchFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * Research recordings (rr2) built here, the way the app writes them (its own LineEncoder and header): never a real
 * recording, those hold the whole GPS track. Read, joined across segments and replayed at the engine's rate.
 */
class Rr2Test {
    @Test fun aSyntheticTripIsReplayedLikeOnThePhone() {
        val trip = Rr2.readStreams(listOf(ByteArrayInputStream(segment(0, last = true) { drive(it) })))
        assertEquals("pocket", trip.placement)
        assertTrue(trip.complete)
        assertFalse(trip.truncated)
        // 200 Hz on disk, about 100 Hz into the engine: bins of at least 10 ms, whole milliseconds.
        val accel = trip.samples.filterIsInstance<TraceSample.Accel>()
        val gaps = accel.zipWithNext { a, b -> b.tMs - a.tMs }
        assertTrue(gaps.min() >= 10)
        assertEquals(DRIVE_S * 100.0, accel.size.toDouble(), DRIVE_S * 2.0)
        assertTrue(accel.drop(10).all { !it.gx.isNaN() })                 // the latest gyroscope bin rides along
        assertEquals(trip.samples.sortedBy { it.tMs }.map { it.tMs }, trip.samples.map { it.tMs })
        val result = Replayer.replay(trip.samples, MemStore())
        val r = Metrics.compute(Metrics.labels(trip.samples), result, trip.samples)
        println(r.toMarkdown("synthetic rr2"))
        assertEquals(3, r.labels)
        assertEquals(3, r.detections)
        assertEquals(1.0, r.precision, 1e-9)
        assertEquals(1.0, r.recall, 1e-9)
        assertEquals(1.2, r.distanceKm, 0.1)
    }

    @Test fun unitsOrderAndPhoneSignals() {
        val trip = Rr2.readStreams(listOf(ByteArrayInputStream(segment(0, last = false) { e ->
            e.sample(20_000, ResearchFormat.GPS, doubleArrayOf(30.0444, 31.2357, 45.5, 8.25, 91.5, 4.2, 0.5, 2.0, 3.0, 650.0))
            e.sample(-50_000, ResearchFormat.SCREEN, doubleArrayOf(1.0))          // before the start: its last value
            e.start(30_001, "unl").end()
            e.sample(30_500, ResearchFormat.AUDIO, doubleArrayOf(2.0, 0.0, 1.0))  // a call at the earpiece
            e.sample(31_000, ResearchFormat.PROXIMITY, doubleArrayOf(0.0))
            e.sample(31_500, ResearchFormat.LIGHT, doubleArrayOf(3.0))
            e.sample(12_345, ResearchFormat.GRAVITY, doubleArrayOf(0.1, 0.2, 9.8))
            e.start(25_000, "lbl").text("bump_strong").end()
            e.start(25_000, "zz").int(1).end()                                     // unknown code: skipped
        })))
        val fix = trip.samples.filterIsInstance<TraceSample.Gps>().single()
        assertEquals(2000L, fix.tMs)
        assertEquals(30.0444, fix.lat, 1e-9)
        assertEquals(8.25 * 3.6, fix.speedKmh, 1e-9)
        assertEquals(91.5, fix.bearing, 1e-9)
        assertEquals(4.2, fix.accuracyM, 1e-9)
        val label = trip.samples.filterIsInstance<TraceSample.Event>().single()
        assertEquals("label", label.type)
        assertEquals("bump_strong", label.note)
        assertEquals(2500L, label.tMs)
        assertEquals(listOf("scr", "unl", "aud", "px", "lx"), trip.phone.map { it.code })
        assertEquals(-5000L, trip.phone[0].tMs)
        assertEquals(listOf(2.0, 0.0, 1.0), trip.phone[2].values)
        assertEquals(3000L, trip.phone[1].tMs)                                   // 3000.1 ms, rounded down
        assertEquals(9.8, trip.gravity.single()[3], 1e-9)
        assertFalse(trip.complete)                                               // no footer: cut short
    }

    @Test fun segmentsJoinAndCutFilesReadToTheirLastFlush() {
        val first = segment(0, last = false) { e -> for (k in 0 until 400) e.sample(k * 50L, ResearchFormat.ACCEL, doubleArrayOf(0.0, 0.0, 9.81)) }
        val second = segment(1, last = true) { e -> for (k in 400 until 800) e.sample(k * 50L, ResearchFormat.ACCEL, doubleArrayOf(0.0, 0.0, 9.81)) }
        val trip = Rr2.readStreams(listOf(ByteArrayInputStream(second), ByteArrayInputStream(first)))   // any order
        assertEquals(2, trip.segments)
        assertTrue(trip.complete)
        val accel = trip.samples.filterIsInstance<TraceSample.Accel>()
        assertEquals(0L, accel.first().tMs)
        assertEquals(3990L, accel.last().tMs)                                    // time runs on across segments
        val cut = segment(0, last = false, cut = true) { e -> for (k in 0 until 100) e.sample(k * 50L, ResearchFormat.ACCEL, doubleArrayOf(0.0, 0.0, 9.81)) }
        val partial = Rr2.readStreams(listOf(ByteArrayInputStream(cut)))
        assertTrue(partial.truncated)
        assertEquals(50, partial.samples.size)                                   // 100 lines at 200 Hz in 10 ms bins; the half line is dropped
    }

    @Test fun refusesOtherFormatsAndMixedTrips() {
        try {
            Rr2.readStreams(listOf(ByteArrayInputStream(segment(0, last = true, format = "rr1") {})))
            fail("rr1 must be refused")
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("format=rr1")) }
        try {
            Rr2.readStreams(listOf(ByteArrayInputStream(segment(0, last = false) {}), ByteArrayInputStream(segment(1, last = true, id = "00ff00ff") {})))
            fail("two trips must be refused")
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("2 different trips")) }
    }

    @Test fun theCommandLineJoinsATripsSegments() {
        val dir = Files.createTempDirectory("rr2").toFile()
        try {
            val name = "rr_3f2a9c1e_20261007T201530"
            File(dir, "${name}_000.csv.gz").writeBytes(segment(0, last = false) { e -> e.sample(0, ResearchFormat.ACCEL, doubleArrayOf(0.0, 0.0, 9.81)) })
            File(dir, "${name}_001.csv.gz").writeBytes(segment(1, last = true) { e -> e.sample(600_000, ResearchFormat.ACCEL, doubleArrayOf(0.0, 0.0, 9.81)) })
            val csv = File(dir, "trace.csv").apply { writeText(TraceWriterCore.toCsv(listOf(TraceSample.Gps(0, 0.5, 0.5, 30.0, 90.0, 5.0)))) }
            assertTrue(Rr2.isResearch(File(dir, "${name}_001.csv.gz")))
            assertFalse(Rr2.isResearch(csv))
            val runs = loadRecordings(listOf(File(dir, "${name}_000.csv.gz"), csv, File(dir, "${name}_001.csv.gz")))
            assertEquals(listOf(name, "trace.csv"), runs.map { it.first })
            assertEquals(listOf(0L, 60_000L), runs[0].second.map { it.tMs })
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Eastbound at 30 km/h with the phone lying flat: 200 Hz accelerometer and gyroscope (with 0.1 ms jitter), 1 Hz GPS
     * written up to 0.6 s late like on the phone, three bumps at 200, 600 and 1000 m, each tapped 0.6 s after the hit.
     */
    private fun drive(e: LineEncoder) {
        val rnd = Random(3)
        val v = 30 / 3.6
        val hits = listOf(200.0, 600.0, 1000.0).map { it / v }
        var pendingFix: Pair<Long, DoubleArray>? = null
        var k = 0L
        while (k * 5 <= DRIVE_S * 1000) {
            val tMs = k * 5
            val t = tMs * 10 + rnd.nextInt(3)
            var vert = rnd.nextGaussian() * 0.1
            for (h in hits) {
                val tau = tMs / 1000.0 - h
                if (tau >= 0 && tau < 0.1) vert += 5.0 * sin(PI * tau / 0.1)
                else if (tau >= 0.1 && tau < 0.2) vert -= 3.5 * sin(PI * (tau - 0.1) / 0.1)
            }
            e.sample(t, ResearchFormat.ACCEL, doubleArrayOf(rnd.nextGaussian() * 0.05, rnd.nextGaussian() * 0.05, 9.81 + vert))
            e.sample(t + 1, ResearchFormat.GYRO, doubleArrayOf(rnd.nextGaussian() * 0.01, rnd.nextGaussian() * 0.01, rnd.nextGaussian() * 0.01))
            if (tMs % 1000 == 0L) {
                val p = Geo.move(30.0444, 31.2357, 90.0, v * tMs / 1000.0)
                pendingFix = Pair(tMs * 10, doubleArrayOf(p[0], p[1], Double.NaN, v, 90.0, 5.0, 0.5, 2.0, Double.NaN, 600.0))
            }
            val f = pendingFix
            if (f != null && tMs * 10 - f.first >= 6000) { e.sample(f.first, ResearchFormat.GPS, f.second); pendingFix = null }
            k++
        }
        for (h in hits) e.start(((h + 0.6) * 10_000).toLong(), "lbl").text("bump").end()
    }

    /** One gzip segment as the app writes it: header, lines, the footer in the last one; [cut] = killed mid-write. */
    private fun segment(
        seg: Int, last: Boolean, format: String = "rr2", id: String = "3f2a9c1e", cut: Boolean = false, body: (LineEncoder) -> Unit,
    ): ByteArray {
        val meta = listOf(
            "app_version=1.8.0-beta1", "android_sdk=34", "device=acme/Phone1", "placement=pocket", "start_source=user",
            "trip_id=7", "start_utc_ms=1791403000000", "start_elapsed_ns=5000000000",
            "sensor.a=Accel;Acme;v1;res=0.001;max=78.4;min_delay_us=5000;max_delay_us=200000;power_ma=0.2;fifo=0;wakeup=false;batch_us=0;asked_us=5000",
            "research_id=$id", "segment=$seg",
        )
        val bos = ByteArrayOutputStream()
        val gz = GZIPOutputStream(bos, true)
        gz.write(ResearchFormat.header(meta).joinToString("") { it.replace("format=rr2", "format=$format") + "\n" }.toByteArray())
        val enc = LineEncoder(1 shl 24)
        body(enc)
        val lines = enc.lines
        if (last) { enc.comment("end_lines=$lines"); enc.comment("end_dropped=0") }
        gz.write(enc.bytes, 0, enc.size)
        if (cut) {
            gz.write("1234,a,0,0".toByteArray())   // half a line, then the app dies after a flush
            gz.flush()
            return bos.toByteArray()
        }
        gz.close()
        return bos.toByteArray()
    }

    private companion object {
        const val DRIVE_S = 140L
    }
}
