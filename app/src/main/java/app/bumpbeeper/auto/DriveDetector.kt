package app.bumpbeeper.auto

/**
 * "Is this a drive?" from GPS speed, with hysteresis (#49). Pure Kotlin, so it is unit-tested.
 * True once fixes have stayed at or above [startKmh] for [sustainMs]. A fix below [resetKmh] starts over (a cyclist
 * or a jogger never gets there); fixes in between neither count nor reset (slowing for a turn). A gap of more than
 * [maxGapMs] between fixes also starts over, so two fast fixes far apart are not a drive.
 */
class SpeedGate(
    val startKmh: Double = 20.0,
    val resetKmh: Double = 15.0,
    val sustainMs: Long = 60_000L,
    val maxGapMs: Long = 20_000L,
) {
    private var sinceMs = -1L
    private var lastMs = -1L

    /** A fast stretch has begun but is not long enough yet. */
    val pending: Boolean get() = sinceMs >= 0

    fun reset() {
        sinceMs = -1L
        lastMs = -1L
    }

    fun add(tMs: Long, kmh: Double): Boolean {
        if (kmh.isNaN()) return false
        if (sinceMs >= 0 && tMs - lastMs > maxGapMs) sinceMs = -1L
        lastMs = tMs
        when {
            kmh >= startKmh -> if (sinceMs < 0) sinceMs = tMs
            kmh < resetKmh -> sinceMs = -1L
        }
        return sinceMs >= 0 && tMs - sinceMs >= sustainMs
    }
}

/**
 * When to spend GPS on checking for a drive, and when to give up (#49, foss edition and play without Google).
 * Pure Kotlin; [DriveWatcher] feeds it from the significant-motion sensor, passive fixes and its own short GPS check.
 *
 *  - Significant motion (the phone moved) starts a GPS check of [checkMs]; a fast stretch still running at the end
 *    may finish, so one check lasts at most [checkMs] + [SpeedGate.sustainMs] (3.5 min).
 *  - A check that saw [SpeedGate.resetKmh] or more is in a vehicle (a slow car-park exit, a jam): no back-off, the
 *    check simply goes on, up to [maxFastRechecks] more windows (a bus crawling for 15 min shouldn't keep GPS on
 *    forever); after that a [firstCooldownMs] pause, which doesn't grow.
 *  - No fix at all within [noFixMs] (indoors, an underground car park) is no evidence of walking: the pause doesn't
 *    grow.
 *  - Only a real miss (fixes, all slow: walking around) grows the pause, [firstCooldownMs] doubling up to
 *    [maxCooldownMs]. After [decayMs] without a miss, or after a drive, it is back to [firstCooldownMs].
 *  - A passive fix (another app's GPS, free; at most [maxPassiveAgeMs] old) at driving speed starts a check even
 *    during a pause.
 *  - [snooze] (after the user pressed Stop) ignores everything for a while.
 */
class DriveDetector(
    val gate: SpeedGate = SpeedGate(),
    val checkMs: Long = 150_000L,
    val noFixMs: Long = 60_000L,
    val firstCooldownMs: Long = 3 * 60_000L,
    val maxCooldownMs: Long = 10 * 60_000L,
    val decayMs: Long = 30 * 60_000L,
    val maxFastRechecks: Int = 4,
    val maxPassiveAgeMs: Long = 30_000L,
) {
    enum class Action { NONE, START_CHECK, END_CHECK, DRIVING }

    var checking = false
        private set
    private var checkStartMs = -1L
    private var gotFix = false
    private var sawVehicleSpeed = false
    private var fastRechecks = 0
    private var cooldownUntilMs = Long.MIN_VALUE
    private var cooldownMs = firstCooldownMs
    private var lastMissMs: Long? = null
    private var snoozeUntilMs = Long.MIN_VALUE

    /** The significant-motion sensor fired. */
    fun onMotion(nowMs: Long): Action =
        if (checking || nowMs < cooldownUntilMs || nowMs < snoozeUntilMs) Action.NONE else startCheck(nowMs)

    /**
     * A fix another app asked for, taken at [tMs]. Ignored during a check (the check's own fixes arrive there too)
     * and when older than [maxPassiveAgeMs] (a cached fix says nothing about now).
     */
    fun onPassiveFix(nowMs: Long, tMs: Long, kmh: Double): Action {
        if (checking || nowMs < snoozeUntilMs || nowMs - tMs > maxPassiveAgeMs) return Action.NONE
        if (kmh.isNaN() || kmh < gate.startKmh) return Action.NONE
        startCheck(nowMs)
        onFix(tMs, kmh)
        return Action.START_CHECK
    }

    /** A fix from the check's own GPS request. */
    fun onFix(tMs: Long, kmh: Double): Action {
        if (!checking) return Action.NONE
        gotFix = true
        if (kmh >= gate.resetKmh) sawVehicleSpeed = true
        if (!gate.add(tMs, kmh)) return Action.NONE
        checking = false
        fastRechecks = 0
        cooldownMs = firstCooldownMs
        return Action.DRIVING
    }

    /** Call every few seconds during a check. */
    fun onTimer(nowMs: Long): Action {
        if (!checking) return Action.NONE
        val age = nowMs - checkStartMs
        val over = (age >= checkMs && !gate.pending) || age >= checkMs + gate.sustainMs || (!gotFix && age >= noFixMs)
        if (!over) return Action.NONE
        if (sawVehicleSpeed && fastRechecks < maxFastRechecks) {
            // In a vehicle but not 60 s at 20 km/h yet: check again at once, GPS stays on.
            fastRechecks++
            checkStartMs = nowMs
            sawVehicleSpeed = false
            return Action.NONE
        }
        checking = false
        fastRechecks = 0
        if (lastMissMs.let { it == null || nowMs - it >= decayMs }) cooldownMs = firstCooldownMs
        when {
            sawVehicleSpeed -> cooldownUntilMs = nowMs + firstCooldownMs
            !gotFix -> cooldownUntilMs = nowMs + cooldownMs
            else -> {
                cooldownUntilMs = nowMs + cooldownMs
                cooldownMs = minOf(cooldownMs * 2, maxCooldownMs)
                lastMissMs = nowMs
            }
        }
        return Action.END_CHECK
    }

    /** Detect nothing until [nowMs] + [ms]: the user pressed Stop and may still be driving. */
    fun snooze(nowMs: Long, ms: Long) {
        snoozeUntilMs = nowMs + ms
    }

    private fun startCheck(nowMs: Long): Action {
        checking = true
        checkStartMs = nowMs
        gotFix = false
        sawVehicleSpeed = false
        fastRechecks = 0
        gate.reset()
        return Action.START_CHECK
    }
}
