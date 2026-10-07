package app.bumpbeeper.research

import app.bumpbeeper.Fix
import app.bumpbeeper.RouteSampler
import app.bumpbeeper.TripPrivacy
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.util.IdentityHashMap
import java.util.zip.GZIPOutputStream

/**
 * The privacy trim before a research recording leaves the phone: every line, whatever its code (sensors, phone state
 * and GPS alike), recorded before the car has both driven [TripPrivacy.RADIUS_M] and left that radius around where the
 * trip started, or after it came that close to where it ended (the rule of `sync.PrivacyZone` and
 * [RouteSampler.sample]). Driven = speed × time from the trip's own GPS lines ([TripPrivacy.driven]), so a GPS jump
 * can't shorten it; the radius ([TripPrivacy.anchors]) catches a parked phone whose speed jitter or wandering fixes add
 * up to "distance". A trip shorter than twice the radius, without usable GPS, or not in the current format
 * ([ResearchFormat.VERSION]) has nothing to upload. Files are read with [ResearchReader], as tools/replay reads them.
 * Plain Kotlin (no Android).
 */
internal object ResearchTrim {
    /** The part of a trip that may leave the phone: lines with [fromT] ≤ t ≤ [toT] (t in 0.1 ms, see [ResearchFormat]). */
    class Window(val fromT: Long, val toT: Long)

    /** One segment's data lines: time range and count, whether the file was cut off (the app was killed), current format. */
    class Stats(val minT: Long, val maxT: Long, val lines: Int, val truncated: Boolean, val known: Boolean = true)

    /** What [scan] read: the trip's GPS fixes (times in ms, for [TripPrivacy.driven]), each fix's own t, each file's [Stats]. */
    class Scan(val fixes: List<Fix>, private val ownT: IdentityHashMap<Fix, Long>, val stats: List<Stats>) {
        fun t(f: Fix): Long = ownT.getValue(f)
        /** Every file is in the current format, whose time unit is known. */
        val known: Boolean get() = stats.all { it.known }
    }

    /** A segment is uploaded as it is, as a trimmed copy, or not at all. */
    enum class Plan { AS_IS, TRIM, NOTHING }

    /** Reads the segments of one trip (in order): its GPS fixes and each segment's [Stats]. */
    fun scan(segments: List<File>): Scan {
        val fixes = ArrayList<Fix>()
        val ownT = IdentityHashMap<Fix, Long>()
        val stats = segments.map { f ->
            var min = Long.MAX_VALUE
            var max = Long.MIN_VALUE
            var n = 0
            var known = false
            val cut = ResearchReader.scan(f.inputStream(), { k, v -> if (k == "format") known = v == ResearchFormat.VERSION }) { r ->
                n++
                if (r.tDms < min) min = r.tDms
                if (r.tDms > max) max = r.tDms
                if (r.code == ResearchFormat.GPS.code) gps(r)?.let { fixes.add(it); ownT[it] = r.tDms }
            }
            Stats(min, max, n, cut, known)
        }
        return Scan(fixes, ownT, stats)
    }

    /**
     * From the first fix past [radiusM] driven and outside [radiusM] of the trip's start and end ([TripPrivacy.anchors])
     * to the last such fix more than [radiusM] before the end. Null when there is none (shorter than 2 × [radiusM], or
     * never out of the zone), without usable GPS or not in the current format: then nothing of the trip is uploaded.
     */
    fun window(s: Scan, radiusM: Double = TripPrivacy.RADIUS_M): Window? {
        if (!s.known) return null
        val ordered = RouteSampler.inTimeOrder(s.fixes)
        if (ordered.size < 2) return null
        val driven = TripPrivacy.driven(ordered)
        val total = driven.last()
        if (!(total >= 2 * radiusM)) return null   // NaN too
        val anchors = TripPrivacy.anchors(ordered)
        fun away(i: Int) = TripPrivacy.outside(ordered[i].lat, ordered[i].lon, anchors, radiusM)
        val from = driven.indices.firstOrNull { driven[it] > radiusM && away(it) } ?: return null
        val to = driven.indices.lastOrNull { total - driven[it] > radiusM && away(it) } ?: return null
        if (s.t(ordered[from]) > s.t(ordered[to])) return null
        return Window(s.t(ordered[from]), s.t(ordered[to]))
    }

    fun plan(s: Stats, w: Window): Plan = when {
        s.lines == 0 || !s.known || s.maxT < w.fromT || s.minT > w.toT -> Plan.NOTHING
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
        if (cached.exists()) return cached   // made with this same window: a trip's window is found once and kept
        return when (plan(stats ?: scan(listOf(src)).stats[0], w)) {
            Plan.NOTHING -> null
            Plan.AS_IS -> src
            Plan.TRIM -> if (copy(src, cached, w) > 0) cached else null
        }
    }

    /**
     * Writes the lines of [src] inside [w] to [dst] as a new, complete gzip file: `# key=value` lines (header and
     * footer) stay, every data line outside [w] goes (lines the reader skips go too), and `# trim_…` lines record what
     * was done. Kept lines are written back exactly as read. Returns the data lines kept; with none, [dst] isn't created.
     */
    fun copy(src: File, dst: File, w: Window): Int {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".tmp")
        var kept = 0
        var dropped = 0
        try {
            GZIPOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024), 64 * 1024).use { out ->
                // Not an IOException inside the reader: it would take a failed write for a cut-off source file.
                fun write(s: String) = try { out.write(s.toByteArray(Charsets.UTF_8)) } catch (e: IOException) { throw UncheckedIOException(e) }
                var noted = false
                fun note() {
                    if (noted) return
                    noted = true
                    write("# trim=first_and_last_${TripPrivacy.RADIUS_M.toInt()}m_driven\n# trim_from_t=${w.fromT}\n# trim_to_t=${w.toT}\n")
                }
                val cut = ResearchReader.scan(src.inputStream(), { k, v -> write("# $k=$v\n") }) { r ->
                    note()   // after the header, before the first data line
                    if (r.tDms in w.fromT..w.toT) { write(line(r)); kept++ } else dropped++
                }
                note()
                write("# trim_dropped_lines=$dropped\n" + if (cut) "# trim_source_cut_off=1\n" else "")
            }
            if (kept == 0) {
                tmp.delete()
                return 0
            }
            if (!tmp.renameTo(dst)) throw IOException("trimmed copy not saved")
            return kept
        } catch (e: UncheckedIOException) {
            tmp.delete()
            throw e.cause ?: IOException(e)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** A data line as the recorder wrote it: `t,code,values…` (the reader split it at the commas). */
    private fun line(r: ResearchRecord): String = buildString {
        append(r.tDms).append(',').append(r.code)
        for (f in r.fields) append(',').append(f)
        append('\n')
    }

    /** A `G` line as a fix whose time is in milliseconds. */
    private fun gps(r: ResearchRecord): Fix? {
        val lat = r.value(0)
        val lon = r.value(1)
        if (lat.isNaN() || lon.isNaN()) return null
        return Fix(Math.floorDiv(r.tDms, ResearchFormat.T_PER_MS), lat, lon, r.value(3), r.value(4), r.value(5))
    }
}
