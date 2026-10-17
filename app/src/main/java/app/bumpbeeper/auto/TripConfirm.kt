package app.bumpbeeper.auto

/**
 * Every automatic start (motion, Google's in-vehicle, the car's Bluetooth) records at once but silently: nothing is
 * shown or played until the trip has had more than 30 s at driving speed ([SpeedGate]). Then [onFix] returns true,
 * once: the "trip started" chime plays and the trip shows as running. A trip never confirmed (within [maxPendingS]:
 * stopped then) is discarded silently, like one under 30 s or 200 m ([ShortTrip]); one the app died in, at the next
 * start ([PendingTrip]). A manual Start is confirmed from the beginning, and so is a pending
 * trip the user starts by hand ([confirmNow]). Pure Kotlin; times are fix times (elapsedRealtime ms).
 */
class TripConfirm(
    automatic: Boolean,
    private val startedMs: Long = 0L,
    private val gate: SpeedGate = SpeedGate(),
    /** Not confirmed within this long (parked with the car's Bluetooth on, a bus stuck in a jam): stop and discard. */
    val maxPendingS: Int = 180,
) {
    var confirmed = !automatic
        private set

    /** A fix of the trip. True only at the fix that confirms it. */
    fun onFix(tMs: Long, kmh: Double): Boolean {
        if (confirmed || !gate.add(tMs, kmh)) return false
        confirmed = true
        return true
    }

    /** The user pressed Start during a silent automatic trip. True if that confirmed it (it wasn't already). */
    fun confirmNow(): Boolean {
        if (confirmed) return false
        confirmed = true
        return true
    }

    /** [nowMs] (elapsedRealtime): still not confirmed [maxPendingS] after the start? Then the trip stops and goes. */
    fun timedOut(nowMs: Long): Boolean = !confirmed && nowMs - startedMs >= maxPendingS * 1000L

    /** At the end: keep the trip only if it was confirmed and isn't too short. */
    fun keep(durationMs: Long, distanceM: Double): Boolean = confirmed && !ShortTrip.discard(durationMs, distanceM)
}
