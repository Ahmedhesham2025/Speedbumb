package app.bumpbeeper.research

import app.bumpbeeper.TraceReader
import app.bumpbeeper.TraceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.sqrt

/** The rr1 research format: what the encoder writes is what the reader gets back, in physical units. */
class ResearchFormatTest {
    private fun file(block: (LineEncoder) -> Unit): ResearchFile {
        val enc = LineEncoder(1 shl 16)
        block(enc)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { z ->
            for (h in ResearchFormat.header(listOf("device=Acme/Phone"))) z.write((h + "\n").toByteArray())
            z.write(enc.bytes, 0, enc.size)
        }
        return ResearchReader.read(out.toByteArray().inputStream())
    }

    @Test fun everyCodeRoundTripsInPhysicalUnits() {
        val rnd = Random(7)
        val sent = ResearchFormat.ALL.filter { c -> c.scales.none { it == 0.0 } }
            .map { c -> c to DoubleArray(c.scales.size) { (rnd.nextDouble() - 0.5) * 40 } }
        val f = file { enc -> sent.forEachIndexed { i, (c, v) -> enc.sample(i * 5L, c, v) } }
        assertFalse(f.truncated)
        assertEquals(sent.size, f.records.size)
        for ((r, s) in f.records.zip(sent)) {
            val (c, v) = s
            assertEquals(c.code, r.code)
            assertEquals(v.size, r.fields.size)
            for (i in v.indices) assertEquals("${c.code}[$i]", v[i], r.value(i), 0.5 / c.scales[i] + 1e-9)
        }
    }

    @Test fun sensorValuesAreWrittenAsFixedPointIntegers() {
        val (a, g, rv, pa, sd) = file { enc ->
            enc.sample(1234, ResearchFormat.ACCEL, floatArrayOf(0.1234f, -9.80665f, 0f))
            enc.sample(1239, ResearchFormat.GYRO, floatArrayOf(0.0123f, -0.0004f, 1.5f))
            enc.sample(1240, ResearchFormat.ROTATION, floatArrayOf(0.1f, 0.2f, 0.3f, 0.927362f))   // no heading accuracy
            enc.sample(1300, ResearchFormat.PRESSURE, floatArrayOf(1013.2512f))
            enc.sample(1301, ResearchFormat.STEP, floatArrayOf(1f))
        }.records
        assertEquals(1234L, a.tMs)
        assertEquals(listOf("123", "-9807", "0"), a.fields)              // mm/s²
        assertEquals(listOf("12", "0", "1500"), g.fields)                // mrad/s
        assertEquals(listOf("1000", "2000", "3000", "9274", ""), rv.fields)
        assertEquals(listOf("1013251"), pa.fields)                       // 0.1 Pa
        assertEquals(1013.251, pa.value(0), 1e-9)                        // back in hPa
        assertEquals(emptyList<String>(), sd.fields)
    }

    @Test fun missingValuesStayEmptyAndReadAsNaN() {
        val fix = doubleArrayOf(30.0444, 31.2357, Double.NaN, 12.5, Double.NaN, 4.0, Double.NaN, Double.NaN, Double.NaN, 180.0)
        val r = file { it.sample(5000, ResearchFormat.GPS, fix) }.records.single()
        assertEquals(listOf("300444000", "312357000", "", "1250", "", "400", "", "", "", "180"), r.fields)
        assertEquals(30.0444, r.value(0), 1e-9)
        assertTrue(r.value(2).isNaN())
        assertEquals(12.5, r.value(3), 1e-9)
    }

    @Test fun textCannotBreakALine() {
        val r = file { it.start(1, ResearchFormat.LABEL.code).text("bump, big\nمطب").end() }.records.single()
        assertEquals(listOf("bump_ big____"), r.fields)
        assertTrue(r.value(0).isNaN())
    }

    @Test fun negativeTimesAndValuesSurvive() {
        val r = file { it.start(-1500, ResearchFormat.PROXIMITY.code).int(-3).end() }.records.single()
        assertEquals(-1500L, r.tMs)
        assertEquals(-3.0, r.value(0), 0.0)
    }

    @Test fun digitsStayAsciiOnAnArabicPhone() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            val enc = LineEncoder(LineEncoder.MAX_LINE)
            enc.sample(12345, ResearchFormat.ACCEL, floatArrayOf(1.5f, -2.25f, 9.81f))
            assertEquals("12345,a,1500,-2250,9810\n", String(enc.bytes, 0, enc.size, Charsets.US_ASCII))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun everyFileStartsWithItsFormatAndCodeTable() {
        val f = file {}
        assertEquals("rr1", f.meta["format"])
        assertEquals("Acme/Phone", f.meta["device"])
        for (c in ResearchFormat.ALL) assertTrue(c.code, f.meta["code.${c.code}"]?.isNotEmpty() == true)
        assertEquals("codes are unique", ResearchFormat.ALL.size, ResearchFormat.ALL.map { it.code }.toSet().size)
    }

