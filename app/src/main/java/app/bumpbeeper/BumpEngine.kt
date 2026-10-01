package app.bumpbeeper

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every tunable number in one place.
 * Units: metres, milliseconds, m/s² (1 g = 9.81), km/h, degrees.
 */
class EngineConfig {
    /** Vertical jolt (gravity removed) that counts as a bump. 3.0 m/s² ≈ 0.3 g. Set by the Sensitivity switch. */
    @Volatile var joltThreshold = 3.0

    /** Slower than this = parked, door slams, getting in. */
    var minSpeedKmh = 3.0
    /** Faster than this = more likely a pothole or road joint than a speed bump. */
    var maxSpeedKmh = 50.0

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

    /** Beep this many seconds before you reach the bump... */
    var leadSeconds = 7.0
    /** ...but never closer than this... */
    var minAlertDistM = 40.0
    /** ...or further than this. */
    var maxAlertDistM = 250.0
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
 * The brain of the app. It has no Android code in it, so it can be tested on a laptop with fake drives.
 *
 * Feed it accelerometer samples ([onAccel], ~50 per second) and GPS fixes ([onFix], ~1 per second),
 * always from the same thread. It will:
 *  1. find vertical jolts that look like a speed bump,
 *  2. put new ones on the map, or add a hit to a bump it already knows,
 *  3. while you drive, watch the known bumps ahead of you and beep before you reach one,
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

    // ---------- GPS state ----------
    private val fixes = ArrayDeque<Fix>()
    private var heading = Double.NaN

    /** A known bump we are currently driving towards. */
    private class Approach(val startMs: Long, var minDist: Double) {
        var beeped = false
        var counted = false
        var hit = false
        var minSpeedNearMps = Double.MAX_VALUE   // slowest speed seen within 30 m of the bump
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
        val v = (x * slowX + y * slowY + z * slowZ) / g - g
        lastVertical = v

        // Is the phone being moved (picked up, dropped, adjusted)?
        val cosTilt = ((slowX * fastX + slowY * fastY + slowZ * fastZ) / (g * f)).coerceIn(-1.0, 1.0)
        if (Math.toDegrees(acos(cosTilt)) > cfg.tiltRejectDeg) lastUnstableMs = tMs

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

    /** A big jolt happened at [tMs]. Is it a speed bump? */
    private fun decide(tMs: Long, peak: Double) {
        val fix = fixes.lastOrNull()
        if (lastUnstableMs >= tMs - 1500) { reject(peak, "phone_moving", fix); return }
        if (fix == null || tMs - fix.timeMs > cfg.maxFixAgeMs) { reject(peak, "no_gps", fix); return }
        if (fix.accuracyM > cfg.maxAccuracyM) { reject(peak, "weak_gps", fix); return }
        val speedKmh = fix.speedMps * 3.6
        if (speedKmh < cfg.minSpeedKmh) { reject(peak, "too_slow", fix); return }
        if (speedKmh > cfg.maxSpeedKmh) { reject(peak, "too_fast", fix); return }
        if (heading.isNaN()) { reject(peak, "no_heading", fix); return }

        // GPS comes once a second; move the last fix forward to where we were at the moment of the jolt.
        val dtS = ((tMs - fix.timeMs) / 1000.0).coerceIn(0.0, 2.0)
        val pos = Geo.move(fix.lat, fix.lon, heading, fix.speedMps * dtS)

        // Feature for later tuning: did we brake before it?
        val maxRecent = fixes.filter { tMs - it.timeMs <= 10_000 }.maxOfOrNull { it.speedMps } ?: fix.speedMps
        val slowdownKmh = max(0.0, (maxRecent - fix.speedMps) * 3.6)

        registerHit(tMs, pos[0], pos[1], speedKmh, peak, slowdownKmh)
    }

    private fun reject(peak: Double, reason: String, fix: Fix?) {
        trip.rejected++
        log(
            "rejected", -1, fix?.lat ?: Double.NaN, fix?.lon ?: Double.NaN,
            (fix?.speedMps ?: Double.NaN) * 3.6, peak, Double.NaN, Double.NaN, reason,
        )
        listener.onJoltRejected(peak, reason)
    }

    // =====================================================================
    // 2. Jolt → bump map
    // =====================================================================

    private fun registerHit(tMs: Long, lat: Double, lon: Double, speedKmh: Double, peak: Double, slowdownKmh: Double) {
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
            b.id = store.insertBump(b)
            bumps.add(b)
            val a = Approach(tMs, 0.0)
            a.beeped = true; a.counted = true; a.hit = true
            approaches[b.id] = a
            lastHitMs[b.id] = tMs
            trip.newBumps++
            trip.hits++
            log("new_bump", b.id, lat, lon, speedKmh, peak, slowdownKmh, 0.0, "")
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
        log("hit", b.id, lat, lon, speedKmh, peak, slowdownKmh, bestD, "hits ${b.hits}/${b.passes}")
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

        val f = Fix(raw.timeMs, raw.lat, raw.lon, speed, brg, raw.accuracyM)
        fixes.addLast(f)
        while (fixes.size > 1 && f.timeMs - fixes.first().timeMs > 12_000) fixes.removeFirst()
        lastFix = f

        // GPS bearing is garbage when crawling; keep the last good one.
        if (!brg.isNaN() && speed >= 1.5) heading = brg
        if (f.accuracyM > cfg.maxAccuracyM || heading.isNaN()) return
        updateApproaches(f)
    }

    private fun updateApproaches(f: Fix) {
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
            if (!a.beeped && moving && dirDiff <= cfg.headingTolDeg && d <= alertDist &&
                aheadDiff <= 45.0 && crossTrack <= cfg.maxCrossTrackM && !b.isMuted(cfg)
            ) {
                a.beeped = true
                if (f.timeMs - lastBeepMs >= cfg.minBeepGapMs) {
                    lastBeepMs = f.timeMs
                    lastBeepedId = b.id
                    trip.beeps++
                    log("beep", b.id, f.lat, f.lon, f.speedMps * 3.6, Double.NaN, Double.NaN, d, "")
                    listener.onBeep(b, d, f.speedMps * 3.6)
                }
            }
        }
    }

    private fun finishPass(b: Bump, a: Approach, f: Fix) {
        if (!a.counted && !a.hit && a.minSpeedNearMps * 3.6 < cfg.minInformativeKmh) {
            // Crawled over it without feeling anything: can't tell, so it counts neither way.
            log("pass_slow", b.id, f.lat, f.lon, a.minSpeedNearMps * 3.6, Double.NaN, Double.NaN, a.minDist, "not counted")
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
                log("miss", b.id, f.lat, f.lon, f.speedMps * 3.6, Double.NaN, Double.NaN, a.minDist, "hits ${b.hits}/${b.passes}")
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
}
