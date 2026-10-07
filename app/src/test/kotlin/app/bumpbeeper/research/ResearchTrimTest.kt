package app.bumpbeeper.research

import app.bumpbeeper.research.ResearchTrim.Plan
import app.bumpbeeper.research.ResearchTrim.Stats
import app.bumpbeeper.research.ResearchTrim.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The privacy trim: every kind of line goes from the first and last 300 m driven, measured by speed × time. */
class ResearchTrimTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A research file; [closed] = false leaves it without its gzip trailer, as a killed app does. */
    private fun file(name: String, lines: List<String>, format: String = ResearchFormat.VERSION, closed: Boolean = true): File {
        val f = File(tmp.root, name)
        val fos = FileOutputStream(f)
        val gz = GZIPOutputStream(fos, true)
        val header = listOf("# format=$format", "# device=Maker/Model", "# segment=0")
        gz.write((header + lines).joinToString("\n", postfix = "\n").toByteArray())
        if (closed) gz.close() else { gz.flush(); fos.close() }
        return f
    }

    /** All lines of a gzip file; throws if it isn't complete. */
    private fun lines(f: File): List<String> = GZIPInputStream(f.inputStream()).bufferedReader().use { it.readLines() }
    private fun data(f: File) = lines(f).filter { !it.startsWith("#") }
    private fun meta(f: File) = lines(f).filter { it.startsWith("# ") && it.contains('=') }
        .associate { it.substring(2).substringBefore('=') to it.substringAfter('=') }
    private fun t(line: String) = line.substringBefore(',').toLong()

    /** Driving north at 10 m/s: a fix every second and every other kind of line in between; t in 0.1 ms (rr2). */
    private fun drive(fromMs: Long, toMs: Long, speedCmS: String = "1000", latStep: Long = 898): List<String> {
        val out = ArrayList<String>()
        if (fromMs == 0L) out.add("-50000,lx,40")   // an on-change sensor's value from before the start
        for (ms in fromMs until toMs step 100) {
            val t = ms * ResearchFormat.T_PER_MS
            out.add("$t,a,10,20,9810")
            out.add("$t,g,1,2,3")
            if (ms % 1000 == 0L) out.add("$t,G,${300_000_000 + ms / 1000 * latStep},312000000,,$speedCmS,0,400,,,,5")
            when (ms % 10_000) {
                500L -> out.add("$t,scr,1")
                1500L -> out.add("$t,unl")
                2500L -> out.add("$t,aud,2,1,7")
                3500L -> out.add("$t,bat,80,2,2,301")
                4500L -> out.add("$t,lbl,bump")
                5500L -> out.add("$t,S,9,20,312")
                6500L -> out.add("$t,lk,0")
            }
        }
        return out
    }

    @Test fun everyKindOfLineGoesFromBothEnds() {
        val first = drive(0, 60_000)
        val last = drive(60_000, 120_001)   // 1,200 m in all
        val s0 = file("rr_0a1b2c3d_20261007T080000_000.csv.gz", first)
        val s1 = file("rr_0a1b2c3d_20261007T080000_001.csv.gz", last)
        val scan = ResearchTrim.scan(listOf(s0, s1))
        val w = ResearchTrim.window(scan)!!
        assertEquals("first fix past 300 m: 31 s, in 0.1 ms", 310_000L, w.fromT)
        assertEquals("last fix more than 300 m before the end", 890_000L, w.toT)
        assertEquals(listOf(Plan.TRIM, Plan.TRIM), scan.stats.map { ResearchTrim.plan(it, w) })

        for ((src, all) in listOf(s0 to first, s1 to last)) {
            val out = File(tmp.root, "upload/" + src.name)
            val kept = ResearchTrim.copy(src, out, w)
            val inside = all.filter { t(it) in 310_000L..890_000L }
            assertEquals("exactly the lines inside, as written, in order", inside, data(out))
            assertEquals(inside.size, kept)
            assertEquals("every kind of line inside stays", setOf("a", "g", "G", "scr", "unl", "aud", "bat", "lbl", "S", "lk"),
                data(out).map { it.split(',')[1] }.toSet())
            val m = meta(out)
            assertEquals("the header stays", "Maker/Model", m["device"])
            assertEquals(ResearchFormat.VERSION, m["format"])
            assertEquals("310000", m["trim_from_t"])
            assertEquals("890000", m["trim_to_t"])
            assertEquals((all.size - kept).toString(), m["trim_dropped_lines"])
        }
    }

    @Test fun onlyTheCurrentFormatIsUploaded() {
        for (format in listOf("rr1", "rr9")) {   // rr1 (whole ms) was never released
            val f = file("rr_0a1b2c3d_20261007T083000_000.csv.gz", drive(0, 120_001), format = format)
            val scan = ResearchTrim.scan(listOf(f))
            assertFalse(scan.known)
            assertNull(format, ResearchTrim.window(scan))
            assertEquals(Plan.NOTHING, ResearchTrim.plan(scan.stats[0], Window(0, Long.MAX_VALUE)))
        }
    }

    @Test fun aTripShorterThan600mUploadsNothing() {
        val short = file("rr_0a1b2c3d_20261007T090000_000.csv.gz", drive(0, 59_001))   // 590 m
        assertNull(ResearchTrim.window(ResearchTrim.scan(listOf(short))))
        val exactly = file("rr_0a1b2c3d_20261007T100000_000.csv.gz", drive(0, 60_001))   // 600 m: nothing in between
        assertNull(ResearchTrim.window(ResearchTrim.scan(listOf(exactly))))
        val noGps = file("rr_0a1b2c3d_20261007T110000_000.csv.gz", drive(0, 120_001).filter { !it.contains(",G,") })
        assertNull(ResearchTrim.window(ResearchTrim.scan(listOf(noGps))))
    }

    @Test fun distanceIsSpeedTimesTimeSoAGpsJumpDoesNotShortenTheTrim() {
        // At 5 s a fix lands 5 km away, but the speed still says 10 m/s.
        val lines = drive(0, 120_001).map { if (it.startsWith("50000,G,")) "50000,G,300500000,312000000,,1000,0,400,,,,5" else it }
        val w = ResearchTrim.window(ResearchTrim.scan(listOf(file("rr_0a1b2c3d_20261007T120000_000.csv.gz", lines))))!!
        assertEquals(310_000L, w.fromT)
        assertEquals(890_000L, w.toT)
    }

    @Test fun withoutSpeedItMeasuresStraightLines() {
        // No speed, 0.001° of latitude (about 111 m) between fixes: 300 m is passed at the third step.
        val lines = drive(0, 10_001, speedCmS = "", latStep = 10_000)
        val w = ResearchTrim.window(ResearchTrim.scan(listOf(file("rr_0a1b2c3d_20261007T130000_000.csv.gz", lines))))!!
        assertEquals(30_000L, w.fromT)
        assertEquals(70_000L, w.toT)
    }

    @Test fun aSegmentInsideGoesAsItIsOneOutsideNotAtAll() {
        val w = Window(10_000, 50_000)
        assertEquals(Plan.AS_IS, ResearchTrim.plan(Stats(10_000, 50_000, 10, false), w))
        assertEquals("cut off: re-written whole", Plan.TRIM, ResearchTrim.plan(Stats(10_000, 50_000, 10, true), w))
        assertEquals(Plan.TRIM, ResearchTrim.plan(Stats(5_000, 15_000, 10, false), w))
        assertEquals(Plan.NOTHING, ResearchTrim.plan(Stats(0, 9_999, 10, false), w))
        assertEquals(Plan.NOTHING, ResearchTrim.plan(Stats(50_001, 90_000, 10, false), w))
        assertEquals("only # lines", Plan.NOTHING, ResearchTrim.plan(Stats(Long.MAX_VALUE, Long.MIN_VALUE, 0, false), w))
    }

    @Test fun aCutOffFileBecomesACompleteOne() {
        val src = file("rr_0a1b2c3d_20261007T140000_001.csv.gz", drive(60_000, 70_000), closed = false)
        val stats = ResearchTrim.scan(listOf(src)).stats[0]
        assertTrue(stats.truncated)
        val out = ResearchTrim.prepare(src, Window(0, 1_000_000), stats, File(tmp.root, "upload"))!!
        assertEquals("1", meta(out)["trim_source_cut_off"])
        assertEquals(drive(60_000, 70_000), data(out))
    }

    @Test fun prepareKeepsItsCopySoARetrySendsTheSameBytes() {
        val cache = File(tmp.root, "upload")
        val w = Window(310_000, 890_000)
        val inside = file("rr_0a1b2c3d_20261007T150000_000.csv.gz", drive(40_000, 50_000))
        assertSame("all inside: the file itself", inside, ResearchTrim.prepare(inside, w, null, cache))
        assertNull("all outside: nothing", ResearchTrim.prepare(inside, Window(0, 300_000), null, cache))
        val partly = file("rr_0a1b2c3d_20261007T150000_001.csv.gz", drive(80_000, 100_000))
        val copy = ResearchTrim.prepare(partly, w, null, cache)!!
        val bytes = copy.readBytes()
        assertEquals(File(cache, partly.name), copy)
        assertTrue(ResearchTrim.prepare(partly, w, null, cache)!!.readBytes().contentEquals(bytes))
    }
}
