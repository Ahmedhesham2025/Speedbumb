package app.bumpbeeper.research

import app.bumpbeeper.Fix
import app.bumpbeeper.RouteSampler
import app.bumpbeeper.TripPrivacy
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.IdentityHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The privacy trim before a research recording leaves the phone: every line recorded within the first and the last
 * [TripPrivacy.RADIUS_M] driven of a trip goes, whatever its code (sensors, phone state and GPS alike). The distance
 * comes from the trip's own GPS lines, speed × time as in [TripPrivacy.driven], so a GPS jump can't shorten the trim;
 * a trip shorter than twice that, without usable GPS, or in a format whose time unit isn't known here has nothing to
 * upload. Works on bytes (a 10-minute segment holds ~700,000 lines). Plain Kotlin (no Android).
 */
internal object ResearchTrim {
    /** The part of a trip that may leave the phone: lines with [fromT] ≤ t ≤ [toT], in the files' own t units. */
    class Window(val fromT: Long, val toT: Long)

    /**
     * One segment's data lines: time range (its own t units) and count, whether the file was cut off (the app was
     * killed), and its t units per millisecond from `# format=` (rr1: 1, rr2: 10; 0 = unknown, never uploaded).
     */
    class Stats(val minT: Long, val maxT: Long, val lines: Int, val truncated: Boolean, val tPerMs: Long = 1)

    /** What [scan] read: the trip's GPS fixes (times in ms, for [TripPrivacy.driven]), each fix's own t, each file's [Stats]. */
    class Scan(val fixes: List<Fix>, private val ownT: IdentityHashMap<Fix, Long>, val stats: List<Stats>) {
        fun t(f: Fix): Long = ownT.getValue(f)
        /** Every file is in a format whose time unit is known. */
        val known: Boolean get() = stats.all { it.tPerMs > 0 }
    }

    /** A segment is uploaded as it is, as a trimmed copy, or not at all. */
    enum class Plan { AS_IS, TRIM, NOTHING }

    /** t units per millisecond of each known format (ResearchFormat.VERSION). */
    private val T_PER_MS = mapOf("rr1" to 1L, "rr2" to 10L)
    private const val NL: Byte = 10
    private const val CR: Byte = 13
    private const val HASH: Byte = 35
    private const val COMMA: Byte = 44
    private const val MINUS: Byte = 45
    private const val G: Byte = 71
    /** Longer "lines" only come from a damaged file: they are skipped, never kept. */
    private const val MAX_LINE = 4096
    /** [time] of a line that has none: a `#` line, an empty or an unreadable one. */
    private const val NO_TIME = Long.MIN_VALUE

    /** Reads the segments of one trip (in order): its GPS fixes and each segment's [Stats]. */
    fun scan(segments: List<File>): Scan {
        val fixes = ArrayList<Fix>()
        val ownT = IdentityHashMap<Fix, Long>()
        val stats = segments.map { f ->
            var min = Long.MAX_VALUE
            var max = Long.MIN_VALUE
            var n = 0
            var tPerMs = 0L
            val cut = eachLine(f) { b, len ->
                val t = time(b, len)
                if (t != NO_TIME) {
                    n++
                    if (t < min) min = t
                    if (t > max) max = t
                    if (tPerMs > 0) gps(b, len, t, tPerMs)?.let { fixes.add(it); ownT[it] = t }
                } else if (len > 9 && b[0] == HASH) {
                    val s = String(b, 0, len, Charsets.US_ASCII)
                    if (s.startsWith("# format=")) tPerMs = T_PER_MS[s.substring(9).trim()] ?: 0L
                }
            }
            Stats(min, max, n, cut, tPerMs)
        }
        return Scan(fixes, ownT, stats)
    }

    /**
     * From the first fix past [radiusM] driven to the last fix more than [radiusM] before the end. Null when the trip
     * is shorter than 2 × [radiusM], has no usable GPS or a format not known here: then nothing of it may be uploaded.
     */
    fun window(s: Scan, radiusM: Double = TripPrivacy.RADIUS_M): Window? {
        if (!s.known) return null
        val ordered = RouteSampler.inTimeOrder(s.fixes)
        if (ordered.size < 2) return null
        val driven = TripPrivacy.driven(ordered)
        val total = driven.last()
        if (!(total >= 2 * radiusM)) return null   // NaN too
        val from = driven.indexOfFirst { it > radiusM }
        val to = driven.indexOfLast { total - it > radiusM }
        if (from < 0 || to < 0 || s.t(ordered[from]) > s.t(ordered[to])) return null
        return Window(s.t(ordered[from]), s.t(ordered[to]))
    }

    fun plan(s: Stats, w: Window): Plan = when {
        s.lines == 0 || s.tPerMs <= 0L || s.maxT < w.fromT || s.minT > w.toT -> Plan.NOTHING
        s.minT >= w.fromT && s.maxT <= w.toT && !s.truncated -> Plan.AS_IS
        else -> Plan.TRIM   // a cut-off file is always re-written: the copy is a complete gzip
    }

    /**
     * The file to upload for segment [src]: [src] itself when all of it lies inside [w], else a trimmed copy in
     * [cacheDir] (kept there until it is uploaded, so a retry sends exactly the bytes reserved), or null when nothing
     * of it lies inside [w]. [stats] saves a second read when known.
     */
    fun prepare(src: File, w: Window, stats: Stats?, cacheDir: File): File? {
        val cached = File(cacheDir, src.name)
        if (cached.exists()) return cached
        return when (plan(stats ?: scan(listOf(src)).stats[0], w)) {
            Plan.NOTHING -> null
            Plan.AS_IS -> src
            Plan.TRIM -> if (copy(src, cached, w) > 0) cached else null
        }
    }

