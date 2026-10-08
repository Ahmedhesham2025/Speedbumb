package app.bumpbeeper

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Thresholds for the driving score. Units: m/s² (1 g = 9.81), km/h, seconds. */
class DrivingConfig {
    /** Your speed limit; time above it counts as speeding. (setting) The phone has no map, so it can't know the real limit. */
    @Volatile var speedLimitKmh = 90.0
    /** Braking harder than this (≈ 0.35 g) is a harsh brake. Normal firm braking is about 2–3 m/s². */
    var harshBrakeMs2 = 3.5
    /** Speeding up harder than this (≈ 0.3 g) is a harsh acceleration. */
    var harshAccelMs2 = 3.0
    /** Sideways force above this in a curve (≈ 0.4 g) is harsh cornering. */
    var harshCornerMs2 = 4.0
    /** A swerve: a sideways jerk one way, then the other, both above this, within [swerveWindowS]. */
    var swerveMs2 = 2.5
    var swerveWindowS = 2.5
    var swerveMinKmh = 30.0
    /** Driving over a known speed bump faster than this. */
    var bumpFastKmh = 25.0

    /** Where the phone sits: mounted | cupholder | pocket | unknown. (setting: Prefs.placement) */
    @Volatile var placement = "unknown"
    /**
     * Phone use: the phone handled ([PhoneStateDetector.movingMs]: tilted, or held by a sign other than its motion) for
     * at least [phoneUseS] while driving at [PhoneStateConfig.movingKmh] or faster, in any placement (pocket mode only
     * excuses a jostle, see [PhoneStateDetector.jostle]); an unlock (not in a holder) or a hand-held call seen while
     * driving at that moment ([PhoneStateDetector.movingCauses]). Never: a mounted phone with its screen on
     * (navigation), an unlock at a red light, a Bluetooth / wired / speaker call. Once per handling, and at most once
     * per [phoneUseGapS].
     */
    var phoneUseS = 1.5
    var phoneUseGapS = 30.0
    /**
     * No braking, speeding up, cornering or swerving is judged from the sensors while the phone is handled, nor
     * [handledMarginS] after; a push waiting for the GPS is dropped if a handling began less than [handledAfterS] after
     * it. Braking and speeding up are then judged from the GPS speed alone (it is the car's, whatever the phone does).
     */
    var handledMarginS = 2.0
    var handledAfterS = 1.0
    /**
     * Braking and speeding up felt by the phone must show in the GPS speed: a = (v2 - v1) / dt between two fixes,
     * up to [longMatchS] before or after the push, with a_gps * sign(a) >= |a| - (longTolFrac * |a| + longTolMs2).
     * A phone tipping in a pocket or sliding in a holder never changes the GPS speed. (Without the forward axis the
     * check is the GPS itself.)
     */
    var longTolFrac = 0.5
    var longTolMs2 = 0.5
    var longMatchS = 1.5

    /**
     * Cornering and swerving must be something the car did, not the phone turning in a pocket or holder.
     * The sideways force from the gyroscope, a = v * omega_gyro, must be backed by the one from the GPS heading,
     * a_gps = v * (heading change between two fixes / time between them), of a fix up to [lateralMatchBeforeS]
     * before or [lateralMatchAfterS] after it (GPS lags):
     *     a_gps * sign(a) >= |a| - (lateralTolFrac * |a| + lateralTolMs2)
     * i.e. the GPS shows the car turning the same way, with at least the gyroscope's force minus the tolerance.
     * One-sided: 1 Hz GPS smooths a short push, but a phone twisting in a pocket never makes the GPS turn.
     * A swerve needs both its pushes backed. Mounted phones are trusted more (looser numbers: for a 2.5 m/s²
     * swerve push the GPS then only must not turn the other way). Without a gyroscope the force comes from the GPS
     * itself, so there is nothing to check.
     */
    var lateralTolFrac = 0.5
    var lateralTolMs2 = 0.5
    var lateralTolFracMounted = 0.7
    var lateralTolMs2Mounted = 1.0
    var lateralMatchBeforeS = 1.0
    var lateralMatchAfterS = 2.5
}

