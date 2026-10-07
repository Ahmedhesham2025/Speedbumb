package app.bumpbeeper

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the phone says about itself besides its motion sensors. The app updates it during a trip (BumpService, on the
 * engine thread); replays and tests leave it at "nothing known", and then only the motion counts.
 * Times are on the sensor clock (elapsedRealtime milliseconds), like [Fix.timeMs].
 */
class PhoneSignals {
    /** The screen is on (ACTION_SCREEN_ON / OFF). */
    @Volatile var screenOn = false
    /** When the user last unlocked the phone (ACTION_USER_PRESENT); [NEVER] = not on this trip. */
    @Volatile var unlockedAtMs = NEVER
    /** Something covers the proximity sensor (a pocket, an ear); null = no sensor or no reading yet. */
    @Volatile var proximityNear: Boolean? = null
    /** Ambient light, lux; NaN = no sensor or no reading yet. */
    @Volatile var lux = Double.NaN
    /** A call held to the ear: in-call or VoIP audio mode with no Bluetooth, wired or speaker route. */
    @Volatile var handheldCall = false
    /** Plugged in: a phone on a cable more likely sits in a holder. */
    @Volatile var charging = false
    /** The lock screen is up; null = unknown. Kept for diagnostics: no rule uses it. */
    @Volatile var keyguardLocked: Boolean? = null

    companion object {
        /** "Never", on the sensor clock. */
        const val NEVER = Long.MIN_VALUE / 4
    }
}

/** Where the phone is: resting in a holder, resting loose (pocket, cup holder, seat), or in someone's hand. */
enum class PhoneState { STABLE_MOUNTED, STABLE_LOOSE, HANDLED }

/** Every threshold of [PhoneStateDetector]. Units: degrees, rad/s, milliseconds, seconds, lux, km/h. */
class PhoneStateConfig {
    /** HANDLED: the phone turned more than this from where it rests (its gravity direction)... */
    var tiltDeg = 28.0
    /** ...for longer than this. The same hold applies to [gyroRads] and [screenTiltDeg]. */
    var holdMs = 500L
    /** HANDLED: rotation RMS (time constant [gyroTauS]) above this that the GPS heading doesn't explain. */
    var gyroRads = 1.0
    var gyroTauS = 0.1
    /** Turning about "up" up to the GPS heading rate plus this much is the car turning, not the phone. */
    var headingSlackRads = 0.2
    /** HANDLED: the 0.25 s and 1 s gravity estimates this far apart (the phone is turning fast right now). */
    var turnDeg = 25.0
    /** HANDLED: unlocked less than this ago, unless the phone sits in a holder. */
    var unlockMs = 3000L
    /** HANDLED: screen on, not in a holder, and tilted at least this far for [holdMs]. */
    var screenTiltDeg = 15.0
    /** HANDLED: out of a pocket. The proximity sensor clears and the light jumps from at most [darkLux] to at least [brightLux], within [pocketExitMs] of each other. */
    var darkLux = 10.0
    var brightLux = 30.0
    var pocketExitMs = 2000L
    /** HANDLED ends after this long with no sign of handling... */
    var calmMs = 2000L
    /** ...or, when only the tilt is left, once the phone has rested within [resettleDeg] for this long (put down somewhere new). */
    var resettleMs = 10_000L
    var resettleDeg = 10.0
    /** The gyroscope-tracked gravity leans towards the accelerometer with this time constant, s. */
    var gravityTauS = 10.0
    /** Where the phone rests follows slowly while it is stable, s... */
    var baselineTauS = 30.0
    /** ...but quickly ([settleTauS], s) for [settleMs] after a handling: the phone is still settling where it was put. */
    var settleMs = 5000L
    var settleTauS = 1.0
    /** Mounted: the tilt RMS over this window (s) stays below [mountedTiltDeg] ([mountedTiltDegCharging] when charging, [mountedTiltDegPlaced] with placement "mounted"). */
    var mountedWindowS = 30.0
    var mountedTiltDeg = 1.5
    var mountedTiltDegCharging = 3.0
    var mountedTiltDegPlaced = 4.0
    /** A jostle: handled for less than this, never tilted for [sustainedTiltMs] in a row, and nothing but motion. */
    var jostleMaxMs = 2000L
    var sustainedTiltMs = 2000L
    /** Pocket mode turns on by itself, for the rest of the trip, after this many jostles while driving within [autoPocketWindowMs]. */
    var autoPocketJostles = 4
    var autoPocketWindowMs = 300_000L
    /** Handling only counts as "while driving" at this speed or faster. */
    var movingKmh = 10.0
}

