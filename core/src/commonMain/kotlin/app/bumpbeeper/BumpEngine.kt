package app.bumpbeeper

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
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
    /** Faster than this = more likely a road joint or a rough patch than a speed bump. (setting) */
    @Volatile var maxSpeedKmh = 50.0
    /**
     * Above [maxSpeedKmh] and up to this speed, a jolt is still learned if it has a clear shape, up or down first
     * (gyroscope needed): shape |score| ≥ [fastJoltMinScore], peak within [fastJoltMinPeakMs2]..[fastJoltMaxPeakMs2]
     * and the car really rocked (≥ [fastJoltMinRockRads]). A road joint or seam is a short sharp jolt that barely
     * rocks the car. Such a spot is only a candidate like any other: it needs hits on later passes to stay.
     */
    var fastJoltMaxKmh = 60.0
    var fastJoltMinScore = 0.25
    var fastJoltMinPeakMs2 = 4.0
    var fastJoltMaxPeakMs2 = 16.0
    /** Rocking (standard deviation of the roll or pitch rate, whichever is bigger) during the jolt, rad/s. */
    var fastJoltMinRockRads = 0.2

    /** After the first crossing, keep looking for the peak for this long. */
    var peakWindowMs = 500L
    /** Wait this long before deciding, so we can tell if the phone was being picked up. */
    var decideAfterMs = 1200L
    /** Ignore further jolts for this long (rear axle, suspension rebound). */
    var refractoryMs = 2500L
    /**
     * ...unless the jolt was rejected as the phone's: then only while the phone is untrusted, if its handling began no
     * later than this after the jolt (a jostle's own jolt comes slightly first). A jolt well before the handling (a
     * real bump that shook the phone) keeps its refractory time.
     */
    var phoneJoltLeadMs = 250L

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
    /**
     * Severity bands of a spot's [Bump.sevIndex] (for now its average jolt, m/s²): mild below [sevMildMax], strong
     * from [sevStrongMin], moderate in between. A spot only changes band once its index is [sevHysteresis] (a fraction
     * of the edge) past an edge. E2 replaces the index with a vibration dose and refits the edges from real drives.
     */
    var sevMildMax = 3.5
    var sevStrongMin = 5.0
    var sevHysteresis = 0.10
    /** At most one "new spot recorded" tick per this long, so a bumpy stretch doesn't machine-gun. */
    var tickGapMs = 1500L
    /** When a warning fires, known spots up to this far beyond the warned one count as one group... */
    var clusterRangeM = 150.0
    /** ...if there are at least this many of them (besides the warned one). The group is announced once. */
    var clusterMinExtra = 2
    /**
     * Groups are announced by voice, so the app turns this off while text-to-speech isn't working:
     * then every spot warns with its own sound and none is silenced.
     */
    @Volatile var groupWarnings = true
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

    /** Shared map: ask the [SpotSource] again after this much driving... */
    var remoteRefreshMs = 30_000L
    /** ...or after moving this far since the last time. */
    var remoteRefreshMoveM = 300.0
    /**
     * How far around the car to ask for shared-map spots. A spot of your own within [matchRadiusM] (same direction)
     * replaces a shared one: the same radius that decides a jolt belongs to a known spot, so the two can't drift apart.
     */
    var remoteRadiusM = 1500.0
}

/**
 * How one jolt looked: diagnostics for the event log, training samples and research. Nothing decides a spot's type
 * from them (every jolt is a bump); only a fast jolt needs a clear shape to count ([EngineConfig.fastJoltMinScore]).
 * [score] runs from -1 (up first, the car pitching) to +1 (down first, the car rolling).
 */
class JoltShape(
    val score: Double,
    /** The first big movement was downwards (a wheel dropping into a dip). */
    val firstDown: Boolean,
    /** Side-to-side rocking ÷ front-to-back rocking. NaN if the gyroscope or the car's forward direction is unknown. */
    val rollRatio: Double,
    /** How much the car rocked: the bigger of the roll and pitch rate standard deviations, rad/s. NaN without gyroscope. */
    val rock: Double = Double.NaN,
) {
    val usedGyro: Boolean get() = !rollRatio.isNaN()

    fun describe(): String {
        val roll = if (usedGyro) " roll/pitch=" + formatFixed(rollRatio, 2) else " no-gyro"
        val r = if (rock.isNaN()) "" else " rock=" + formatFixed(rock, 2)
        return "score=${formatFixed(score, 2)} first=${if (firstDown) "down" else "up"}$roll$r"
    }
}

/**
 * The brain of the app. It has no Android code in it, so it can be tested on a laptop with fake drives.
 *
 * Feed it accelerometer samples ([onAccel], ~50 per second), gyroscope samples ([onGyro], optional)
 * and GPS fixes ([onFix], ~1 per second), always from the same thread. It will:
 *  1. find vertical jolts that look like a bump, and how hard they hit (its severity),
 *  2. put new ones on the map, or add a hit to a spot it already knows,
 *  3. while you drive, watch the known spots ahead of you and beep before you reach one,
 *  4. count every pass (felt or not), so spots that are rarely felt get muted.
 *
 * Shared map (both optional; null = behaves exactly as without them):
 *  - [spotSource]: confirmed spots from other phones warn like your own (same lead time, direction, quiet and
 *    severity rules), logged as `beep` / `beep_quiet` with a note starting with `remote`; in those rows `bumpId` is the
 *    shared map's server id, not a local spot id. They are never stored and never count passes. A spot of your
 *    own within [EngineConfig.matchRadiusM] and the same direction replaces its shared twin, so it warns once,
 *    and muting your spot silences the shared one too. [muteBump] on a shared spot stores a muted spot of your
 *    own in its place, which silences it for good.
 *    In [EngineListener.onBeep] a shared spot arrives as a stand-in [Bump] with a negative id, see [remoteSpotId].
 *  - [observationSink]: gets a `jolt` for every new spot, a `known_hit` for every hit on a known spot and a
 *    `pass_clear` for every pass that felt nothing at an informative speed (never for crawled-over passes).
 *    No privacy filtering happens here: the app drops the 300 m around trip start and end before upload.
 *  - [sampleSink] ("Help improve detection"): a [JoltSample] with the signal window for every jolt it judged
 *    (learned, hit, rejected), every miss and shared-spot pass_clear, and every mute. The default keeps nothing.
 */