/** Everything measured on one trip, and the score made from it. */
class DrivingStats {
    var movingS = 0.0
    var distanceM = 0.0
    var maxSpeedKmh = 0.0
    var speedingS = 0.0
    /** Sum of (speed − limit) × seconds while speeding, for the average excess. */
    var speedingExcess = 0.0
    var harshBrakes = 0
    var harshAccels = 0
    var harshCorners = 0
    var swerves = 0
    var bumpsFast = 0
    var phoneUse = 0

    // Diagnostics for replays and tuning (not part of the score).
    /** Cornering / swerves the gyroscope saw but the GPS heading did not back ([DrivingConfig.lateralTolFrac]). */
    var cornersIgnored = 0
    var swervesIgnored = 0
    /** Braking / speeding up the phone felt but the GPS speed did not back ([DrivingConfig.longTolFrac]). */
    var brakesIgnored = 0
    var accelsIgnored = 0
    /** Strongest sideways force from GPS heading changes on the trip, m/s². */
    var maxGpsLateralMs2 = 0.0

    // Speeding against real road limits (set by [withSpeedLimits] after the trip; see [SpeedLimitScoring]).
    /** Share 0..1 of the distance with a known road limit (show with [SpeedLimitScoring.ATTRIBUTION]), -1 = not looked up. */
    var limitKnownShare = -1.0
    /** Seconds driven with a known limit (the base the limit-based penalty is normalised by). */
    var limitKnownS = 0.0
    /** Seconds over the limit by more than +10, +20 and +30 km/h (nested), and the most held for 3 s, km/h. */
    var overLimit10S = 0.0; var overLimit20S = 0.0; var overLimit30S = 0.0
    var maxOverLimitKmh = 0.0

    /** True when the speed part of the score comes from road limits (known for ≥ 50 % of the distance). */
    val usesSpeedLimits: Boolean get() = limitKnownShare >= SpeedLimitScoring.MIN_KNOWN_SHARE

    val speedingShare: Double get() = if (movingS > 0) speedingS / movingS else 0.0
    val avgExcessKmh: Double get() = if (speedingS > 0) speedingExcess / speedingS else 0.0
    val avgSpeedKmh: Double get() = if (movingS > 0) distanceM / movingS * 3.6 else 0.0

    /** Short trips count as 5 km, so one event on a 1 km trip doesn't sink the score. */
    private val per10km: Double get() = 10.0 / max(distanceM / 1000.0, 5.0)

    /** Road limits when known for enough of the trip ([SpeedLimitScoring.penalty]), else the fixed threshold. Both 0..40. */
    private fun speedPenalty() =
        if (usesSpeedLimits) SpeedLimitScoring.penalty(limitKnownS, overLimit10S, overLimit20S, overLimit30S)
        else min(40.0, speedingShare * 60.0 + speedingShare * avgExcessKmh)

    /**
     * A copy of these stats rescored with road speed limits ([SpeedLimitScoring.evaluate] of this trip).
     * If limits are known for less than half the distance, the score keeps the fixed-threshold speed part.
     */
    fun withSpeedLimits(r: SpeedLimitResult): DrivingStats = DrivingStats().also {
        it.copyFrom(this)
        it.limitKnownShare = r.knownShare; it.limitKnownS = r.knownS
        it.overLimit10S = r.over10S; it.overLimit20S = r.over20S; it.overLimit30S = r.over30S
        it.maxOverLimitKmh = r.maxExcessKmh
    }
    private fun brakePenalty() = (harshBrakes * 5.0 + harshAccels * 3.0) * per10km
    private fun steerPenalty() = (harshCorners * 4.0 + swerves * 6.0) * per10km
    private fun bumpPenalty() = bumpsFast * 4.0 * per10km
    private fun phonePenalty() = phoneUse * 6.0 * per10km

    /** 0–100, or -1 if the trip is too short to judge (under 0.5 km). */
    fun score(): Int {
        if (distanceM < 500) return -1
        val s = 100.0 - speedPenalty() - brakePenalty() - steerPenalty() - bumpPenalty() - phonePenalty()
        return s.coerceIn(0.0, 100.0).roundToInt()
    }

