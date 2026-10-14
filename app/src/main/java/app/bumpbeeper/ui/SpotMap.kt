package app.bumpbeeper.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import app.bumpbeeper.Bump
import app.bumpbeeper.BumpMapView
import app.bumpbeeper.Confidence
import app.bumpbeeper.EngineConfig
import app.bumpbeeper.Geo
import app.bumpbeeper.Severity
import app.bumpbeeper.Ui
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * The map on the Drive and Map screens: the street map ([StreetMap], MapLibre + OpenFreeMap) over the old offline map
 * ([BumpMapView]). The offline map is shown until the streets have loaded, and stays when they can't (no network and
 * nothing cached, or no map engine on this phone). Both get the same spots, position and camera, so switching is
 * seamless. The pages talk to it like they did to the offline map: [bumps], [centerLat], [radiusM], [meLat]…
 */
class SpotMap(ctx: Context, interactive: Boolean) : FrameLayout(ctx) {
    private val offline = BumpMapView(ctx)
    private val street = StreetMap(ctx, interactive)
    private var streetsOn = false
    private val cfg = EngineConfig()

    /** A pin for the street map: key "m<id>", position, icon, selected. */
    class Pin(val key: String, val lat: Double, val lon: Double, val icon: StreetMap.Icon, val selected: Boolean)

    var onSelect: (Bump) -> Unit = {}
        set(v) { field = v; offline.onSelect = v }

    var bumps: List<Bump> = emptyList()
        set(v) { field = v; offline.bumps = v; pushPins() }
    var selectedId: Long = -1L
        set(v) { field = v; offline.selectedId = v; pushPins() }

    var centerLat: Double
        get() = offline.centerLat
        set(v) { offline.centerLat = v; camera() }
    var centerLon: Double
        get() = offline.centerLon
        set(v) { offline.centerLon = v; camera() }
    /** Metres from the centre to the top edge (the street map's zoom follows it). */
    var radiusM: Double
        get() = offline.radiusM
        set(v) {
            // Zooming the street map keeps the place you panned to (the offline map's centre follows it).
            if (streetsOn) street.camera()?.let { offline.centerLat = it[0]; offline.centerLon = it[1] }
            offline.radiusM = v
            camera()
        }

    var meLat: Double
        get() = offline.meLat
        set(v) { offline.meLat = v; pushMe() }
    var meLon: Double
        get() = offline.meLon
        set(v) { offline.meLon = v; pushMe() }
    /** Direction of travel, degrees (NaN: unknown, then a dot instead of the arrow). */
    var heading: Double = Double.NaN
        set(v) { field = v; pushMe(); if (headingUp) camera() }
    /** Driving: the street map turns so the road ahead points up. The offline map stays north-up. */
    var headingUp = false
        set(v) {
            if (field != v) street.setMaxFps(if (v) DRIVE_FPS else 60)   // driving: a calm 30 frames a second
            field = v
            camera()
        }

    /** True while the street map is on screen (false: the offline map is). */
    val showingStreets: Boolean get() = streetsOn

    init {
        addView(offline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (street.available) {
            street.view.visibility = View.INVISIBLE   // until the style has loaded
            addView(street.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            street.onReady = { streetsOn = true; street.view.visibility = View.VISIBLE; pushPins(); pushMe(); camera() }
            street.onFailed = { streetsOn = false; street.view.visibility = View.INVISIBLE }
            street.onSpot = { key -> bumps.firstOrNull { "m${it.id}" == key }?.let { selectedId = it.id; onSelect(it) } }
        }
    }

    /** The offline map's colours, for list rows and sheets. */
    fun colorOf(b: Bump): Int = offline.colorOf(b)

    fun resume() = street.resume()
    fun pause() = street.pause()
    fun destroy() = street.destroy()

    override fun invalidate() {
        super.invalidate()
        offline.invalidate()
    }

    private fun pushPins() {
        if (!street.available) return
        street.setSpots(bumps.map { b -> Pin("m${b.id}", b.lat, b.lon, iconOf(b), b.id == selectedId) })
    }

    private fun pushMe() {
        if (street.available) street.setMe(meLat, meLon, heading)
    }

    private var last: DoubleArray? = null   // lat, lon, zoom, bearing last sent to the street map

    /**
     * Sends the camera to the street map, but only when it moved enough to see (3 m, a twentieth of a zoom step,
     * 3°): each move redraws the map, and while driving a fix comes every second (battery).
     */
    private fun camera() {
        if (!street.available || centerLat == 0.0 && centerLon == 0.0) return
        val zoom = zoomFor(radiusM, centerLat)
        val bearing = if (headingUp && !heading.isNaN()) heading else 0.0
        val l = last
        if (l != null && Geo.distance(l[0], l[1], centerLat, centerLon) < 3.0 && abs(l[2] - zoom) < 0.05 &&
            abs(((l[3] - bearing + 540.0) % 360.0) - 180.0) < 3.0) return
        last = doubleArrayOf(centerLat, centerLon, zoom, bearing)
        street.moveTo(centerLat, centerLon, zoom, bearing)
    }

    /** For the screenshot test's check file. */
    internal fun debug(): String = "streets=$streetsOn available=${street.available} bumps=${bumps.size} " +
        "centre=$centerLat,$centerLon radius=$radiusM ${if (street.available) street.debug() else ""}"

    /** The street map's zoom that shows about [radiusM] from the centre to the top edge (512 px tiles, in dp). */
    private fun zoomFor(radiusM: Double, lat: Double): Double {
        val halfDp = max(height, Ui.dp(context, 300)) / resources.displayMetrics.density / 2.0
        val z = ln(EARTH_M * cos(Math.toRadians(lat)) * halfDp / (radiusM * 512.0)) / ln(2.0)
        return z.coerceIn(3.0, 18.5)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        camera()
    }

    /** The pin a spot gets: muted first, then "maybe", then its severity. */
    fun iconOf(b: Bump): StreetMap.Icon = when {
        b.isMuted(cfg) -> StreetMap.Icon.MUTED
        b.confidence(cfg) == Confidence.SOFT -> StreetMap.Icon.MAYBE
        else -> when (b.severity(cfg)) {
            Severity.MILD -> StreetMap.Icon.MILD
            Severity.MODERATE -> StreetMap.Icon.MODERATE
            Severity.STRONG -> StreetMap.Icon.STRONG
        }
    }

    private companion object {
        const val EARTH_M = 40_075_016.686
        const val DRIVE_FPS = 30
    }
}
