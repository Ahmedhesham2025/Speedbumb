package app.bumpbeeper.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch

/** ResearchWriter: segment files on disk, flushing, and dropping lines (never waiting) when the disk is slow. */
class ResearchWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun name(seg: Int) = String.format(Locale.US, "rr_test_%03d.csv.gz", seg)

    private fun writer(
        dir: File, segmentMs: Long = ResearchWriter.SEGMENT_MS, chunkBytes: Int = 64 * 1024, chunks: Int = 16,
        minFree: Long = 0L, open: (File) -> OutputStream = { FileOutputStream(it) },
    ) = ResearchWriter({ File(dir, name(it)) }, { listOf("# format=rr1", "# segment=$it") }, {}, minFree, segmentMs, chunkBytes, chunks, open)

    private fun line(w: ResearchWriter, t: Long) {
        w.room()?.sample(t, ResearchFormat.ACCEL, floatArrayOf(0.1f, 0.2f, 9.8f))
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!ok()) {
            assertTrue(what, System.currentTimeMillis() < until)
            Thread.sleep(10)
        }
    }

    @Test fun linesReachTheFileUnderItsHeader() {
        val dir = tmp.newFolder()
        val w = writer(dir)
        for (t in 0L until 1000L) line(w, t)
        w.tick(2000)
        w.finish(listOf("end_lines=${w.lines}", "end_dropped=${w.dropped}"))
        assertTrue(w.awaitClosed(5000))
        assertEquals(listOf(name(0)), dir.list()!!.toList())   // no .part left
        val f = ResearchReader.read(File(dir, name(0)))
        assertFalse(f.truncated)
        assertEquals("rr1", f.meta["format"])
        assertEquals("0", f.meta["segment"])
        assertEquals((0L until 1000L).toList(), f.records.map { it.tMs })
        assertEquals("1000", f.meta["end_lines"])
        assertEquals("0", f.meta["end_dropped"])
    }

    @Test fun aNewFileEverySegmentAndTheClockRunsOn() {
        val dir = tmp.newFolder()
        val w = writer(dir, segmentMs = 1000)
        for (t in 0L until 3000L step 10) {
            line(w, t)
            if (t % 500 == 0L) w.tick(t)
        }
        w.finish()
        assertTrue(w.awaitClosed(5000))
        val names = dir.list()!!.sorted()
        assertEquals(listOf(name(0), name(1), name(2)), names)
        val files = names.map { ResearchReader.read(File(dir, it)) }
        assertEquals(listOf("0", "1", "2"), files.map { it.meta["segment"] })
        assertEquals((0L until 3000L step 10).toList(), files.flatMap { f -> f.records.map { it.tMs } })
    }

    @Test fun theOpenFileIsAPartFileThatCanAlreadyBeRead() {
        val dir = tmp.newFolder()
        val w = writer(dir)
        for (t in 0L until 50L) line(w, t)
        w.tick(2000)   // flushes
        val part = File(dir, name(0) + ResearchWriter.PART)
        waitFor("50 lines in the .part file") { part.exists() && ResearchReader.read(part).records.size == 50 }
        assertTrue("no gzip trailer yet", ResearchReader.read(part).truncated)
        w.finish()
        assertTrue(w.awaitClosed(5000))
        assertFalse(part.exists())
        assertEquals(50, ResearchReader.read(File(dir, name(0))).records.size)
    }

    @Test(timeout = 30_000) fun aSlowDiskDropsLinesInsteadOfWaiting() {
        val dir = tmp.newFolder()
        val disk = CountDownLatch(1)
        val w = writer(dir, chunkBytes = 1024, chunks = 2, open = { f ->
            object : FilterOutputStream(FileOutputStream(f)) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    disk.await()   // a stuck disk
                    out.write(b, off, len)
                }
            }
        })
        var kept = 0
        for (t in 0L until 5000L) {
            val e = w.room() ?: continue
            e.start(t, ResearchFormat.ACCEL.code).int(1).int(2).int(3).end()
            kept++
        }
        // Reaching this line at all means the producer never waited for the disk.
        assertTrue("kept $kept of 5000", kept in 1 until 5000)
        assertEquals(5000L - kept, w.dropped)
        disk.countDown()
        waitFor("buffers back from the disk") { w.freeBuffers > 0 }
        w.tick(6000)   // notes the drops in the file
        w.finish()
        assertTrue(w.awaitClosed(5000))
        val f = ResearchReader.read(File(dir, name(0)))
        assertEquals(kept, f.records.count { it.code == "a" })
        assertEquals((5000L - kept).toDouble(), f.records.single { it.code == "drop" }.value(0), 0.0)
    }

    @Test fun aFullDiskWritesNothing() {
        val dir = tmp.newFolder()
        val w = writer(dir, minFree = Long.MAX_VALUE)
        for (t in 0L until 100L) line(w, t)
        w.tick(2000)
        w.finish()
        assertTrue(w.awaitClosed(5000))
        assertEquals("storage_full", w.failure)
        assertEquals(0, dir.list()!!.size)
        assertEquals(100L, w.dropped)
    }
}