/**
 * Is the phone resting in its holder ([PhoneState.STABLE_MOUNTED]), resting loose ([PhoneState.STABLE_LOOSE]), or being
 * handled ([PhoneState.HANDLED])? [BumpEngine] feeds it the same samples it gets; jolts and driving events are not
 * trusted while the phone is handled, and a long enough handling is phone use ([DrivingMonitor]).
 *
 * Where the phone rests is a gravity direction in phone axes (the baseline), learned while it is stable. The current
 * gravity is the 0.25 s low-pass of the accelerometer, kept on track by the gyroscope so that the car speeding up,
 * braking or cornering doesn't look like a tilt (without a gyroscope: the tilt is also judged from how much of
 * "up" is left). HANDLED starts when any of these holds:
 *  - the tilt from the baseline stays above [PhoneStateConfig.tiltDeg] for longer than [PhoneStateConfig.holdMs];
 *  - the phone rotates (RMS above [PhoneStateConfig.gyroRads] for longer than the hold) more than the GPS heading turns;
 *  - it turns fast right now ([PhoneStateConfig.turnDeg], the engine's old "phone moving" rule);
 *  - it was unlocked less than [PhoneStateConfig.unlockMs] ago, or its screen is on and it tilts
 *    ([PhoneStateConfig.screenTiltDeg]), unless it sits in a holder;
 *  - a hand-held call ([PhoneSignals.handheldCall]);
 *  - it comes out of a pocket (proximity clears and the light jumps from dark to bright).
 * It ends after [PhoneStateConfig.calmMs] without any of them (or once the phone has rested in a new place for
 * [PhoneStateConfig.resettleMs]), and the baseline is learned again where the phone is then.
 *
 * Feed it from one thread, like the engine. Times are the sensor clock, milliseconds.
 */
class PhoneStateDetector(val cfg: PhoneStateConfig = PhoneStateConfig(), val signals: PhoneSignals = PhoneSignals()) {
    /** Where the user says the phone sits: mounted | cupholder | pocket | unknown. [DrivingMonitor] sets it. */
    @Volatile var placement = "unknown"

    var state = PhoneState.STABLE_LOOSE
        private set
    /** Start of the HANDLED stretch going on (back-dated to its first sign), or [PhoneSignals.NEVER]. */
    var handledSinceMs = NEVER
        private set
    /** When the last HANDLED stretch ended, or [PhoneSignals.NEVER]. */
    var lastHandledEndMs = NEVER
        private set

    // The HANDLED stretch going on, or the last one ("episode").
    /** Numbers the episodes from 1; 0 = none yet. */
    var episode = 0
        private set
    var episodeStartMs = NEVER
        private set
    /** What showed the handling: bits [TILT], [GYRO], [TURN], [UNLOCK], [SCREEN], [CALL], [POCKET_EXIT]. */
    var causes = 0
        private set
    /** Time something showed handling, and the part of it while driving ([PhoneStateConfig.movingKmh]). */
    var handledMs = 0L
        private set
    var movingMs = 0L
        private set
    /** Longest time the tilt stayed above [PhoneStateConfig.tiltDeg] in a row. */
    var tiltHeldMs = 0L
        private set
    /** The phone sat in a holder when the episode began. */
    var fromMount = false
        private set

    /** Pocket mode: placement "pocket", or a phone that kept jostling while driving on this trip. */
    val pocketMode: Boolean get() = placement == "pocket" || autoPocket
    /** The episode is (so far) only a jostle: short, no sustained tilt, nothing but motion. */
    val jostle: Boolean
        get() = handledMs < cfg.jostleMaxMs && tiltHeldMs < cfg.sustainedTiltMs && (causes and NOT_MOTION) == 0

    /** For tests and tuning: the tilt from the baseline, degrees. */
    var tiltDeg = 0.0
        private set

