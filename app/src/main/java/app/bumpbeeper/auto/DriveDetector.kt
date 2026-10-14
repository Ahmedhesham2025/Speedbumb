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
 *  - Significant motion (the phone moved) starts a GPS check of [checkMs], or [quickCheckMs] when started by
 *    vehicle-like vibration in a pause, counted once there is a fix: without one it runs up to [noFixMs] (a cold
 *    GPS in the car), and after the first fix it runs at least [minAfterFixMs] more.
 *  - Driving speed ([SpeedGate.startKmh], two fixes in a row) is enough: [Action.DRIVING] starts a silent recording
 *    at once, so no data is lost; BumpService confirms it only after 30 s of driving ([TripConfirm]).
 *  - A check that saw [SpeedGate.resetKmh] or more (two fixes in a row) is in a vehicle (a slow car-park exit, a
 *    jam): no pause, the check simply goes on, up to [maxFastRechecks] more windows; after that a
 *    [firstCooldownMs] pause.
 *  - No fix at all within [noFixMs] (indoors, a cold GPS in an underground car park) doesn't grow the pause.
 *  - Only a real miss (fixes, all slow: walking around) grows the pause, [firstCooldownMs] doubling up to
 *    [maxCooldownMs]. After [decayMs] without a miss, or after a drive, it is back to [firstCooldownMs].
 *  - Before any check, [DriveWatcher] asks the accelerometer for a few seconds ([MotionKind]): a phone lying still
 *    (put down, on a table) never costs a GPS check.
 *  - During a pause, and during a back-off, motion that vibrates like a vehicle ([MotionKind.Kind.VEHICLE]: steady,
 *    fast road and engine vibration) is a phone in a moving car: a quick check runs anyway, at most one per
 *    [vehicleRetryMs]; motion that comes sooner is retried then ([Action.RETRY_LATER], [retryAtMs]). Before this,
 *    motion during a pause was dropped: walking to the car (a miss, then 3-10 min of pause) hid the drive after it.
 *    That is also why the other checks can be short: getting into the car is caught by the car's vibration.
 *  - Battery: after [missesBeforeBackoff] checks in a row without vehicle speed (moving around at home), walking and
 *    other motion start no check for [firstBackoffMs], then 10 min longer each time up to [maxBackoffMs], and after
 *    that only vehicle-like vibration does. Vehicle speed or a drive resets it; so does any recording (car
 *    Bluetooth, Google), because the watcher and this detector are made anew after it.
 *    At most [maxVehiclePasses] car-like checks get through one back-off (a phone on a running washing machine).
 *  - A passive fix (another app's GPS, free; at most [maxPassiveAgeMs] old) at driving speed starts a check even
 *    during a pause.
 *  - [snooze] (after the user pressed Stop) ignores everything for a while.
 * Every decision worth knowing goes to [log] (Diagnostics, [StartLog]).
 */
class DriveDetector(
    val gate: SpeedGate = SpeedGate(sustainMs = 0L),
    val checkMs: Long = 60_000L,
    val noFixMs: Long = 90_000L,
    val minAfterFixMs: Long = 20_000L,
    val firstCooldownMs: Long = 3 * 60_000L,
    val maxCooldownMs: Long = 10 * 60_000L,
    val decayMs: Long = 30 * 60_000L,
    val maxFastRechecks: Int = 4,
    val maxPassiveAgeMs: Long = 30_000L,
    val quickCheckMs: Long = 30_000L,
    val vehicleRetryMs: Long = 60_000L,
    val missesBeforeBackoff: Int = 3,
    val firstBackoffMs: Long = 10 * 60_000L,
    val maxBackoffMs: Long = 30 * 60_000L,
    /** Car-like checks one back-off lets through (a phone on a running washing machine vibrates like a car). */
    val maxVehiclePasses: Int = 3,
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
    private var firstFixMs = -1L
    private var sawVehicleSpeed = false
    private var lastFast = false
    private var fastRechecks = 0
    private var cooldownUntilMs = Long.MIN_VALUE
    private var cooldownMs = firstCooldownMs
    private var lastMissMs: Long? = null
    private var snoozeUntilMs = Long.MIN_VALUE
    private var vehicleThisCheck = false
    private var missesInRow = 0
    private var backoffUntilMs = Long.MIN_VALUE
    private var backoffMs = firstBackoffMs
    private var passes = 0   // car-like checks let through the current back-off (kept when a miss extends it)

    /** Motion now could start a check: the watcher should first ask the accelerometer what kind of motion it is. */
    fun wantsMotionKind(nowMs: Long): Boolean = !checking && nowMs >= snoozeUntilMs

    /**
     * The significant-motion sensor fired; [kind]: what the accelerometer says ([MotionKind]). Not known counts as
     * walking: a pause then holds, as before.
     */
    fun onMotion(nowMs: Long, kind: MotionKind.Kind = MotionKind.Kind.WALKING): Action {
        if (nowMs >= backoffUntilMs) passes = 0   // that back-off is over
        return motion(nowMs, kind)
    }

    private fun motion(nowMs: Long, kind: MotionKind.Kind): Action = when {
        checking -> Action.NONE
        nowMs < snoozeUntilMs -> { log(StartLog.Reason.SKIP_SNOOZED); Action.NONE }
        kind == MotionKind.Kind.STILL -> { log(StartLog.Reason.SKIP_STILL); Action.NONE }
        // A drive may begin during a back-off: only the car's own vibration gets through it, [maxVehiclePasses] times.
        nowMs < backoffUntilMs && (kind != MotionKind.Kind.VEHICLE || passes >= maxVehiclePasses) -> {
            log(StartLog.Reason.SKIP_BACKOFF)
            Action.NONE
        }
        // In a pause, or after a back-off, only vehicle-like motion checks: moving around at home costs nothing.
        kind != MotionKind.Kind.VEHICLE && (nowMs < cooldownUntilMs || backedOff) -> {
            log(StartLog.Reason.MOTION_WALKING)
            Action.NONE
        }
        nowMs >= cooldownUntilMs && !backedOff -> {
            log(StartLog.Reason.MOTION_CHECK)
            startCheck(nowMs, quick = false)
        }
        nowMs - lastCheckEndMs >= vehicleRetryMs -> {
            log(StartLog.Reason.MOTION_CHECK)
            if (nowMs < backoffUntilMs) passes++
            startCheck(nowMs, quick = true)
        }
        else -> { retryAtMs = lastCheckEndMs + vehicleRetryMs; Action.RETRY_LATER }
    }

    private val backedOff: Boolean get() = missesInRow >= missesBeforeBackoff

    private fun resetBackoff() {
        missesInRow = 0
        passes = 0
        backoffMs = firstBackoffMs
        backoffUntilMs = Long.MIN_VALUE
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
        if (!gotFix) firstFixMs = tMs
        gotFix = true
        // Two fixes in a row: one GPS jump at home (wander) is not a vehicle.
        val fast = kmh >= gate.resetKmh
        val twice = fast && lastFast
        lastFast = fast
        if (twice && !sawVehicleSpeed) {
            sawVehicleSpeed = true
            vehicleThisCheck = true
            resetBackoff()
            log(StartLog.Reason.WAITING_FOR_SPEED)
        }
        if (!gate.add(tMs, kmh)) return Action.NONE
        checking = false
        fastRechecks = 0
        cooldownMs = firstCooldownMs
        resetBackoff()
        return Action.DRIVING
    }

    /** Call every few seconds during a check. */
    fun onTimer(nowMs: Long): Action {
        if (!checking) return Action.NONE
        val age = nowMs - checkStartMs
        // No fix yet (a cold GPS): up to noFixMs. Then the window, but at least minAfterFixMs after the first fix.
        val over = if (!gotFix) age >= noFixMs
            else age >= (if (quick) quickCheckMs else checkMs) && nowMs - firstFixMs >= minAfterFixMs
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
        if (!vehicleThisCheck && ++missesInRow >= missesBeforeBackoff) {
            // Moving around without a vehicle (at home): no checks for a while, longer each time.
            backoffUntilMs = nowMs + backoffMs
            backoffMs = minOf(backoffMs + firstBackoffMs, maxBackoffMs)
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
        firstFixMs = -1L
        lastFast = false
        sawVehicleSpeed = false
        vehicleThisCheck = false
        fastRechecks = 0
        gate.reset()
        return Action.START_CHECK
    }
}

/**
 * What kind of motion? From a few seconds of accelerometer magnitudes (m/s², 25 Hz). Pure Kotlin.
 *  - [Kind.STILL]: standard deviation below [STILL_SD]; a phone lying on a table (sensor noise is about 0.01-0.03).
 *  - [Kind.WALKING]: above [WALKING_SD]; walking with the phone swings it by several m/s² at every step.
 *  - [Kind.VEHICLE]: in between, and the way a car shakes a phone: [SEGMENTS] slices of the window all vibrate
 *    (steady, no slice below [STILL_SD], none more than [MAX_SLICE_RATIO] times another), and the vibration is fast
 *    (road and engine, ~5-20 Hz): the sample-to-sample change is at least [MIN_FAST] of what pure noise gives.
 *  - [Kind.MOVED]: anything else: carried gently (slow sway, ~1 Hz), picked up or put down (one burst), and no
 *    accelerometer at all. It never gets through a pause or a back-off.
 */
object MotionKind {
    enum class Kind { STILL, VEHICLE, MOVED, WALKING }

    const val STILL_SD = 0.06
    const val WALKING_SD = 1.5
    const val SEGMENTS = 8
    const val MAX_SLICE_RATIO = 4.0
    const val MIN_FAST = 0.5
    const val MIN_SAMPLES = 40

    private fun sd(v: List<Double>): Double {
        val mean = v.average()
        return Math.sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
    }

    fun of(mags: List<Double>): Kind {
        if (mags.size < MIN_SAMPLES) return Kind.MOVED
        val sd = sd(mags)
        if (sd < STILL_SD) return Kind.STILL
        if (sd > WALKING_SD) return Kind.WALKING
        val n = mags.size / SEGMENTS
        val slices = (0 until SEGMENTS).map { sd(mags.subList(it * n, it * n + n)) }
        val steady = slices.min() >= STILL_SD && slices.max() <= MAX_SLICE_RATIO * slices.min()
        // RMS of sample-to-sample differences over sqrt(2)·sd: about 1 for fast vibration and noise, near 0 for sway.
        val diffs = (1 until mags.size).map { mags[it] - mags[it - 1] }
        val fast = Math.sqrt(diffs.sumOf { it * it } / diffs.size) / (Math.sqrt(2.0) * sd)
        return if (steady && fast >= MIN_FAST) Kind.VEHICLE else Kind.MOVED
    }
}