    /**
     * Writes the lines of [src] inside [w] to [dst] as a new, complete gzip file: `#` lines (header and footer) stay,
     * every data line outside [w] goes (and any line without a readable time), and `# trim_…` lines record what was
     * done (times in the file's own t units). Returns the data lines kept; with none, [dst] is not created.
     */
    fun copy(src: File, dst: File, w: Window): Int {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".tmp")
        var kept = 0
        var dropped = 0
        try {
            GZIPOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024), 64 * 1024).use { out ->
                var noted = false
                fun note() {
                    if (noted) return
                    noted = true
                    out.write(ascii("# trim=first_and_last_${TripPrivacy.RADIUS_M.toInt()}m_driven\n" +
                        "# trim_from_t=${w.fromT}\n# trim_to_t=${w.toT}\n"))
                }
                val cut = eachLine(src) { b, len ->
                    val t = time(b, len)
                    when {
                        t != NO_TIME -> {
                            note()   // after the header, before the first data line
                            if (t in w.fromT..w.toT) { out.write(b, 0, len); out.write(NL.toInt()); kept++ } else dropped++
                        }
                        len > 0 && b[0] == HASH -> { out.write(b, 0, len); out.write(NL.toInt()) }
                        len > 0 -> dropped++
                    }
                }
                note()
                out.write(ascii("# trim_dropped_lines=$dropped\n" + if (cut) "# trim_source_cut_off=1\n" else ""))
            }
            if (kept == 0) {
                tmp.delete()
                return 0
            }
            if (!tmp.renameTo(dst)) throw IOException("trimmed copy not saved")
            return kept
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    /**
     * Calls [line] with each complete line of a gzip (or plain text) file, without its line break. True if the file
     * was cut off (no gzip trailer, or a last line without its break: the app died while writing it).
     */
    private fun eachLine(f: File, line: (ByteArray, Int) -> Unit): Boolean {
        var cur = ByteArray(512)
        var n = 0
        var junk = false
        var cut = false
        BufferedInputStream(f.inputStream(), 64 * 1024).use { raw ->
            raw.mark(2)
            val gzip = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val buf = ByteArray(64 * 1024)
            val src: InputStream = if (gzip) GZIPInputStream(raw, 64 * 1024) else raw
            try {
                while (true) {
                    val k = src.read(buf)
                    if (k < 0) break
                    for (i in 0 until k) {
                        val c = buf[i]
                        if (c == NL) {
                            if (!junk) line(cur, if (n > 0 && cur[n - 1] == CR) n - 1 else n)
                            n = 0
                            junk = false
                        } else if (!junk) {
                            if (n == MAX_LINE) { junk = true; continue }
                            if (n == cur.size) cur = cur.copyOf(minOf(n * 2, MAX_LINE))
                            cur[n++] = c
                        }
                    }
                }
            } catch (e: IOException) {
                cut = true   // "Unexpected end of ZLIB input stream": the writer never closed it
            } finally {
                if (src !== raw) try { src.close() } catch (_: IOException) {}   // frees the Inflater's native memory
            }
        }
        return cut || n > 0 || junk
    }

    /** t of a data line (`t,code,…`), or [NO_TIME]. */
    private fun time(b: ByteArray, len: Int): Long {
        if (len == 0 || b[0] == HASH) return NO_TIME
        val neg = b[0] == MINUS
        var i = if (neg) 1 else 0
        val start = i
        var v = 0L
        while (i < len) {
            val d = b[i] - 48
            if (d !in 0..9) break
            v = v * 10 + d
            i++
        }
        if (i == start || i - start > 18 || i >= len || b[i] != COMMA) return NO_TIME
        return if (neg) -v else v
    }

    /**
     * A `G` line (`t,G,lat,lon,alt,speed,bearing,accuracy,…` in the fixed units of [ResearchFormat.GPS]) as a fix whose
     * time is [t] in milliseconds ([tPerMs] t units each).
     */
    private fun gps(b: ByteArray, len: Int, t: Long, tPerMs: Long): Fix? {
        var i = 0
        while (i < len && b[i] != COMMA) i++
        if (i + 2 >= len || b[i + 1] != G || b[i + 2] != COMMA) return null
        i += 3
        val v = LongArray(6)
        val has = BooleanArray(6)
        for (k in 0 until 6) {
            if (i > len) break
            val neg = i < len && b[i] == MINUS
            if (neg) i++
            var x = 0L
            var digits = 0
            while (i < len) {
                val d = b[i] - 48
                if (d !in 0..9) break
                x = x * 10 + d
                i++
                digits++
            }
            if (digits in 1..18 && (i == len || b[i] == COMMA)) { v[k] = if (neg) -x else x; has[k] = true }
            while (i < len && b[i] != COMMA) i++
            i++
        }
        if (!has[0] || !has[1]) return null
        val s = ResearchFormat.GPS.scales
        fun value(k: Int) = if (has[k]) v[k] / s[k] else Double.NaN
        return Fix(Math.floorDiv(t, tPerMs), value(0), value(1), value(3), value(4), value(5))
    }
}
