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
 * Writes research lines into gzip segment files on its own background thread ("research-io"), so a slow disk never
 * holds up the thread that receives the sensors (Android silently drops events a listener is too slow for; here every
 * lost line is counted). The one producer thread asks [room] for a buffer before each line; full buffers go to the
 * writer, and [tick] (every 2 s) hands over a partly filled one and flushes the file. When all 16 buffers of 64 KB
 * (about 25 s at full rate) wait for the disk, [room] returns null: that line is dropped and counted ([dropped]).
 *
 * The open segment is `<name>.part` until it closes; a new one, with the full header, starts at the first [tick] after
 * [segmentMs]. Times are t units (0.1 ms), like the lines. Plain Kotlin (no Android).
 */
class ResearchWriter(
    private val file: (Int) -> File,             // final file of segment n (writer thread)
    private val header: (Int) -> List<String>,   // complete `#` lines that start segment n (writer thread)
    private val beforeSegment: () -> Unit = {},  // writer thread, before each new segment: pruning
    private val minFreeBytes: Long = 0L,         // don't start a segment with less free space than this
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
        fun reset() { enc.clear(); flush = false; endSegment = false; last = false }
    }

    private val free = ArrayBlockingQueue<Chunk>(chunks)
    // One place more than there are buffers: the last message may come with a buffer of its own (see finish).
    private val full = ArrayBlockingQueue<Chunk>(chunks + 1)
    private val drops = AtomicLong()
    private val lost = AtomicLong()   // lines the writer could not put on disk
    private val segmentT = segmentMs * ResearchFormat.T_PER_MS
    private var cur: Chunk? = null    // producer only, like the four below
    private var segStart = 0L
    private var noted = 0L            // drops already written as a `drop` line
    private var sent = 0L
    private var finished = false

    /** Why the writer stopped writing ("storage_full", "io: …"); null while all is well. */
    @Volatile var failure: String? = null
        private set

    /** Lines that never reached the file: no buffer was free, or the disk failed. */
    val dropped: Long get() = drops.get() + lost.get()

    /** Lines written so far (producer thread). */
    val lines: Long get() = sent + (cur?.enc?.lines ?: 0)

    internal val freeBuffers: Int get() = free.size

    init {
        repeat(chunks) { free.add(Chunk(chunkBytes)) }
    }

    // Declared last: the thread starts once everything above is set up. Background priority: with 25 s of buffers it
    // never needs to compete with the engine thread.
    private val thread = Thread({ drain() }, "research-io").apply {
        priority = BACKGROUND_PRIORITY
        start()
    }

    /** Producer: the buffer to write one line into (at most [LineEncoder.MAX_LINE] bytes), or null: drop the line. */
    fun room(): LineEncoder? {
        if (finished) return null
        cur?.let { if (it.enc.room >= LineEncoder.MAX_LINE) return it.enc else send(it) }
        val next = free.poll() ?: run { drops.incrementAndGet(); return null }
        cur = next
        return next.enc
    }

    /** Producer, every 2 s, at line time [t]: hands over what is buffered (the writer flushes it); a new segment after [segmentMs]. */
    fun tick(t: Long) {
        if (finished) return
        val d = drops.get()
        if (d != noted) room()?.let { it.start(t, ResearchFormat.DROP.code).int(d).end(); noted = d }
        val c = cur ?: return   // nothing buffered since the last tick: nothing to flush, a new segment waits
        val roll = t - segStart >= segmentT
        if (roll) segStart = t
        c.flush = true
        c.endSegment = roll
        send(c)
    }

    /** Producer, once at the end: [footer] lines (`key=value`) go last, then the writer closes the file and stops. */
    fun finish(footer: List<String> = emptyList()) {
        if (finished) return
        finished = true
        // The footer must fit: a nearly full buffer goes first. A disk stuck with every buffer: a small one of its own.
        cur?.let { if (it.enc.room < LineEncoder.MAX_LINE * footer.size) send(it) }
        val c = cur ?: free.poll(2, TimeUnit.SECONDS) ?: Chunk(LineEncoder.MAX_LINE * (footer.size + 1))
        cur = null
        for (f in footer) c.enc.comment(f)
        c.flush = true
        c.last = true
        full.put(c)
    }

    /** Waits (at most [ms]) until the last file is closed. */
    fun awaitClosed(ms: Long): Boolean = thread.join(ms).let { !thread.isAlive }

    private fun send(c: Chunk) {
        cur = null
        sent += c.enc.lines
        full.put(c)   // never waits: the queue has more places than there are buffers
    }

    private fun drain() {
        var out: OutputStream? = null
        var seg = 0
        while (true) {
            val c = try { full.take() } catch (e: InterruptedException) { return }
            val e = c.enc
            if (e.size > 0) {
                try {
                    if (out == null && failure == null) out = openSegment(seg)
                    val o = out
                    if (o == null) lost.addAndGet(e.lines.toLong()) else { o.write(e.bytes, 0, e.size); if (c.flush) o.flush() }
                } catch (x: Exception) {
                    failure = "io: ${x.javaClass.simpleName} ${x.message}"
                    lost.addAndGet(e.lines.toLong())
                    out?.let { close(it, seg) }
                    out = null
                }
            }
            val o = out
            if ((c.endSegment || c.last) && o != null) {
                close(o, seg)
                out = null
                seg++
            }
            val last = c.last
            c.reset()
            free.offer(c)   // the extra footer buffer may not fit: then it is simply let go
            if (last) return
        }
    }

    /** Segment [seg] as a `.part` file with its header, after pruning; null (and [failure]) when storage is short. */
    private fun openSegment(seg: Int): OutputStream? {
        try { beforeSegment() } catch (x: Exception) { /* pruning is best effort */ }
        val p = part(seg)
        p.parentFile?.mkdirs()
        if ((p.parentFile?.usableSpace ?: 0L) < minFreeBytes) {
            failure = "storage_full"
            return null
        }
        val o = GZIPOutputStream(open(p), 64 * 1024, true)   // syncFlush: flush() really flushes
        try {
            for (h in header(seg)) o.write((h + "\n").toByteArray(Charsets.UTF_8))
        } catch (x: IOException) {
            close(o, seg)
            throw x
        }
        return o
    }

    private fun part(seg: Int) = File(file(seg).path + PART)

    private fun close(o: OutputStream, seg: Int) {
        try { o.close() } catch (_: IOException) {}
        part(seg).renameTo(file(seg))
    }

    companion object {
        /** A new file every 10 minutes. */
        const val SEGMENT_MS = 10 * 60_000L
        const val PART = ".part"
        /** Java priority 4 = Android's THREAD_PRIORITY_BACKGROUND (nice 10, background scheduling group). */
        private const val BACKGROUND_PRIORITY = 4
    }
}
