package app.bumpbeeper

/**
 * How hard a spot hits, from [Bump.sevIndex]: mild below [EngineConfig.sevMildMax], strong from
 * [EngineConfig.sevStrongMin], moderate in between. Mild warns with one beep, moderate with two, strong by voice.
 */
enum class Severity(val label: String) {
    MILD("mild"),
    MODERATE("moderate"),
    STRONG("strong");

    companion object {
        /**
         * The band of [index]. A spot stays in its [previous] band until the index is [EngineConfig.sevHysteresis]
         * past an edge (up from edge × 1.1, down below edge × 0.9), so one odd hit next to an edge doesn't flip it.
         * Without a previous band the plain edges apply.
         */
        fun of(index: Double, previous: Severity?, cfg: EngineConfig): Severity {
            val h = cfg.sevHysteresis
            // Each edge as seen from the previous band: harder to cross, whichever way the index moves.
            val mildEdge = cfg.sevMildMax * when (previous) { null -> 1.0; Severity.MILD -> 1 + h; else -> 1 - h }
            val strongEdge = cfg.sevStrongMin * when (previous) { null -> 1.0; Severity.STRONG -> 1 - h; else -> 1 + h }
            return when {
                index >= strongEdge -> Severity.STRONG
                index >= mildEdge -> Severity.MODERATE
                else -> Severity.MILD
            }
        }
    }
}

/** How sure the app is about a spot: a [SOFT] one warns with one soft beep ("maybe"), a [FULL] one by its severity. */
enum class Confidence(val label: String) {
    /** Felt once, or an old pothole spot ([Bump.legacy]) not felt since. */
    SOFT("soft"),
    /** Felt at least twice, or one strong hit with both axles felt ([Bump.axleHits]). */
    FULL("full"),
}

/** One bump on the map, learned from your own drives. Every jolt is a bump; how hard it hits is its [severity]. */
class Bump(
    var id: Long,
    var lat: Double,
    var lon: Double,
    /** Direction of travel when it was hit (degrees). A bump only beeps for cars going this way. */
    var heading: Double,
    /** Times you felt it. */
    var hits: Int,
    /** Times you drove over this spot (felt or not). */
    var passes: Int,
    /** Passes where nothing was felt. */
    var misses: Int,
    /** How many readings the position is averaged from. */
    var nPos: Int,
    var firstSeen: Long,
    var lastSeen: Long,
    /** You pressed "Mute last beep" for this one. */
    var userMuted: Boolean = false,
    /** Average jolt of its hits, m/s². */
    var peakAvg: Double = 0.0,
    /**
     * What its [severity] is judged from: a running average over its hits. For now the same average of jolt peaks
     * as [peakAvg] (m/s²); E2 feeds it a speed-normalised vibration index instead.
     */
    var sevIndex: Double = 0.0,
    /** Hits where both axles were felt (front wheels, then the rear ones a wheelbase later). 0 until E2 detects them. */
    var axleHits: Int = 0,
    /** An old pothole spot (from before v2, your own or shared): only a soft "maybe" until it is felt again. */
    var legacy: Boolean = false,
    /** The band [severity] settled on when [sevIndex] last changed: the memory for its hysteresis. Null = none yet. */
    var lastBand: Severity? = null,
) {
    val hitRate: Double get() = if (passes <= 0) 1.0 else hits.toDouble() / passes

    /** How hard it hits: the band of [sevIndex], staying in [lastBand] inside the hysteresis ([Severity.of]). */
    fun severity(cfg: EngineConfig): Severity = Severity.of(sevIndex, lastBand, cfg)

    /**
     * [Confidence.FULL] once felt twice, or after one strong hit with both axles felt ([axleHits], from E2).
     * An old pothole spot ([legacy]) stays [Confidence.SOFT] until it is felt again.
     */
    fun confidence(cfg: EngineConfig): Confidence = when {
        legacy -> Confidence.SOFT
        hits >= 2 || (axleHits >= 1 && severity(cfg) == Severity.STRONG) -> Confidence.FULL
        else -> Confidence.SOFT
    }

    /**
     * Add one hit's severity index to the running average (same weights as [addPeak]) and settle the band.
     * [hitsBefore] = hits counted before this one. A spot loaded without [lastBand] had the plain band of its index.
     */
    fun addSeverity(index: Double, hitsBefore: Int, cfg: EngineConfig) {
        val before = lastBand ?: if (hitsBefore > 0) Severity.of(sevIndex, null, cfg) else null
        val w = 1.0 / (minOf(hitsBefore, 9) + 1)
        sevIndex += (index - sevIndex) * w
        lastBand = Severity.of(sevIndex, before, cfg)
    }

    /** For screens and logs, in English: "moderate bump", "strong bump (maybe)". */
    fun describe(cfg: EngineConfig): String =
        severity(cfg).label + " bump" + if (confidence(cfg) == Confidence.SOFT) " (maybe)" else ""

    /**
     * Silent but kept on the map: either you muted it, or it is probably a false detection
     * (passed several times but rarely felt).
     */
    fun isMuted(cfg: EngineConfig): Boolean =
        userMuted || (passes >= cfg.muteAfterPasses && hitRate < cfg.muteBelowHitRate)

    /** Add one hit's jolt to the average. [hitsBefore] = hits counted before this one. */
    fun addPeak(peak: Double, hitsBefore: Int) {
        val w = 1.0 / (minOf(hitsBefore, 9) + 1)
        peakAvg += (peak - peakAvg) * w
    }

    fun copy() = Bump(
        id, lat, lon, heading, hits, passes, misses, nPos, firstSeen, lastSeen, userMuted,
        peakAvg, sevIndex, axleHits, legacy, lastBand,
    )
}

