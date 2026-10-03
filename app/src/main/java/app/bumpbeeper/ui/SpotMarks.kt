package app.bumpbeeper.ui

import app.bumpbeeper.Bump
import app.bumpbeeper.BumpKind
import app.bumpbeeper.EngineConfig
import app.bumpbeeper.Geo
import app.bumpbeeper.RemoteSpot
import app.bumpbeeper.Side

/** Which icon a spot gets on the street map. [color] is the icon's fill; [mark] is drawn on it. */
enum class SpotIcon(val id: String, val color: Int, val mark: String) {
    BUMP("bb-bump", 0xFFFFA726.toInt(), ""),
    POTHOLE("bb-pothole", 0xFFEF5350.toInt(), ""),
    HARSH("bb-harsh", 0xFFB71C1C.toInt(), "!"),
    UNSURE("bb-unsure", 0xFFFFEB3B.toInt(), "?"),
    MUTED("bb-muted", 0xFF78909C.toInt(), ""),
}

/**
 * One spot as the street map draws it: one of your own ([localId] ≥ 0) or a confirmed spot of the shared map
 * ([sharedId] ≥ 0). Pure data, so the mapping is tested without a map.
 */
class MapSpot(
    /** Unique on the map: "m<id>" for yours, "s<id>" for shared ones. The map feature carries it back on a tap. */
    val key: String,
    val lat: Double,
    val lon: Double,
    val heading: Double,
    val kind: BumpKind,
    val side: Side,
    val harsh: Boolean,
    val muted: Boolean,
    /** Average jolt, m/s². */
    val jolt: Double,
    /** Yours: times felt / times driven over with nothing felt. Shared: 0. */
    val hits: Int,
    val clears: Int,
    /** Shared: how many phones confirmed it. Yours: 0. */
    val devices: Int,
    val localId: Long = -1,
    val sharedId: Long = -1,
) {
    val shared: Boolean get() = sharedId >= 0
    val icon: SpotIcon get() = SpotMarks.iconOf(kind, harsh, muted)
}

object SpotMarks {
    /** Map filter chips, in order: all, speed bumps (and unsure), potholes, harsh potholes, muted. */
    const val ALL = 0
    const val BUMPS = 1
    const val POTHOLES = 2
    const val HARSH = 3
    const val MUTED = 4

    /** Spots are grouped into a numbered bubble up to this zoom (13 ≈ a city district on screen), single above it. */
    const val CLUSTER_MAX_ZOOM = 13
    /** How close (screen dp) spots must be to share a bubble. */
    const val CLUSTER_RADIUS_DP = 44
    /** Bubble sizes (dp radius) for up to 9, up to 99, and 100+ spots. */
    val CLUSTER_SIZES = intArrayOf(15, 19, 24)
    /** A shared spot this close to one of yours is the same place: only yours is drawn. */
    const val SAME_PLACE_M = 20.0

    /** Muted wins (you said "don't warn me"), then harsh, then the kind. */
    fun iconOf(kind: BumpKind, harsh: Boolean, muted: Boolean): SpotIcon = when {
        muted -> SpotIcon.MUTED
        kind == BumpKind.POTHOLE && harsh -> SpotIcon.HARSH
        kind == BumpKind.POTHOLE -> SpotIcon.POTHOLE
        kind == BumpKind.BUMP -> SpotIcon.BUMP
        else -> SpotIcon.UNSURE
    }

    fun clusterSizeDp(count: Int): Int = when {
        count < 10 -> CLUSTER_SIZES[0]
        count < 100 -> CLUSTER_SIZES[1]
        else -> CLUSTER_SIZES[2]
    }

    fun fromLocal(b: Bump, cfg: EngineConfig) = MapSpot(
        key = "m${b.id}", lat = b.lat, lon = b.lon, heading = b.heading, kind = b.kind, side = b.side,
        harsh = b.isHarsh(cfg), muted = b.isMuted(cfg), jolt = b.peakAvg,
        hits = b.hits, clears = b.misses, devices = 0, localId = b.id,
    )

    /** Shared spots can't be muted here (the engine has no mute for them); a pothole is harsh by the same rule as yours. */
    fun fromShared(r: RemoteSpot, cfg: EngineConfig) = MapSpot(
        key = "s${r.id}", lat = r.lat, lon = r.lon, heading = r.heading, kind = r.kind, side = r.side,
        harsh = r.kind == BumpKind.POTHOLE && r.severity >= cfg.harshPotholeMs2, muted = false, jolt = r.severity,
        hits = 0, clears = 0, devices = r.nDevices, sharedId = r.id,
    )

    /** Yours first, then each shared spot that isn't already one of yours (within [SAME_PLACE_M]). */
    fun merge(mine: List<MapSpot>, shared: List<MapSpot>): List<MapSpot> {
        if (shared.isEmpty()) return mine
        val out = ArrayList<MapSpot>(mine.size + shared.size)
        out.addAll(mine)
        for (s in shared) {
            if (mine.none { Geo.distance(it.lat, it.lon, s.lat, s.lon) <= SAME_PLACE_M }) out.add(s)
        }
        return out
    }

    fun matches(s: MapSpot, filter: Int): Boolean = when (filter) {
        BUMPS -> (s.kind == BumpKind.BUMP || s.kind == BumpKind.UNSURE)
        POTHOLES -> s.kind == BumpKind.POTHOLE
        HARSH -> s.harsh
        MUTED -> s.muted
        else -> true
    }

    /** The properties a map feature carries: the icon to draw and the key to find the spot again on a tap. */
    fun properties(s: MapSpot): Map<String, Any> = mapOf(
        "key" to s.key,
        "icon" to s.icon.id,
        "shared" to s.shared,
        // Shared spots are drawn a little smaller than yours, under them.
        "size" to if (s.shared) 0.85 else 1.0,
        "order" to if (s.shared) 0 else 1,
    )

    /** Spots within [radiusM] of a point, nearest first (the Drive map's "coming up" and the list). */
    fun nearest(spots: List<MapSpot>, lat: Double, lon: Double, radiusM: Double = Double.MAX_VALUE): List<MapSpot> =
        spots.map { it to Geo.distance(lat, lon, it.lat, it.lon) }
            .filter { it.second <= radiusM }
            .sortedBy { it.second }
            .map { it.first }
}
