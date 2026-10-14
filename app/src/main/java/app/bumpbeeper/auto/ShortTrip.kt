package app.bumpbeeper.auto

/**
 * Trips too short to be a drive are not kept: under [MIN_MS] or under [MIN_M] (a Start pressed by mistake, a car moved
 * across a car park). They are deleted with the spots only they found, and nothing of them is uploaded. Pure Kotlin.
 */
object ShortTrip {
    const val MIN_MS = 30_000L
    const val MIN_M = 200.0

    fun discard(durationMs: Long, distanceM: Double): Boolean = durationMs < MIN_MS || !(distanceM >= MIN_M)
}