class BumpEngine(
    val cfg: EngineConfig,
    private val store: BumpStore,
    private val listener: EngineListener,
    private val wallClock: () -> Long,
    val tripId: Long = 0L,
    private val spotSource: SpotSource? = null,
    private val observationSink: ObservationSink? = null,
    private val sampleSink: JoltSampleSink = JoltSampleSink.NONE,
) {
    val bumps: MutableList<Bump> = store.loadBumps().toMutableList()
    val trip = TripStats()

    /**
     * Whether the phone rests in a holder, rests loose or is being handled ([PhoneStateDetector]), fed with the same
     * samples as the engine. The app fills in its [PhoneStateDetector.signals] (screen, unlock, calls, proximity, light).
     */
    val phone = PhoneStateDetector()

    /** Latest vertical acceleration with gravity removed, m/s². For the live graph. */
    var lastVertical = 0.0
        private set
    var lastFix: Fix? = null
        private set

    val mutedCount: Int get() = bumps.count { it.isMuted(cfg) }

    /** True once the engine knows which way the car's nose points in phone coordinates (needed to tell roll from pitch). */
    val forwardKnown: Boolean get() = forwardUnit() != null

    /** "Up" in phone axes (unit vector): long-term gravity once settled, the 1 s estimate before that. Null before any data. */
    fun upVector(): DoubleArray? {
        val useGrav = gravSettledS >= 3.0
        val x = if (useGrav) gravX else slowX
        val y = if (useGrav) gravY else slowY
        val z = if (useGrav) gravZ else slowZ
        val g = sqrt(x * x + y * y + z * z)
        return if (!accelReady || g < 5.0) null else doubleArrayOf(x / g, y / g, z / g)
    }

    /** The car's forward direction in phone axes (unit vector), or null while still unknown. */
    fun forwardVector(): DoubleArray? = forwardUnit()?.let { doubleArrayOf(it.first, it.second, it.third) }

    /** For tests and tuning: how far forward-learning has got. */
    val forwardDebug: String
        get() {
            val g = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
            val d = if (g > 0) (fwdX * gravX + fwdY * gravY + fwdZ * gravZ) / g else 0.0
            val n = sqrt(max(0.0, fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ - d * d))
            val h = sqrt(horX * horX + horY * horY + horZ * horZ)
            return "w=${formatFixed(fwdWeight, 1)} n=${formatFixed(n, 1)} hor=${formatFixed(h, 2)} settled=${formatFixed(gravSettledS, 0)}s"
        }

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
    private var joltStartMs = -1L
    private var joltPeak = 0.0
    private var refractoryUntilMs = Long.MIN_VALUE / 4
    /**
     * The last jolt was the phone's ([handledReason]) and its handling had begun by then ([EngineConfig.phoneJoltLeadMs]):
     * it holds the next one off only while the phone is untrusted.
     */
    private var phoneJolt = false
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
    /** Longer signal history for [sampleSink] windows; only kept when someone listens. */
    private val ring: WindowRing? = if (sampleSink === JoltSampleSink.NONE) null else WindowRing()
    /** Spot (own id, or shared stand-in id) → last time it was felt or passed closest, for a user_mute window. */
    private val nearMs = HashMap<Long, Near>()
    /** When a spot was last felt or passed closest, and the speed and jolt then (the event a mute labels). */
    private class Near(val ms: Long, val kmh: Double, val peak: Double)
    /** Reused for the car's forward direction, so the ring buffer allocates nothing per sample. */
    private val fwdTmp = DoubleArray(3)
    /** Spots muted with no window at hand: their next hit or pass also gives the user_mute sample. */
    private val pendingMute = HashSet<Long>()

    // ---------- which way is forward ----------
    // Gravity alone. The 1 s "slow" estimate above tilts along with any speed-up or braking and would hide it,
    // so this one only follows quickly while the car drives at a steady speed (GPS and accelerometer agree),
    // and barely moves otherwise. "Forward" is only learned once it has settled.
    private var gravX = 0.0
    private var gravY = 0.0
    private var gravZ = 0.0
    private var gravSettledS = 0.0
    private var gpsSteadyFixes = 0
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
        var minDistMs = startMs                   // fix time of the closest approach
    }

    /** The bump that beeped most recently (for "Mute last beep"), or -1. */
    var lastBeepedId = -1L
        private set

    // ---------- shared map ----------
    /** Every shared spot near the car (stand-in bumps with negative ids), and the ones not replaced by a spot of your own. */
    private var remoteAll: List<Bump> = emptyList()
    private var remoteActive: List<Bump> = emptyList()
    private var remoteAskedMs = Long.MIN_VALUE / 4
    private var remoteAskedLat = Double.NaN
    private var remoteAskedLon = Double.NaN

    private val approaches = HashMap<Long, Approach>()
    private val recentMissMs = HashMap<Long, Long>()
    private val lastHitMs = HashMap<Long, Long>()
    private var lastBeepMs = Long.MIN_VALUE / 4
    private var lastTickMs = Long.MIN_VALUE / 4
    /** Spots announced as part of a group ("3 bumps ahead"): they stay silent until this time (fix clock). */
    private val grouped = HashMap<Long, Long>()

    // =====================================================================
    // 1. Accelerometer → jolt detection
    // =====================================================================

    /** Rotation rate in rad/s, phone axes. Optional: without it, a jolt's shape is judged from the jolt alone. */
    fun onGyro(tMs: Long, x: Double, y: Double, z: Double) {
        phone.onGyro(tMs, x, y, z)
        gyroX = x; gyroY = y; gyroZ = z
        gyroSeen = true
    }

    /** [tMs] monotonic milliseconds; x, y, z raw accelerometer in m/s² (gravity included). */
    fun onAccel(tMs: Long, x: Double, y: Double, z: Double) {
        phone.onAccel(tMs, x, y, z)
        if (!accelReady) {
            slowX = x; slowY = y; slowZ = z
            fastX = x; fastY = y; fastZ = z
            gravX = x; gravY = y; gravZ = z
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

        // The rest, measured against gravity alone, is horizontal (speeding up, braking, cornering).
        // Averaged and matched with GPS speed changes, it shows which way is forward.
        val horNow = sqrt(horX * horX + horY * horY + horZ * horZ)
        val steady = (gpsSteadyFixes >= 1 && horNow < 0.4) ||   // steady speed and nothing pushing sideways
            (gravSettledS < 3.0 && gpsSteadyFixes >= 3)          // first time: trust a few seconds of steady GPS
        val aGrav = dt / ((if (steady) 3.0 else 60.0) + dt)
        if (steady) gravSettledS += dt
        gravX += aGrav * (x - gravX); gravY += aGrav * (y - gravY); gravZ += aGrav * (z - gravZ)
        val gg = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        if (gg > 5.0) {
            val alongG = (x * gravX + y * gravY + z * gravZ) / gg
            val hx = x - alongG * gravX / gg
            val hy = y - alongG * gravY / gg
            val hz = z - alongG * gravZ / gg
            horX += aSlow * (hx - horX); horY += aSlow * (hy - horY); horZ += aSlow * (hz - horZ)
        }

        bufT[bufHead] = tMs
        bufV[bufHead] = v
        bufGx[bufHead] = gyroX
        bufGy[bufHead] = gyroY
        bufGz[bufHead] = gyroZ
        bufHead = (bufHead + 1) % BUF
        if (bufCount < BUF) bufCount++
        ring?.let { addToRing(it, tMs, v) }

        // Is the phone turning (picked up, dropped, adjusted)? Whether it is handled is [phone]'s call; the angle is its fast turn.
        val cosTilt = ((slowX * fastX + slowY * fastY + slowZ * fastZ) / (g * f)).coerceIn(-1.0, 1.0)
        if (radToDeg(acos(cosTilt)) > phone.cfg.turnDeg) {
            // It may sit differently when it is put back: learn gravity and "forward" again.
            gravX = slowX; gravY = slowY; gravZ = slowZ
            gravSettledS = 0.0
            horX = 0.0; horY = 0.0; horZ = 0.0
            fwdX = 0.0; fwdY = 0.0; fwdZ = 0.0; fwdWeight = 0.0
        }

        if (joltStartMs >= 0) {
            if (tMs - joltStartMs <= cfg.peakWindowMs) joltPeak = max(joltPeak, abs(v))
            if (tMs - joltStartMs >= cfg.decideAfterMs) {
                val start = joltStartMs
                joltStartMs = -1
                decide(start, joltPeak)
            }
        } else if (abs(v) >= cfg.joltThreshold && (tMs >= refractoryUntilMs || (phoneJolt && !phone.untrusted(tMs, tMs)))) {
            // A rejected jostle has no rear axle to wait for: a bump right after it (braking before it) still counts.
            joltStartMs = tMs
            joltPeak = abs(v)
            refractoryUntilMs = tMs + cfg.refractoryMs
            phoneJolt = false
        }
    }

    /** A big jolt happened at [tMs]. Is it a bump? */
    private fun decide(tMs: Long, peak: Double) {
        // The decision comes 1.2 s after the jolt, so a newer fix may already be in. Use the one closest in time.
        val fix = fixes.minByOrNull { abs(it.timeMs - tMs) }
        handledReason(tMs)?.let {
            phoneJolt = phone.episodeStartMs <= tMs + cfg.phoneJoltLeadMs
            reject(tMs, peak, it, fix)
            return
        }
        if (fix == null || abs(tMs - fix.timeMs) > cfg.maxFixAgeMs) { reject(tMs, peak, "no_gps", fix); return }
        if (fix.accuracyM > cfg.maxAccuracyM) { reject(tMs, peak, "weak_gps", fix); return }
        val speedKmh = fix.speedMps * 3.6
        if (speedKmh < cfg.minSpeedKmh) { reject(tMs, peak, "too_slow", fix); return }
        if (heading.isNaN()) { reject(tMs, peak, "no_heading", fix); return }

        val shape = shapeOf(tMs, peak)
        // Too fast for a speed bump: kept only if, not much faster, it has a clear shape (needs the gyroscope).
        if (speedKmh > cfg.maxSpeedKmh && !clearFastJolt(speedKmh, peak, shape)) {
            reject(tMs, peak, "too_fast", fix, shape.describe(), shape); return
        }

        // GPS comes once a second; move that fix forward (or back, if it came after) to the moment of the jolt.
        val dtS = ((tMs - fix.timeMs) / 1000.0).coerceIn(-2.0, 2.0)
        val pos = Geo.move(fix.lat, fix.lon, heading, fix.speedMps * dtS)

        // Feature for later tuning: did we brake before it?
        val maxRecent = fixes.filter { tMs - it.timeMs <= 10_000 }.maxOfOrNull { it.speedMps } ?: fix.speedMps
        val slowdownKmh = max(0.0, (maxRecent - fix.speedMps) * 3.6)

        registerHit(tMs, pos[0], pos[1], speedKmh, peak, slowdownKmh, shape)
    }

    /**
     * Why a jolt at [tMs] is the phone's and not the road's, or null: its readings were not trusted from the jolt until
     * now ([PhoneStateDetector.untrusted]: handled, or within 2 s after a handling; a jostle ends 0.5 s after its motion,
     * with no margin). The decision waits [EngineConfig.decideAfterMs], so a pick-up that starts with the jolt is seen.
     * A jostle in pocket mode is "phone_moving", anything else "handled". No new spot, no hit, in someone's hand.
     */
    private fun handledReason(tMs: Long): String? {
        if (!phone.untrusted(tMs, lastAccelMs)) return null
        return if (phone.pocketMode && phone.onlyJostles(tMs, lastAccelMs)) "phone_moving" else "handled"
    }

    /**
     * A jolt above [EngineConfig.maxSpeedKmh] that still counts ([EngineConfig.fastJoltMaxKmh]). Without the gyroscope
     * the score is only the sign of the first movement (always ±1), which a road joint has too, so it is never "clear".
     */
    private fun clearFastJolt(speedKmh: Double, peak: Double, shape: JoltShape): Boolean =
        speedKmh <= cfg.fastJoltMaxKmh && shape.usedGyro && abs(shape.score) >= cfg.fastJoltMinScore &&
            peak in cfg.fastJoltMinPeakMs2..cfg.fastJoltMaxPeakMs2 && shape.rock >= cfg.fastJoltMinRockRads

    private fun reject(tMs: Long, peak: Double, reason: String, fix: Fix?, note: String = "", shape: JoltShape? = null) {
        trip.rejected++
        sample("rejected", reason, tMs, (fix?.speedMps ?: 0.0) * 3.6, peak, shape, null, fix?.lat ?: Double.NaN, fix?.lon ?: Double.NaN, null)
        log(
            "rejected", -1, fix?.lat ?: Double.NaN, fix?.lon ?: Double.NaN,
            (fix?.speedMps ?: Double.NaN) * 3.6, peak, Double.NaN, Double.NaN,
            if (note.isEmpty()) reason else "$reason $note",
        )
        listener.onJoltRejected(peak, reason)
    }

    // =====================================================================
    // 1b. How the jolt looked (diagnostics)
    // =====================================================================
    //
    // Two clues, both measured on the front-axle hit, kept for the event log, training samples and research:
    //  • Which way the car moves first: up (a hump) or down (a wheel dropping into a dip, then hitting its far edge).
    //  • How the car rocks (gyroscope): pitching nose-up/nose-down (both front wheels together) or rolling side to side
    //    (one wheel). The score runs from -1 (up first, pitching) to +1 (down first, rolling).
    // Every jolt is a bump whatever its shape; only a fast one needs a clear shape to count ([clearFastJolt]).

    private fun shapeOf(start: Long, peak: Double): JoltShape {
        // First big movement: up or down?
        var firstDown = false
        for (i in 0 until bufCount) {
            val k = (bufHead - bufCount + i + BUF) % BUF
            val t = bufT[k]
            if (t < start - 100 || t > start + 600) continue
            // The drop into a dip is softer than the hit on its far edge, so a third of the peak already counts.
            if (abs(bufV[k]) >= 0.35 * peak) { firstDown = bufV[k] < 0; break }
        }
        val signScore = if (firstDown) 1.0 else -1.0

        val fwd = forwardUnit()
        if (!gyroSeen || fwd == null) return JoltShape(signScore, firstDown, Double.NaN)

        // Roll axis = forward; pitch axis = up × forward (pointing left).
        val g = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        val ux = gravX / g; val uy = gravY / g; val uz = gravZ / g
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
        return JoltShape(score, firstDown, ratio, max(rollRms, pitchRms))
    }

    /** Unit vector of the car's forward direction in phone axes, or null while it is still unknown. */
    private fun forwardUnit(): Triple<Double, Double, Double>? {
        val o = DoubleArray(3)
        return if (forwardInto(o)) Triple(o[0], o[1], o[2]) else null
    }

    /** [forwardUnit] without allocating: writes it into [out] and returns true, or false while still unknown. */
    private fun forwardInto(out: DoubleArray): Boolean {
        if (fwdWeight < 6.0) return false
        val g = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        if (g < 5.0) return false
        val ux = gravX / g; val uy = gravY / g; val uz = gravZ / g
        val d = fwdX * ux + fwdY * uy + fwdZ * uz
        val x = fwdX - d * ux; val y = fwdY - d * uy; val z = fwdZ - d * uz
        val n = sqrt(x * x + y * y + z * z)
        // Weight = sum of |push| × |speed change|. If every push pointed the same way, |sum| ≈ weight;
        // pushes in mixed directions (cornering, bumps) give a much shorter vector.
        if (n < 0.6 * fwdWeight) return false
        out[0] = x / n; out[1] = y / n; out[2] = z / n
        return true
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
        // A shared spot we are driving towards was felt: its pass must not report "pass_clear".
        var sharedFelt = false
        for (r in remoteActive) {
            val ra = approaches[r.id] ?: continue
            if (Geo.distance(lat, lon, r.lat, r.lon) <= cfg.matchRadiusM && Geo.angleDiff(heading, r.heading) <= cfg.headingTolDeg) {
                ra.hit = true
                sharedFelt = true
            }
        }

        if (best == null) {
            // First time here → record it. No beep: we are already on top of it.
            val b = Bump(0, lat, lon, heading, hits = 1, passes = 1, misses = 0, nPos = 1, firstSeen = now, lastSeen = now)
            b.addPeak(peak, 0)
            b.addSeverity(peak, 0, cfg)   // E2: the vibration index instead of the peak
            b.id = store.insertBump(b)
            countSpot(b)
            bumps.add(b)
            val a = Approach(tMs, 0.0)
            a.beeped = true; a.counted = true; a.hit = true
            approaches[b.id] = a
            lastHitMs[b.id] = tMs
            trip.newBumps++
            trip.hits++
            log("new_bump", b.id, lat, lon, speedKmh, peak, slowdownKmh, 0.0, "bump ${bandNote(b)} ${shape.describe()}")
            observe("jolt", lat, lon, speedKmh, peak, shape.score, now)
            sample("learned", null, tMs, speedKmh, peak, shape, null, lat, lon, b.id)
            if (remoteAll.isNotEmpty()) dedupRemote()   // the new spot of your own replaces its shared twin from now on
            listener.onNewBump(b)
            // No tick when the shared map already knew it: confirming a known spot isn't news.
            if (!sharedFelt && tMs - lastTickMs >= cfg.tickGapMs) {
                lastTickMs = tMs
                listener.onNewSpotTick(b)
            }
            return
        }

        val b: Bump = best
        val a = approaches[b.id]
        val last = lastHitMs[b.id]
        if ((a != null && a.hit) || (a == null && last != null && tMs - last < 15_000)) {
            // Second jolt on the same pass (rear wheels, a pair of bumps close together). Count once.
            log("hit_repeat", b.id, lat, lon, speedKmh, peak, slowdownKmh, bestD, "same pass")
            sample("hit", "same_pass", tMs, speedKmh, peak, shape, null, lat, lon, b.id)
            return
        }
        lastHitMs[b.id] = tMs

        // Refine the position: running average of all readings (the newest one always keeps ≥10% weight).
        val w = 1.0 / (min(b.nPos, 9) + 1)
        b.lat += (lat - b.lat) * w
        b.lon += (lon - b.lon) * w
        b.heading = Geo.blendAngle(b.heading, heading, w)
        b.nPos++
        b.addPeak(peak, b.hits)
        b.addSeverity(peak, b.hits, cfg)
        b.hits++
        b.legacy = false   // felt again: an old pothole spot is an ordinary bump from now on
        b.lastSeen = now
        countSpot(b)

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
            "hits ${b.hits}/${b.passes} now=bump ${bandNote(b)} ${shape.describe()}",
        )
        observe("known_hit", lat, lon, speedKmh, peak, shape.score, now)
        sample("hit", null, tMs, speedKmh, peak, shape, null, lat, lon, b.id)
        listener.onKnownBumpHit(b)
    }

    /** For the event log: the spot's band and confidence after this hit ("sev=moderate conf=soft"). */
    private fun bandNote(b: Bump) = "sev=${b.severity(cfg).label} conf=${b.confidence(cfg).label}"

    /** Every spot felt is counted once per pass, by its band and confidence after the hit. */
    private fun countSpot(b: Bump) {
        when (b.severity(cfg)) {
            Severity.MILD -> trip.mild++
            Severity.MODERATE -> trip.moderate++
            Severity.STRONG -> trip.strong++
        }
        if (b.confidence(cfg) == Confidence.FULL) trip.full++ else trip.soft++
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
                if (abs(aLong) < 0.5) gpsSteadyFixes++ else gpsSteadyFixes = 0   // 0.5: GPS speed itself wobbles a little
                // Only real speed changes: GPS says so AND the phone feels a push. GPS speed wobble at a steady
                // speed must not count, or it slowly drowns out what was learned.
                val horMag = sqrt(horX * horX + horY * horY + horZ * horZ)
                if (gravSettledS >= 3.0 && abs(aLong) >= 0.6 && horMag >= 0.3 && max(speed, prev.speedMps) > 2.0) {
                    val decay = 0.995
                    fwdX = fwdX * decay + horX * aLong
                    fwdY = fwdY * decay + horY * aLong
                    fwdZ = fwdZ * decay + horZ * aLong
                    fwdWeight = fwdWeight * decay + horMag * abs(aLong)
                }
            }
        }

        val f = Fix(raw.timeMs, raw.lat, raw.lon, speed, brg, raw.accuracyM)
        fixes.addLast(f)
        while (fixes.size > 1 && f.timeMs - fixes.first().timeMs > 12_000) fixes.removeFirst()
        lastFix = f
        phone.onFix(f)

        // GPS bearing is garbage when crawling; keep the last good one.
        if (!brg.isNaN() && speed >= 1.5) heading = brg
        val joltSinceFix = maxVertSinceFix
        maxVertSinceFix = 0.0
        if (spotSource != null && f.accuracyM <= cfg.maxAccuracyM) maybeAskRemote(f)
        if (f.accuracyM > cfg.maxAccuracyM || heading.isNaN()) return
        updateApproaches(f, joltSinceFix)
    }

    // =====================================================================
    // 3b. Shared map: spots from other phones
    // =====================================================================

    /** Ask the [SpotSource] at the first good fix, then every [EngineConfig.remoteRefreshMs] of driving or after moving far. */
    private fun maybeAskRemote(f: Fix) {
        val first = remoteAskedLat.isNaN()
        val due = f.speedMps >= 1.5 && f.timeMs - remoteAskedMs >= cfg.remoteRefreshMs
        if (!first && !due && Geo.distance(f.lat, f.lon, remoteAskedLat, remoteAskedLon) < cfg.remoteRefreshMoveM) return
        remoteAskedMs = f.timeMs
        remoteAskedLat = f.lat
        remoteAskedLon = f.lon
        val spots = try {
            spotSource!!.spotsNear(f.lat, f.lon, cfg.remoteRadiusM)
        } catch (e: Exception) {
            return   // a broken cache must not stop the engine; keep what we had
        }
        remoteAll = spots.filter { it.id in 0 until Long.MAX_VALUE / 2 }.map { r ->
            // Felt once per phone that confirmed it: two phones make it FULL. A shared pothole is an old (soft) spot.
            @Suppress("DEPRECATION") val old = r.kind == BumpKind.POTHOLE
            Bump(
                -2L - r.id, r.lat, r.lon, r.heading, hits = r.nDevices.coerceAtLeast(0), passes = 0, misses = 0, nPos = 0,
                firstSeen = 0, lastSeen = 0, peakAvg = r.severity, sevIndex = r.severity, legacy = old,
            )
        }
        dedupRemote()
    }

    /** Drop shared spots that have a spot of your own within [EngineConfig.matchRadiusM] going the same way. */
    private fun dedupRemote() {
        remoteActive = remoteAll.filter { r ->
            bumps.none { b ->
                abs(b.lat - r.lat) <= 0.0005 && abs(b.lon - r.lon) <= 0.0006 &&   // quick ~55 m box
                    Geo.distance(b.lat, b.lon, r.lat, r.lon) <= cfg.matchRadiusM &&
                    Geo.angleDiff(b.heading, r.heading) <= cfg.headingTolDeg
            }
        }
        val keep = remoteActive.mapTo(HashSet()) { it.id }
        approaches.keys.removeAll { it <= -2L && it !in keep }
        grouped.keys.removeAll { it <= -2L && it !in keep }
    }

    private fun observe(
        kind: String, lat: Double, lon: Double, speedKmh: Double, peak: Double, shapeScore: Double, now: Long,
        dir: Double = heading,
    ) {
        val sink = observationSink ?: return
        try {
            // The shape score goes up as a diagnostic in the old kind_score field; bumps have no side (0).
            sink.record(Observation(randomUuid(), kind, lat, lon, dir, speedKmh, peak, shapeScore, 0.0, now))
        } catch (e: Exception) {
            // A full or broken outbox must not stop detection.
        }
    }

    // ---------- "Help improve detection" samples (only with a [sampleSink]) ----------

    /** Roll and pitch rate in the car's frame need the gyroscope and the forward direction; NaN until both are known. */
    private fun addToRing(r: WindowRing, tMs: Long, v: Double) {
        if (!gyroSeen || !forwardInto(fwdTmp)) { r.add(tMs, v, Double.NaN, Double.NaN); return }
        val g = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        val ux = gravX / g; val uy = gravY / g; val uz = gravZ / g
        val fx = fwdTmp[0]; val fy = fwdTmp[1]; val fz = fwdTmp[2]
        val roll = gyroX * fx + gyroY * fy + gyroZ * fz
        val pitch = gyroX * (uy * fz - uz * fy) + gyroY * (uz * fx - ux * fz) + gyroZ * (ux * fy - uy * fx)
        r.add(tMs, v, roll, pitch)
    }

    /**
     * Hands one judged candidate to the [sampleSink] with its window around [centerMs]. [spotKey] is the spot's id in
     * this engine (own, or shared stand-in), [dir] the direction used to find a confirmed shared twin. A jolt that was
     * looked at ([shape]) is classified by its own band; a pass or mute by the spot's [band].
     */
    private fun sample(
        decision: String, reason: String?, centerMs: Long, speedKmh: Double, peak: Double, shape: JoltShape?,
        band: Severity?, lat: Double, lon: Double, spotKey: Long?, dir: Double = heading,
    ) {
        val r = ring ?: return
        if (spotKey != null) nearMs[spotKey] = Near(centerMs, speedKmh, peak)
        // Jolts are cut at the decision (1.2 s after the trigger); passes later, so they get the full 2 s after.
        val w = r.window(centerMs) ?: return
        val fix = fixes.minByOrNull { abs(it.timeMs - centerMs) }
        // E2: the jolt's vibration index instead of its peak.
        val cls = (if (shape != null) Severity.of(peak, null, cfg) else band)?.label
        val shared = spotKey?.let { remoteSpotId(it) } ?: if (lat.isNaN()) null else sharedIdAt(lat, lon, dir)
        val s = JoltSample(
            decision, reason, cls, if (speedKmh.isNaN()) 0.0 else speedKmh, headingChange(centerMs), fix?.accuracyM ?: Double.NaN,
            peak, shape?.score ?: Double.NaN, shape?.firstDown, shape?.rollRatio ?: Double.NaN, Double.NaN,
            shared, lat, lon, wallClock(), w,
        )
        emit(s)
        if (spotKey != null && pendingMute.remove(spotKey)) {
            emit(JoltSample("user_mute", null, s.classification, s.speedKmh, s.headingChangeDeg, s.gpsAccuracyM, s.peak,
                s.shapeScore, s.firstDown, s.rollPitchRatio, s.sideScore, s.sharedSpotId, lat, lon, s.wallTimeMs, w))
        }
    }

    /** User mute of [b] (found as [key] in this engine): sample now if it was felt or passed this trip, else at its next pass. */
    private fun muteSample(b: Bump, key: Long, newKey: Long) {
        val r = ring ?: return
        val at = nearMs[key]
        if (at == null || r.window(at.ms) == null) { pendingMute.add(newKey); return }
        // Speed and jolt of the event the mute labels (the hit or the pass), not of the moment the button was pressed.
        sample("user_mute", null, at.ms, at.kmh, at.peak, null, b.severity(cfg), b.lat, b.lon, null, b.heading)
        nearMs[newKey] = at
    }

    /** Slowest speed near the spot on this pass (the fix speed if it never came within 30 m). */
    private fun passKmh(a: Approach, f: Fix) = (if (a.minSpeedNearMps == Double.MAX_VALUE) f.speedMps else a.minSpeedNearMps) * 3.6

    private fun emit(s: JoltSample) {
        try {
            sampleSink.onSample(s)
        } catch (e: Exception) {
            // A broken training outbox must not stop detection.
        }
    }

    /** The confirmed shared spot at this place and direction, if any (its server id). */
    private fun sharedIdAt(lat: Double, lon: Double, dir: Double): Long? = remoteAll.firstOrNull {
        abs(it.lat - lat) <= 0.0005 && abs(it.lon - lon) <= 0.0006 &&
            Geo.distance(lat, lon, it.lat, it.lon) <= cfg.matchRadiusM &&
            (dir.isNaN() || Geo.angleDiff(dir, it.heading) <= cfg.headingTolDeg)
    }?.let { remoteSpotId(it.id) }

    /** Signed heading change from ~4 s before [tMs] to the fix nearest it, degrees; NaN when unknown. */
    private fun headingChange(tMs: Long): Double {
        val withDir = fixes.filter { !it.bearingDeg.isNaN() && it.speedMps >= 1.5 }
        val now = withDir.minByOrNull { abs(it.timeMs - tMs) } ?: return Double.NaN
        val before = withDir.filter { it.timeMs <= now.timeMs - 3000 }.maxByOrNull { it.timeMs } ?: return Double.NaN
        return ((now.bearingDeg - before.bearingDeg) % 360.0 + 540.0) % 360.0 - 180.0
    }

    private fun updateApproaches(f: Fix, joltSinceFix: Double) {
        val moving = f.speedMps >= 1.5
        val alertDist = (f.speedMps * cfg.leadSeconds).coerceIn(cfg.minAlertDistM, cfg.maxAlertDistM)
        for (b in bumps) watch(b, false, f, joltSinceFix, moving, alertDist)
        for (b in remoteActive) watch(b, true, f, joltSinceFix, moving, alertDist)
    }

    /** One spot (your own, or a shared one if [remote]) on this fix: start or stop watching it, count the pass, beep. */
    private fun watch(b: Bump, remote: Boolean, f: Fix, joltSinceFix: Double, moving: Boolean, alertDist: Double) {
        var a = approaches[b.id]
        if (abs(b.lat - f.lat) > 0.006 || abs(b.lon - f.lon) > 0.007) {   // more than ~600 m away
            if (a != null) approaches.remove(b.id)
            grouped.remove(b.id)
            return
        }
        val d = Geo.distance(f.lat, f.lon, b.lat, b.lon)
        val aheadDiff = Geo.angleDiff(heading, Geo.bearing(f.lat, f.lon, b.lat, b.lon))
        val dirDiff = Geo.angleDiff(heading, b.heading)
        // Turned round (or onto another road): a group announced earlier no longer covers this spot.
        if (moving && dirDiff > 70.0) grouped.remove(b.id)

        if (a == null) {
            // Start watching: same direction, ahead of us, within range.
            if (!moving || dirDiff > cfg.headingTolDeg || d > cfg.approachRadiusM || d < 8.0 || aheadDiff > 60.0) return
            a = Approach(f.timeMs, d)
            approaches[b.id] = a
        }
        if (d < a.minDist) { a.minDist = d; a.minDistMs = f.timeMs }
        if (d <= 30.0) a.minSpeedNearMps = min(a.minSpeedNearMps, f.speedMps)
        if (d <= 40.0) a.maxJoltNear = max(a.maxJoltNear, joltSinceFix)

        // Drove over it (came close, now moving away or it is behind us) → count the pass.
        val passed = a.minDist <= cfg.passRadiusM && (d >= a.minDist + 25.0 || (d > 15.0 && aheadDiff > 110.0))
        if (passed) {
            if (remote) finishRemotePass(b, a, f) else finishPass(b, a, f)
            approaches.remove(b.id)
            grouped.remove(b.id)
            return
        }
        // Turned off before reaching it.
        val gaveUp = d > cfg.approachRadiusM + 150.0 ||
            f.timeMs - a.startMs > 10 * 60_000L ||
            (moving && dirDiff > 70.0 && d > 40.0)
        if (gaveUp) {
            approaches.remove(b.id)
            grouped.remove(b.id)
            return
        }

        // Beep: close enough for our speed, straight ahead, on our side of the road, not muted.
        val crossTrack = d * sin(degToRad(aheadDiff))
        if (!a.beeped && moving && dirDiff <= cfg.headingTolDeg && d <= alertDist &&
            aheadDiff <= 45.0 && crossTrack <= cfg.maxCrossTrackM && warnable(b)
        ) {
            val speedKmh = f.speedMps * 3.6
            val logId = if (remote) remoteSpotId(b.id)!! else b.id   // shared spots are logged with their server id
            if (speedKmh < cfg.quietBelowKmh) {
                // Already slow: you have seen it. Stay quiet, but keep checking in case you speed up again.
                if (!a.quietLogged) {
                    a.quietLogged = true
                    val why = "slower than ${cfg.quietBelowKmh.toInt()} km/h"
                    log("beep_quiet", logId, f.lat, f.lon, speedKmh, Double.NaN, Double.NaN, d, if (remote) "remote, $why" else why)
                }
                return
            }
            a.beeped = true
            val groupedUntil = grouped[b.id]
            if (groupedUntil != null && f.timeMs <= groupedUntil) {
                // Already announced with the group in front of it ("3 bumps ahead"): stay silent.
                log("beep_grouped", logId, f.lat, f.lon, speedKmh, Double.NaN, Double.NaN, d, if (remote) "remote" else "")
                return
            }
            if (f.timeMs - lastBeepMs >= cfg.minBeepGapMs) {
                lastBeepMs = f.timeMs
                // Only the spot that actually warned: "Mute last warning" after a group mutes the first spot,
                // never the silenced ones behind it.
                lastBeepedId = b.id
                trip.beeps++
                val sound = soundFor(b)
                val cluster = clusterAhead(b, d, f)
                val note = (if (remote) "remote " else "") + sound.name.lowercase() + (if (cluster != null) " group of ${cluster.count}" else "")
                log("beep", logId, f.lat, f.lon, speedKmh, Double.NaN, Double.NaN, d, note)
                listener.onWarning(Warning(b, d, speedKmh, sound, cluster))
            }
        }
    }

    /**
     * The group line could not be spoken (text-to-speech failed): let the spots that were silenced for it warn
     * on their own. Call on the engine thread, e.g. from [EngineListener.onWarning].
     */
    fun ungroup() = grouped.clear()

    /** Should this spot warn at all? Not when muted. */
    private fun warnable(b: Bump): Boolean = !b.isMuted(cfg)

    /** A "maybe" spot gets one soft beep whatever its severity; a confirmed one the sound of its band. */
    private fun soundFor(b: Bump): WarnSound =
        if (b.confidence(cfg) == Confidence.SOFT) WarnSound.SOFT
        else when (b.severity(cfg)) {
            Severity.MILD -> WarnSound.MILD
            Severity.MODERATE -> WarnSound.MODERATE
            Severity.STRONG -> WarnSound.STRONG
        }

    /**
     * When [first] warns at [firstDist] m: the other warnable spots (your own and shared) that lie ahead in the same
     * direction of travel, not yet warned for, no further than [EngineConfig.clusterRangeM] beyond it. With enough of
     * them they form a group: announced once now, and each stays silent when its own turn comes.
     */
    private fun clusterAhead(first: Bump, firstDist: Double, f: Fix): HazardCluster? {
        if (!cfg.groupWarnings) return null
        val members = ArrayList<Pair<Bump, Double>>()
        val maxDist = firstDist + cfg.clusterRangeM
        for (s in bumps.asSequence() + remoteActive.asSequence()) {
            if (s.id == first.id) continue
            if (abs(s.lat - f.lat) > 0.006 || abs(s.lon - f.lon) > 0.007) continue   // more than ~600 m away
            val ds = Geo.distance(f.lat, f.lon, s.lat, s.lon)
            if (ds > maxDist || ds < 8.0) continue
            if (approaches[s.id]?.beeped == true || !warnable(s)) continue
            if (Geo.angleDiff(heading, s.heading) > cfg.headingTolDeg) continue
            // "Further along the same road" is measured from the warned spot along its own direction of travel
            // (averaged over its hits), not along the car's GPS heading: a few degrees of GPS wobble would
            // otherwise throw a spot 200 m ahead off the path.
            val gap = Geo.distance(first.lat, first.lon, s.lat, s.lon)
            val off = degToRad(Geo.angleDiff(first.heading, Geo.bearing(first.lat, first.lon, s.lat, s.lon)))
            val along = gap * cos(off)
            if (along < -cfg.matchRadiusM || along > cfg.clusterRangeM || gap * sin(off) > cfg.maxCrossTrackM) continue
            members.add(s to ds)
        }
        if (members.size < cfg.clusterMinExtra) return null

        // Silent until about when you get there (twice the time at today's speed, plus a margin); cleared
        // earlier if you turn round or drive away from it.
        for ((s, ds) in members) grouped[s.id] = f.timeMs + 2 * (ds / max(f.speedMps, 3.0) * 1000).toLong() + 30_000L
        val all = listOf(first) + members.map { it.first }
        return HazardCluster(all.size, all.maxOf { it.severity(cfg) }, all.any { it.confidence(cfg) == Confidence.FULL })
    }

    private fun finishPass(b: Bump, a: Approach, f: Fix) {
        if (ring != null && !a.hit) nearMs[b.id] = Near(a.minDistMs, passKmh(a, f), a.maxJoltNear)
        val nearNote = "strongest jolt nearby ${formatFixed(a.maxJoltNear, 1)} m/s²"
        val near = phone.cfg.marginMs
        if (!a.counted && !a.hit && phone.untrusted(a.minDistMs - near, a.minDistMs + near)) {
            // The phone was in someone's hand over the spot: its jolts weren't trusted, so the pass says nothing.
            log("pass_handled", b.id, f.lat, f.lon, passKmh(a, f), a.maxJoltNear, Double.NaN, a.minDist, "not counted, phone handled")
            listener.onPassed(b, false)
            return
        }
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
                observe("pass_clear", b.lat, b.lon, a.minSpeedNearMps * 3.6, a.maxJoltNear, 0.0, wallClock(), b.heading)
                sample("miss", null, a.minDistMs, passKmh(a, f), a.maxJoltNear, null, b.severity(cfg), b.lat, b.lon, b.id, b.heading)
            }
            store.updateBump(b)
        }
        listener.onPassed(b, a.hit)
    }

    /**
     * Passed a shared spot. Nothing is counted locally, but a clean pass at an informative speed tells the
     * shared map the spot may be gone.
     */
    private fun finishRemotePass(b: Bump, a: Approach, f: Fix) {
        if (ring != null) nearMs[b.id] = Near(a.minDistMs, passKmh(a, f), a.maxJoltNear)
        if (a.hit || a.minSpeedNearMps * 3.6 < cfg.minInformativeKmh) return
        observe("pass_clear", b.lat, b.lon, a.minSpeedNearMps * 3.6, a.maxJoltNear, 0.0, wallClock(), b.heading)
        sample("pass_clear", null, a.minDistMs, passKmh(a, f), a.maxJoltNear, null, b.severity(cfg), b.lat, b.lon, b.id, b.heading)
    }

    /**
     * "Mute last beep": silence this bump for good (it stays on the map and in the export).
     * For a shared spot (negative stand-in id) a muted spot of your own is stored at its place, so it stays
     * silent on every later trip, whatever the shared-map cache says.
     */
    fun muteBump(id: Long): Bump? {
        if (remoteSpotId(id) != null) return muteRemote(id)
        val b = bumps.firstOrNull { it.id == id } ?: return null
        b.userMuted = true
        store.updateBump(b)
        log("user_mute", b.id, b.lat, b.lon, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "")
        muteSample(b, b.id, b.id)
        return b
    }

    private fun muteRemote(id: Long): Bump? {
        val r = remoteAll.firstOrNull { it.id == id } ?: return null
        val now = wallClock()
        val b = Bump(
            0, r.lat, r.lon, r.heading, hits = 0, passes = 0, misses = 0, nPos = 1, firstSeen = now, lastSeen = now,
            userMuted = true, peakAvg = r.peakAvg, sevIndex = r.sevIndex, legacy = r.legacy,
        )
        b.id = store.insertBump(b)
        bumps.add(b)
        dedupRemote()   // your muted spot now replaces the shared one
        log("user_mute", b.id, b.lat, b.lon, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "remote ${remoteSpotId(id)}")
        muteSample(b, id, b.id)
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
        /**
         * The shared-map server id behind a stand-in [Bump] handed to [EngineListener.onBeep], or null for a spot
         * of your own. Shared spots are not in [bumps]; [muteBump] with the stand-in id stores a muted spot of
         * your own in their place. Server ids must be below Long.MAX_VALUE / 2 (larger ones are ignored).
         */
        fun remoteSpotId(bumpId: Long): Long? = if (bumpId <= -2L) -2L - bumpId else null

        /** Sample buffer size: ≥ 2.5 s even if the phone delivers 200 samples per second. */
        private const val BUF = 512
    }
}
