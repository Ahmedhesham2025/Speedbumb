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

    /** Time at [CLEAR_KMH] or faster so far (gaps between fixes count up to [MAX_GAP_MS]). */
    var fastMs = 0L
        private set
    private var lastFixMs = Long.MIN_VALUE

    /** A fix of the trip. True only at the fix that confirms it. */
    fun onFix(tMs: Long, kmh: Double): Boolean {
        if (lastFixMs != Long.MIN_VALUE && tMs > lastFixMs && kmh >= CLEAR_KMH) fastMs += minOf(tMs - lastFixMs, MAX_GAP_MS)
        lastFixMs = tMs
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

    /**
     * At the end: so clearly a drive that it is kept and uploaded without asking "Was this a drive?" (owner decision):
     * at least [CLEAR_M] and [CLEAR_FAST_MS] at [CLEAR_KMH] or faster. A false start at home (seconds, metres), a walk
     * with the phone or a short car-park move never gets there; a real drive does within a minute or two of town
     * traffic. Shorter or slower trips still ask, and go unsent when unanswered.
     */
    fun clearDrive(distanceM: Double): Boolean = confirmed && distanceM >= CLEAR_M && fastMs >= CLEAR_FAST_MS

    companion object {
        const val CLEAR_M = 1000.0
        const val CLEAR_KMH = 20.0
        const val CLEAR_FAST_MS = 60_000L
        /** A longer gap between fixes (a tunnel) counts as this much: the time above the speed isn't known. */
        const val MAX_GAP_MS = 5_000L
    }
}
