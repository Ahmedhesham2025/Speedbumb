package app.bumpbeeper

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every tunable number in one place.
 * Units: metres, milliseconds, m/s² (1 g = 9.81), km/h, degrees.
 * The ones marked (setting) are changed from the app screen, see [Prefs.applyTo].
 */
class EngineConfig {
    /** Vertical jolt (gravity removed) that counts as a bump. 3.0 m/s² ≈ 0.3 g. (setting: Sensitivity) */
    @Volatile var joltThreshold = 3.0

    /** Slower than this = parked, door slams, getting in. */
    var minSpeedKmh = 3.0
    /** Faster than this = more likely a pothole or road joint than a speed bump. (setting) */
    @Volatile var maxSpeedKmh = 50.0
    /** Above [maxSpeedKmh], a jolt is still recorded if it is clearly a pothole, up to this speed. */
    var potholeMaxSpeedKmh = 100.0
    /** Pothole score (-1..1) a fast jolt needs to be recorded as a pothole. */
    var fastPotholeMinScore = 0.6

    /** After the first crossing, keep looking for the peak for this long. */
    var peakWindowMs = 500L
    /** Wait this long before deciding, so we can tell if the phone was being picked up. */
    var decideAfterMs = 1200L
    /** Ignore further jolts for this long (rear axle, suspension rebound). */
    var refractoryMs = 2500L
    /** Angle between the fast and slow "down" estimates that means the phone is being moved. */
    var tiltRejectDeg = 25.0

    /** A hit this close to a known bump (same direction) is that bump. */
    var matchRadiusM = 20.0
    /** Max heading difference that still counts as "the same direction of travel". */
    var headingTolDeg = 45.0

    /** Beep this many seconds before you reach the bump... (setting) */
    @Volatile var leadSeconds = 7.0
    /** ...but never closer than this... */
    var minAlertDistM = 40.0
    /** ...or further than this. */
    var maxAlertDistM = 250.0
    /** Don't beep if you are already slower than this: you have clearly seen it. 0 = always beep. (setting) */
    @Volatile var quietBelowKmh = 20.0
    /** Beep for spots that look like potholes. (setting) */
    @Volatile var warnPotholes = true
    /** Only beep for bumps within this sideways distance of your path (ignores parallel service roads). */
    var maxCrossTrackM = 20.0
    /** Start watching a bump when it is this close and ahead of you. */
    var approachRadiusM = 300.0
    /** You "drove over" a bump if you came at least this close to its stored position. */
    var passRadiusM = 25.0
    /** Two beeps closer together than this are merged into one. */
    var minBeepGapMs = 2500L

    /**
     * A pass with no jolt only counts as a miss if you went at least this fast near the bump.
     * Crawling over a bump (because the app warned you!) may be too gentle to feel, and must not mute it.
     */
    var minInformativeKmh = 12.0
    /** After this many passes... */
    var muteAfterPasses = 3
    /** ...stop beeping for spots felt less than this fraction of the time. */
    var muteBelowHitRate = 0.5

    var maxFixAgeMs = 3000L
    var maxAccuracyM = 30.0
}

/**
 * How one jolt looked, used to tell a speed bump from a pothole.
 * [score] runs from -1 (clearly a speed bump) to +1 (clearly a pothole).
 */
class JoltShape(
    val score: Double,
    /** The first big movement was downwards (a wheel dropping into a hole). */
    val firstDown: Boolean,
    /** Side-to-side rocking ÷ front-to-back rocking. NaN if the gyroscope or the car's forward direction is unknown. */
    val rollRatio: Double,
) {
    val usedGyro: Boolean get() = !rollRatio.isNaN()

    fun describe(): String {
        val kind = when {
            score >= Bump.KIND_MARGIN -> "pothole"
            score <= -Bump.KIND_MARGIN -> "bump"
            else -> "unsure"
        }
        val roll = if (usedGyro) String.format(Locale.US, " roll/pitch=%.2f", rollRatio) else " no-gyro"
        return String.format(Locale.US, "looks=%s score=%.2f first=%s%s", kind, score, if (firstDown) "down" else "up", roll)
    }
}

