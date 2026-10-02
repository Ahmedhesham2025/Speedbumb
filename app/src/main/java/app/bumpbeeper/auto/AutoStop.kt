package app.bumpbeeper.auto

/**
 * Ends a recording once the car is parked (#49, #50). Pure Kotlin, so it is unit-tested; times are elapsedRealtime ms.
 *
 * Rules, chosen so a red light or a traffic jam never ends a trip:
 *  - Only after the car has really driven this recording (one fix at [armKmh] or more). A manual start in a car park
 *    waits for the drive instead of stopping before it.
 *  - Stop when the last fix is below [movingKmh] and nothing faster came for [stopAfterMs] (the user's setting,
 *    default 3 min; 0 = never). After Google's "left the vehicle" signal (play edition) [afterExitMs] is enough.
 *  - No fix at all for [noFixMs] (an underground car park) also stops. A short loss of fix while moving (a tunnel)
 *    doesn't count as parked, because the last known speed was not low.
 */
class AutoStop(
    var stopAfterMs: Long,
    val movingKmh: Double = 5.0,
    val armKmh: Double = 15.0,
    val afterExitMs: Long = 60_000L,
    val noFixMs: Long = 15 * 60_000L,
) {
    private var armed = false
    private var lastFixMs = -1L
    private var lastKmh = Double.NaN
    private var lastMovingMs = -1L
    private var exited = false

    fun onFix(tMs: Long, kmh: Double) {
        lastFixMs = tMs
        if (kmh.isNaN()) return
        lastKmh = kmh
        if (kmh >= movingKmh) lastMovingMs = tMs
        if (kmh >= armKmh) {
            armed = true
            exited = false   // really driving again: a stale "left the vehicle" no longer applies
        }
    }

    /** Google activity recognition: the user left the vehicle (play edition). */
    fun vehicleExit() { exited = true }

    /** Google activity recognition: back in a vehicle. */
    fun vehicleEnter() { exited = false }

    fun shouldStop(nowMs: Long): Boolean {
        if (stopAfterMs <= 0 || !armed || lastFixMs < 0) return false
        if (nowMs - lastFixMs >= noFixMs) return true
        if (!(lastKmh < movingKmh)) return false
        val limit = if (exited) minOf(stopAfterMs, afterExitMs) else stopAfterMs
        return nowMs - lastMovingMs >= limit
    }
}
