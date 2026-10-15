package app.bumpbeeper.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import app.bumpbeeper.Bump
import app.bumpbeeper.BumpMapView
import app.bumpbeeper.Confidence
import app.bumpbeeper.EngineConfig
import app.bumpbeeper.Geo
import app.bumpbeeper.R
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

    /** Follows the car (Drive). A finger on the map stops it until [recenter]; [onPanned] shows the button. */
    var following = true
        private set
    var onPanned: () -> Unit = {}
    /** Drive: your puck about 70 % down the map, so more of the road ahead is visible. */
    var puckLow = false
        set(v) { field = v; street.topPaddingPx = if (v) height * 0.4 else 0.0; last = null; camera() }

    fun recenter() {
        following = true
        last = null
        camera()
    }

    /** The road you are on, from the tiles on screen (null: unknown, or the offline map is showing). */
    fun roadName(arabic: Boolean): String? =
        (if (streetsOn && !meLat.isNaN()) street.roadNameAt(meLat, meLon, arabic) else null) ?: roadNameForTest

    /** The screenshot test's road name when the emulator's tiles give none at the puck (never set by the app). */
    internal var roadNameForTest: String? = null

    /** The map data credit to show with the map (OSM / OpenFreeMap / OpenMapTiles), or null when no streets show. */
    val credit: String? get() = if (streetsOn) context.getString(R.string.map_attribution) else null

    /** The streets appeared or went. */
    var onStreetsChanged: () -> Unit = {}

    private val credits = ArrayList<View>()

    /**
     * The map data credit (OpenStreetMap / OpenFreeMap / OpenMapTiles, their licences ask for it) for a page to put
     * at the bottom where nothing covers it: tap for the OSM copyright page. Shown only while the streets show.
     */
    fun creditView(): android.widget.TextView = Ui.text(context, 10f, Ui.DIM, value = context.getString(R.string.map_attribution)).apply {
        setPadding(Ui.dp(context, 6), Ui.dp(context, 2), Ui.dp(context, 6), Ui.dp(context, 2))
        background = Ui.rounded(context, (Ui.SURFACE and 0x00FFFFFF) or 0xCC000000.toInt(), 6)
        isClickable = true
        setOnClickListener {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(StreetMap.COPYRIGHT_URL)))
            } catch (_: Exception) {}
        }
        visibility = if (streetsOn) View.VISIBLE else View.GONE
        credits.add(this)
    }

    private fun showCredits() = credits.forEach { it.visibility = if (streetsOn) View.VISIBLE else View.GONE }

    /** The street map has drawn everything (tiles, labels) and is not moving; false while the offline map shows. */
    val idle: Boolean get() = streetsOn && street.idle

    /** Where the "Offline map" chip sits under the page's own top bars, dp. */
    var offlineChipTopDp = 12
        set(v) { field = v; (offlineChip.layoutParams as LayoutParams).topMargin = Ui.dp(context, v); offlineChip.requestLayout() }

    /** Says the map is the offline one on purpose (no network or no cached streets), so the plain grid looks meant. */
    private val offlineChip = Ui.chip(ctx, ctx.getString(R.string.map_offline_chip), R.drawable.ic_cloud_off)

    /**
     * The road to a strong bump [distanceM] ahead, tinted red: from you along your heading (the engine says how far,
     * the spot is on the road ahead within a few degrees). Null distance clears it.
     */
    fun aheadStrong(distanceM: Double?) {
        if (!street.available) return
        if (distanceM == null || meLat.isNaN() || heading.isNaN()) { street.setAhead(null, null); return }
        val d = distanceM / 6_371_000.0
        val b = Math.toRadians(heading)
        val la = Math.toRadians(meLat)
        val lo = Math.toRadians(meLon)
        val la2 = Math.asin(Math.sin(la) * Math.cos(d) + Math.cos(la) * Math.sin(d) * Math.cos(b))
        val lo2 = lo + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(la), Math.cos(d) - Math.sin(la) * Math.sin(la2))
        street.setAhead(meLat to meLon, Math.toDegrees(la2) to Math.toDegrees(lo2))
    }

    /** True while the street map is on screen (false: the offline map is). */
    val showingStreets: Boolean get() = streetsOn

    init {
        addView(offline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (street.available) {
            street.view.visibility = View.INVISIBLE   // until the style has loaded
            addView(street.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            street.onReady = {
                streetsOn = true; street.view.visibility = View.VISIBLE; offlineChip.visibility = View.GONE
                showCredits(); onStreetsChanged()
                pushPins(); pushMe(); camera()
            }
            street.onFailed = {
                streetsOn = false; street.view.visibility = View.INVISIBLE; offlineChip.visibility = View.VISIBLE
                showCredits(); onStreetsChanged()
            }
        }
        addView(offlineChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = Ui.dp(ctx, offlineChipTopDp) })
        offlineChip.visibility = if (street.available) View.GONE else View.VISIBLE
        if (street.available) {
            street.onSpot = { key -> bumps.firstOrNull { "m${it.id}" == key }?.let { selectedId = it.id; onSelect(it) } }
            street.onUserMove = { if (headingUp && following) { following = false; onPanned() } }
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
        if (headingUp && !following) return   // a finger moved the map: leave it until Recenter
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
        "centre=$centerLat,$centerLon radius=$radiusM road=${roadName(false)} ${if (street.available) street.debug() else ""}"

    /** The street map's zoom that shows about [radiusM] from the centre to the top edge (512 px tiles, in dp). */
    private fun zoomFor(radiusM: Double, lat: Double): Double {
        val halfDp = max(height, Ui.dp(context, 300)) / resources.displayMetrics.density / 2.0
        val z = ln(EARTH_M * cos(Math.toRadians(lat)) * halfDp / (radiusM * 512.0)) / ln(2.0)
        return z.coerceIn(3.0, 18.5)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (puckLow) street.topPaddingPx = h * 0.4
        last = null
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
