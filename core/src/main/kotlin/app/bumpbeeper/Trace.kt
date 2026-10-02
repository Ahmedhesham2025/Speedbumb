package app.bumpbeeper

import java.util.Locale

/**
 * One row of a recorded drive (the debug CSV written by the app's TraceWriter, or by [TraceWriterCore]).
 * [tMs] is milliseconds since the start of the recording.
 */
sealed class TraceSample {
    abstract val tMs: Long

    /** Accelerometer (m/s², gravity included) with the latest gyroscope values (rad/s, NaN = no gyroscope yet). */
    class Accel(
        override val tMs: Long,
        val ax: Double, val ay: Double, val az: Double,
        val gx: Double, val gy: Double, val gz: Double,
        /** The engine's vertical jolt when it was recorded. Informational only; replay recomputes it. */
        val vertical: Double = Double.NaN,
    ) : TraceSample()

    /** GPS fix. NaN = not reported. */
    class Gps(
        override val tMs: Long,
        val lat: Double, val lon: Double,
        val speedKmh: Double, val bearing: Double, val accuracyM: Double,
    ) : TraceSample()

    /** Engine/monitor event or a user label ([type] = "label", kind in [note]). */
    class Event(
        override val tMs: Long,
        val type: String,
        /** -1 if none. */
        val bumpId: Long,
        val peak: Double,
        val note: String,
    ) : TraceSample()
}

/** Something the driver marked by hand while driving: bump, pothole_l, pothole_r, rough. Placed at the nearest GPS fix. */
data class Label(val tMs: Long, val kind: String, val lat: Double, val lon: Double)

/**
 * Reads recorded drives. Columns are found by header name, so added columns don't break it; lines starting
 * with `#` (metadata) and rows of unknown type are skipped.
 */
object TraceReader {
    val HEADER = listOf(
        "t_s", "type", "ax", "ay", "az", "gx", "gy", "gz", "vertical",
        "lat", "lon", "speed_kmh", "bearing", "accuracy_m", "event", "bump_id", "peak", "note",
    )

    fun read(lines: Sequence<String>): List<TraceSample> {
        val out = ArrayList<TraceSample>()
        var cols: Map<String, Int>? = null
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split(',')
            if (cols == null) {
                val names = parts.map { it.trim() }
                if ("type" in names && "t_s" in names) {
                    cols = names.withIndex().associate { (i, n) -> n to i }
                    continue
                }
                cols = HEADER.withIndex().associate { (i, n) -> n to i }   // no header: assume the standard columns
            }
            parse(parts, cols)?.let { out.add(it) }
        }
        return out
    }

    private fun parse(p: List<String>, cols: Map<String, Int>): TraceSample? {
        fun s(name: String): String = cols[name]?.let { p.getOrNull(it) }?.trim() ?: ""
        fun d(name: String): Double = s(name).let { if (it.isEmpty()) Double.NaN else it.toDoubleOrNull() ?: Double.NaN }
        val t = d("t_s")
        if (t.isNaN()) return null
        val tMs = Math.round(t * 1000.0)
        return when (s("type")) {
            "accel" -> {
                val ax = d("ax"); val ay = d("ay"); val az = d("az")
                if (ax.isNaN() || ay.isNaN() || az.isNaN()) null
                else TraceSample.Accel(tMs, ax, ay, az, d("gx"), d("gy"), d("gz"), d("vertical"))
            }
            "gps" -> {
                val lat = d("lat"); val lon = d("lon")
                if (lat.isNaN() || lon.isNaN()) null
                else TraceSample.Gps(tMs, lat, lon, d("speed_kmh"), d("bearing"), d("accuracy_m"))
            }
            "event" -> TraceSample.Event(tMs, s("event"), s("bump_id").toLongOrNull() ?: -1L, d("peak"), s("note"))
            else -> null
        }
    }

    /** User labels in time order; an "undo" label removes the label before it. */
    fun labels(samples: List<TraceSample>): List<Label> {
        val fixes = samples.filterIsInstance<TraceSample.Gps>()
        val out = ArrayList<Label>()
        for (e in samples.filterIsInstance<TraceSample.Event>().sortedBy { it.tMs }) {
            if (e.type != "label") continue
            val kind = e.note.trim()
            if (kind == "undo") {
                if (out.isNotEmpty()) out.removeAt(out.size - 1)
                continue
            }
            val f = fixes.minByOrNull { kotlin.math.abs(it.tMs - e.tMs) }
            out.add(Label(e.tMs, kind, f?.lat ?: Double.NaN, f?.lon ?: Double.NaN))
        }
        return out
    }
}

/**
 * Writes samples in the same CSV format as the app's TraceWriter (same columns, same decimals), so simulated
 * drives can be exported and replayed like real ones. Times are written relative to [startMs]
 * (default: the first sample).
 */
object TraceWriterCore {
    fun write(samples: List<TraceSample>, out: Appendable, startMs: Long? = null) {
        out.append(TraceReader.HEADER.joinToString(",")).append('\n')
        val start = startMs ?: samples.firstOrNull()?.tMs ?: 0L
        for (smp in samples) {
            out.append(f((smp.tMs - start) / 1000.0, 3))
            when (smp) {
                is TraceSample.Accel -> {
                    out.append(",accel,")
                    out.append(f(smp.ax, 3)).append(',').append(f(smp.ay, 3)).append(',').append(f(smp.az, 3)).append(',')
                    out.append(f(smp.gx, 4)).append(',').append(f(smp.gy, 4)).append(',').append(f(smp.gz, 4)).append(',')
                    out.append(f(smp.vertical, 3)).append(",,,,,,,,,\n")
                }
                is TraceSample.Gps -> {
                    out.append(",gps,,,,,,,,")
                    out.append(f(smp.lat, 7)).append(',').append(f(smp.lon, 7)).append(',')
                    out.append(f(smp.speedKmh, 1)).append(',').append(f(smp.bearing, 0)).append(',')
                    out.append(f(smp.accuracyM, 1)).append(",,,,\n")
                }
                is TraceSample.Event -> {
                    out.append(",event,").append(",".repeat(12))   // ax..vertical and lat..accuracy_m empty
                    out.append(smp.type.replace(',', ';')).append(',')
                    out.append(if (smp.bumpId >= 0) smp.bumpId.toString() else "").append(',')
                    out.append(f(smp.peak, 2)).append(',').append(smp.note.replace(',', ';')).append('\n')
                }
            }
        }
    }

    fun toCsv(samples: List<TraceSample>, startMs: Long? = null): String =
        StringBuilder().also { write(samples, it, startMs) }.toString()

    private fun f(x: Double, digits: Int): String = if (x.isNaN()) "" else String.format(Locale.US, "%.${digits}f", x)
}
