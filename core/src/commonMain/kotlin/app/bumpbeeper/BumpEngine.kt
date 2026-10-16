package app.bumpbeeper

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    /**
     * The Drive card's next spots ([BumpEngine.spotsAhead]): spots in the same direction within [aheadRangeM], straight
     * ahead within [aheadNearDeg] of the heading up to [approachRadiusM] away and within [aheadFarDeg] beyond it.
     */
    var aheadRangeM = 1000.0
    var aheadNearDeg = 60.0
    var aheadFarDeg = 25.0
    /**
     * Approach beeps ([EngineListener.onApproachBeep]): after a confirmed spot's warning, while the driver is still at
     * [approachFastKmh] or faster (the "bump taken fast" speed, [DrivingConfig.bumpFastKmh]), short beeps from
     * [approachDelayMs] after the warning (its sound or voice first) until the spot is [approachEndM] away. The gap
     * between beeps shrinks from [approachStartMs] at [approachFarM] or further to [approachEndMs] at [approachNearM]
     * or closer, along (distance fraction)^[approachCurve]. At most one sequence per spot and pass; once it stops (slower
     * than [approachFastKmh], reached, muted, turned away) it does not start again. Never for a "maybe" spot.
     * The speed is judged on every sensor sample: the last fix's GPS speed, which is [approachGpsLagS] late, less the
     * braking the accelerometer felt since then (along the learned forward axis; before it is learned, any horizontal
     * push above [approachBrakeDeadMs2] while the GPS speed isn't rising), or less the GPS deceleration carried on
     * over the lag, whichever is lower.
     * Every warning holds them for [approachHoldMs]; one with a voice (a strong bump, a group line) until the app
     * says it was spoken ([warningSpoken]), at most [approachVoiceMaxMs], then [approachAfterVoiceMs] more.
     */
    @Volatile var approachBeeps = true
    var approachFastKmh = 25.0
    var approachDelayMs = 2500L
    var approachStartMs = 1200L
    var approachEndMs = 250L
    var approachFarM = 100.0
    var approachNearM = 10.0
    var approachCurve = 1.0
    var approachEndM = 5.0
    var approachGpsLagS = 1.0
    var approachBrakeDeadMs2 = 0.5
    var approachHoldMs = 2000L
    var approachVoiceMaxMs = 6000L
    var approachAfterVoiceMs = 400L

    /** Gap before the next approach beep with the spot [distanceM] away, ms. */
    fun approachIntervalMs(distanceM: Double): Long {
        val frac = ((distanceM - approachNearM) / (approachFarM - approachNearM)).coerceIn(0.0, 1.0)
        return approachEndMs + ((approachStartMs - approachEndMs) * frac.pow(approachCurve)).toLong()
    }

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

    /**
     * E2: the car's own signature. Every jolt is checked for both axles ([AxleSignature], with [axle]) and for a
     * slowdown: the speed lost from the top GPS fix in the [speedBeforeMs] before the jolt to the lowest fix through it
     * (from 1 s before up to the decision), or the braking the accelerometer felt along the learned forward axis over
     * the same time, whichever is more; [speedEvidenceKmh] or more is a slowdown. The decision waits for the rear
     * axle: [AxleSignature.decideWindowMs] at the lag-corrected speed, at least [decideAfterMs].
     *  - Confirmed on its own (counts in [Bump.axleHits]): both axles felt, or a jolt of [strongJoltMs2] or more with
     *    an axle score of at least [axleEvidenceMin] and a slowdown.
     *  - Some evidence (an axle score of at least [axleEvidenceMin], or a slowdown): an ordinary hit.
     *  - Neither, where the axle check could look ([AXLE_BLIND] it could not): rejected in a pocket ("no_evidence"); otherwise a new spot is only a maybe, and on a known spot the
     *    jolt counts the pass as felt but not as a hit ("hit_weak"), so it never confirms a spot.
     *  - "No axle" ([AxleVerdict.ONE]) is rejected in a pocket ("no_axle"); elsewhere it is no axle evidence.
     * A jolt while the phone is handled stays the phone's unless both axles show so clearly that it can only be the
     * car: a pair scoring [overrideMinScore] or more, Δt of at least [overrideMinDtMs], pitch order +1 with the phone
     * itself turning less than [overrideMaxRotRads] over the pair, braking before it, a handling that began after the
     * front impulse and, once the car has [overrideMinProfileHits] axle hits, Δt × speed within
     * [overrideWheelbaseTolM] of the learned wheelbase. Never without a gyroscope, never after an unlock, the screen,
     * a call or out of a pocket.
     */
    val axle = AxleConfig()
    var speedBeforeMs = 5000L
    var speedEvidenceKmh = 5.0
    var axleEvidenceMin = 0.3
    var strongJoltMs2 = 5.0
    var overrideMinScore = 0.9
    var overrideMinDtMs = 250.0
    var overrideMaxRotRads = 0.6
    var overrideMinProfileHits = 3
    var overrideWheelbaseTolM = 0.3

    /**
     * E4, Egyptian raised humps. **Polarity**: a raised hump lifts the car first, the nose up as the front wheels climb;
     * a pothole, drain, dip or manhole drops it first, nose down. Only with a trusted car frame: the phone resting in a
     * holder ([PhoneState.STABLE_MOUNTED]) at the trigger and not handled at the decision, the forward direction known
     * (a loose or pocketed phone turns on its own). The vertical's first big movement ([JoltShape.firstDown]) and the
     * pitch turned in the [polarityPitchMs] after the trigger, less its average rate over the [polarityBaselineMs]
     * before (braking pitches the car too), at least [polarityMinPitchRad] (else the axle pair's pitch order), give UP
     * (evidence, like a slowdown), DOWN (never a bump: rejected "down_first") or, down first with the pitch unknown,
     * DOWN_WEAK (at most a maybe). Anything else is UNCLEAR, which is neutral; so is a down-first jolt below
     * [vMaxMinKmh] without a nose-down pitch (a rounded hump crawled over can show its crest first).
     * **Crossing profile** (GPS speed, [axle]'s lag taken off the fix times): SLOWDOWN when the slowest speed within
     * [vNearMs] of the jolt is [vMaxMinKmh] or less and at least [vMinDropFraction] below the top speed [vBeforeFromMs]
     * to [vBeforeToMs] before it, which was at least [vMinApproachKmh] or that much faster by [vMinDropKmh] (not a
     * start from standstill over a ramp or kerb); V when the speed then rises [vRiseKmh] above that minimum within [vAfterMs]. A V with
     * UP or both axles confirms the spot ([Bump.axleHits] at least 2: FULL).
     */
    var polarityPitchMs = 200L
    var polarityBaselineMs = 300L
    var vMinApproachKmh = 25.0
    var vMinDropKmh = 10.0
    var polarityMinPitchRad = 0.004
    var vBeforeFromMs = 8000L
    var vBeforeToMs = 5000L
    var vNearMs = 3000L
    var vMaxMinKmh = 15.0
    var vMinDropFraction = 0.4
    var vAfterMs = 8000L
    var vRiseKmh = 8.0

    /**
     * E4b, crawling over a hump: below [crawlKmh] (the lag-corrected low speed, [AxleSignature.lowSpeedMps]) the rear
     * axle comes after [AxleConfig.decideMaxMs]; the decision then waits for it, Δt at that speed (learned wheelbase)
     * + [AxleConfig.dtTolerance] + [AxleConfig.decideMarginMs], at most [crawlDecideMaxMs]. The refractory time is at
     * least that wait, so the rear axle never triggers a jolt of its own. Warnings don't wait for decisions.
     */
    var crawlKmh = 10.0
    var crawlDecideMaxMs = 3000L

    /**
     * E4c, a hump's shape, from the front wheels' impulse alone, before the rear ones arrive: only with a clear axle
     * pair ([AxleVerdict.BOTH]; in a pocket a score of at least [shapePocketMinScore]), whose Δt also gives the speed
     * (wheelbase / Δt, no GPS lag). In the band-passed vertical ([shapeLowHz]..[shapeHighHz]) from the front impulse
     * to just before the rear one, the strong samples (at least [shapeStrongFraction] of the peak) form lobes. A short
     * hump climbs, crests and comes down: three lobes or more. A long flat top only climbs onto it (up, then a balanced
     * stop, the two within [shapeBalance]) and then stays quiet for [shapeQuietM] or more before the rear axle: a
     * LONG_FLAT top the car stands on (3 beeps and the voice, like a strong bump). Height, relative only: the first
     * lobe's velocity step × the front impulse's length / (pi/2 × speed); under [shapeLowM] LOW. Else SHORT_STEEP;
     * anything else UNKNOWN (null), which keeps the band's sound.
     */
    var shapeLowHz = 0.5
    var shapeHighHz = 15.0
    var shapeStrongFraction = 0.35
    var shapeQuietM = 1.2
    var shapeBalance = 0.2
    var shapeTyreM = 0.2
    var shapeLowM = 0.04
    var shapePocketMinScore = 0.8

    /**
     * E4c, humps in a row: at [rowMaxKmh] or slower the refractory time ends with the decision, so the next hump of a
     * row is felt; a jolt on the same pass [rowMinGapM] or more from the spot just felt is a hump of its own, unless it
     * is a wheelbase away (± [AxleConfig.dtTolerance]: the rear axle). Spots within [rowMaxGapM] of each other ahead
     * warn as one group ("2 bumps ahead"), even when fewer than [clusterMinExtra] follow.
     */
    var rowMaxKmh = 20.0
    var rowMinGapM = 3.0
    var rowMaxGapM = 25.0
}

