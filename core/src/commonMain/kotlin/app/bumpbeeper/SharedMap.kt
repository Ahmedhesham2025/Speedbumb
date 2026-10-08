package app.bumpbeeper

/**
 * The shared map's old spot kind (server column `kind`), as the app's cache still delivers it. Spots have a
 * [Severity] and a [Confidence] now: the engine only reads [POTHOLE], which marks an old pothole spot ([Bump.legacy]).
 */
@Deprecated("Spots have a Severity and a Confidence now; only the shared map's cache still carries this (until spots_near_v2).")
enum class BumpKind(val label: String) {
    BUMP("speed bump"),
    POTHOLE("pothole"),
    UNSURE("bump (unsure)"),
}

/** The shared map's old pothole side (server column `side`). Not used any more: bumps have no side. */
@Deprecated("Bumps have no side; only the shared map's cache still carries this (until spots_near_v2).")
enum class Side(val label: String) {
    LEFT("left side"),
    RIGHT("right side"),
    UNKNOWN("side not known yet"),
}

/**
 * A spot confirmed on the shared online map (hit by other phones), as the app's local cache holds it.
 * The engine warns for it like for a spot it learned itself, but never stores it or counts passes on it.
 */
@Suppress("DEPRECATION")
data class RemoteSpot(
    /** Server id (≥ 0). */
    val id: Long,
    val lat: Double,
    val lon: Double,
    /** Direction of travel it was hit in (degrees). It only warns for cars going this way. */
    val heading: Double,
    /** The old kind: [BumpKind.POTHOLE] makes it an old pothole spot ([Bump.legacy], soft until felt); nothing else is read. */
    val kind: BumpKind,
    /** Not used any more. */
    val side: Side,
    /** Average jolt of its hits, m/s² (same unit as [Bump.peakAvg]): the spot's [Bump.sevIndex]. */
    val severity: Double,
    /** How many different phones confirmed it: two or more make it [Confidence.FULL], one leaves it soft. */
    val nDevices: Int,
    /** Hits in total, one phone twice counts (`spots_near_v2`); [nDevices] where the server didn't say. Not read yet (E2). */
    val nHits: Int = nDevices,
    /** The server's band of [severity] (plain edges, no hysteresis); null where it didn't say. Not read yet (E2). */
    val band: Severity? = null,
    /** The server's confidence (its rule is the engine's, over [nHits]); null where it didn't say. Not read yet (E2). */
    val confidence: Confidence? = null,
    /** An old pothole spot: soft until felt. Same as [kind] == [BumpKind.POTHOLE], which the engine still reads. */
    val legacy: Boolean = kind == BumpKind.POTHOLE,
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
    /** This jolt's shape score, a diagnostic only ([JoltShape.score]): -1 up first and pitching .. +1 down first and rolling. 0 for pass_clear. */
    val kindScore: Double,
    /** Always 0 since v2 (bumps have no side); kept for the upload format. */
    val sideScore: Double,
    val wallTimeMs: Long,
    /** The hit's severity index (0..100, what [Bump.sevIndex] averages); null when not known. Not filled yet (E2). */
    val sevIndex: Double? = null,
    /** The hit's axle score (0..1; ≥ 0.6 = both axles felt); null when not known. Not filled yet (E2). */
    val axle: Double? = null,
)

/** Where the engine sends [Observation]s (the app queues them in an outbox for upload). Called on the engine thread. */
interface ObservationSink {
    fun record(o: Observation)
}
