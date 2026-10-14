package app.bumpbeeper.auto

/**
 * "Is this a drive?" from GPS speed, with hysteresis (#49). Pure Kotlin, so it is unit-tested.
 * True once fixes have stayed at or above [startKmh] for more than [sustainMs] (30 s of real driving). A fix below
 * [resetKmh] starts over (a cyclist or a jogger never gets there); fixes in between neither count nor reset (slowing
 * for a turn). A gap of more than [maxGapMs] between fixes also starts over, so two fast fixes far apart are not a drive.
 */
class SpeedGate(
    val startKmh: Double = 20.0,
    val resetKmh: Double = 15.0,
    val sustainMs: Long = 30_000L,
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
        return sinceMs >= 0 && tMs - sinceMs > sustainMs
    }
}

/**
 * When to spend GPS on checking for a drive, and when to give up (#49; foss, and play next to Google's detection).
 * Pure Kotlin; [DriveWatcher] feeds it from the significant-motion sensor, passive fixes and its own short GPS check.
 *
 *  - Significant motion (the phone moved) starts a GPS check of [checkMs].
 *  - Driving speed ([SpeedGate.startKmh], two fixes in a row) is enough: [Action.DRIVING] starts a silent recording
 *    at once, so no data is lost; BumpService confirms it only after 30 s of driving ([TripConfirm]).
 *  - A check that saw [SpeedGate.resetKmh] or more is in a vehicle (a slow car-park exit, a jam): no back-off, the
 *    check simply goes on, up to [maxFastRechecks] more windows; after that a [firstCooldownMs] pause.
 *  - No fix at all within [noFixMs] (indoors, a cold GPS in an underground car park) doesn't grow the pause.
 *  - Only a real miss (fixes, all slow: walking around) grows the pause, [firstCooldownMs] doubling up to
 *    [maxCooldownMs]. After [decayMs] without a miss, or after a drive, it is back to [firstCooldownMs].
 *  - During a pause, motion that is not walking ([DriveWatcher] asks the accelerometer for a few seconds first, see
 *    [wantsMotionKind]) is a phone in a moving vehicle: a short check of [quickCheckMs] runs anyway, at most one per
 *    [vehicleRetryMs]; motion that comes sooner is retried then ([Action.RETRY_LATER], [retryAtMs]). Before this,
 *    motion during a pause was dropped: walking to the car (a miss, then 3-10 min of pause) hid the drive after it.
 *  - A passive fix (another app's GPS, free; at most [maxPassiveAgeMs] old) at driving speed starts a check even
 *    during a pause.
 *  - [snooze] (after the user pressed Stop) ignores everything for a while.
 * Every decision worth knowing goes to [log] (Diagnostics, [StartLog]).
 */
