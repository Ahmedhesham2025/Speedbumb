package app.bumpbeeper

/**
 * A spot confirmed on the shared online map (hit by other phones), as the app's local cache holds it.
 * The engine warns for it like for a spot it learned itself, but never stores it or counts passes on it.
 */
data class RemoteSpot(
    /** Server id (≥ 0). */
    val id: Long,
    val lat: Double,
    val lon: Double,
    /** Direction of travel it was hit in (degrees). It only warns for cars going this way. */
    val heading: Double,
    val kind: BumpKind,
    val side: Side,
    /** Average jolt of its hits, m/s² (same unit as [Bump.peakAvg]); a pothole is harsh above [EngineConfig.harshPotholeMs2]. */
    val severity: Double,
    /** How many different phones confirmed it. */
    val nDevices: Int,
)

/**
 * Where the engine gets shared-map spots from. Called on the engine thread, so it must be fast and must
 * never touch the network: the app backs it with a local cache table that is filled in the background.
 */
interface SpotSource {
    /** Confirmed spots within [radiusM] metres of the given point (any direction of travel). */
    fun spotsNear(lat: Double, lon: Double, radiusM: Double): List<RemoteSpot>
}

/**
 * One hazard observation for the shared map.
 *
 * Privacy: the engine reports every observation, including ones next to home or work. The app must drop
 * observations within 300 m of a trip's start and end (the privacy zone) before anything is uploaded.
 */
data class Observation(
    /** Random UUID; makes the upload idempotent (the server ignores a clientId it has seen). */
    val clientId: String,
    /** "jolt" (a new spot was felt), "known_hit" (a known spot was felt again) or "pass_clear" (drove over a spot, felt nothing). */
    val kind: String,
    val lat: Double,
    val lon: Double,
    /** Direction of travel, degrees. */
    val heading: Double,
    val speedKmh: Double,
    /** Vertical jolt, m/s². For pass_clear: the strongest jolt felt near the spot (below the trigger). */
    val peak: Double,
    /** This hit's pothole score, -1 (speed bump) .. +1 (pothole). 0 for pass_clear. */
    val kindScore: Double,
    /** Which wheel: -1 left, +1 right, 0 unknown. 0 for pass_clear. */
    val sideScore: Double,
    val wallTimeMs: Long,
)

/** Where the engine sends [Observation]s (the app queues them in an outbox for upload). Called on the engine thread. */
interface ObservationSink {
    fun record(o: Observation)
}
