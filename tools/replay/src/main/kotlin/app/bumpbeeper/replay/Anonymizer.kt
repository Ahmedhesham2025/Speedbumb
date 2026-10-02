package app.bumpbeeper.replay

import app.bumpbeeper.*
import java.util.Locale
import kotlin.math.*

/**
 * Makes a recording safe to commit: the first GPS fix moves to a fake origin (0.5, 0.5) and every other point keeps
 * its distance and bearing from it, the clock starts at 0, and only harmless metadata is kept (no `device=`).
 * Every other column, labels included, is copied unchanged.
 */
object Anonymizer {
    const val FAKE_LAT = 0.5
    const val FAKE_LON = 0.5
    /** Metadata worth keeping. Anything else (device=, unknown keys) is dropped. */
    val KEEP_META = setOf("app_version", "android", "placement", "gyro")
    private const val EARTH_R = 6_371_000.0   // same sphere as Geo

    fun anonymize(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var cols: Map<String, Int>? = null
        var t0 = Double.NaN
        var origin: DoubleArray? = null
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                val body = line.removePrefix("#").trim()
                if (body.substringBefore('=').trim() in KEEP_META && '=' in body) out.add("# $body")
                continue
            }
            val parts = line.split(',').toMutableList()
            var c = cols
            if (c == null) {
                val names = parts.map { it.trim() }
                if ("type" in names && "t_s" in names) {
                    cols = names.withIndex().associate { (i, n) -> n to i }
                    out.add(line)
                    continue
                }
                c = TraceReader.HEADER.withIndex().associate { (i, n) -> n to i }   // no header: standard columns
                cols = c
                out.add(TraceReader.HEADER.joinToString(","))
            }
            val ti = c["t_s"]
            val t = ti?.let { parts.getOrNull(it)?.trim()?.toDoubleOrNull() }
            if (ti != null && t != null) {
                if (t0.isNaN()) t0 = t
                parts[ti] = String.format(Locale.US, "%.3f", t - t0)
            }
            val la = c["lat"]
            val lo = c["lon"]
            if (la != null && lo != null && la < parts.size && lo < parts.size) {
                val lat = parts[la].trim().toDoubleOrNull()
                val lon = parts[lo].trim().toDoubleOrNull()
                if (lat != null && lon != null) {
                    val o = origin ?: doubleArrayOf(lat, lon).also { origin = it }
                    val p = shift(o[0], o[1], lat, lon)
                    parts[la] = String.format(Locale.US, "%.7f", p[0])
                    parts[lo] = String.format(Locale.US, "%.7f", p[1])
                } else {
                    parts[la] = ""   // never leave half a coordinate behind
                    parts[lo] = ""
                }
            }
            out.add(parts.joinToString(","))
        }
        return out
    }

    /** Same distance and bearing from the fake origin as the point had from [lat0],[lon0] (great circle). */
    fun shift(lat0: Double, lon0: Double, lat: Double, lon: Double): DoubleArray {
        val d = Geo.distance(lat0, lon0, lat, lon)
        if (d == 0.0) return doubleArrayOf(FAKE_LAT, FAKE_LON)
        val b = Math.toRadians(Geo.bearing(lat0, lon0, lat, lon))
        val a = d / EARTH_R
        val p1 = Math.toRadians(FAKE_LAT)
        val p2 = asin(sin(p1) * cos(a) + cos(p1) * sin(a) * cos(b))
        val l2 = Math.toRadians(FAKE_LON) + atan2(sin(b) * sin(a) * cos(p1), cos(a) - sin(p1) * sin(p2))
        return doubleArrayOf(Math.toDegrees(p2), Math.toDegrees(l2))
    }
}
