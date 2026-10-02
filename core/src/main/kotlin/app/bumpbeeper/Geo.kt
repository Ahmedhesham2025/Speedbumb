package app.bumpbeeper

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geometry helpers. Distances in metres, angles in degrees (0 = north, 90 = east). */
object Geo {
    private const val EARTH_R = 6_371_000.0

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    /** Great-circle (haversine) distance. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dp = p2 - p1
        val dl = rad(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R * asin(min(1.0, sqrt(a)))
    }

    /** Compass bearing from point 1 to point 2. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dl = rad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (deg(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest difference between two headings, 0..180. */
    fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)

    /** Move from a point by [distM] metres along [bearingDeg]. Flat-earth approximation; fine for short hops. */
    fun move(lat: Double, lon: Double, bearingDeg: Double, distM: Double): DoubleArray {
        val north = distM * cos(rad(bearingDeg))
        val east = distM * sin(rad(bearingDeg))
        val dLat = deg(north / EARTH_R)
        val dLon = deg(east / (EARTH_R * cos(rad(lat))))
        return doubleArrayOf(lat + dLat, lon + dLon)
    }

    /** Blend two headings on the circle; [weightB] = 0 keeps a, 1 gives b. */
    fun blendAngle(a: Double, b: Double, weightB: Double): Double {
        val x = (1 - weightB) * cos(rad(a)) + weightB * cos(rad(b))
        val y = (1 - weightB) * sin(rad(a)) + weightB * sin(rad(b))
        return (deg(atan2(y, x)) + 360.0) % 360.0
    }
}
