package app.bumpbeeper.auto

/**
 * Battery saving while recording (#50), decided from GPS speed alone. Pure Kotlin, so it is unit-tested.
 *
 *  - Stopped (every fix below [stopKmh] for [stopAfterMs]): GPS every 5 s instead of every 1 s, sensor events batched
 *    (delivered once a second, each with its exact timestamp) and the gyroscope off.
 *  - Moving again (one fix at or above [moveKmh]): back to 1 s GPS, unbatched sensors and the gyroscope on at once.
 *
 * The accelerometer rate never changes, so whenever the car moves the engine gets the same 50 Hz samples as before.
 * The gyroscope comes on at [moveKmh] (just above walking pace), not later: speed bumps are often taken at 5–10 km/h
 * and the engine needs a live gyroscope to tell a bump from a pothole there.
 */
class PowerPolicy(
    val stopKmh: Double = 5.0,
    val stopAfterMs: Long = 30_000L,
    val moveKmh: Double = 6.0,
) {
    data class Mode(val stopped: Boolean, val gyro: Boolean) {
        val gpsIntervalMs: Long get() = if (stopped) STOPPED_GPS_MS else MOVING_GPS_MS
        /** maxReportLatencyUs for SensorManager.registerListener: 0 = deliver each event right away. */
        val sensorLatencyUs: Int get() = if (stopped) STOPPED_LATENCY_US else 0
    }

    /** Trip start: full rate, gyroscope off until the first fix shows the car moving. */
    var mode = Mode(stopped = false, gyro = false)
        private set
    private var slowSinceMs = -1L

    /** Feed every GPS fix (time on the elapsedRealtime clock). Returns the new mode when it changed, else null. */
    fun onFix(tMs: Long, kmh: Double): Mode? {
        if (kmh.isNaN()) return null
        val old = mode
        when {
            kmh >= moveKmh -> {
                slowSinceMs = -1L
                mode = Mode(stopped = false, gyro = true)
            }
            kmh < stopKmh -> {
                if (slowSinceMs < 0) slowSinceMs = tMs
                if (!mode.stopped && tMs - slowSinceMs >= stopAfterMs) mode = Mode(stopped = true, gyro = false)
            }
            // Creeping between the two: neither clearly stopped nor clearly moving, keep the current mode.
            else -> slowSinceMs = -1L
        }
        return if (mode != old) mode else null
    }

    companion object {
        const val MOVING_GPS_MS = 1000L
        const val STOPPED_GPS_MS = 5000L
        const val STOPPED_LATENCY_US = 1_000_000
    }
}
