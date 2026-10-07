package app.bumpbeeper.research

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/**
 * Writes research lines into gzip segment files on its own thread ("research-io"), so a slow disk never holds up the
 * thread that receives the sensors (Android silently drops sensor events a listener is too slow for; here every lost
 * line is counted).
 *
 * One producer thread asks [room] for a buffer before each line. Full buffers go to the writer thread, and [tick]
 * (every 2 s) also hands over a partly filled one and flushes the file. When every buffer is still waiting for the disk,
 * [room] returns null: that line is dropped and counted ([dropped]), never waited for. 16 buffers of 64 KB hold about
 * 25 s of data at full rate.
 *
 * The open segment is `<name>.part` and gets its final name when it closes. A new segment, with the full header, starts
 * at the first [tick] after [segmentMs]. Plain Kotlin (no Android).
 */
class ResearchWriter(
    /** Final file of segment n (0, 1, …). Writer thread. */
    private val file: (Int) -> File,
    /** Complete `#` lines that start segment n. Writer thread. */
    private val header: (Int) -> List<String>,
    /** Writer thread, before each new segment: pruning old files. */
    private val beforeSegment: () -> Unit = {},
    /** Don't start a segment with less free space than this. */
    private val minFreeBytes: Long = 0L,
    private val segmentMs: Long = SEGMENT_MS,
    chunkBytes: Int = 64 * 1024,
    chunks: Int = 16,
    private val open: (File) -> OutputStream = { FileOutputStream(it) },
) {
    private class Chunk(bytes: Int) {
        val enc = LineEncoder(bytes)
        var flush = false
        var endSegment = false
        var last = false
    }

    private val free = ArrayBlockingQueue<Chunk>(chunks)
    // One place more than there are buffers: the last message may come with a buffer of its own (see finish).
    private val full = ArrayBlockingQueue<Chunk>(chunks + 1)
    private val drops = AtomicLong()
    private val lost = AtomicLong()   // lines the writer could not put on disk
    private var cur: Chunk? = null    // producer only, like the four below
    private var segStartMs = 0L
    private var noted = 0L            // drops already written as a `drop` line
    private var sent = 0L
    private var finished = false

    /** Why the writer stopped writing ("storage_full", "io: …"); null while all is well. */
    @Volatile
    var failure: String? = null
        private set

    /** Lines that never reached the file: no buffer was free, or the disk failed. */
    val dropped: Long get() = drops.get() + lost.get()

    /** Lines written so far (producer thread). */
    val lines: Long get() = sent + (cur?.enc?.lines ?: 0)

    internal val freeBuffers: Int get() = free.size

    init {
        repeat(chunks) { free.add(Chunk(chunkBytes)) }
    }

    // Declared last: the thread starts once everything above is set up.
    private val thread = Thread({ drain() }, "research-io").apply { start() }

    /** Producer: the buffer to write one line into (at most [LineEncoder.MAX_LINE] bytes), or null: drop the line. */
    fun room(): LineEncoder? {
        if (finished) return null
        val c = cur
        if (c != null) {
            if (c.enc.room >= LineEncoder.MAX_LINE) return c.enc
            send(c)
        }
        val next = free.poll()
        if (next == null) {
            drops.incrementAndGet()
            return null
        }
        cur = next
        return next.enc
    }

    /** Producer, every 2 s: hands over what is buffered (the writer flushes it); a new segment after [segmentMs]. */
    fun tick(tMs: Long) {
        if (finished) return
        val d = drops.get()
        if (d != noted) room()?.let { it.start(tMs, ResearchFormat.DROP.code).int(d).end(); noted = d }
        val c = cur ?: return   // nothing buffered since the last tick: nothing to flush, a new segment waits
        val roll = tMs - segStartMs >= segmentMs
        if (roll) segStartMs = tMs
        c.flush = true
        c.endSegment = roll
        send(c)
    }

    /** Producer, once at the end: [footer] lines (`key=value`) go last, then the writer closes the file and stops. */
    fun finish(footer: List<String> = emptyList()) {
        if (finished) return
        finished = true
        // The disk may be stuck with every buffer: then a small one of its own carries the footer.
        val c = cur ?: free.poll(2, TimeUnit.SECONDS) ?: Chunk(LineEncoder.MAX_LINE * 4)
        cur = null
        for (f in footer) if (c.enc.room >= LineEncoder.MAX_LINE) c.enc.comment(f)
        c.flush = true
        c.last = true
        full.put(c)
    }

    /** Waits (at most [ms]) until the last file is closed. */
    fun awaitClosed(ms: Long): Boolean {
        thread.join(ms)
        return !thread.isAlive
    }

    private fun send(c: Chunk) {
        cur = null
        sent += c.enc.lines
        full.put(c)   // never waits: the queue has more places than there are buffers
    }

    private fun drain() {
        var out: OutputStream? = null
        var part: File? = null
        var seg = 0
        while (true) {
            val c = try { full.take() } catch (e: InterruptedException) { return }
            val e = c.enc
            try {
                if (e.size > 0) {
                    if (out == null && failure == null) {
                        try { beforeSegment() } catch (x: Exception) { /* pruning is best effort */ }
                        val p = File(file(seg).path + PART)
                        p.parentFile?.mkdirs()
                        if ((p.parentFile?.usableSpace ?: 0L) < minFreeBytes) {
                            failure = "storage_full"
                        } else {
                            val o = GZIPOutputStream(open(p), 64 * 1024, true)   // syncFlush: flush() really flushes
                            part = p
                            out = o
                            for (h in header(seg)) o.write((h + "\n").toByteArray(Charsets.UTF_8))
                        }
                    }
                    val o = out
                    if (o == null) {
                        lost.addAndGet(e.lines.toLong())
                    } else {
                        o.write(e.bytes, 0, e.size)
                        if (c.flush) o.flush()
                    }
                }
            } catch (x: Exception) {
                failure = "io: ${x.javaClass.simpleName} ${x.message}"
                lost.addAndGet(e.lines.toLong())
                out?.let { close(it, part) }
                out = null
                part = null
            }
            val o = out
            if ((c.endSegment || c.last) && o != null) {
                close(o, part)
                out = null
                part = null
                seg++
            }
            val last = c.last
            e.clear()
            c.flush = false
            c.endSegment = false
            c.last = false
            free.offer(c)   // the extra footer buffer may not fit: then it is simply let go
            if (last) return
        }
    }

    private fun close(o: OutputStream, p: File?) {
        try { o.close() } catch (_: IOException) {}
        if (p != null) p.renameTo(File(p.path.removeSuffix(PART)))
    }

    companion object {
        /** A new file every 10 minutes. */
        const val SEGMENT_MS = 10 * 60_000L
        const val PART = ".part"
    }
}