/** One GPS reading. [timeMs] is on the same monotonic clock as the accelerometer samples. */
class Fix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    /** m/s, NaN if unknown. */
    val speedMps: Double,
    /** degrees, NaN if unknown. */
    val bearingDeg: Double,
    val accuracyM: Double,
)

/** A row in the event log (exported as CSV for tuning). */
class BumpEvent(
    val wallTime: Long,
    val tripId: Long,
    /** new_bump, hit, hit_repeat, miss, pass_slow, beep, beep_quiet, beep_grouped, rejected, user_mute */
    val type: String,
    val bumpId: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val heading: Double,
    /** Peak vertical jolt, m/s². For misses: the strongest jolt felt near the bump. */
    val peak: Double,
    /** How much you slowed down in the 10 s before the jolt, km/h. */
    val slowdownKmh: Double,
    /** Distance to the bump (for beeps and hits), metres. */
    val distanceM: Double,
    val note: String,
)

class TripStats {
    var hits = 0
    var newBumps = 0
    var beeps = 0
    var misses = 0
    var rejected = 0
    var distanceM = 0.0
    /** Spots felt this trip (new ones included, once per pass), by severity band after the hit... */
    var mild = 0
    var moderate = 0
    var strong = 0
    /** ...and by confidence after the hit: still a "maybe" (soft), or confirmed (full). */
    var soft = 0
    var full = 0
}

/** Where bumps and events are kept. SQLite on the phone, in-memory in tests. */
interface BumpStore {
    fun loadBumps(): List<Bump>
    fun insertBump(b: Bump): Long
    fun updateBump(b: Bump)
    fun logEvent(e: BumpEvent)
}

/** How a warning should sound. The engine picks it (so it can be tested); the app plays it. */
enum class WarnSound {
    /** A spot that is only a "maybe" ([Confidence.SOFT]), whatever its severity: one soft, short beep. */
    SOFT,
    /** Mild bump: one beep. */
    MILD,
    /** Moderate bump: two beeps. */
    MODERATE,
    /** Strong bump: the voice says "Strong bump ahead." ([Phrases.strongBump]). */
    STRONG,
}

/** Several known spots close together ahead: announced once by voice ("3 bumps ahead") instead of one sound each. */
class HazardCluster(
    /** Spots in the group, the warned one included (at least 3). */
    val count: Int,
    /** The strongest band in the group, "maybe" spots included. */
    val maxSeverity: Severity,
    /** At least one spot in the group is confirmed ([Confidence.FULL]). The app doesn't announce a group of maybes. */
    val anyFull: Boolean,
)

/** One warning ahead of a spot. [cluster] is set when more spots follow closely; they then stay silent. */
class Warning(
    val spot: Bump,
    val distanceM: Double,
    val speedKmh: Double,
    val sound: WarnSound,
    val cluster: HazardCluster?,
)

/** What the engine tells the outside world. All methods are optional. */
interface EngineListener {
    fun onNewBump(b: Bump) {}
    /**
     * A new spot was just recorded (first pass): time for a soft tick. Called right after [onNewBump],
     * at most once per [EngineConfig.tickGapMs], so a bumpy stretch doesn't machine-gun.
     */
    fun onNewSpotTick(b: Bump) {}
    fun onKnownBumpHit(b: Bump) {}
    /** A warning ahead, with the sound to play. By default it is handed on to [onBeep]. */
    fun onWarning(w: Warning) { onBeep(w.spot, w.distanceM, w.speedKmh) }
    fun onBeep(b: Bump, distanceM: Double, speedKmh: Double) {}
    fun onPassed(b: Bump, felt: Boolean) {}
    fun onJoltRejected(peak: Double, reason: String) {}
}
