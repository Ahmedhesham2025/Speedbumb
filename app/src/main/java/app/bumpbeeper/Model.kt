package app.bumpbeeper

/** What a spot on the map is, judged from how the car moved each time it was hit. */
enum class BumpKind(val label: String) {
    BUMP("speed bump"),
    POTHOLE("pothole"),
    UNSURE("bump (unsure)"),
}

/** One speed bump (or pothole) on the map, learned from your own drives. */
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
    /** Average pothole score of all hits: -1 = clearly a speed bump, +1 = clearly a pothole. */
    var kindScore: Double = 0.0,
    /** How many hits the score is averaged from. */
    var kindVotes: Int = 0,
) {
    val hitRate: Double get() = if (passes <= 0) 1.0 else hits.toDouble() / passes

    val kind: BumpKind
        get() = when {
            kindVotes == 0 -> BumpKind.UNSURE
            kindScore >= KIND_MARGIN -> BumpKind.POTHOLE
            kindScore <= -KIND_MARGIN -> BumpKind.BUMP
            else -> BumpKind.UNSURE
        }

    /**
     * Silent but kept on the map: either you muted it, or it is probably a false detection
     * (passed several times but rarely felt).
     */
    fun isMuted(cfg: EngineConfig): Boolean =
        userMuted || (passes >= cfg.muteAfterPasses && hitRate < cfg.muteBelowHitRate)

    /** Add one hit's pothole score to the running average (recent hits keep at least 1/10 weight). */
    fun addKindVote(score: Double) {
        val w = 1.0 / (minOf(kindVotes, 9) + 1)
        kindScore += (score - kindScore) * w
        kindVotes++
    }

    fun copy() = Bump(id, lat, lon, heading, hits, passes, misses, nPos, firstSeen, lastSeen, userMuted, kindScore, kindVotes)

    companion object {
        const val KIND_MARGIN = 0.25
    }
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
    /** new_bump, hit, hit_repeat, miss, pass_slow, beep, beep_quiet, rejected, user_mute */
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
}

/** Where bumps and events are kept. SQLite on the phone, in-memory in tests. */
interface BumpStore {
    fun loadBumps(): List<Bump>
    fun insertBump(b: Bump): Long
    fun updateBump(b: Bump)
    fun logEvent(e: BumpEvent)
}

/** What the engine tells the outside world. All methods are optional. */
interface EngineListener {
    fun onNewBump(b: Bump) {}
    fun onKnownBumpHit(b: Bump) {}
    fun onBeep(b: Bump, distanceM: Double, speedKmh: Double) {}
    fun onPassed(b: Bump, felt: Boolean) {}
    fun onJoltRejected(peak: Double, reason: String) {}
}