    @Test fun aFileCutOffByAKilledAppReadsUpToItsLastFlush() {
        val out = ByteArrayOutputStream()
        val z = GZIPOutputStream(out, 512, true)
        val enc = LineEncoder(1 shl 16)
        repeat(100) { enc.sample(it.toLong(), ResearchFormat.ACCEL, floatArrayOf(it.toFloat(), 0f, 9.8f)) }
        z.write(enc.bytes, 0, enc.size)
        z.flush()                                  // what the writer does every 2 s
        val flushed = out.size()
        enc.clear()
        repeat(3000) { enc.sample(100L + it, ResearchFormat.ACCEL, floatArrayOf(it.toFloat(), 0f, 9.8f)) }
        z.write(enc.bytes, 0, enc.size)            // then the app dies: no flush, no gzip trailer
        val bytes = out.toByteArray()
        for (cut in listOf(flushed, flushed + 7, (flushed + bytes.size) / 2, bytes.size).map { minOf(it, bytes.size) }) {
            val f = ResearchReader.read(bytes.copyOf(cut).inputStream())
            assertTrue("cut at $cut", f.truncated)
            assertTrue("cut at $cut: ${f.records.size} lines", f.records.size >= 100)
            assertTrue("no half lines", f.records.all { it.fields.size == 3 })
            assertEquals((0L until f.records.size).toList(), f.records.map { it.tMs })
        }
    }

    /**
     * Size per hour, measured on the real anonymized drive01 (pocket, ~100 Hz accelerometer + gyroscope) re-encoded as
     * rr1, plus the streams a phone derives from the same motion (uncalibrated, gravity, linear, rotation vectors,
     * compass at 5 Hz). The recorder's 200 Hz doubles the four raw streams; the PR has the projection.
     */
    @Test fun sizePerHourOnARealDrive() {
        val trace = File("../testdata/real/drive01_pocket.csv.gz")
        assumeTrue("needs testdata/real/drive01_pocket.csv.gz", trace.exists())
        val s = GZIPInputStream(trace.inputStream()).bufferedReader().useLines { TraceReader.read(it) }
            .filterIsInstance<TraceSample.Accel>().filter { !it.gx.isNaN() }
        val out = ByteArrayOutputStream()
        val z = GZIPOutputStream(out, 64 * 1024, true)
        val enc = LineEncoder(1 shl 20)
        val rnd = Random(1)
        val grav = doubleArrayOf(s[0].ax, s[0].ay, s[0].az)
        val q = doubleArrayOf(0.0, 0.0, 0.0, 1.0)   // x, y, z, w
        var prev = s[0].tMs
        var flushedAt = s[0].tMs
        for (p in s) {
            val t = p.tMs - s[0].tMs
            val dt = (p.tMs - prev) / 1000.0
            prev = p.tMs
            val k = dt / (0.5 + dt)
            grav[0] += k * (p.ax - grav[0]); grav[1] += k * (p.ay - grav[1]); grav[2] += k * (p.az - grav[2])
            val (x, y, zq, w) = q.toList()
            q[0] = x + 0.5 * dt * (w * p.gx + y * p.gz - zq * p.gy)
            q[1] = y + 0.5 * dt * (w * p.gy - x * p.gz + zq * p.gx)
            q[2] = zq + 0.5 * dt * (w * p.gz + x * p.gy - y * p.gx)
            q[3] = w + 0.5 * dt * (-x * p.gx - y * p.gy - zq * p.gz)
            val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            for (i in 0..3) q[i] /= n
            enc.sample(t, ResearchFormat.ACCEL, doubleArrayOf(p.ax, p.ay, p.az))
            enc.sample(t, ResearchFormat.ACCEL_UNCAL, doubleArrayOf(p.ax + 0.012, p.ay - 0.031, p.az + 0.007, 0.012, -0.031, 0.007))
            enc.sample(t, ResearchFormat.GYRO, doubleArrayOf(p.gx, p.gy, p.gz))
            enc.sample(t, ResearchFormat.GYRO_UNCAL, doubleArrayOf(p.gx + 0.0021, p.gy - 0.0013, p.gz + 0.0008, 0.0021, -0.0013, 0.0008))
            enc.sample(t, ResearchFormat.GRAVITY, grav)
            enc.sample(t, ResearchFormat.LINEAR, doubleArrayOf(p.ax - grav[0], p.ay - grav[1], p.az - grav[2]))
            enc.sample(t, ResearchFormat.GAME_ROTATION, q)
            enc.sample(t, ResearchFormat.ROTATION, doubleArrayOf(q[0], q[1], q[2], q[3], 0.524))
            if (t % 200 < 10) {
                enc.sample(t, ResearchFormat.MAGNETIC, doubleArrayOf(25 + rnd.nextGaussian() * 0.4, -12 + rnd.nextGaussian() * 0.4, 38 + rnd.nextGaussian() * 0.4))
            }
            val flush = p.tMs - flushedAt >= 2000
            if (flush || enc.room < 64 * 1024) {
                z.write(enc.bytes, 0, enc.size)
                enc.clear()
                if (flush) { z.flush(); flushedAt = p.tMs }
            }
        }
        z.write(enc.bytes, 0, enc.size)
        z.close()
        val hours = (s.last().tMs - s[0].tMs) / 3_600_000.0
        val mbPerHour = out.size() / 1e6 / hours
        println(String.format(Locale.US, "research size: %.1f MB/h at %.0f Hz (drive01 re-encoded as rr1, all streams at that rate)", mbPerHour, s.size / (hours * 3600)))
        assertTrue(String.format(Locale.US, "research files grew: %.1f MB/h", mbPerHour), mbPerHour < 40)
    }
}
