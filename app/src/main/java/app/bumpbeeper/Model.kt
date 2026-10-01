package app.bumpbeeper

/** One speed bump on the map, learned from your own drives. */
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
) {
    val hitRate: Double get() = if (passes <= 0) 1.0 else hits.toDouble() / passes

    /**
     * Silent but kept on the map: either you muted it, or it is probably a false detection
     * (passed several times but rarely felt).
     */
    fun isMuted(cfg: EngineConfig): Boolean =
        userMuted || (passes >= cfg.muteAfterPasses && hitRate < cfg.muteBelowHitRate)

    fun copy() = Bump(id, lat, lon, heading, hits, passes, misses, nPos, firstSeen, lastSeen, userMuted)
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
    /** new_bump, hit, hit_repeat, miss, beep, rejected */
    val type: String,
    val bumpId: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val heading: Double,
    /** Peak vertical jolt, m/s². */
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
