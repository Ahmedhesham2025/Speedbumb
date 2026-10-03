package app.bumpbeeper.auto

import app.bumpbeeper.Geo

/**
 * Ends a recording once the car is parked (#49, #50). Pure Kotlin, so it is unit-tested; times are elapsedRealtime ms.
 *
 * Rules, chosen so a red light, a checkpoint or a jam doesn't end a trip:
 *  - Only after the car has really driven this recording (one fix at [armKmh] or more). A manual start in a car park
 *    waits for the drive instead of stopping before it.
 *  - Parked = no "moving" fix for [stopAfterMs] (the user's setting, default 5 min; 0 = never). Moving needs both
 *    [movingKmh] or more AND [movedM] away from where the car last moved, so GPS jitter while parked (a speed spike,
 *    a position wandering 10–20 m) never restarts the clock.
 *  - While the car's Bluetooth is connected ([carConnected]) it never stops: the disconnect ends those trips.
 *  - After the user was seen walking (Google, play edition, [leftVehicle]) [afterWalkingMs] is enough.
 *  - No fix at all for [noFixMs] (an underground car park) also stops. Losing the fix while moving (a tunnel)
 *    doesn't count as parked, because the last known speed was not low.
 *  - A recording started by a guess (motion or Google's IN_VEHICLE, see [autoStarted]) that never reaches [armKmh]
 *    stops after [notDrivingMs], whatever the parked setting (even "never"): someone sat in a parked car with the
 *    engine on, or the guess was wrong. Without it a wrong guess would record (and hold a wake lock) for hours.
 */
class AutoStop(
    var stopAfterMs: Long,
    val movingKmh: Double = 5.0,
    val movedM: Double = 30.0,
    val armKmh: Double = 15.0,
    val afterWalkingMs: Long = 60_000L,
    val noFixMs: Long = 15 * 60_000L,
    val notDrivingMs: Long = 10 * 60_000L,
) {
    private var autoStartMs = -1L
    /** The car's Bluetooth is connected: only its disconnect (with the usual grace) ends the trip. */
    var carConnected = false

    private var armed = false
    private var lastFixMs = -1L
    private var lastKmh = Double.NaN
    private var lastMovingMs = -1L
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var walking = false

    fun onFix(tMs: Long, kmh: Double, lat: Double, lon: Double) {
        lastFixMs = tMs
        if (kmh.isNaN()) return
        lastKmh = kmh
        if (kmh >= armKmh) {
            if (!armed) moved(tMs, lat, lon)
            armed = true
            walking = false   // really driving again: a stale "walking" no longer applies
        }
        if (kmh >= movingKmh && Geo.distance(anchorLat, anchorLon, lat, lon) >= movedM) moved(tMs, lat, lon)
    }

    private fun moved(tMs: Long, lat: Double, lon: Double) {
        lastMovingMs = tMs
        anchorLat = lat
        anchorLon = lon
    }

    /** This recording started automatically at [nowMs] (motion or Google, not the user or the car's Bluetooth). */
    fun autoStarted(nowMs: Long) { autoStartMs = nowMs }

    /** Google activity recognition saw the user walking: they left the car (play edition). */
    fun leftVehicle() { walking = true }

    /** Google activity recognition: back in a vehicle. */
    fun backInVehicle() { walking = false }

    fun shouldStop(nowMs: Long): Boolean {
        if (carConnected) return false
        if (!armed) return autoStartMs >= 0 && nowMs - autoStartMs >= notDrivingMs
        if (stopAfterMs <= 0) return false
        if (nowMs - lastFixMs >= noFixMs) return true
        // Fixes stopped coming while the car was moving: a tunnel, not a car park.
        if (lastKmh >= movingKmh && nowMs - lastFixMs > STALE_FIX_MS) return false
        val limit = if (walking) minOf(stopAfterMs, afterWalkingMs) else stopAfterMs
        return nowMs - lastMovingMs >= limit
    }

    private companion object {
        /** Fixes come every 1–5 s while recording; older than this means the fix was lost. */
        const val STALE_FIX_MS = 30_000L
    }
}
