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
    /**
     * The phone has a lock screen; false = none, so every screen-on reads as an unlock and only counts with motion
     * ([PhoneStateConfig.unlockMs]); null = unknown (an unlock counts).
     */
    @Volatile var keyguardPresent: Boolean? = null

    companion object {
        /** "Never", on the sensor clock. */
        const val NEVER = Long.MIN_VALUE / 4
    }
}

/** Where the phone is: resting in a holder, resting loose (pocket, cup holder, seat), or in someone's hand. */
enum class PhoneState { STABLE_MOUNTED, STABLE_LOOSE, HANDLED }

/** Every threshold of [PhoneStateDetector]. Units: degrees, rad/s, m/s, m/s², milliseconds, seconds, lux, km/h. */
class PhoneStateConfig {
    /** HANDLED: the phone turned more than this from where it rests (its gravity direction)... */
    var tiltDeg = 28.0
    /** ...for longer than this. The same hold applies to [gyroRads] and [screenTiltDeg]. */
    var holdMs = 500L
    /** HANDLED: rotation RMS (time constant [gyroTauS]) above this that the GPS heading doesn't explain. */
    var gyroRads = 1.0
    var gyroTauS = 0.1
    /**
     * Turning about "up" up to the GPS heading rate plus this much is the car turning, not the phone. The heading rate is
     * the largest over the last [headingWindowMs], from fixes at least [headingMinMps] fast and at most [maxFixGapS] apart.
     */
    var headingSlackRads = 0.2
    var headingMinMps = 4.0
    var headingWindowMs = 4000L
    var maxFixGapS = 3.0
    /**
     * HANDLED: the [fastTauS] and [slowTauS] low-passes of the accelerometer this far apart (the phone turns fast right
     * now). [BumpEngine] drops a jolt on the same angle.
     */
    var turnDeg = 25.0
    var fastTauS = 0.25
    var slowTauS = 1.0
    /**
     * HANDLED: unlocked less than this ago, unless the phone sits in a holder. Without a lock screen
     * ([PhoneSignals.keyguardPresent] false) it only counts if the phone also moved within this time of the "unlock".
     */
    var unlockMs = 3000L
    /**
     * HANDLED: screen on, not in a holder, and turned at least this far from where it last rested. It rests where it
     * stays [restMs] with a rotation RMS under [stillRads].
     */
    var screenTiltDeg = 15.0
    var restMs = 1000L
    var stillRads = 0.2
    /**
     * HANDLED, for [pocketExitHoldMs]: out of a pocket. The proximity sensor clears and the light jumps from at most
     * [darkLux] to at least [brightLux], within [pocketExitMs] of each other.
     */
    var darkLux = 10.0
    var brightLux = 30.0
    var pocketExitMs = 2000L
    var pocketExitHoldMs = 1000L
    /** HANDLED ends after this long with no sign of handling... */
    var calmMs = 2000L
    /**
     * ...or once the phone has rested within [resettleDeg] for this long, whatever the screen says (put down or slipped
     * somewhere new; not during a call). A phone not handled that rests this long somewhere new rests there now.
     * Not while the screen is on and the phone is turned past [tiltDeg]: a hand held still at an angle looks the same,
     * and the hand wins. Such a phone stays HANDLED until the screen goes off or it moves (a holder that slips that far
     * with the screen on pauses bump learning meanwhile).
     */
    var resettleMs = 10_000L
    var resettleDeg = 10.0
    /**
     * The gyroscope-tracked gravity leans towards the accelerometer with this time constant (s), only while the car
     * drives steadily: the GPS speed changes less than [steadyLongMs2] and nothing pushes the phone more than [steadyHorizMs2].
     */
    var gravityTauS = 10.0
    var steadyLongMs2 = 1.0
    var steadyHorizMs2 = 0.5
    /** Warm-up: until the car has driven steadily for this long in a row, gravity and the baseline learn fast ([settleTauS]). */
    var warmupMs = 4000L
    /** Where the phone rests follows slowly while it is stable, s... */
    var baselineTauS = 30.0
    /** ...but quickly ([settleTauS], s) for [settleMs] after a handling: the phone is still settling where it was put. */
    var settleMs = 5000L
    var settleTauS = 1.0
    /**
     * Mounted: the tilt RMS over this window (s; steady driving, at rest) stays below [mountedTiltDeg]
     * ([mountedTiltDegCharging] when charging, [mountedTiltDegPlaced] with placement "mounted").
     */
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
 * trusted while the phone is handled, and a long enough handling while driving is phone use ([DrivingMonitor]).
 *
 * Where the phone rests is a gravity direction in phone axes (the baseline). The current gravity is tracked by the
 * gyroscope and leans towards the accelerometer only while the car drives steadily, so speeding up, braking or
 * cornering doesn't look like a tilt (without a gyroscope: the tilt is also judged from how much of "up" is left).
 * HANDLED starts when any of these holds:
 *  - the tilt from the baseline stays above [PhoneStateConfig.tiltDeg] for longer than [PhoneStateConfig.holdMs];
 *  - the phone rotates (RMS above [PhoneStateConfig.gyroRads] for longer than the hold) more than the GPS heading turns;
 *  - it turns fast right now ([PhoneStateConfig.turnDeg]);
 *  - it was unlocked less than [PhoneStateConfig.unlockMs] ago, or its screen is on and it turned
 *    ([PhoneStateConfig.screenTiltDeg]) from where it last rested, unless it sits in a holder;
 *  - a hand-held call ([PhoneSignals.handheldCall]);
 *  - it comes out of a pocket (proximity clears and the light jumps from dark to bright).
 * It ends after [PhoneStateConfig.calmMs] without any of them, or once the phone has rested in one place for
 * [PhoneStateConfig.resettleMs] (not while the screen is on and it is turned past [PhoneStateConfig.tiltDeg]: held
 * still in a hand, it looks the same). Put back where it rested before, it keeps its mounted or loose standing; anywhere
 * else it is learned again from scratch.
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
    /** What showed the handling: bits [TILT], [GYRO], [TURN], [UNLOCK], [SCREEN], [CALL], [POCKET_EXIT]... */
    var causes = 0
        private set
    /** ...and which of them showed while driving ([PhoneStateConfig.movingKmh]). */
    var movingCauses = 0
        private set
    /** Time the phone moved or a sign other than its motion held. */
    var handledMs = 0L
        private set
    /**
     * Time, while driving, the phone was tilted past [PhoneStateConfig.tiltDeg] or held by a sign other than its motion
     * (the screen only while the phone moves).
     */
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
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0   // slow low-pass of the accelerometer
    private var fx = 0.0; private var fy = 0.0; private var fz = 0.0   // fast low-pass
    private var cx = 0.0; private var cy = 0.0; private var cz = 0.0   // gravity now (unit), gyroscope-tracked
    private var bx = 0.0; private var by = 0.0; private var bz = 0.0   // where the phone rests (unit)
    private var ax = 0.0; private var ay = 0.0; private var az = 0.0   // anchor: it has stayed within resettleDeg of this since anchorMs
    private var rx = 0.0; private var ry = 0.0; private var rz = 0.0   // where it last rested (still for restMs)
    private var anchorMs = NEVER
    private var stillSinceMs = NEVER
    private var gyroSeen = false
    private var lastGyroMs = NEVER
    private var wx = 0.0; private var wy = 0.0; private var wz = 0.0
    private var raw2 = 0.0   // rotation the GPS heading doesn't explain, squared, this sample...
    private var e2 = 0.0     // ...and its mean (time constant gyroTauS)
    private var turn = 0.0
    private var tiltSince = NEVER; private var gyroSince = NEVER; private var screenSince = NEVER   // over the threshold since
    private var tiltRunMs = 0L
    private var lastSignMs = NEVER
    private var lastMoveMs = NEVER   // the phone last rotated fast or was turned from where it rested
    private var movedDriving = false
    private var tiltMs2 = 0.0   // mean square tilt over the mounted window
    private var stableSinceMs = NEVER
    private var speedKmh = 0.0
    private var prevFix: Fix? = null
    /** GPS heading rate, rad/s, of the last few fixes (fix time, rate). */
    private val gpsTurns = ArrayDeque<Pair<Long, Double>>()
    private var gpsTurnRads = 0.0
    private var gpsSteady = true
    private var carSteady = true
    private var steadyRunMs = 0L
    private var warm = false
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
        if (dt <= 0 || dt > cfg.maxFixGapS) return
        if (!f.bearingDeg.isNaN() && !p.bearingDeg.isNaN() && f.speedMps >= cfg.headingMinMps && p.speedMps >= cfg.headingMinMps) {
            var d = f.bearingDeg - p.bearingDeg
            if (d > 180) d -= 360
            if (d < -180) d += 360
            gpsTurns.addLast(Pair(f.timeMs, abs(degToRad(d)) / dt))
        }
        while (gpsTurns.isNotEmpty() && gpsTurns.first().first < f.timeMs - cfg.headingWindowMs) gpsTurns.removeFirst()
        gpsTurnRads = gpsTurns.maxOfOrNull { it.second } ?: 0.0
        val v1 = if (f.speedMps.isNaN()) 0.0 else f.speedMps
        val v0 = if (p.speedMps.isNaN()) 0.0 else p.speedMps
        gpsSteady = abs(v1 - v0) / dt < cfg.steadyLongMs2
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
            raw2 = rest + extra * extra
            e2 += dt / (cfg.gyroTauS + dt) * (raw2 - e2)
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
            if (n < MIN_G) return
            cx = x / n; cy = y / n; cz = z / n
            bx = cx; by = cy; bz = cz
            ax = cx; ay = cy; az = cz
            rx = cx; ry = cy; rz = cz
            ready = true
            lastAccelMs = tMs
            stableSinceMs = tMs; anchorMs = tMs; stillSinceMs = tMs
            return
        }
        val dtMs = (tMs - lastAccelMs).coerceIn(1L, 200L)
        lastAccelMs = tMs
        val dt = dtMs / 1000.0
        val aS = dt / (cfg.slowTauS + dt)
        val aF = dt / (cfg.fastTauS + dt)
        sx += aS * (x - sx); sy += aS * (y - sy); sz += aS * (z - sz)
        fx += aF * (x - fx); fy += aF * (y - fy); fz += aF * (z - fz)
        val gs = sqrt(sx * sx + sy * sy + sz * sz)
        val gf = sqrt(fx * fx + fy * fy + fz * fz)
        if (gs < MIN_G || gf < MIN_F) return
        val ux = fx / gf; val uy = fy / gf; val uz = fz / gf
        // The car drives steadily (GPS) and nothing pushes the phone: the accelerometer shows gravity alone.
        val along = (fx * sx + fy * sy + fz * sz) / gs
        carSteady = gpsSteady && sqrt(max(0.0, gf * gf - along * along)) < cfg.steadyHorizMs2
        steadyRunMs = if (carSteady) steadyRunMs + dtMs else 0L
        if (!warm && steadyRunMs >= cfg.warmupMs) {
            warm = true
            stableSinceMs = tMs - steadyRunMs
        }
        if (gyroSeen) {
            if (carSteady) {
                val k = dt / ((if (warm) cfg.gravityTauS else cfg.settleTauS) + dt)
                val ox = cx; val oy = cy; val oz = cz
                setC(cx + k * (ux - cx), cy + k * (uy - cy), cz + k * (uz - cz))
                // That corrects the estimate, the phone did not move: shift where it rested along with it.
                val dx = cx - ox; val dy = cy - oy; val dz = cz - oz
                var n = sqrt((ax + dx) * (ax + dx) + (ay + dy) * (ay + dy) + (az + dz) * (az + dz))
                ax = (ax + dx) / n; ay = (ay + dy) / n; az = (az + dz) / n
                n = sqrt((rx + dx) * (rx + dx) + (ry + dy) * (ry + dy) + (rz + dz) * (rz + dz))
                rx = (rx + dx) / n; ry = (ry + dy) / n; rz = (rz + dz) / n
            }
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
        val gyro2 = cfg.gyroRads * cfg.gyroRads
        val still = e2 <= cfg.stillRads * cfg.stillRads
        val rawOver = raw2 > gyro2
        val gyroOver = e2 > gyro2
        val turning = turn > cfg.turnDeg
        // Where the phone has stayed (within resettleDeg, since anchorMs), and where it last rested (still for restMs).
        if (angle(cx, cy, cz, ax, ay, az) > cfg.resettleDeg || gyroOver || turning) {
            ax = cx; ay = cy; az = cz
            anchorMs = t
        }
        if (!still || anchorMs == t) stillSinceMs = t
        if (t - stillSinceMs >= cfg.restMs) { rx = cx; ry = cy; rz = cz }
        val moved = angle(cx, cy, cz, rx, ry, rz)
        if (rawOver || moved > cfg.screenTiltDeg) lastMoveMs = t

        var signs = 0
        val tiltOver = tiltDeg > cfg.tiltDeg
        if (tiltOver) {
            if (tiltSince == NEVER) tiltSince = t
            if (t - tiltSince >= cfg.holdMs) signs = signs or TILT
        } else tiltSince = NEVER
        if (gyroOver) {
            if (gyroSince == NEVER) gyroSince = t
            if (t - gyroSince >= cfg.holdMs) signs = signs or GYRO
        } else gyroSince = NEVER
        if (turning) signs = signs or TURN
        // No lock screen: the "unlock" is only the screen turning on, so the phone must also have moved close to it.
        val unlock = signals.keyguardPresent != false || lastMoveMs >= signals.unlockedAtMs - cfg.unlockMs
        if (loose && unlock && t - signals.unlockedAtMs in 0..cfg.unlockMs) signs = signs or UNLOCK
        if (signals.screenOn && loose && moved > cfg.screenTiltDeg) {
            if (screenSince == NEVER) screenSince = t
            if (t - screenSince >= cfg.holdMs) signs = signs or SCREEN
        } else screenSince = NEVER
        if (signals.handheldCall) signs = signs or CALL
        if (pocketExit(t)) signs = signs or POCKET_EXIT
        val moving = speedKmh >= cfg.movingKmh

        var began = false
        if (signs != 0) {
            if (!handled) {
                var start = t
                if (tiltSince != NEVER) start = min(start, tiltSince)
                if ((signs and GYRO) != 0) start = min(start, gyroSince)
                if ((signs and SCREEN) != 0) start = min(start, screenSince)
                if ((signs and UNLOCK) != 0) start = min(start, max(signals.unlockedAtMs, t - cfg.unlockMs))
                begin(start, t, signs, moving)
                handled = true
                began = true
            }
            lastSignMs = t
            causes = causes or signs
            if (moving) movingCauses = movingCauses or signs
        }
        if (handled) {
            // The screen keeps the phone handled, but is handling time only while the phone moves (a slipped phone rests).
            val notMotion = (signs and (if (still) NOT_MOTION and SCREEN.inv() else NOT_MOTION)) != 0
            if (moving && (tiltOver || rawOver)) movedDriving = true
            if (!began) {
                if (tiltOver || rawOver || notMotion) handledMs += dtMs
                if (moving && (tiltOver || notMotion)) movingMs += dtMs
                if (tiltOver) {
                    tiltRunMs += dtMs
                    tiltHeldMs = max(tiltHeldMs, tiltRunMs)
                } else tiltRunMs = 0L
            }
            // Rested in one place for a while (put down, slipped in its holder): that is where it is now. Not while the
            // screen is on and the phone is turned past tiltDeg: a hand held still looks the same, and the hand wins.
            val rested = t - anchorMs >= cfg.resettleMs && !(signals.screenOn && tiltOver)
            if (signs != 0 && (signs and (GYRO or TURN or CALL)) == 0 && rested) end(t)
            else if (t - lastSignMs >= cfg.calmMs) end(t)
        } else {
            val dt = dtMs / 1000.0
            val settling = !warm || t - lastHandledEndMs < cfg.settleMs
            val k = dt / ((if (settling) cfg.settleTauS else cfg.baselineTauS) + dt)
            setB(bx + k * (cx - bx), by + k * (cy - by), bz + k * (cz - bz))
            val restsThere = angle(rx, ry, rz, bx, by, bz) <= cfg.resettleDeg
            if (!warm) stableSinceMs = t
            else if (!settling && carSteady && t - anchorMs >= cfg.restMs && restsThere) {
                tiltMs2 += dt / (cfg.mountedWindowS + dt) * (tiltDeg * tiltDeg - tiltMs2)
            }
            // Came to rest somewhere new (slipped in its holder, slid in the cup holder): it rests there now.
            if (!restsThere && t - anchorMs >= cfg.resettleMs) { bx = rx; by = ry; bz = rz }
            state = stableState(t)
        }
    }

    private fun begin(start: Long, t: Long, signs: Int, moving: Boolean) {
        fromMount = state == PhoneState.STABLE_MOUNTED
        state = PhoneState.HANDLED
        episode++
        episodeStartMs = start
        handledSinceMs = start
        causes = 0
        movingCauses = 0
        handledMs = t - start
        tiltRunMs = if (tiltSince != NEVER) t - tiltSince else 0L
        tiltHeldMs = tiltRunMs
        movingMs = if (!moving) 0L else if ((signs and NOT_MOTION) != 0) t - start else tiltRunMs
        ax = cx; ay = cy; az = cz
        anchorMs = t
        movedDriving = false
        val i = count % HISTORY
        starts[i] = start
        ends[i] = Long.MAX_VALUE
        count++
    }

    private fun end(t: Long) {
        ends[(count - 1) % HISTORY] = t
        lastHandledEndMs = t
        handledSinceMs = NEVER
        // Put back where it rested before (the holder): it keeps its standing. Anywhere else: learned from scratch.
        if (angle(cx, cy, cz, bx, by, bz) > cfg.resettleDeg) {
            tiltMs2 = 0.0
            stableSinceMs = t
        }
        bx = cx; by = cy; bz = cz
        tiltSince = NEVER; gyroSince = NEVER; screenSince = NEVER
        if (movedDriving && jostle) noteJostle(t)
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
        return t - exitAtMs in 0L..cfg.pocketExitHoldMs
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
        /** Sanity checks, not tuning: the slow low-pass must look like gravity, the fast one must not be near 0 (free fall). */
        private const val MIN_G = 5.0
        private const val MIN_F = 1.0
    }
}