class DriveDetector(
    val gate: SpeedGate = SpeedGate(sustainMs = 0L),
    val checkMs: Long = 150_000L,
    val noFixMs: Long = 90_000L,
    val firstCooldownMs: Long = 3 * 60_000L,
    val maxCooldownMs: Long = 10 * 60_000L,
    val decayMs: Long = 30 * 60_000L,
    val maxFastRechecks: Int = 4,
    val maxPassiveAgeMs: Long = 30_000L,
    val quickCheckMs: Long = 45_000L,
    val vehicleRetryMs: Long = 60_000L,
    private val log: (StartLog.Reason) -> Unit = {},
) {
    enum class Action { NONE, START_CHECK, END_CHECK, DRIVING, RETRY_LATER }

    var checking = false
        private set
    /** After [Action.RETRY_LATER]: when to call [onMotion] again (elapsedRealtime ms). */
    var retryAtMs = -1L
        private set
    private var quick = false
    private var checkStartMs = -1L
    private var lastCheckEndMs = Long.MIN_VALUE / 2
    private var gotFix = false
    private var sawVehicleSpeed = false
    private var fastRechecks = 0
    private var cooldownUntilMs = Long.MIN_VALUE
    private var cooldownMs = firstCooldownMs
    private var lastMissMs: Long? = null
    private var snoozeUntilMs = Long.MIN_VALUE

    /** In a pause, motion only counts if it isn't walking: the watcher should find that out first. */
    fun wantsMotionKind(nowMs: Long): Boolean = !checking && nowMs >= snoozeUntilMs && nowMs < cooldownUntilMs

    /**
     * The significant-motion sensor fired; [walking]: the accelerometer says someone walks with the phone ([MotionKind]).
     * Not known counts as walking: a pause then holds, as before.
     */
    fun onMotion(nowMs: Long, walking: Boolean = true): Action = when {
        checking -> Action.NONE
        nowMs < snoozeUntilMs -> { log(StartLog.Reason.SKIP_SNOOZED); Action.NONE }
        nowMs >= cooldownUntilMs -> { log(StartLog.Reason.MOTION_CHECK); startCheck(nowMs, quick = false) }
        walking -> { log(StartLog.Reason.MOTION_WALKING); Action.NONE }
        nowMs - lastCheckEndMs >= vehicleRetryMs -> { log(StartLog.Reason.MOTION_CHECK); startCheck(nowMs, quick = true) }
        else -> { retryAtMs = lastCheckEndMs + vehicleRetryMs; Action.RETRY_LATER }
    }

    /**
     * A fix another app asked for, taken at [tMs]. Ignored during a check (the check's own fixes arrive there too)
     * and when older than [maxPassiveAgeMs] (a cached fix says nothing about now).
     */
    fun onPassiveFix(nowMs: Long, tMs: Long, kmh: Double): Action {
        if (checking || nowMs < snoozeUntilMs || nowMs - tMs > maxPassiveAgeMs) return Action.NONE
        if (kmh.isNaN() || kmh < gate.startKmh) return Action.NONE
        startCheck(nowMs, quick = false)
        onFix(tMs, kmh)
        return Action.START_CHECK
    }

    /** A fix from the check's own GPS request. */
    fun onFix(tMs: Long, kmh: Double): Action {
        if (!checking) return Action.NONE
        gotFix = true
        if (kmh >= gate.resetKmh && !sawVehicleSpeed) {
            sawVehicleSpeed = true
            log(StartLog.Reason.WAITING_FOR_SPEED)
        }
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
        val over = age >= (if (quick) quickCheckMs else checkMs) || (!gotFix && age >= noFixMs)
        if (!over) return Action.NONE
        if (sawVehicleSpeed && fastRechecks < maxFastRechecks) {
            // In a vehicle but not at 20 km/h yet: check again at once, GPS stays on.
            fastRechecks++
            checkStartMs = nowMs
            sawVehicleSpeed = false
            return Action.NONE
        }
        checking = false
        fastRechecks = 0
        lastCheckEndMs = nowMs
        if (lastMissMs.let { it == null || nowMs - it >= decayMs }) cooldownMs = firstCooldownMs
        when {
            sawVehicleSpeed -> {
                log(StartLog.Reason.SKIP_VEHICLE_SLOW)
                cooldownUntilMs = nowMs + firstCooldownMs
            }
            !gotFix -> {
                log(StartLog.Reason.SKIP_NO_FIX)
                cooldownUntilMs = nowMs + cooldownMs
            }
            else -> {
                log(StartLog.Reason.SKIP_SLOW)
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

    fun snoozed(nowMs: Long): Boolean = nowMs < snoozeUntilMs

    private fun startCheck(nowMs: Long, quick: Boolean): Action {
        checking = true
        this.quick = quick
        checkStartMs = nowMs
        gotFix = false
        sawVehicleSpeed = false
        fastRechecks = 0
        gate.reset()
        return Action.START_CHECK
    }
}

/**
 * Walking or riding? From a few seconds of accelerometer magnitudes (m/s²): walking with the phone swings it by
 * several m/s² at every step; a phone lying or held in a moving car stays within about 1 m/s². Pure Kotlin.
 */
object MotionKind {
    const val WALKING_SD = 1.5
    const val MIN_SAMPLES = 20

    /** Too few samples (no accelerometer): not walking, so a check may run. */
    fun walking(mags: List<Double>): Boolean {
        if (mags.size < MIN_SAMPLES) return false
        val mean = mags.average()
        val sd = Math.sqrt(mags.sumOf { (it - mean) * (it - mean) } / mags.size)
        return sd > WALKING_SD
    }
}
