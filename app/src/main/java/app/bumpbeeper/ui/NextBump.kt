package app.bumpbeeper.ui

import app.bumpbeeper.Confidence
import app.bumpbeeper.Severity
import app.bumpbeeper.SpotAhead

/**
 * The Drive screen's next-bump card, pure rules (no Android). The engine publishes the next two spots on the road
 * ahead in `LiveState.spotsAhead`; the screen copies only the numbers it shows and never keeps the engine's spots.
 */
object NextBump {
    /** One spot ahead as the card shows it: how far, its band, "maybe" or confirmed, and its warning's beeps. */
    class Ahead(val distanceM: Double, val severity: Severity, val maybe: Boolean, val beeps: Int)

    /** A strong bump closer than this gets the "near" card: bigger, a red edge, and the speed to slow to. */
    const val NEAR_M = 150.0

    /** The engine's list, nearest first, at most two, as plain copies. */
    fun from(list: List<SpotAhead>): List<Ahead> = list.take(2).map {
        Ahead(it.distanceM, it.severity, it.confidence == Confidence.SOFT, it.beeps)
    }

    fun near(a: Ahead): Boolean = a.severity == Severity.STRONG && !a.maybe && a.distanceM <= NEAR_M

    /** Distances on the card: metres rounded to 10 below 1 km, then km with one decimal (Locale-free digits). */
    fun distanceText(m: Double): Pair<String, Boolean> =
        if (m >= 1000) String.format(java.util.Locale.US, "%.1f", m / 1000) to true
        else (((m / 10).toInt() * 10).coerceAtLeast(10)).toString() to false
}