    private var ready = false
    private var lastAccelMs = 0L
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0   // 1 s low-pass of the accelerometer
    private var fx = 0.0; private var fy = 0.0; private var fz = 0.0   // 0.25 s low-pass
    private var cx = 0.0; private var cy = 0.0; private var cz = 0.0   // gravity now (unit), gyroscope-tracked
    private var bx = 0.0; private var by = 0.0; private var bz = 0.0   // where the phone rests (unit)
    private var ax = 0.0; private var ay = 0.0; private var az = 0.0   // resettle anchor (unit)
    private var anchorMs = NEVER
    private var gyroSeen = false
    private var lastGyroMs = NEVER
    private var wx = 0.0; private var wy = 0.0; private var wz = 0.0
    private var e2 = 0.0
    private var turn = 0.0
    private var tiltSince = NEVER; private var gyroSince = NEVER; private var screenSince = NEVER   // over the threshold since
    private var tiltRunMs = 0L
    private var lastSignMs = NEVER
    private var tiltMs2 = 0.0   // mean square tilt over the mounted window
    private var stableSinceMs = NEVER
    private var speedKmh = 0.0
    private var prevFix: Fix? = null
    /** GPS heading rate, rad/s, of the last few fixes (fix time, rate). */
    private val gpsTurns = ArrayDeque<Pair<Long, Double>>()
    private var gpsTurnRads = 0.0
    private var lastNear: Boolean? = null
    private var farAtMs = NEVER; private var darkAtMs = NEVER; private var brightAtMs = NEVER; private var exitAtMs = NEVER
    private val jostles = ArrayDeque<Long>()
    private var autoPocket = false
    // Recent episodes (start, end; end = Long.MAX_VALUE while going on), for [handledDuring].
    private val starts = LongArray(HISTORY); private val ends = LongArray(HISTORY); private var count = 0

    /** True if the phone was HANDLED at any time from [fromMs] to [toMs]. */
    fun handledDuring(fromMs: Long, toMs: Long): Boolean {
        for (i in 0 until min(count, HISTORY)) if (starts[i] <= toMs && ends[i] >= fromMs) return true
        return false
    }

    fun onFix(f: Fix) {
        speedKmh = (if (f.speedMps.isNaN()) 0.0 else f.speedMps) * 3.6
        val p = prevFix
        prevFix = f
        if (p == null) return
        val dt = (f.timeMs - p.timeMs) / 1000.0
        if (dt <= 0 || dt > 3.0) return
        if (!f.bearingDeg.isNaN() && !p.bearingDeg.isNaN() && f.speedMps >= 4.0 && p.speedMps >= 4.0) {
            var d = f.bearingDeg - p.bearingDeg
            if (d > 180) d -= 360
            if (d < -180) d += 360
            gpsTurns.addLast(Pair(f.timeMs, abs(degToRad(d)) / dt))
        }
        while (gpsTurns.isNotEmpty() && gpsTurns.first().first < f.timeMs - 4000) gpsTurns.removeFirst()
        gpsTurnRads = gpsTurns.maxOfOrNull { it.second } ?: 0.0
    }

    /** Rotation rate, rad/s, phone axes. */
    fun onGyro(tMs: Long, x: Double, y: Double, z: Double) {
        if (ready && lastGyroMs != NEVER) {
            val dt = (tMs - lastGyroMs).coerceIn(1L, 200L) / 1000.0
            // A direction fixed in the world, seen from the turning phone: dv/dt = v × w.
            setC(cx + dt * (cy * wz - cz * wy), cy + dt * (cz * wx - cx * wz), cz + dt * (cx * wy - cy * wx))
            // All rotation except turning about "up" as much as the GPS heading turns (plus some slack).
            val yaw = wx * cx + wy * cy + wz * cz
            val rest = max(0.0, wx * wx + wy * wy + wz * wz - yaw * yaw)
            val extra = max(0.0, abs(yaw) - gpsTurnRads - cfg.headingSlackRads)
            e2 += dt / (cfg.gyroTauS + dt) * (rest + extra * extra - e2)
        }
        wx = x; wy = y; wz = z
        lastGyroMs = tMs
        gyroSeen = true
    }