    /** Each area on its own scale of 0–100 (100 = nothing to improve). */
    fun breakdown(): List<Pair<String, Int>> {
        fun sub(p: Double, worst: Double) = (100.0 * (1.0 - min(1.0, p / worst))).roundToInt()
        return listOf(
            "Speed" to sub(speedPenalty(), 40.0),
            "Braking & acceleration" to sub(brakePenalty(), 30.0),
            "Steering & swerving" to sub(steerPenalty(), 30.0),
            "Speed bumps" to sub(bumpPenalty(), 20.0),
            "Phone use" to sub(phonePenalty(), 20.0),
        )
    }

    fun copyFrom(o: DrivingStats) {
        movingS = o.movingS; distanceM = o.distanceM; maxSpeedKmh = o.maxSpeedKmh
        speedingS = o.speedingS; speedingExcess = o.speedingExcess
        harshBrakes = o.harshBrakes; harshAccels = o.harshAccels; harshCorners = o.harshCorners
        swerves = o.swerves; bumpsFast = o.bumpsFast; phoneUse = o.phoneUse
        cornersIgnored = o.cornersIgnored; swervesIgnored = o.swervesIgnored; maxGpsLateralMs2 = o.maxGpsLateralMs2
        brakesIgnored = o.brakesIgnored; accelsIgnored = o.accelsIgnored
        limitKnownShare = o.limitKnownShare; limitKnownS = o.limitKnownS
        overLimit10S = o.overLimit10S; overLimit20S = o.overLimit20S; overLimit30S = o.overLimit30S
        maxOverLimitKmh = o.maxOverLimitKmh
    }

    companion object {
        fun grade(score: Int): String = when {
            score < 0 -> "Too short to score"
            score >= 90 -> "Excellent"
            score >= 75 -> "Good"
            score >= 60 -> "Fair"
            else -> "Needs work"
        }
    }
}

/**
 * Watches how you drive, from the same sensors the bump engine uses (pure Kotlin, tested with the simulator).
 *
 *  • Braking / speeding up: the push along the car's forward axis (known once [BumpEngine] has learned it),
 *    or GPS speed changes until then.
 *  • Cornering / swerving: sideways force = speed × turning rate (gyroscope around "up", or GPS heading changes).
 *  • Speeding: time above your speed limit.
 *  • Speed bumps taken too fast, and phone use ([DrivingConfig.phoneUseS]).
 *
 * Cornering and swerving only count when the GPS heading agrees ([DrivingConfig.lateralTolFrac]), braking and speeding
 * up when the GPS speed agrees ([DrivingConfig.longTolFrac]). While the phone is handled ([BumpEngine.phone]) and for
 * [DrivingConfig.handledMarginS] after, nothing is judged from the sensors (the readings are the hand's, not the car's);
 * braking and speeding up then come from the GPS speed alone.
 *
 * Feed it from the engine thread, right after the engine got the same sample.
 */
