package app.bumpbeeper

// The few things the engine needs from the platform. On the JVM (the Android app, replay) each one is the exact
// Java call the engine always used, so numbers and text stay bit-for-bit the same; iOS gets an equivalent.

/** `String.format(Locale.US, "%.<digits>f", x)`: dot decimal separator, rounded half up. */
expect fun formatFixed(x: Double, digits: Int): String
/** `Math.toRadians`. */
expect fun degToRad(deg: Double): Double
/** `Math.toDegrees`. */
expect fun radToDeg(rad: Double): Double
/** `Math.round(Double)`: nearest Long, halves rounded up, NaN → 0. */
expect fun javaRound(x: Double): Long
/** A random (version 4) UUID, lower case, like `java.util.UUID.randomUUID().toString()`. */
expect fun randomUuid(): String

/**
 * [formatFixed] in plain Kotlin, the way Java does it: take the shortest decimal digits of [x]
 * (`Double.toString`) and round them half up to [digits] places. Used where there is no `String.format`.
 */
internal fun formatFixedPortable(x: Double, digits: Int): String {
    if (x.isNaN()) return "NaN"
    val neg = x.toRawBits() < 0   // Java keeps the sign of -0.0 and of values that round to zero
    if (x.isInfinite()) return if (neg) "-Infinity" else "Infinity"
    // Significant digits and where the decimal point goes: "1.2345E-5" → "12345", point at -4.
    val s = (if (neg) -x else x).toString()
    val e = s.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (e < 0) s else s.substring(0, e)
    val exp = if (e < 0) 0 else s.substring(e + 1).removePrefix("+").toInt()
    val dot = mantissa.indexOf('.')
    val intDigits = if (dot < 0) mantissa.length else dot
    val all = mantissa.replace(".", "")
    val lead = all.indexOfFirst { it != '0' }
    if (lead < 0) return (if (neg) "-" else "") + "0" + (if (digits > 0) "." + "0".repeat(digits) else "")
    val sig = all.substring(lead).trimEnd('0').ifEmpty { "0" }
    val point = intDigits + exp - lead
    // Integer part and fraction as plain digit strings.
    val intPart = if (point > 0) sig.take(point).padEnd(point, '0') else "0"
    val frac = if (point >= 0) sig.drop(point) else "0".repeat(-point) + sig
    val keep = intPart + frac.take(digits).padEnd(digits, '0')
    val roundUp = frac.length > digits && frac[digits] >= '5'
    val kept = if (!roundUp) keep else {
        val c = keep.toCharArray()
        var i = c.size - 1
        while (i >= 0 && c[i] == '9') { c[i] = '0'; i-- }
        if (i >= 0) { c[i] = c[i] + 1; c.concatToString() } else "1" + c.concatToString()
    }
    val intLen = kept.length - digits
    val intStr = kept.substring(0, intLen).trimStart('0').ifEmpty { "0" }
    val out = if (digits > 0) intStr + "." + kept.substring(intLen) else intStr
    return (if (neg) "-" else "") + out
}