    /** Raw accelerometer, m/s² (gravity included). */
    fun onAccel(tMs: Long, x: Double, y: Double, z: Double) {
        if (!ready) {
            sx = x; sy = y; sz = z; fx = x; fy = y; fz = z
            val n = sqrt(x * x + y * y + z * z)
            if (n < 5.0) return
            cx = x / n; cy = y / n; cz = z / n
            bx = cx; by = cy; bz = cz
            ready = true
            lastAccelMs = tMs
            stableSinceMs = tMs
            return
        }
        val dtMs = (tMs - lastAccelMs).coerceIn(1L, 200L)
        lastAccelMs = tMs
        val dt = dtMs / 1000.0
        val aS = dt / (1.0 + dt)
        val aF = dt / (0.25 + dt)
        sx += aS * (x - sx); sy += aS * (y - sy); sz += aS * (z - sz)
        fx += aF * (x - fx); fy += aF * (y - fy); fz += aF * (z - fz)
        val gs = sqrt(sx * sx + sy * sy + sz * sz)
        val gf = sqrt(fx * fx + fy * fy + fz * fz)
        if (gs < 5.0 || gf < 1.0) return
        val ux = fx / gf; val uy = fy / gf; val uz = fz / gf
        if (gyroSeen) {
            val k = dt / (cfg.gravityTauS + dt)
            setC(cx + k * (ux - cx), cy + k * (uy - cy), cz + k * (uz - cz))
            tiltDeg = angle(cx, cy, cz, bx, by, bz)
        } else {
            cx = ux; cy = uy; cz = uz
            // Speeding up or braking leans the measured "down" too: also ask how much of "up" is left.
            val up = ((ux * bx + uy * by + uz * bz) * gf / G).coerceIn(-1.0, 1.0)
            tiltDeg = min(angle(ux, uy, uz, bx, by, bz), radToDeg(acos(up)))
        }
        turn = radToDeg(acos(((sx * fx + sy * fy + sz * fz) / (gs * gf)).coerceIn(-1.0, 1.0)))
        step(tMs, dtMs)
    }

    private fun step(t: Long, dtMs: Long) {
        var handled = state == PhoneState.HANDLED
        val loose = if (handled) !fromMount else state != PhoneState.STABLE_MOUNTED
        var signs = 0
        val tiltOver = tiltDeg > cfg.tiltDeg
        if (tiltOver) {
            if (tiltSince == NEVER) tiltSince = t
            if (t - tiltSince >= cfg.holdMs) signs = signs or TILT
        } else tiltSince = NEVER
        val gyroOver = sqrt(e2) > cfg.gyroRads
        if (gyroOver) {
            if (gyroSince == NEVER) gyroSince = t
            if (t - gyroSince >= cfg.holdMs) signs = signs or GYRO
        } else gyroSince = NEVER
        if (turn > cfg.turnDeg) signs = signs or TURN
        if (loose && t - signals.unlockedAtMs in 0..cfg.unlockMs) signs = signs or UNLOCK
        if (signals.screenOn && loose && tiltDeg > cfg.screenTiltDeg) {
            if (screenSince == NEVER) screenSince = t
            if (t - screenSince >= cfg.holdMs) signs = signs or SCREEN
        } else screenSince = NEVER
        if (signals.handheldCall) signs = signs or CALL
        if (pocketExit(t)) signs = signs or POCKET_EXIT

        var began = false
        if (signs != 0) {
            if (!handled) {
                var start = t
                if ((signs and TILT) != 0) start = min(start, tiltSince)
                if ((signs and GYRO) != 0) start = min(start, gyroSince)
                if ((signs and SCREEN) != 0) start = min(start, screenSince)
                if ((signs and UNLOCK) != 0) start = min(start, max(signals.unlockedAtMs, t - cfg.unlockMs))
                begin(start, t)
                handled = true
                began = true
            }
            lastSignMs = t
            causes = causes or signs
        }
        if (handled) {
            if (!began) {
                if (tiltOver || gyroOver || (signs and NOT_MOTION) != 0) {
                    handledMs += dtMs
                    if (speedKmh >= cfg.movingKmh) movingMs += dtMs
                }
                if (tiltOver) {
                    tiltRunMs += dtMs
                    tiltHeldMs = max(tiltHeldMs, tiltRunMs)
                } else tiltRunMs = 0L
            }
            // Put down somewhere new: only the tilt still says "handled", and the phone has rested there a while.
            if (anchorMs == NEVER || angle(cx, cy, cz, ax, ay, az) > cfg.resettleDeg || gyroOver || (signs and TURN) != 0) {
                ax = cx; ay = cy; az = cz
                anchorMs = t
            }
            if (signs == TILT && t - anchorMs >= cfg.resettleMs) end(t)
            else if (t - lastSignMs >= cfg.calmMs) end(t)
        } else {
            val dt = dtMs / 1000.0
            val k = dt / ((if (t - lastHandledEndMs < cfg.settleMs) cfg.settleTauS else cfg.baselineTauS) + dt)
            setB(bx + k * (cx - bx), by + k * (cy - by), bz + k * (cz - bz))
            tiltMs2 += dt / (cfg.mountedWindowS + dt) * (tiltDeg * tiltDeg - tiltMs2)
            state = stableState(t)
        }
    }