/**
 * The brain of the app. It has no Android code in it, so it can be tested on a laptop with fake drives.
 *
 * Feed it accelerometer samples ([onAccel], ~50 per second), gyroscope samples ([onGyro], optional)
 * and GPS fixes ([onFix], ~1 per second), always from the same thread. It will:
 *  1. find vertical jolts that look like a speed bump or pothole, and tell which of the two it is,
 *  2. put new ones on the map, or add a hit to a spot it already knows,
 *  3. while you drive, watch the known spots ahead of you and beep before you reach one,
 *  4. count every pass (felt or not), so spots that are rarely felt get muted.
 */
class BumpEngine(
    val cfg: EngineConfig,
    private val store: BumpStore,
    private val listener: EngineListener,
    private val wallClock: () -> Long,
    val tripId: Long = 0L,
) {
    val bumps: MutableList<Bump> = store.loadBumps().toMutableList()
    val trip = TripStats()

    /** Latest vertical acceleration with gravity removed, m/s². For the live graph. */
    var lastVertical = 0.0
        private set
    var lastFix: Fix? = null
        private set

    val mutedCount: Int get() = bumps.count { it.isMuted(cfg) }

    /** True once the engine knows which way the car's nose points in phone coordinates (needed to tell roll from pitch). */
    val forwardKnown: Boolean get() = forwardUnit() != null

    // ---------- accelerometer state ----------
    // Two low-pass filtered copies of the raw accelerometer vector:
    //  slow (≈1 s): the direction of gravity in phone coordinates → tells us which way is "up"
    //  fast (≈0.25 s): follows quicker; if it points somewhere else than slow, the phone is being moved
    private var slowX = 0.0
    private var slowY = 0.0
    private var slowZ = 0.0
    private var fastX = 0.0
    private var fastY = 0.0
    private var fastZ = 0.0
    private var accelReady = false
    private var lastAccelMs = 0L
    private var lastUnstableMs = Long.MIN_VALUE / 4
    private var joltStartMs = -1L
    private var joltPeak = 0.0
    private var refractoryUntilMs = Long.MIN_VALUE / 4
    /** Strongest vertical jolt since the last GPS fix (to report how close a "miss" came). */
    private var maxVertSinceFix = 0.0

    // ---------- gyroscope + shape analysis ----------
    private var gyroX = 0.0
    private var gyroY = 0.0
    private var gyroZ = 0.0
    private var gyroSeen = false
    // The last ~2.5 s of samples, so a jolt's shape can be looked at once it is over.
    private val bufT = LongArray(BUF)
    private val bufV = DoubleArray(BUF)
    private val bufGx = DoubleArray(BUF)
    private val bufGy = DoubleArray(BUF)
    private val bufGz = DoubleArray(BUF)
    private var bufHead = 0
    private var bufCount = 0

    // ---------- which way is forward ----------
    // Horizontal part of the accelerometer (≈1 s average) and its correlation with GPS speed changes.
    // Speeding up pushes it forward, braking backward, so the sum points at the car's nose.
    private var horX = 0.0
    private var horY = 0.0
    private var horZ = 0.0
    private var fwdX = 0.0
    private var fwdY = 0.0
    private var fwdZ = 0.0
    private var fwdWeight = 0.0

    // ---------- GPS state ----------
    private val fixes = ArrayDeque<Fix>()
    private var heading = Double.NaN

    /** A known bump we are currently driving towards. */
    private class Approach(val startMs: Long, var minDist: Double) {
        var beeped = false
        var quietLogged = false
        var counted = false
        var hit = false
        var minSpeedNearMps = Double.MAX_VALUE   // slowest speed seen within 30 m of the bump
        var maxJoltNear = 0.0                     // strongest jolt felt within 40 m of the bump
    }

    /** The bump that beeped most recently (for "Mute last beep"), or -1. */
    var lastBeepedId = -1L
        private set

    private val approaches = HashMap<Long, Approach>()
    private val recentMissMs = HashMap<Long, Long>()
    private val lastHitMs = HashMap<Long, Long>()
    private var lastBeepMs = Long.MIN_VALUE / 4

    // =====================================================================
    // 1. Accelerometer → jolt detection
    // =====================================================================

    /** Rotation rate in rad/s, phone axes. Optional: without a gyroscope, bump/pothole is judged from the jolt alone. */
    fun onGyro(tMs: Long, x: Double, y: Double, z: Double) {
        gyroX = x; gyroY = y; gyroZ = z
        gyroSeen = true
    }

    /** [tMs] monotonic milliseconds; x, y, z raw accelerometer in m/s² (gravity included). */
    fun onAccel(tMs: Long, x: Double, y: Double, z: Double) {
        if (!accelReady) {
            slowX = x; slowY = y; slowZ = z
            fastX = x; fastY = y; fastZ = z
            accelReady = true
            lastAccelMs = tMs
            return
        }
        val dt = (tMs - lastAccelMs).coerceIn(1L, 200L) / 1000.0
        lastAccelMs = tMs

        val aSlow = dt / (1.0 + dt)
        val aFast = dt / (0.25 + dt)
        slowX += aSlow * (x - slowX); slowY += aSlow * (y - slowY); slowZ += aSlow * (z - slowZ)
        fastX += aFast * (x - fastX); fastY += aFast * (y - fastY); fastZ += aFast * (z - fastZ)

        val g = sqrt(slowX * slowX + slowY * slowY + slowZ * slowZ)
        val f = sqrt(fastX * fastX + fastY * fastY + fastZ * fastZ)
        if (g < 5.0 || f < 1.0) return   // free fall or garbage

        // Project the raw reading onto "up" and subtract gravity → pure vertical jolt,
        // whatever angle the phone sits at in its holder.
        val along = (x * slowX + y * slowY + z * slowZ) / g
        val v = along - g
        lastVertical = v
        if (abs(v) > maxVertSinceFix) maxVertSinceFix = abs(v)

        // The rest is horizontal (speeding up, braking, cornering). Averaged, it shows which way is forward.
        val hx = x - along * slowX / g
        val hy = y - along * slowY / g
        val hz = z - along * slowZ / g
        horX += aSlow * (hx - horX); horY += aSlow * (hy - horY); horZ += aSlow * (hz - horZ)

        bufT[bufHead] = tMs
        bufV[bufHead] = v
        bufGx[bufHead] = gyroX
        bufGy[bufHead] = gyroY
        bufGz[bufHead] = gyroZ
        bufHead = (bufHead + 1) % BUF
        if (bufCount < BUF) bufCount++

        // Is the phone being moved (picked up, dropped, adjusted)?
        val cosTilt = ((slowX * fastX + slowY * fastY + slowZ * fastZ) / (g * f)).coerceIn(-1.0, 1.0)
        if (Math.toDegrees(acos(cosTilt)) > cfg.tiltRejectDeg) {
            lastUnstableMs = tMs
            // It may sit differently when it is put back: learn "forward" again.
            fwdX = 0.0; fwdY = 0.0; fwdZ = 0.0; fwdWeight = 0.0
        }

        if (joltStartMs >= 0) {
            if (tMs - joltStartMs <= cfg.peakWindowMs) joltPeak = max(joltPeak, abs(v))
            if (tMs - joltStartMs >= cfg.decideAfterMs) {
                val start = joltStartMs
                joltStartMs = -1
                decide(start, joltPeak)
            }
        } else if (abs(v) >= cfg.joltThreshold && tMs >= refractoryUntilMs) {
            joltStartMs = tMs
            joltPeak = abs(v)
            refractoryUntilMs = tMs + cfg.refractoryMs
        }
    }

    /** A big jolt happened at [tMs]. Is it a speed bump or pothole? */
    private fun decide(tMs: Long, peak: Double) {
        val fix = fixes.lastOrNull()
        if (lastUnstableMs >= tMs - 1500) { reject(peak, "phone_moving", fix); return }
        if (fix == null || tMs - fix.timeMs > cfg.maxFixAgeMs) { reject(peak, "no_gps", fix); return }
        if (fix.accuracyM > cfg.maxAccuracyM) { reject(peak, "weak_gps", fix); return }
        val speedKmh = fix.speedMps * 3.6
        if (speedKmh < cfg.minSpeedKmh) { reject(peak, "too_slow", fix); return }
        if (heading.isNaN()) { reject(peak, "no_heading", fix); return }

        val shape = shapeOf(tMs, peak)
        if (speedKmh > cfg.maxSpeedKmh) {
            // Too fast for a speed bump. Keep it only if it is clearly a pothole (needs the gyroscope to be sure).
            val pothole = speedKmh <= cfg.potholeMaxSpeedKmh && shape.usedGyro && shape.score >= cfg.fastPotholeMinScore
            if (!pothole) { reject(peak, "too_fast", fix, shape.describe()); return }
        }

        // GPS comes once a second; move the last fix forward to where we were at the moment of the jolt.
        val dtS = ((tMs - fix.timeMs) / 1000.0).coerceIn(0.0, 2.0)
        val pos = Geo.move(fix.lat, fix.lon, heading, fix.speedMps * dtS)

        // Feature for later tuning: did we brake before it?
        val maxRecent = fixes.filter { tMs - it.timeMs <= 10_000 }.maxOfOrNull { it.speedMps } ?: fix.speedMps
        val slowdownKmh = max(0.0, (maxRecent - fix.speedMps) * 3.6)

        registerHit(tMs, pos[0], pos[1], speedKmh, peak, slowdownKmh, shape)
    }

    private fun reject(peak: Double, reason: String, fix: Fix?, note: String = "") {
        trip.rejected++
        log(
            "rejected", -1, fix?.lat ?: Double.NaN, fix?.lon ?: Double.NaN,
            (fix?.speedMps ?: Double.NaN) * 3.6, peak, Double.NaN, Double.NaN,
            if (note.isEmpty()) reason else "$reason $note",
        )
        listener.onJoltRejected(peak, reason)
    }

    // =====================================================================
    // 1b. Speed bump or pothole?
    // =====================================================================
    //
    // Two clues, both measured on the front-axle hit:
    //  • Which way the car moves first. A speed bump pushes the car UP first; a pothole drops a wheel DOWN first,
    //    then slams it into the far edge.
    //  • How the car rocks (gyroscope). A speed bump spans the lane, so both front wheels rise together and the car
    //    pitches nose-up/nose-down. A pothole usually catches one wheel, so the car rolls side to side.
    // Each hit gives a score from -1 (bump) to +1 (pothole); the map keeps a running average per spot,
    // so one odd reading doesn't change what a spot is.

    private fun shapeOf(start: Long, peak: Double): JoltShape {
        // First big movement: up or down?
        var firstDown = false
        for (i in 0 until bufCount) {
            val k = (bufHead - bufCount + i + BUF) % BUF
            val t = bufT[k]
            if (t < start - 100 || t > start + 600) continue
            // The drop into a hole is softer than the slam out of it, so a third of the peak already counts.
            if (abs(bufV[k]) >= 0.35 * peak) { firstDown = bufV[k] < 0; break }
        }
        val signScore = if (firstDown) 1.0 else -1.0

        val fwd = forwardUnit()
        if (!gyroSeen || fwd == null) return JoltShape(signScore, firstDown, Double.NaN)

        // Roll axis = forward; pitch axis = up × forward (pointing left).
        val g = sqrt(slowX * slowX + slowY * slowY + slowZ * slowZ)
        val ux = slowX / g; val uy = slowY / g; val uz = slowZ / g
        val (fx, fy, fz) = fwd
        val lx = uy * fz - uz * fy
        val ly = uz * fx - ux * fz
        val lz = ux * fy - uy * fx

        var n = 0
        var sr = 0.0; var sr2 = 0.0; var sp = 0.0; var sp2 = 0.0
        for (i in 0 until bufCount) {
            val k = (bufHead - bufCount + i + BUF) % BUF
            val t = bufT[k]
            if (t < start - 100 || t > start + 400) continue
            val r = bufGx[k] * fx + bufGy[k] * fy + bufGz[k] * fz
            val p = bufGx[k] * lx + bufGy[k] * ly + bufGz[k] * lz
            sr += r; sr2 += r * r; sp += p; sp2 += p * p
            n++
        }
        if (n < 5) return JoltShape(signScore, firstDown, Double.NaN)
        // Standard deviation, so steady rotation (a curve) doesn't count.
        val rollRms = sqrt(max(0.0, sr2 / n - (sr / n) * (sr / n)))
        val pitchRms = sqrt(max(0.0, sp2 / n - (sp / n) * (sp / n)))
        val ratio = rollRms / max(pitchRms, 0.03)
        val rollScore = (ln(max(ratio, 1e-3)) / ln(3.0)).coerceIn(-1.0, 1.0)   // 3× more roll → +1, 3× more pitch → -1
        val score = (signScore + 2.0 * rollScore) / 3.0
        return JoltShape(score, firstDown, ratio)
    }

    /** Unit vector of the car's forward direction in phone axes, or null while it is still unknown. */
    private fun forwardUnit(): Triple<Double, Double, Double>? {
        if (fwdWeight < 6.0) return null
        val g = sqrt(slowX * slowX + slowY * slowY + slowZ * slowZ)
        if (g < 5.0) return null
        val ux = slowX / g; val uy = slowY / g; val uz = slowZ / g
        val d = fwdX * ux + fwdY * uy + fwdZ * uz
        val x = fwdX - d * ux; val y = fwdY - d * uy; val z = fwdZ - d * uz
        val n = sqrt(x * x + y * y + z * z)
        // If the samples agree, |sum| ≈ weight. Noise alone gives a much shorter vector.
        if (n < 0.5 * fwdWeight) return null
        return Triple(x / n, y / n, z / n)
    }

    // =====================================================================
    // 2. Jolt → bump map
    // =====================================================================

    private fun registerHit(
        tMs: Long, lat: Double, lon: Double, speedKmh: Double, peak: Double, slowdownKmh: Double, shape: JoltShape,
    ) {
        // Nearest known bump within matchRadius, same direction of travel.
        var best: Bump? = null
        var bestD = Double.MAX_VALUE
        for (b in bumps) {
            if (abs(b.lat - lat) > 0.0005 || abs(b.lon - lon) > 0.0006) continue   // quick ~55 m box
            val d = Geo.distance(lat, lon, b.lat, b.lon)
            if (d <= cfg.matchRadiusM && d < bestD && Geo.angleDiff(heading, b.heading) <= cfg.headingTolDeg) {
                best = b
                bestD = d
            }
        }
        val now = wallClock()

        if (best == null) {
            // First time here → record it. No beep: we are already on top of it.
            val b = Bump(0, lat, lon, heading, hits = 1, passes = 1, misses = 0, nPos = 1, firstSeen = now, lastSeen = now)
            b.addKindVote(shape.score)
            b.id = store.insertBump(b)
            bumps.add(b)
            val a = Approach(tMs, 0.0)
            a.beeped = true; a.counted = true; a.hit = true
            approaches[b.id] = a
            lastHitMs[b.id] = tMs
            trip.newBumps++
            trip.hits++
            log("new_bump", b.id, lat, lon, speedKmh, peak, slowdownKmh, 0.0, "${b.kind.name.lowercase()} ${shape.describe()}")
            listener.onNewBump(b)
            return
        }

        val b: Bump = best
        val a = approaches[b.id]
        val last = lastHitMs[b.id]
        if ((a != null && a.hit) || (a == null && last != null && tMs - last < 15_000)) {
            // Second jolt on the same pass (rear wheels, a pair of bumps close together). Count once.
            log("hit_repeat", b.id, lat, lon, speedKmh, peak, slowdownKmh, bestD, "same pass")
            return
        }
        lastHitMs[b.id] = tMs

        // Refine the position: running average of all readings (the newest one always keeps ≥10% weight).
        val w = 1.0 / (min(b.nPos, 9) + 1)
        b.lat += (lat - b.lat) * w
        b.lon += (lon - b.lon) * w
        b.heading = Geo.blendAngle(b.heading, heading, w)
        b.nPos++
        b.hits++
        b.lastSeen = now
        b.addKindVote(shape.score)

        if (a != null) {
            a.hit = true
            if (!a.counted) { a.counted = true; b.passes++ }
        } else {
            val missAt = recentMissMs.remove(b.id)
            if (missAt != null && tMs - missAt < 20_000) {
                // GPS thought we had already passed it and logged a miss; it was just late. Undo the miss.
                if (b.misses > 0) b.misses--
                if (trip.misses > 0) trip.misses--
            } else {
                b.passes++
            }
        }
        store.updateBump(b)
        trip.hits++
        log(
            "hit", b.id, lat, lon, speedKmh, peak, slowdownKmh, bestD,
            "hits ${b.hits}/${b.passes} now=${b.kind.name.lowercase()} ${shape.describe()}",
        )
        listener.onKnownBumpHit(b)
    }

    // =====================================================================
    // 3. GPS → watch bumps ahead, beep, count passes
    // =====================================================================

    fun onFix(raw: Fix) {
        val prev = fixes.lastOrNull()
        var speed = raw.speedMps
        var brg = raw.bearingDeg
        if (prev != null && raw.timeMs > prev.timeMs) {
            val d = Geo.distance(prev.lat, prev.lon, raw.lat, raw.lon)
            if (speed.isNaN()) speed = d / ((raw.timeMs - prev.timeMs) / 1000.0)
            if (brg.isNaN() && d > 3.0) brg = Geo.bearing(prev.lat, prev.lon, raw.lat, raw.lon)
            if (raw.accuracyM <= cfg.maxAccuracyM && speed > 1.0) trip.distanceM += d
        }
        if (speed.isNaN()) speed = 0.0

        // Learn which way is forward: correlate the horizontal accelerometer with GPS speed changes.
        if (prev != null && raw.accuracyM <= cfg.maxAccuracyM) {
            val dtS = (raw.timeMs - prev.timeMs) / 1000.0
            if (dtS in 0.5..2.0) {
                val aLong = (speed - prev.speedMps) / dtS
                if (abs(aLong) >= 0.6 && max(speed, prev.speedMps) > 2.0) {
                    val decay = 0.995
                    fwdX = fwdX * decay + horX * aLong
                    fwdY = fwdY * decay + horY * aLong
                    fwdZ = fwdZ * decay + horZ * aLong
                    fwdWeight = fwdWeight * decay + aLong * aLong
                }
            }
        }

        val f = Fix(raw.timeMs, raw.lat, raw.lon, speed, brg, raw.accuracyM)
        fixes.addLast(f)
        while (fixes.size > 1 && f.timeMs - fixes.first().timeMs > 12_000) fixes.removeFirst()
        lastFix = f

        // GPS bearing is garbage when crawling; keep the last good one.
        if (!brg.isNaN() && speed >= 1.5) heading = brg
        val joltSinceFix = maxVertSinceFix
        maxVertSinceFix = 0.0
        if (f.accuracyM > cfg.maxAccuracyM || heading.isNaN()) return
        updateApproaches(f, joltSinceFix)
    }

    private fun updateApproaches(f: Fix, joltSinceFix: Double) {
        val moving = f.speedMps >= 1.5
        val alertDist = (f.speedMps * cfg.leadSeconds).coerceIn(cfg.minAlertDistM, cfg.maxAlertDistM)

        for (b in bumps) {
            var a = approaches[b.id]
            if (abs(b.lat - f.lat) > 0.006 || abs(b.lon - f.lon) > 0.007) {   // more than ~600 m away
                if (a != null) approaches.remove(b.id)
                continue
            }
            val d = Geo.distance(f.lat, f.lon, b.lat, b.lon)
            val aheadDiff = Geo.angleDiff(heading, Geo.bearing(f.lat, f.lon, b.lat, b.lon))
            val dirDiff = Geo.angleDiff(heading, b.heading)

            if (a == null) {
                // Start watching: same direction, ahead of us, within range.
                if (!moving || dirDiff > cfg.headingTolDeg || d > cfg.approachRadiusM || d < 8.0 || aheadDiff > 60.0) continue
                a = Approach(f.timeMs, d)
                approaches[b.id] = a
            }
            a.minDist = min(a.minDist, d)
            if (d <= 30.0) a.minSpeedNearMps = min(a.minSpeedNearMps, f.speedMps)
            if (d <= 40.0) a.maxJoltNear = max(a.maxJoltNear, joltSinceFix)

            // Drove over it (came close, now moving away or it is behind us) → count the pass.
            val passed = a.minDist <= cfg.passRadiusM && (d >= a.minDist + 25.0 || (d > 15.0 && aheadDiff > 110.0))
            if (passed) {
                finishPass(b, a, f)
                approaches.remove(b.id)
                continue
            }
            // Turned off before reaching it.
            val gaveUp = d > cfg.approachRadiusM + 150.0 ||
                f.timeMs - a.startMs > 10 * 60_000L ||
                (moving && dirDiff > 70.0 && d > 40.0)
            if (gaveUp) {
                approaches.remove(b.id)
                continue
            }

            // Beep: close enough for our speed, straight ahead, on our side of the road, not muted.
            val crossTrack = d * sin(Math.toRadians(aheadDiff))
            val wanted = !b.isMuted(cfg) && (cfg.warnPotholes || b.kind != BumpKind.POTHOLE)
            if (!a.beeped && moving && dirDiff <= cfg.headingTolDeg && d <= alertDist &&
                aheadDiff <= 45.0 && crossTrack <= cfg.maxCrossTrackM && wanted
            ) {
                val speedKmh = f.speedMps * 3.6
                if (speedKmh < cfg.quietBelowKmh) {
                    // Already slow: you have seen it. Stay quiet, but keep checking in case you speed up again.
                    if (!a.quietLogged) {
                        a.quietLogged = true
                        log("beep_quiet", b.id, f.lat, f.lon, speedKmh, Double.NaN, Double.NaN, d, "slower than ${cfg.quietBelowKmh.toInt()} km/h")
                    }
                    continue
                }
                a.beeped = true
                if (f.timeMs - lastBeepMs >= cfg.minBeepGapMs) {
                    lastBeepMs = f.timeMs
                    lastBeepedId = b.id
                    trip.beeps++
                    log("beep", b.id, f.lat, f.lon, speedKmh, Double.NaN, Double.NaN, d, b.kind.name.lowercase())
                    listener.onBeep(b, d, speedKmh)
                }
            }
        }
    }

    private fun finishPass(b: Bump, a: Approach, f: Fix) {
        val nearNote = String.format(Locale.US, "strongest jolt nearby %.1f m/s²", a.maxJoltNear)
        if (!a.counted && !a.hit && a.minSpeedNearMps * 3.6 < cfg.minInformativeKmh) {
            // Crawled over it without feeling anything: can't tell, so it counts neither way.
            log("pass_slow", b.id, f.lat, f.lon, a.minSpeedNearMps * 3.6, a.maxJoltNear, Double.NaN, a.minDist, "not counted, $nearNote")
            listener.onPassed(b, false)
            return
        }
        if (!a.counted) {
            a.counted = true
            b.passes++
            if (!a.hit) {
                b.misses++
                trip.misses++
                recentMissMs[b.id] = f.timeMs
                log(
                    "miss", b.id, f.lat, f.lon, f.speedMps * 3.6, a.maxJoltNear, Double.NaN, a.minDist,
                    "hits ${b.hits}/${b.passes}, $nearNote",
                )
            }
            store.updateBump(b)
        }
        listener.onPassed(b, a.hit)
    }

    /** "Mute last beep": silence this bump for good (it stays on the map and in the export). */
    fun muteBump(id: Long): Bump? {
        val b = bumps.firstOrNull { it.id == id } ?: return null
        b.userMuted = true
        store.updateBump(b)
        log("user_mute", b.id, b.lat, b.lon, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "")
        return b
    }

    private fun log(
        type: String, bumpId: Long, lat: Double, lon: Double, speedKmh: Double,
        peak: Double, slowdownKmh: Double, distM: Double, note: String,
    ) {
        store.logEvent(
            BumpEvent(wallClock(), tripId, type, bumpId, lat, lon, speedKmh, heading, peak, slowdownKmh, distM, note)
        )
    }

    companion object {
        /** Sample buffer size: ≥ 2.5 s even if the phone delivers 200 samples per second. */
        private const val BUF = 512
    }
}