/** How a jolt started, in the car's frame (E4, [EngineConfig.polarityPitchMs]). */
enum class Polarity { UP, DOWN, DOWN_WEAK, UNCLEAR }

/** The speed around a jolt (E4, [EngineConfig.vMaxMinKmh]): slowed to a crawl and sped up again (V), or only slowed. */
enum class CrossProfile { V, SLOWDOWN, NONE }

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
    /** The car's wheelbase, learned from both-axle hits ([VehicleProfile.learn]); the app keeps it between trips. */
    val vehicle: VehicleProfile = VehicleProfile(),
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
    /** How long after the trigger the jolt is decided: long enough for its rear axle ([axleWaitMs]). */
    private var joltWaitMs = 0L
    /** The car's left axis when the jolt triggered (a pick-up before the decision makes the frame be learned again). */
    private var joltLeft: DoubleArray? = null
    /** The car frame could be trusted when the jolt triggered (E4 polarity): the phone resting in a holder. */
    private var joltFrameTrusted = false
    /** Spots felt with a slowdown and UP or both axles, waiting for the speed to rise again (a V, E4). */
    private class PendingV(val bumpId: Long, val tMs: Long, val minKmh: Double)
    private val pendingV = ArrayList<PendingV>()
    /** The spot the last [registerHit] learned or hit, null for a repeat or a weak hit. */
    private var lastSpot: Bump? = null
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
        // Approach beeps: the spot, from the last fix (distance, speed, time); next beep due; the sequence ended.
        var seqSpot: Bump? = null
        var seqDist = 0.0
        var seqMps = 0.0
        var seqFixMs = 0L
        var seqDecel = 0.0   // m/s², from the last two fixes (> 0 braking)
        var seqBrakeAtLag = 0.0   // [brakeInt] when the last fix's speed was true (fix time − GPS lag)
        var seqRising = false     // the GPS speed rose at the last fix
        var nextBeepMs = 0L
        var seqDone = false
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
            noteBraking(tMs, dt, hx, hy, hz, aFast)
        }
        approachBeep(tMs)

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
            if (tMs - joltStartMs >= joltWaitMs) {
                val start = joltStartMs
                joltStartMs = -1
                decide(start, joltPeak)
            }
        } else if (abs(v) >= cfg.joltThreshold && (tMs >= refractoryUntilMs || (phoneJolt && !phone.untrusted(tMs, tMs)))) {
            // A rejected jostle has no rear axle to wait for: a bump right after it (braking before it) still counts.
            joltStartMs = tMs
            joltPeak = abs(v)
            joltWaitMs = axleWaitMs(tMs)
            joltLeft = leftUnit()
            joltFrameTrusted = joltLeft != null && !phone.pocketMode && phone.state == PhoneState.STABLE_MOUNTED
            val crawl = (lastFix?.speedMps ?: Double.MAX_VALUE) * 3.6 <= cfg.rowMaxKmh
            refractoryUntilMs = tMs + if (crawl) joltWaitMs else max(cfg.refractoryMs, joltWaitMs)
            phoneJolt = false
        }
    }

    /** A big jolt happened at [tMs]. Is it a bump? */
    private fun decide(tMs: Long, peak: Double) {
        // The decision comes 1.2 s after the jolt, so a newer fix may already be in. Use the one closest in time.
        val fix = fixes.minByOrNull { abs(it.timeMs - tMs) }
        val ax = if (fix != null && abs(tMs - fix.timeMs) <= cfg.maxFixAgeMs) axleOf(tMs, fix.speedMps) else null
        val axNote = ax?.let { " axle=${it.verdict.name.lowercase()} ${formatFixed(it.score, 2)} ${it.reason} dt=${formatFixed(it.dtMs, 0)} pitch=${it.pitchOrder}" } ?: ""
        val overridden = handledReason(tMs)?.let {
            val refused = refusedOverride(tMs, ax, fix)
            if (refused != null) {
                phoneJolt = phone.episodeStartMs <= tMs + cfg.phoneJoltLeadMs
                val why = if (ax?.verdict == AxleVerdict.BOTH) " kept=$refused" else ""
                reject(tMs, peak, it, fix, (axNote + why).trim())
                return
            }
            true
        } ?: false
        if (fix == null || abs(tMs - fix.timeMs) > cfg.maxFixAgeMs) { reject(tMs, peak, "no_gps", fix); return }
        if (fix.accuracyM > cfg.maxAccuracyM) { reject(tMs, peak, "weak_gps", fix); return }
        val speedKmh = fix.speedMps * 3.6
        if (speedKmh < cfg.minSpeedKmh) { reject(tMs, peak, "too_slow", fix); return }
        if (heading.isNaN()) { reject(tMs, peak, "no_heading", fix); return }

        val shape = shapeOf(tMs, peak)
        // Too fast for a speed bump: kept only if, not much faster, it has a clear shape (needs the gyroscope).
        if (speedKmh > cfg.maxSpeedKmh && !clearFastJolt(speedKmh, peak, shape)) {
            reject(tMs, peak, "too_fast", fix, shape.describe() + axNote, shape); return
        }

        // GPS comes once a second; move that fix forward (or back, if it came after) to the moment of the jolt.
        val dtS = ((tMs - fix.timeMs) / 1000.0).coerceIn(-2.0, 2.0)
        val pos = Geo.move(fix.lat, fix.lon, heading, fix.speedMps * dtS)

        // Feature for later tuning: did we brake before it?
        val maxRecent = fixes.filter { tMs - it.timeMs <= 10_000 }.maxOfOrNull { it.speedMps } ?: fix.speedMps
        val slowdownKmh = max(0.0, (maxRecent - fix.speedMps) * 3.6)

        // E2: the car's own signature (EngineConfig.axle): both axles, and a slowdown.
        val drop = speedDropKmh(tMs)
        val slowed = drop >= cfg.speedEvidenceKmh
        val axleSeen = ax != null && ax.score >= cfg.axleEvidenceMin
        // The axle check could not look (rear past the decision at a crawl, too fast, no data, the hit still ringing
        // where the rear would be): that is no evidence
        // against it, so the jolt is an ordinary hit (a steady crawl has no slowdown either).
        val blind = ax == null || ax.reason in AXLE_BLIND
        val pol = polarityOf(tMs, shape, ax, fix.speedMps * 3.6)
        val (profile, minKmh) = crossProfile(tMs)
        val note = "$axNote slow=${formatFixed(drop, 1)} polarity=${pol.name.lowercase()} profile=${profile.name.lowercase()}" +
            if (overridden) " over_handled" else ""
        if (phone.pocketMode && ax?.verdict == AxleVerdict.ONE) { reject(tMs, peak, "no_axle", fix, note.trim(), shape); return }
        if (pol == Polarity.DOWN) { reject(tMs, peak, "down_first", fix, note.trim(), shape); return }
        val up = pol == Polarity.UP
        if (phone.pocketMode && !axleSeen && !slowed && !blind && !up) { reject(tMs, peak, "no_evidence", fix, note.trim(), shape); return }
        val grade = when {
            pol == Polarity.DOWN_WEAK -> 0   // down first: at most a maybe, never a confirmation
            ax?.verdict == AxleVerdict.BOTH || (peak >= cfg.strongJoltMs2 && axleSeen && slowed) -> 2
            axleSeen || slowed || blind || up -> 1
            else -> 0
        }
        if (ax?.verdict == AxleVerdict.BOTH) vehicle.learn(ax.dtMs, fix.speedMps)
        // For the shared map: the hit's vibration dose (SeverityIndex) and how clearly both axles showed.
        val sev = SeverityIndex.of(bufTimes(), bufVerticals(), tMs, fix.speedMps).index
        val hump = ax?.let { bumpShape(tMs, it) }
        val hitInfo = HitInfo(sev.takeIf { !it.isNaN() }, ax?.score?.takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0), hump?.first)
        val humpNote = " hump=" + (hump?.let { it.first.name.lowercase() + it.second } ?: "unknown")
        registerHit(tMs, pos[0], pos[1], speedKmh, peak, slowdownKmh, shape, grade, note + humpNote, hitInfo)
        val spot = lastSpot
        if (spot != null && profile == CrossProfile.SLOWDOWN && (up || ax?.verdict == AxleVerdict.BOTH)) {
            pendingV.add(PendingV(spot.id, tMs, minKmh))
        }
    }

    /** [EngineConfig.polarityPitchMs]: UP, DOWN, DOWN_WEAK or UNCLEAR; UNCLEAR whenever the car frame isn't trusted. */
    private fun polarityOf(tMs: Long, shape: JoltShape, ax: AxleResult?, speedKmh: Double): Polarity {
        val left = joltLeft
        if (!joltFrameTrusted || left == null || phone.state == PhoneState.HANDLED) return Polarity.UNCLEAR
        fun noseUp(k: Int) = -(bufGx[k] * left[0] + bufGy[k] * left[1] + bufGz[k] * left[2])
        // The rate before the jolt (braking, a curve's slope, gyro bias) is not the jolt's.
        var sum = 0.0
        var m = 0
        for (i in 0 until bufCount) {
            val k = (bufHead - bufCount + i + BUF) % BUF
            if (bufT[k] >= tMs - 50 - cfg.polarityBaselineMs && bufT[k] < tMs - 50) { sum += noseUp(k); m++ }
        }
        val base = if (m >= 3) sum / m else 0.0
        // Already climbing nose-up before a down-first trigger: the crest of a rounded hump, not a drop.
        val climbing = base * cfg.polarityPitchMs / 1000.0 >= cfg.polarityMinPitchRad
        var turn = 0.0
        var prevT = -1L
        for (i in 0 until bufCount) {
            val k = (bufHead - bufCount + i + BUF) % BUF
            val t = bufT[k]
            if (t < tMs - 50 || t > tMs + cfg.polarityPitchMs) continue
            if (prevT >= 0) turn += (noseUp(k) - base) * (t - prevT) / 1000.0
            prevT = t
        }
        val pitch = when {
            turn >= cfg.polarityMinPitchRad -> 1
            turn <= -cfg.polarityMinPitchRad -> -1
            else -> ax?.pitchOrder ?: 0
        }
        return when {
            !shape.firstDown && pitch > 0 -> Polarity.UP
            shape.firstDown && climbing -> Polarity.UNCLEAR
            shape.firstDown && pitch < 0 -> Polarity.DOWN
            shape.firstDown && speedKmh < cfg.vMaxMinKmh -> Polarity.UNCLEAR   // a rounded hump's crest can come first
            shape.firstDown && pitch == 0 -> Polarity.DOWN_WEAK
            else -> Polarity.UNCLEAR
        }
    }

    /** The speed profile so far around the jolt at [tMs] (V needs the rise, [checkV]) and its slowest speed, km/h. */
    private fun crossProfile(tMs: Long): Pair<CrossProfile, Double> {
        val lag = cfg.axle.gpsLagMs.toLong()
        val before = fixes.filter { it.timeMs - lag in tMs - cfg.vBeforeFromMs..tMs - cfg.vBeforeToMs }.maxOfOrNull { it.speedMps }
        val min = fixes.filter { abs(it.timeMs - lag - tMs) <= cfg.vNearMs }.minOfOrNull { it.speedMps }
        if (before == null || min == null) return CrossProfile.NONE to Double.NaN
        val approached = before * 3.6 >= cfg.vMinApproachKmh || (before - min) * 3.6 >= cfg.vMinDropKmh
        val slowed = approached && min * 3.6 <= cfg.vMaxMinKmh && (before - min) >= cfg.vMinDropFraction * before
        return (if (slowed) CrossProfile.SLOWDOWN else CrossProfile.NONE) to min * 3.6
    }

    /** On each fix: a spot whose crossing sped up again ([EngineConfig.vRiseKmh]) is confirmed (a V with UP or both axles). */
    private fun checkV(f: Fix) {
        if (pendingV.isEmpty()) return
        val t = f.timeMs - cfg.axle.gpsLagMs.toLong()
        val it = pendingV.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (t > p.tMs + cfg.vAfterMs) { it.remove(); continue }
            if (t < p.tMs || f.speedMps * 3.6 < p.minKmh + cfg.vRiseKmh) continue
            it.remove()
            val b = bumps.firstOrNull { s -> s.id == p.bumpId } ?: continue
            b.axleHits = max(b.axleHits + 1, 2)
            store.updateBump(b)
            log("cross_v", b.id, b.lat, b.lon, f.speedMps * 3.6, Double.NaN, Double.NaN, Double.NaN,
                "speed ${formatFixed(p.minKmh, 0)} -> ${formatFixed(f.speedMps * 3.6, 0)} km/h, now ${bandNote(b)}")
        }
    }

    /** The decision wait for a trigger at [tMs]: [AxleSignature.decideWindowMs] at the lag-corrected speed. */
    private fun axleWaitMs(tMs: Long): Long {
        val f = lastFix ?: return cfg.decideAfterMs
        val low = AxleSignature.lowSpeedMps(fixes.toList(), tMs, f.speedMps, cfg.axle)
        val wait = max(cfg.decideAfterMs, AxleSignature.decideWindowMs(low, vehicle.wheelbaseM, cfg.axle))
        if (low * 3.6 >= cfg.crawlKmh) return wait
        // A crawl (E4b): wait for the rear axle at the slowest plausible speed, past the usual cap.
        val rear = vehicle.wheelbaseM / low * 1000.0 * (1 + cfg.axle.dtTolerance) + cfg.axle.decideMarginMs
        return rear.toLong().coerceIn(wait, max(wait, cfg.crawlDecideMaxMs))
    }

    /** The two-axle signature of the jolt at [trig], from the sample buffer (pitch nose-up; none until the frame is known). */
    private fun axleOf(trig: Long, speedMps: Double): AxleResult {
        val n = bufCount
        val left = joltLeft
        val p = if (left == null) null else DoubleArray(n) { i ->
            val k = (bufHead - n + i + BUF) % BUF
            -(bufGx[k] * left[0] + bufGy[k] * left[1] + bufGz[k] * left[2])
        }
        return AxleSignature.analyze(bufTimes(), bufVerticals(), p, trig, speedMps, vehicle.wheelbaseM, fixes.toList(), cfg.axle)
    }

    /** The sample buffer's times and vertical jolts, oldest first. */
    private fun bufTimes() = LongArray(bufCount) { bufT[(bufHead - bufCount + it + BUF) % BUF] }
    private fun bufVerticals() = DoubleArray(bufCount) { bufV[(bufHead - bufCount + it + BUF) % BUF] }

    /** What a hit tells the shared map besides its peak ([Observation.sevIndex], [Observation.axle]); null = unknown. */
    private class HitInfo(val sevIndex: Double?, val axle: Double?, val shape: BumpShape? = null)

    /** The hump's shape from its front impulse ([EngineConfig.shapeQuietM]) and its numbers for the log; null: unknown. */
    private fun bumpShape(trigMs: Long, ax: AxleResult): Pair<BumpShape, String>? {
        if (ax.verdict != AxleVerdict.BOTH || !(ax.dtMs > 0)) return null
        if (phone.pocketMode && ax.score < cfg.shapePocketMinScore) return null
        val speed = vehicle.wheelbaseM / (ax.dtMs / 1000.0)
        val front = if (ax.rearFirst) trigMs - ax.dtMs.toLong() else trigMs
        val end = front + ax.dtMs.toLong() - max(60L, (ax.dtMs * 0.1).toLong())   // just before the rear impulse
        val t = bufTimes()
        val rate = Biquad.rateHz(t)
        if (t.size < 20 || rate < 20.0 || t[0] > front - 1000) return null
        val x = Biquad.bandPass(bufVerticals(), cfg.shapeLowHz, cfg.shapeHighHz, rate)
        val w = t.indices.filter { t[it] >= front - 100 && t[it] <= end }
        if (w.size < 5) return null
        val peak = w.maxOf { abs(x[it]) }
        val strong = w.filter { abs(x[it]) >= cfg.shapeStrongFraction * peak }
        // Lobes: runs of one sign among the strong samples, each with the velocity step of its whole run.
        val lobes = ArrayList<Double>()
        var lastSign = 0
        for (k in strong) {
            val sign = if (x[k] > 0) 1 else -1
            if (sign == lastSign) continue
            lastSign = sign
            var a = k
            while (a > w.first() && x[a - 1] * sign > 0) a--
            var b = k
            while (b < w.last() && x[b + 1] * sign > 0) b++
            var area = 0.0
            for (q in a + 1..b) area += 0.5 * (x[q] + x[q - 1]) * (t[q] - t[q - 1]) / 1000.0
            lobes.add(area)
        }
        val frontM = (t[strong.last()] - t[strong.first()]) / 1000.0 * speed + cfg.shapeTyreM
        val quietM = (end - t[strong.last()]) / 1000.0 * speed
        val heightM = abs(lobes.first()) * frontM / (PI / 2 * speed)
        val flatTop = lobes.size == 2 && lobes[0] > 0 && lobes[1] < 0 &&
            abs(lobes[0] + lobes[1]) <= cfg.shapeBalance * lobes[0] && quietM >= cfg.shapeQuietM
        val shape = when {
            flatTop -> BumpShape.LONG_FLAT
            heightM < cfg.shapeLowM -> BumpShape.LOW
            else -> BumpShape.SHORT_STEEP
        }
        return shape to " lobes=${lobes.size} quiet=${formatFixed(quietM, 1)}m height~${formatFixed(heightM * 100, 0)}cm"
    }

    /** The car's left axis (up × forward) in phone axes; null without gyroscope or a known forward direction. */
    private fun leftUnit(): DoubleArray? {
        val fwd = forwardUnit()
        if (!gyroSeen || fwd == null) return null
        val g = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        val ux = gravX / g
        val uy = gravY / g
        val uz = gravZ / g
        val (fx, fy, fz) = fwd
        return doubleArrayOf(uy * fz - uz * fy, uz * fx - ux * fz, ux * fy - uy * fx)
    }

    /** Speed lost before and through the jolt at [tMs], km/h: by GPS, or felt along the forward axis ([EngineConfig.axle]). */
    private fun speedDropKmh(tMs: Long): Double {
        val top = fixes.filter { it.timeMs in tMs - cfg.speedBeforeMs..tMs }.maxOfOrNull { it.speedMps }
        val low = fixes.filter { it.timeMs >= tMs - 1000 }.minOfOrNull { it.speedMps }
        val gps = if (top == null || low == null) 0.0 else max(0.0, top - low) * 3.6
        return max(gps, (fwdBrakeAt(lastAccelMs) - fwdBrakeAt(tMs - cfg.speedBeforeMs)) * 3.6)
    }

    /** Why a jolt while the phone is handled stays the phone's, or null: a clear two-axle pair of the car ([EngineConfig.axle]). */
    private fun refusedOverride(tMs: Long, ax: AxleResult?, fix: Fix?): String? {
        if (ax == null || fix == null || ax.verdict != AxleVerdict.BOTH) return "no_pair"
        if (!gyroSeen) return "no_gyro"
        val never = PhoneStateDetector.UNLOCK or PhoneStateDetector.SCREEN or PhoneStateDetector.CALL or
            PhoneStateDetector.POCKET_EXIT
        if ((phone.causes and never) != 0 || phone.outOfPocketSeen) return "signal"
        if (ax.score < cfg.overrideMinScore) return "score"
        if (ax.dtMs < cfg.overrideMinDtMs) return "short_dt"
        if (ax.pitchOrder != 1) return "pitch"
        if (ax.braked != true) return "no_braking"
        val front = if (ax.rearFirst) tMs - ax.dtMs.toLong() else tMs
        if (phone.episodeStartMs <= front) return "handled_first"
        if (maxRotation(front - 100, front + ax.dtMs.toLong() + 200) > cfg.overrideMaxRotRads) return "phone_turned"
        val agrees = vehicle.hits < cfg.overrideMinProfileHits ||
            abs(ax.dtMs / 1000.0 * fix.speedMps - vehicle.wheelbaseM) <= cfg.overrideWheelbaseTolM
        return if (agrees) null else "wheelbase"
    }

    /** The phone's fastest rotation from [fromMs] to [toMs], rad/s (0 without gyroscope samples there). */
    private fun maxRotation(fromMs: Long, toMs: Long): Double {
        var m = 0.0
        for (i in 0 until bufCount) {
            val k = (bufHead - bufCount + i + BUF) % BUF
            if (bufT[k] in fromMs..toMs) m = max(m, sqrt(bufGx[k] * bufGx[k] + bufGy[k] * bufGy[k] + bufGz[k] * bufGz[k]))
        }
        return m
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
        /** 2: confirmed by the car's signature, 1: some evidence, 0: none ([EngineConfig.axle]); [evidence] for the log. */
        grade: Int, evidence: String, info: HitInfo,
    ) {
        lastSpot = null
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

        // Humps in a row: the same pass, a few metres on, but not a wheelbase on (that is the rear axle).
        best?.let { b0 ->
            val a0 = approaches[b0.id]
            val last0 = lastHitMs[b0.id]
            val samePass = (a0 != null && a0.hit) || (a0 == null && last0 != null && tMs - last0 < 15_000)
            val rearAxle = abs(bestD - vehicle.wheelbaseM) <= cfg.axle.dtTolerance * vehicle.wheelbaseM
            if (samePass && bestD >= cfg.rowMinGapM && speedKmh <= cfg.rowMaxKmh && !rearAxle) best = null
        }
        if (best == null) {
            // First time here → record it. No beep: we are already on top of it.
            val b = Bump(0, lat, lon, heading, hits = 1, passes = 1, misses = 0, nPos = 1, firstSeen = now, lastSeen = now)
            b.addPeak(peak, 0)
            b.addSeverity(peak, 0, cfg)   // E2: the vibration index instead of the peak
            if (grade >= 2) b.axleHits = 1
            b.shape = info.shape
            b.id = store.insertBump(b)
            countSpot(b)
            bumps.add(b)
            val a = Approach(tMs, 0.0)
            a.beeped = true; a.counted = true; a.hit = true
            approaches[b.id] = a
            lastHitMs[b.id] = tMs
            trip.newBumps++
            trip.hits++
            log("new_bump", b.id, lat, lon, speedKmh, peak, slowdownKmh, 0.0, "bump ${bandNote(b)} ${shape.describe()}$evidence")
            observe("jolt", lat, lon, speedKmh, peak, shape.score, now, info = info)
            sample("learned", null, tMs, speedKmh, peak, shape, null, lat, lon, b.id)
            if (remoteAll.isNotEmpty()) dedupRemote()   // the new spot of your own replaces its shared twin from now on
            listener.onNewBump(b)
            // No tick when the shared map already knew it: confirming a known spot isn't news.
            if (!sharedFelt && tMs - lastTickMs >= cfg.tickGapMs) {
                lastTickMs = tMs
                listener.onNewSpotTick(b)
            }
            lastSpot = b
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
        if (grade == 0) {
            // Felt, but nothing of a car going over a bump: the pass counts as felt, the hit doesn't (it never confirms).
            if (a != null) {
                a.hit = true
                if (!a.counted) { a.counted = true; b.passes++ }
            } else b.passes++
            store.updateBump(b)
            log("hit_weak", b.id, lat, lon, speedKmh, peak, slowdownKmh, bestD, "hits ${b.hits}/${b.passes}$evidence")
            sample("hit", "weak", tMs, speedKmh, peak, shape, null, lat, lon, b.id)
            return
        }

        // Refine the position: running average of all readings (the newest one always keeps ≥10% weight).
        val w = 1.0 / (min(b.nPos, 9) + 1)
        b.lat += (lat - b.lat) * w
        b.lon += (lon - b.lon) * w
        b.heading = Geo.blendAngle(b.heading, heading, w)
        b.nPos++
        b.addPeak(peak, b.hits)
        b.addSeverity(peak, b.hits, cfg)
        b.hits++
        if (grade >= 2) b.axleHits++
        info.shape?.let { b.shape = it }
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
            "hits ${b.hits}/${b.passes} now=bump ${bandNote(b)} ${shape.describe()}$evidence",
        )
        observe("known_hit", lat, lon, speedKmh, peak, shape.score, now, info = HitInfo(info.sevIndex, info.axle, info.shape ?: b.shape))
        sample("hit", null, tMs, speedKmh, peak, shape, null, lat, lon, b.id)
        listener.onKnownBumpHit(b)
        lastSpot = b
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
        checkV(f)

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
            // v2: its hits (one phone twice counts) and the server's confidence and band, where it gives them; an old
            // pothole spot (legacy) is soft until felt. Without v2 fields: one hit per phone, the band from the index.
            @Suppress("DEPRECATION") val old = r.legacy || r.kind == BumpKind.POTHOLE
            val hits = when (r.confidence) {
                Confidence.FULL -> max(r.nHits, 2)
                Confidence.SOFT -> min(r.nHits, 1)
                null -> r.nHits
            }.coerceAtLeast(0)
            Bump(
                -2L - r.id, r.lat, r.lon, r.heading, hits = hits, passes = 0, misses = 0, nPos = 0,
                firstSeen = 0, lastSeen = 0, peakAvg = r.severity, sevIndex = r.severity, legacy = old, lastBand = r.band,
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
        dir: Double = heading, info: HitInfo? = null,
    ) {
        val sink = observationSink ?: return
        try {
            // The shape score goes up as a diagnostic in the old kind_score field; bumps have no side (0).
            sink.record(Observation(randomUuid(), kind, lat, lon, dir, speedKmh, peak, shapeScore, 0.0, now, info?.sevIndex, info?.axle, info?.shape))
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

    /**
     * The next spots on the road ahead, nearest first (at most two: "Strong bump in 120 m. Then: mild bump in 650 m"),
     * your own and shared, not muted. Updated on every fix while moving; empty until the heading is known.
     */
    var spotsAhead: List<SpotAhead> = emptyList()
        private set

    private fun updateSpotsAhead(f: Fix) {
        if (f.speedMps < 1.5 || heading.isNaN()) return
        val out = ArrayList<SpotAhead>()
        for (s in bumps.asSequence() + remoteActive.asSequence()) {
            if (abs(s.lat - f.lat) > 0.01 || abs(s.lon - f.lon) > 0.012) continue   // more than ~1 km away
            if (!warnable(s) || Geo.angleDiff(heading, s.heading) > cfg.headingTolDeg) continue
            val d = Geo.distance(f.lat, f.lon, s.lat, s.lon)
            if (d > cfg.aheadRangeM || d < 1.0) continue
            val off = Geo.angleDiff(heading, Geo.bearing(f.lat, f.lon, s.lat, s.lon))
            if (off > (if (d <= cfg.approachRadiusM) cfg.aheadNearDeg else cfg.aheadFarDeg)) continue
            out.add(SpotAhead(s, d, s.severity(cfg), s.confidence(cfg), soundFor(s)))
        }
        out.sortBy { it.distanceM }
        spotsAhead = out.take(2)
    }

    private fun updateApproaches(f: Fix, joltSinceFix: Double) {
        updateSpotsAhead(f)
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

        // Approach beeps going on: follow the spot, or end them (slower, muted, turned away).
        if (a.seqSpot != null && !a.seqDone) {
            val dt = (f.timeMs - a.seqFixMs) / 1000.0
            a.seqDecel = if (dt > 0 && dt <= 3.0) (a.seqMps - f.speedMps) / dt else 0.0
            a.seqRising = f.speedMps > a.seqMps + 0.3
            a.seqSpot = b; a.seqDist = d; a.seqMps = f.speedMps; a.seqFixMs = f.timeMs
            a.seqBrakeAtLag = brakeIntAt(f.timeMs - (cfg.approachGpsLagS * 1000).toLong())
            if (!warnable(b) || aheadDiff > 90.0 || !cfg.approachBeeps) a.seqDone = true
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
                // Approach beeps wait for this warning: a voice until it is spoken, beeps for a fixed hold.
                val voice = sound.voice || cluster != null
                approachHoldUntilMs = f.timeMs + if (voice) cfg.approachVoiceMaxMs else cfg.approachHoldMs
                if (cfg.approachBeeps && sound != WarnSound.SOFT && speedKmh >= cfg.approachFastKmh) {
                    a.seqSpot = b; a.seqDist = d; a.seqMps = f.speedMps; a.seqFixMs = f.timeMs; a.seqDecel = 0.0
                    a.seqBrakeAtLag = brakeIntAt(f.timeMs - (cfg.approachGpsLagS * 1000).toLong()); a.seqRising = false
                    a.nextBeepMs = if (voice) f.timeMs else f.timeMs + cfg.approachDelayMs
                }
                listener.onWarning(Warning(b, d, speedKmh, sound, cluster))
            }
        }
    }

    /**
     * Approach beeps, on the sensor clock: the nearest spot with a sequence going on beeps when it is due. Its distance
     * is carried on from the last fix at that fix's speed.
     */
    private fun approachBeep(tMs: Long) {
        var best: Approach? = null
        var bestD = Double.MAX_VALUE
        var bestMps = 0.0
        for (a in approaches.values) {
            if (a.seqSpot == null || a.seqDone) continue
            val sinceS = (tMs - a.seqFixMs) / 1000.0
            val d = a.seqDist - a.seqMps * sinceS
            // The GPS speed is ~1 s late: take off the deceleration over that lag and since the fix.
            val byGps = a.seqMps - max(0.0, a.seqDecel) * (cfg.approachGpsLagS + sinceS)
            val felt = if (a.seqRising && fwdBrake == null) 0.0 else brakeInt - a.seqBrakeAtLag
            val mps = min(byGps, a.seqMps - felt)
            if (d <= cfg.approachEndM || mps * 3.6 < cfg.approachFastKmh) { a.seqDone = true; continue }
            if (d < bestD) { bestD = d; best = a; bestMps = mps }
        }
        val a = best ?: return
        if (tMs < a.nextBeepMs || tMs < approachHoldUntilMs) return
        a.nextBeepMs = tMs + cfg.approachIntervalMs(bestD)
        listener.onApproachBeep(a.seqSpot!!, bestD, bestMps * 3.6)
    }

    // Braking felt by the accelerometer, integrated (m/s), with its recent history for [brakeIntAt].
    private var brakeInt = 0.0
    private var brkX = 0.0; private var brkY = 0.0; private var brkZ = 0.0   // horizontal push, fast low-pass
    private var fwdBrake: Double? = null   // the last sample's braking along the learned forward axis, if learned
    private val brakeHist = ArrayDeque<Pair<Long, Double>>()
    private val brkFwd = DoubleArray(3)

    private fun noteBraking(tMs: Long, dt: Double, hx: Double, hy: Double, hz: Double, aFast: Double) {
        brkX += aFast * (hx - brkX); brkY += aFast * (hy - brkY); brkZ += aFast * (hz - brkZ)
        val f = brkFwd
        fwdBrake = if (forwardInto(f)) max(0.0, -(brkX * f[0] + brkY * f[1] + brkZ * f[2])) else null
        val push = fwdBrake ?: sqrt(brkX * brkX + brkY * brkY + brkZ * brkZ).let { if (it >= cfg.approachBrakeDeadMs2) it else 0.0 }
        brakeInt += push * dt
        brakeHist.addLast(tMs to brakeInt)
        while (brakeHist.size > 1 && brakeHist.first().first < tMs - 5000) brakeHist.removeFirst()
        fwdBrake?.let { fwdBrakeInt += it * dt }
        fwdHist.addLast(tMs to fwdBrakeInt)
        while (fwdHist.size > 1 && fwdHist.first().first < tMs - 10_000) fwdHist.removeFirst()
    }

    // Braking felt along the learned forward axis only, integrated (m/s), for [speedDropKmh].
    private var fwdBrakeInt = 0.0
    private val fwdHist = ArrayDeque<Pair<Long, Double>>()

    /** [fwdBrakeInt] as it was at [tMs] (the oldest kept if earlier). */
    private fun fwdBrakeAt(tMs: Long): Double {
        var v = fwdHist.firstOrNull()?.second ?: fwdBrakeInt
        for ((t, i) in fwdHist) { if (t > tMs) break; v = i }
        return v
    }

    /** [brakeInt] as it was at [tMs] (the oldest kept if earlier). */
    private fun brakeIntAt(tMs: Long): Double {
        var v = brakeHist.firstOrNull()?.second ?: brakeInt
        for ((t, i) in brakeHist) { if (t > tMs) break; v = i }
        return v
    }

    /** Until then no approach beep (a warning is playing). */
    private var approachHoldUntilMs = Long.MIN_VALUE / 4

    /**
     * The app finished speaking a warning (or its sound fell back to beeps): approach beeps may follow
     * [EngineConfig.approachAfterVoiceMs] from now. Call on the engine thread.
     */
    fun warningSpoken() {
        approachHoldUntilMs = min(approachHoldUntilMs, lastAccelMs + cfg.approachAfterVoiceMs)
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
        else if (b.shape == BumpShape.LONG_FLAT) WarnSound.STRONG   // "very hard or long": 3 beeps and the voice
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
        if (members.size < cfg.clusterMinExtra) {
            // A row of humps: each within rowMaxGapM of the one before it, from the warned one on.
            val row = ArrayList<Pair<Bump, Double>>()
            var prev = firstDist
            for (m in members.sortedBy { it.second }) {
                if (m.second - prev > cfg.rowMaxGapM) break
                row.add(m)
                prev = m.second
            }
            if (row.isEmpty()) return null
            members.clear()
            members.addAll(row)
        }

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

        /** Sample buffer size: ≥ 7.5 s even at 200 samples per second (the axle signature's span at a crawl, E4b). */
        private const val BUF = 1536

        /** [AxleResult.reason]s where the axle check could not look: no evidence either way. */
        val AXLE_BLIND = setOf("window", "slow", "no_data", "fast", "ringing")
    }
}
