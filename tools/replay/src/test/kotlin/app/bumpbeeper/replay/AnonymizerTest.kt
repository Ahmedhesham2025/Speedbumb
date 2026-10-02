package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.Random

class AnonymizerTest {
    /** A wandering 3 km drive in Cairo that starts 100 s into the recording, with labels and an undo. */
    private fun rawTrace(): String {
        val rnd = Random(3)
        val s = ArrayList<TraceSample>()
        var p = doubleArrayOf(30.0444, 31.2357)
        var brg = 40.0
        for (i in 0 until 300) {
            val t = 100_000L + i * 1000L
            s.add(TraceSample.Accel(t - 500, 0.1, 0.2, 9.8, 0.0, 0.01, 0.0))
            s.add(TraceSample.Gps(t, p[0], p[1], 36.0, brg, 4.0))
            brg = (brg + rnd.nextGaussian() * 15 + 360) % 360
            p = Geo.move(p[0], p[1], brg, 10.0)
        }
        s.add(TraceSample.Event(150_200, "label", -1, Double.NaN, "pothole_l"))
        s.add(TraceSample.Event(250_200, "label", -1, Double.NaN, "bump"))
        s.add(TraceSample.Event(250_900, "label", -1, Double.NaN, "undo"))
        s.add(TraceSample.Event(320_000, "label", -1, Double.NaN, "rough"))
        val meta = "# app_version=1.5.0\n# device=samsung/SM-A515F\n# android=33\n# placement=holder\n# gyro=yes\n"
        return meta + TraceWriterCore.toCsv(s.sortedBy { it.tMs }, startMs = 0)
    }

    private val raw = rawTrace()
    private val anon = Anonymizer.anonymize(raw.lines())
    private val before = TraceReader.read(raw.lineSequence())
    private val after = TraceReader.read(anon.asSequence())

    @Test fun distancesArePreserved() {
        val a = before.filterIsInstance<TraceSample.Gps>()
        val b = after.filterIsInstance<TraceSample.Gps>()
        assertEquals(a.size, b.size)
        var worst = 0.0
        for (i in a.indices step 7) for (j in a.indices step 11) {
            val d1 = Geo.distance(a[i].lat, a[i].lon, a[j].lat, a[j].lon)
            val d2 = Geo.distance(b[i].lat, b[i].lon, b[j].lat, b[j].lon)
            worst = maxOf(worst, kotlin.math.abs(d1 - d2))
        }
        println(String.format(Locale.US, "worst distance change %.3f m", worst))
        assertTrue("distances changed by up to $worst m", worst <= 0.5)
        assertEquals(Anonymizer.FAKE_LAT, b[0].lat, 1e-7)
        assertEquals(Anonymizer.FAKE_LON, b[0].lon, 1e-7)
    }

    @Test fun noOriginalCoordinateOrDeviceRemains() {
        val text = anon.joinToString("\n")
        for (g in before.filterIsInstance<TraceSample.Gps>()) {
            assertFalse(text.contains(String.format(Locale.US, "%.7f", g.lat)))
            assertFalse(text.contains(String.format(Locale.US, "%.7f", g.lon)))
        }
        assertFalse(text.contains("30.04"))
        assertFalse(text.contains("31.23"))
        assertFalse(text.contains("device"))
        assertFalse(text.contains("samsung"))
        assertTrue(text.contains("# android=33"))
        assertTrue(text.contains("# placement=holder"))
        assertTrue(text.contains("# gyro=yes"))
        for (g in after.filterIsInstance<TraceSample.Gps>()) assertTrue(g.lat in 0.4..0.6 && g.lon in 0.4..0.6)
    }

    @Test fun clockStartsAtZeroAndLabelsSurvive() {
        assertEquals(0L, after.first().tMs)
        assertEquals(before.size, after.size)
        for (i in before.indices) assertEquals(before[i].tMs - before[0].tMs, after[i].tMs)
        val l1 = TraceReader.labels(before)
        val l2 = TraceReader.labels(after)
        assertEquals(listOf("pothole_l", "rough"), l2.map { it.kind })
        assertEquals(l1.map { it.kind }, l2.map { it.kind })
    }
}