class DrivingMonitor(
    val cfg: DrivingConfig,
    private val engine: BumpEngine,
    private val log: (type: String, lat: Double, lon: Double, speedKmh: Double, value: Double, note: String) -> Unit,
) {
    val stats = DrivingStats()

    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var gyroSeen = false
    private var lastAccelMs = -1L
    private var yawLp = 0.0          // turning rate, rad/s, ≈0.3 s average (positive = turning left)
    private var longLp = 0.0         // forward push, m/s², ≈0.5 s average (positive = speeding up)
    private var speedMps = 0.0
    private var lastFix: Fix? = null
    private var lastBearing = Double.NaN

    private var cornerSinceMs = -1L
    private var brakeSinceMs = -1L
    private var accelSinceMs = -1L
    private var lastLeftMs = Long.MIN_VALUE / 4      // last strong sideways push to the left
    private var lastRightMs = Long.MIN_VALUE / 4
    private var coolCornerUntil = Long.MIN_VALUE / 4
    private var coolLongUntil = Long.MIN_VALUE / 4
    private var coolSwerveUntil = Long.MIN_VALUE / 4
    private var coolPhoneUntil = Long.MIN_VALUE / 4
    /** The last handling judged for phone use ([PhoneStateDetector.episode]). */
    private var phoneEpisode = 0
    private val phone = engine.phone
    /** Sideways force from GPS heading changes (fix time, m/s², positive = to the left), the last few seconds. */
    private val gpsLateral = ArrayDeque<Pair<Long, Double>>()
    /** Speed change from one fix to the next (fix time, m/s², positive = faster), the last few seconds. */
    private val gpsLong = ArrayDeque<Pair<Long, Double>>()
    /** A push (sideways, or along the car) from the sensors waiting for the GPS to confirm it. */
    private class Push(val tMs: Long, val lat: Double)
    private var pendingCorner: Push? = null
    private var pendingSwerve: Pair<Push, Push>? = null
    /** Braking (negative) or speeding up waiting for the GPS speed to confirm it ([DrivingConfig.longTolFrac]). */
    private var pendingLong: Push? = null
    private var lastLeftLat = 0.0
    private var lastRightLat = 0.0
    /** Judging was paused for a handling. */
    private var gated = false
    private var speedingRunS = 0.0
    private var speedingRunMaxKmh = 0.0
    /** When the sensor-based braking check last ran, and since when it has been running without a break. */
    private var lastFwdMs = Long.MIN_VALUE / 4
    private var fwdSinceMs = -1L
    // For tests and tuning.
    private var fwdSamples = 0
    private var minLong = 0.0
    private var maxLong = 0.0
    val debug: String get() = "fwdSamples=$fwdSamples longLp min=${formatFixed(minLong, 2)} max=${formatFixed(maxLong, 2)} " +
        "cornersIgnored=${stats.cornersIgnored} swervesIgnored=${stats.swervesIgnored} brakesIgnored=${stats.brakesIgnored} " +
        "accelsIgnored=${stats.accelsIgnored} pocketMode=${phone.pocketMode}"

    /** Pocket mode ([PhoneStateDetector.pocketMode]): placement "pocket", or a phone that kept jostling on this trip. */
    val pocketMode: Boolean get() = phone.pocketMode

    init {
        phone.placement = cfg.placement
    }

    fun onGyro(x: Double, y: Double, z: Double) {
        gx = x; gy = y; gz = z
        gyroSeen = true
    }

    fun onAccel(tMs: Long, x: Double, y: Double, z: Double) {
        val dt = if (lastAccelMs < 0) 0.02 else ((tMs - lastAccelMs).coerceIn(1L, 200L) / 1000.0)
        lastAccelMs = tMs
        checkPhoneUse()
        val up = engine.upVector() ?: return
        if (handledNear(tMs)) {   // the readings are the hand's, not the car's
            gated = true
            return
        }
        if (gated) {   // judging again after a handling: nothing measured before it carries over
            gated = false
            yawLp = 0.0
            cornerSinceMs = -1; brakeSinceMs = -1; accelSinceMs = -1
            lastLeftMs = Long.MIN_VALUE / 4; lastRightMs = Long.MIN_VALUE / 4
        }

        // Turning rate around "up" → sideways force = speed × turning rate.
        if (gyroSeen) {
            val yaw = gx * up[0] + gy * up[1] + gz * up[2]
            yawLp += dt / (0.3 + dt) * (yaw - yawLp)
            checkSideways(tMs, speedMps * yawLp)
        }

        // Push along the car's forward axis.
        val fwd = engine.forwardVector()
        if (fwd != null) {
            val along = x * up[0] + y * up[1] + z * up[2]
            val hx = x - along * up[0]; val hy = y - along * up[1]; val hz = z - along * up[2]
            val a = hx * fwd[0] + hy * fwd[1] + hz * fwd[2]
            if (tMs - lastFwdMs > 1000) longLp = a   // (re)starting: don't ramp up from an old value
            longLp += dt / (0.5 + dt) * (a - longLp)
            if (fwdSinceMs < 0 || tMs - lastFwdMs > 1000) fwdSinceMs = tMs
            lastFwdMs = tMs
            fwdSamples++
            minLong = min(minLong, longLp)
            maxLong = max(maxLong, longLp)
            checkLongitudinal(tMs, longLp, sustainMs = 400)
        }
    }

    fun onFix(f: Fix) {
        val prev = lastFix
        lastFix = f
        phone.placement = cfg.placement
        speedMps = f.speedMps.takeIf { !it.isNaN() } ?: 0.0
        val kmh = speedMps * 3.6
        if (prev == null) return
        val dt = (f.timeMs - prev.timeMs) / 1000.0
        if (dt <= 0 || dt > 3.0) return

        if (speedMps >= 2.0) {
            stats.movingS += dt
            stats.distanceM += speedMps * dt
            stats.maxSpeedKmh = max(stats.maxSpeedKmh, kmh)
        }
        // Speeding: time above the limit; one logged episode per stretch of 10 s or more.
        if (kmh > cfg.speedLimitKmh) {
            stats.speedingS += dt
            stats.speedingExcess += (kmh - cfg.speedLimitKmh) * dt
            speedingRunS += dt
            speedingRunMaxKmh = max(speedingRunMaxKmh, kmh)
        } else {
            if (speedingRunS >= 10) event("speeding", speedingRunMaxKmh, "${formatFixed(speedingRunS, 0)} s above ${formatFixed(cfg.speedLimitKmh, 0)} km/h")
            speedingRunS = 0.0
            speedingRunMaxKmh = 0.0
        }

        // Until the sensor-based check has been running for 2 s, judge braking / speeding up from GPS
        // (a bit less exact, so a higher bar). The forward axis is often learned *during* a hard brake;
        // without this hand-over, that brake would be missed by both checks.
        // The same GPS judgement while the phone is handled: the hand moves the sensors, not the GPS speed.
        val handled = handledNear(f.timeMs)
        var aGps = Double.NaN
        var sensorReady = false
        if (f.accuracyM <= 30 && !prev.speedMps.isNaN()) {
            aGps = (speedMps - prev.speedMps) / dt
            gpsLong.addLast(Pair(f.timeMs, aGps))
            sensorReady = fwdSinceMs >= 0 && f.timeMs - lastFwdMs <= 1000 && lastFwdMs - fwdSinceMs >= 2000
        }
        while (gpsLong.isNotEmpty() && gpsLong.first().first < f.timeMs - 10_000) gpsLong.removeFirst()
        // Sideways force from the GPS heading, to confirm (or not) what the gyroscope felt.
        if (!f.bearingDeg.isNaN() && !prev.bearingDeg.isNaN() && speedMps >= 4.0 && !prev.speedMps.isNaN() && prev.speedMps >= 4.0) {
            var d = f.bearingDeg - prev.bearingDeg
            if (d > 180) d -= 360
            if (d < -180) d += 360
            val a = (speedMps + prev.speedMps) / 2 * degToRad(-d) / dt
            gpsLateral.addLast(Pair(f.timeMs, a))
            stats.maxGpsLateralMs2 = max(stats.maxGpsLateralMs2, abs(a))
        }
        while (gpsLateral.isNotEmpty() && gpsLateral.first().first < f.timeMs - 10_000) gpsLateral.removeFirst()
        resolvePending(f.timeMs)
        if (!aGps.isNaN() && (!sensorReady || handled)) {
            checkLongitudinal(f.timeMs, if (aGps > 0) aGps - 0.5 else aGps + 0.5, sustainMs = 0, fromGps = true)
        }

        // Without a gyroscope, turning rate comes from the GPS heading.
        if (!gyroSeen && !f.bearingDeg.isNaN() && speedMps >= 4.0) {
            if (!lastBearing.isNaN() && !handled) {
                var d = f.bearingDeg - lastBearing
                if (d > 180) d -= 360
                if (d < -180) d += 360
                checkSideways(f.timeMs, speedMps * degToRad(-d) / dt, fromGps = true)   // bearing grows clockwise = turning right
            }
            lastBearing = f.bearingDeg
        }
    }

    /**
     * A known spot was hit at [speedKmh]. Confirmed bumps (felt before, [Confidence.FULL]) taken too fast count against
     * the score; a spot felt for the first time could be anything, so it doesn't.
     */
    fun onBumpHit(b: Bump, speedKmh: Double) {
        if (b.confidence(engine.cfg) == Confidence.FULL && speedKmh > cfg.bumpFastKmh) {
            stats.bumpsFast++
            event("bump_fast", speedKmh, "speed bump #${b.id} at ${speedKmh.roundToInt()} km/h")
        }
    }

    fun finish() {
        resolvePending(Long.MAX_VALUE)
        if (speedingRunS >= 10) event("speeding", speedingRunMaxKmh, "${formatFixed(speedingRunS, 0)} s above ${formatFixed(cfg.speedLimitKmh, 0)} km/h")
        speedingRunS = 0.0
    }

    /** [fromGps]: the push comes from the GPS speed itself (no forward axis yet), so there is nothing to cross-check. */
    private fun checkLongitudinal(tMs: Long, a: Double, sustainMs: Long, fromGps: Boolean = false) {
        if (tMs < coolLongUntil) return
        if (a <= -cfg.harshBrakeMs2) {
            if (brakeSinceMs < 0) brakeSinceMs = tMs
            if (tMs - brakeSinceMs >= sustainMs) {
                coolLongUntil = tMs + 3000
                brakeSinceMs = -1
                if (fromGps) countLong(a) else if (pendingLong == null) pendingLong = Push(tMs, a)
            }
        } else brakeSinceMs = -1
        if (a >= cfg.harshAccelMs2) {
            if (accelSinceMs < 0) accelSinceMs = tMs
            if (tMs - accelSinceMs >= sustainMs) {
                coolLongUntil = tMs + 3000
                accelSinceMs = -1
                if (fromGps) countLong(a) else if (pendingLong == null) pendingLong = Push(tMs, a)
            }
        } else accelSinceMs = -1
    }

    private fun countLong(a: Double) {
        if (a < 0) {
            stats.harshBrakes++
            event("harsh_brake", abs(a), "${formatFixed(abs(a), 1)} m/s²")
        } else {
            stats.harshAccels++
            event("harsh_accel", a, "${formatFixed(a, 1)} m/s²")
        }
    }

    /** [fromGps]: the force comes from the GPS heading itself (no gyroscope), so there is nothing to cross-check. */
    private fun checkSideways(tMs: Long, lat: Double, fromGps: Boolean = false) {
        if (speedMps * 3.6 < 15) { cornerSinceMs = -1; return }
        // Swerve: strong sideways push one way, then the other, within a couple of seconds (each push: its peak).
        if (lat >= cfg.swerveMs2) {
            lastLeftLat = if (tMs - lastLeftMs > 500) lat else max(lastLeftLat, lat)
            lastLeftMs = tMs
        }
        if (lat <= -cfg.swerveMs2) {
            lastRightLat = if (tMs - lastRightMs > 500) lat else min(lastRightLat, lat)
            lastRightMs = tMs
        }
        if (speedMps * 3.6 >= cfg.swerveMinKmh && tMs >= coolSwerveUntil &&
            abs(lastLeftMs - lastRightMs) <= (cfg.swerveWindowS * 1000).toLong() && min(lastLeftMs, lastRightMs) > tMs - 5000
        ) {
            coolSwerveUntil = tMs + 4000
            if (fromGps) countSwerve(abs(lat)) else pendingSwerve = Pair(Push(lastLeftMs, lastLeftLat), Push(lastRightMs, lastRightLat))
            lastLeftMs = Long.MIN_VALUE / 4; lastRightMs = Long.MIN_VALUE / 4
            return
        }
        // Harsh cornering: strong sideways force held for half a second.
        if (abs(lat) >= cfg.harshCornerMs2) {
            if (cornerSinceMs < 0) cornerSinceMs = tMs
            if (tMs - cornerSinceMs >= 500 && tMs >= coolCornerUntil) {
                coolCornerUntil = tMs + 4000
                if (fromGps) countCorner(lat) else if (pendingCorner == null) pendingCorner = Push(tMs, lat)
            }
        } else cornerSinceMs = -1
    }

    private fun countSwerve(value: Double) {
        stats.swerves++
        event("swerve", value, "sudden left-right")
    }

    private fun countCorner(lat: Double) {
        stats.harshCorners++
        event("harsh_corner", abs(lat), "${formatFixed(abs(lat), 1)} m/s² sideways")
    }

    /**
     * Count or drop the sensor pushes the GPS has had time to confirm, as of [nowMs]. A push close to a handling of
     * the phone ([handledAround]) is dropped either way: it was the hand.
     */
    private fun resolvePending(nowMs: Long) {
        pendingCorner?.let { p ->
            val ok = agrees(p, nowMs) ?: return@let
            pendingCorner = null
            if (handledAround(p.tMs, p.tMs)) return@let
            if (ok) countCorner(p.lat) else stats.cornersIgnored++
        }
        pendingSwerve?.let { (l, r) ->
            val a = agrees(l, nowMs)
            val b = agrees(r, nowMs)
            if (!(a == true && b == true) && a != false && b != false) return@let
            pendingSwerve = null
            if (handledAround(min(l.tMs, r.tMs), max(l.tMs, r.tMs))) return@let
            if (a == true && b == true) countSwerve(max(abs(l.lat), abs(r.lat))) else stats.swervesIgnored++
        }
        pendingLong?.let { p ->
            val ok = longAgrees(p, nowMs) ?: return@let
            pendingLong = null
            if (handledAround(p.tMs, p.tMs)) {
                coolLongUntil = p.tMs   // the hand's push: the GPS speed alone judges this stop (onFix)
                return@let
            }
            when {
                ok -> countLong(p.lat)
                p.lat < 0 -> stats.brakesIgnored++
                else -> stats.accelsIgnored++
            }
        }
    }

    /** Does the GPS speed back the braking / speeding up [p] ([DrivingConfig.longTolFrac])? Null while it still may. */
    private fun longAgrees(p: Push, nowMs: Long): Boolean? {
        val tol = cfg.longTolFrac * abs(p.lat) + cfg.longTolMs2
        val from = p.tMs - (cfg.longMatchS * 1000).toLong()
        val to = p.tMs + (cfg.longMatchS * 1000).toLong()
        val sign = if (p.lat >= 0) 1.0 else -1.0
        for ((t, a) in gpsLong) if (t in from..to && a * sign >= abs(p.lat) - tol) return true
        return if (nowMs > to) false else null
    }

    /** The phone was handled between [DrivingConfig.handledMarginS] before [fromMs] and [DrivingConfig.handledAfterS] after [toMs]. */
    private fun handledAround(fromMs: Long, toMs: Long) =
        phone.handledDuring(fromMs - (cfg.handledMarginS * 1000).toLong(), toMs + (cfg.handledAfterS * 1000).toLong())

    /** Handled now, or less than [DrivingConfig.handledMarginS] ago. */
    private fun handledNear(tMs: Long) = phone.handledDuring(tMs - (cfg.handledMarginS * 1000).toLong(), tMs)

    /**
     * Does the GPS heading back the gyroscope push [p] ([DrivingConfig.lateralTolFrac])? True or false,
     * or null while a later fix could still confirm it.
     */
    private fun agrees(p: Push, nowMs: Long): Boolean? {
        val mounted = cfg.placement == "mounted"
        val tol = (if (mounted) cfg.lateralTolFracMounted else cfg.lateralTolFrac) * abs(p.lat) +
            (if (mounted) cfg.lateralTolMs2Mounted else cfg.lateralTolMs2)
        val from = p.tMs - (cfg.lateralMatchBeforeS * 1000).toLong()
        val to = p.tMs + (cfg.lateralMatchAfterS * 1000).toLong()
        val sign = if (p.lat >= 0) 1.0 else -1.0
        for ((t, a) in gpsLateral) if (t in from..to && a * sign >= abs(p.lat) - tol) return true
        return if (nowMs > to) false else null
    }

    /**
     * Phone use ([DrivingConfig.phoneUseS]), judged on every sample of a handling: a hand-held call or an unlock (when
     * not in a holder, see [PhoneStateDetector]) seen while driving, or handled long enough while driving. Pocket mode
     * excuses only a jostle. Once per handling, at most once per [DrivingConfig.phoneUseGapS].
     */
    private fun checkPhoneUse() {
        val p = phone
        if (p.episode == 0 || p.episode == phoneEpisode) return
        val note = when {
            (p.movingCauses and PhoneStateDetector.CALL) != 0 -> "hand-held call while driving"
            (p.movingCauses and PhoneStateDetector.UNLOCK) != 0 -> "phone unlocked while driving"
            p.movingMs >= (cfg.phoneUseS * 1000).toLong() && !(p.pocketMode && p.jostle) -> "phone held while driving"
            else -> return
        }
        phoneEpisode = p.episode
        if (p.episodeStartMs < coolPhoneUntil) return
        coolPhoneUntil = p.episodeStartMs + (cfg.phoneUseGapS * 1000).toLong()
        stats.phoneUse++
        event("phone_use", 0.0, note)
    }

    private fun event(type: String, value: Double, note: String) {
        val f = lastFix
        log(type, f?.lat ?: Double.NaN, f?.lon ?: Double.NaN, speedMps * 3.6, value, note)
    }

    @Suppress("unused")
    private fun mag(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
}
