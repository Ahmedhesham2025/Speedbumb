package app.bumpbeeper

import platform.Foundation.NSUUID
import kotlin.math.floor

actual fun formatFixed(x: Double, digits: Int): String = formatFixedPortable(x, digits)

// The same constants as Java 17's Math.toRadians / Math.toDegrees.
actual fun degToRad(deg: Double): Double = deg * 0.017453292519943295

actual fun radToDeg(rad: Double): Double = rad * 57.29577951308232

actual fun javaRound(x: Double): Long {
    if (x.isNaN()) return 0L
    val f = floor(x)
    return (if (x - f >= 0.5) f + 1.0 else f).toLong()   // x - floor(x) is exact; toLong() clamps like Java
}

actual fun randomUuid(): String = NSUUID().UUIDString.lowercase()
