package app.bumpbeeper.auto

/**
 * Battery saving while recording (#50), decided from GPS speed alone. Pure Kotlin, so it is unit-tested.
 *
 *  - Stopped (every fix below [stopKmh] for [stopAfterMs]): GPS every 5 s instead of every 1 s.
 *  - Moving again (one fix at or above [moveKmh]): back to 1 s GPS at once.
 *
 * GPS is the big cost. The sensors never change: the accelerometer and the gyroscope stay at 50 Hz, unbatched, for
 * the whole recording. Switching the gyroscope off froze its last value inside the engine (a jolt then looked like a
 * certain speed bump). Batching was dropped too: changing it means re-registering the accelerometer, which discards
 * up to 1 s of queued samples, and it saved almost nothing because the recording's wake lock keeps the CPU awake.
 */
class PowerPolicy(
    val stopKmh: Double = 5.0,
    val stopAfterMs: Long = 30_000L,
    val moveKmh: Double = 6.0,
) {
    /** Trip start: full rate. */
    var stopped = false
        private set
    private var slowSinceMs = -1L

    val gpsIntervalMs: Long get() = if (stopped) STOPPED_GPS_MS else MOVING_GPS_MS

    /** Feed every GPS fix (time on the elapsedRealtime clock). Returns true when [stopped] changed. */
    fun onFix(tMs: Long, kmh: Double): Boolean {
        if (kmh.isNaN()) return false
        val old = stopped
        when {
            kmh >= moveKmh -> {
                slowSinceMs = -1L
                stopped = false
            }
            kmh < stopKmh -> {
                if (slowSinceMs < 0) slowSinceMs = tMs
                if (tMs - slowSinceMs >= stopAfterMs) stopped = true
            }
            // Creeping between the two: neither clearly stopped nor clearly moving, keep the current mode.
            else -> slowSinceMs = -1L
        }
        return stopped != old
    }

    companion object {
        const val MOVING_GPS_MS = 1000L
        const val STOPPED_GPS_MS = 5000L
    }
}
