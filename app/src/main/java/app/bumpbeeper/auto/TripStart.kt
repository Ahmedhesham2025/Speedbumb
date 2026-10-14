package app.bumpbeeper.auto

/** Where a trip starts (pure Kotlin, unit-tested). */
object TripStart {
    /** The start goes back at most this far (a detection never takes longer than its 3 min check). */
    const val MAX_BACKDATE_MS = 5 * 60_000L

    /**
     * Wall-clock start of a trip that starts now ([nowMs]; [nowElapsedMs] on the elapsedRealtime clock). Driving
     * detected: [movedSinceMs] (elapsedRealtime) is when the movement began, and the trip starts there, not 30 s later
     * when it was confirmed. -1 (the user, the car's Bluetooth): now.
     */
    fun wallMs(nowMs: Long, nowElapsedMs: Long, movedSinceMs: Long): Long =
        if (movedSinceMs < 0) nowMs else nowMs - (nowElapsedMs - movedSinceMs).coerceIn(0L, MAX_BACKDATE_MS)
}
