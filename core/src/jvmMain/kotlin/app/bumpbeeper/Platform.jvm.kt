package app.bumpbeeper

import java.util.Locale
import java.util.UUID

actual fun formatFixed(x: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", x)

actual fun degToRad(deg: Double): Double = Math.toRadians(deg)

actual fun radToDeg(rad: Double): Double = Math.toDegrees(rad)

actual fun javaRound(x: Double): Long = Math.round(x)

actual fun randomUuid(): String = UUID.randomUUID().toString()