    private fun begin(start: Long, t: Long) {
        fromMount = state == PhoneState.STABLE_MOUNTED
        state = PhoneState.HANDLED
        episode++
        episodeStartMs = start
        handledSinceMs = start
        causes = 0
        handledMs = t - start
        movingMs = if (speedKmh >= cfg.movingKmh) t - start else 0L
        tiltRunMs = if (tiltSince != NEVER) t - tiltSince else 0L
        tiltHeldMs = tiltRunMs
        anchorMs = NEVER
        val i = count % HISTORY
        starts[i] = start
        ends[i] = Long.MAX_VALUE
        count++
    }

    private fun end(t: Long) {
        ends[(count - 1) % HISTORY] = t
        lastHandledEndMs = t
        handledSinceMs = NEVER
        // Learn where the phone rests now.
        bx = cx; by = cy; bz = cz
        tiltMs2 = 0.0
        stableSinceMs = t
        tiltSince = NEVER; gyroSince = NEVER; screenSince = NEVER
        if (movingMs > 0 && jostle) noteJostle(t)
        state = stableState(t)
    }

    /** Mounted when its tilt has been steady for the whole window; pocket and cup holder are never mounted. */
    private fun stableState(t: Long): PhoneState {
        val limit = when (placement) {
            "pocket", "cupholder" -> return PhoneState.STABLE_LOOSE
            "mounted" -> cfg.mountedTiltDegPlaced
            else -> if (signals.charging) cfg.mountedTiltDegCharging else cfg.mountedTiltDeg
        }
        if (t - stableSinceMs < (cfg.mountedWindowS * 1000).toLong()) {
            return if (placement == "mounted") PhoneState.STABLE_MOUNTED else PhoneState.STABLE_LOOSE
        }
        return if (sqrt(tiltMs2) < limit) PhoneState.STABLE_MOUNTED else PhoneState.STABLE_LOOSE
    }

    private fun noteJostle(t: Long) {
        jostles.addLast(t)
        while (jostles.first() < t - cfg.autoPocketWindowMs) jostles.removeFirst()
        if (jostles.size >= cfg.autoPocketJostles) autoPocket = true
    }

    /** Out of a pocket: the proximity sensor clears and the light jumps from dark to bright, close together in time. */
    private fun pocketExit(t: Long): Boolean {
        val near = signals.proximityNear
        if (near != null && near != lastNear) {
            if (lastNear == true && !near) farAtMs = t
            lastNear = near
        }
        val lux = signals.lux
        if (!lux.isNaN()) {
            if (lux <= cfg.darkLux) darkAtMs = t
            else if (lux >= cfg.brightLux && t - darkAtMs <= cfg.pocketExitMs && brightAtMs < darkAtMs) brightAtMs = t
        }
        val exit = max(farAtMs, brightAtMs)
        if (abs(farAtMs - brightAtMs) <= cfg.pocketExitMs && exit > exitAtMs) exitAtMs = exit
        return t - exitAtMs in 0L..1000L
    }

    private fun setC(x: Double, y: Double, z: Double) { val n = sqrt(x * x + y * y + z * z); cx = x / n; cy = y / n; cz = z / n }
    private fun setB(x: Double, y: Double, z: Double) { val n = sqrt(x * x + y * y + z * z); bx = x / n; by = y / n; bz = z / n }

    private fun angle(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Double =
        radToDeg(acos((x1 * x2 + y1 * y2 + z1 * z2).coerceIn(-1.0, 1.0)))

    companion object {
        const val TILT = 1
        const val GYRO = 2
        const val TURN = 4
        const val UNLOCK = 8
        const val SCREEN = 16
        const val CALL = 32
        const val POCKET_EXIT = 64
        /** Signs that are not the phone's motion. */
        private const val NOT_MOTION = UNLOCK or SCREEN or CALL or POCKET_EXIT
        private const val NEVER = PhoneSignals.NEVER
        private const val HISTORY = 8
        private const val G = 9.81
    }
}
