package app.bumpbeeper.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import app.bumpbeeper.R
import app.bumpbeeper.Ui
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * A real street map (MapLibre Native + free OpenFreeMap tiles, dark style) with the spots as icons on the road.
 * Used by the Map tab ([interactive]: pan, pinch, rotate, tap a spot) and the Drive screen (follows the car).
 *
 * Offline: tiles you have seen stay in MapLibre's ambient cache (about 50 MB). Without network and cache the style
 * can't load; then a plain dark background is used so your spots still show.
 * The owner of this object must call [resume] / [pause] / [destroy]: a paused map draws nothing (battery).
 */
class StreetMap(private val ctx: Context, private val interactive: Boolean) {
    private fun dp(v: Int) = Ui.dp(ctx, v)

    /** Interactive: gets the touches first, so a map inside a scrolling page moves the map, not the page.
     *  Not interactive (Drive screen): never takes a touch, so the page scrolls over it. */
    val view: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (interactive && e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            return super.dispatchTouchEvent(e)
        }
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean = !interactive || super.onInterceptTouchEvent(e)
        override fun onTouchEvent(e: MotionEvent): Boolean = interactive && super.onTouchEvent(e)
    }
    var onSpot: (MapSpot) -> Unit = {}

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var spots: List<MapSpot> = emptyList()
    private var me: DoubleArray? = null          // lat, lon, bearing (NaN = unknown)
    private var pendingCamera: CameraPosition? = null
    private var started = false
    private var resumed = false
    private var offlineStyle = false

    /** False when the map engine couldn't start (e.g. the unit-test runtime); the view then shows a short note. */
    val available: Boolean get() = mapView != null

    init {
        view.background = Ui.rounded(ctx, Ui.SURFACE, 14)
        view.clipToOutline = true
        mapView = try { create() } catch (t: Throwable) { null }
        mapView?.let { view.addView(it, FrameLayout.LayoutParams(-1, -1)) } ?: view.addView(
            Ui.text(ctx, 13f, Ui.DIM, value = ctx.getString(R.string.map_unavailable)).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1),
        )
        // The licences of OpenStreetMap (ODbL), OpenMapTiles and OpenFreeMap ask for this line on the map.
        view.addView(Ui.text(ctx, 10f, 0xFFCFD8DC.toInt(), value = ctx.getString(R.string.map_attribution)).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = Ui.rounded(ctx, 0x99000000.toInt(), 6)
            isClickable = true
            setOnClickListener {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COPYRIGHT_URL))) } catch (_: Exception) {}
            }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(4), 0, dp(4), dp(4)) })
    }

    private fun create(): MapView? {
        if (Build.FINGERPRINT == "robolectric") return null   // no native map engine in JVM tests
        MapLibre.getInstance(ctx.applicationContext)
        setCacheSizeOnce(ctx.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(ctx)
            .logoEnabled(false)
            .attributionEnabled(false)       // our own attribution line, above
            .compassEnabled(interactive)
            .foregroundLoadColor(Ui.BG)
        val mv = MapView(ctx, options)
        mv.onCreate(null)
        mv.addOnDidFailLoadingMapListener {
            // No network and no cached style: a plain background, so your spots are still drawn.
            if (style == null && !offlineStyle) { offlineStyle = true; map?.setStyle(Style.Builder().fromJson(OFFLINE_STYLE)) { styleReady(it) } }
        }
        mv.getMapAsync { m -> setUp(m) }
        return mv
    }

    private fun setUp(m: MapLibreMap) {
        map = m
        m.uiSettings.apply {
            setLogoEnabled(false)
            setAttributionEnabled(false)
            setTiltGesturesEnabled(false)
            setCompassEnabled(interactive)
            setCompassGravity(Gravity.TOP or Gravity.END)
            setCompassMargins(0, dp(8), dp(8), 0)
            if (!interactive) setAllGesturesEnabled(false)
        }
        m.setMinZoomPreference(2.0)
        m.setMaxZoomPreference(19.0)
        pendingCamera?.let { m.cameraPosition = it; pendingCamera = null }
        if (interactive) m.addOnMapClickListener { tapped(m, it) }
        m.setStyle(Style.Builder().fromUri(STYLE_URL)) { styleReady(it) }
    }

    private fun styleReady(s: Style) {
        style = s
        for (i in SpotIcon.entries) s.addImage(i.id, spotIcon(i))
        s.addImage(ME_ARROW, meIcon(arrow = true))
        s.addImage(ME_DOT, meIcon(arrow = false))
        s.addSource(GeoJsonSource(SRC_SPOTS, FeatureCollection.fromFeatures(emptyList<Feature>()), GeoJsonOptions()
            .withCluster(true)
            .withClusterMaxZoom(SpotMarks.CLUSTER_MAX_ZOOM)
            .withClusterRadius(SpotMarks.CLUSTER_RADIUS_DP)))
        s.addSource(GeoJsonSource(SRC_ME))
        val z = SpotMarks.CLUSTER_SIZES
        s.addLayer(CircleLayer(L_CLUSTERS, SRC_SPOTS).withFilter(has("point_count")).withProperties(
            circleColor(Ui.ACCENT), circleStrokeColor(Ui.ON_ACCENT), circleStrokeWidth(2f),
            circleRadius(step(get("point_count"), literal(z[0].toFloat()), stop(10, z[1].toFloat()), stop(100, z[2].toFloat()))),
        ))
        s.addLayer(SymbolLayer(L_COUNT, SRC_SPOTS).withFilter(has("point_count")).withProperties(
            textField(get("point_count_abbreviated")), textFont(arrayOf(FONT)), textSize(13f), textColor(Ui.ON_ACCENT),
            textAllowOverlap(true), textIgnorePlacement(true),
        ))
        s.addLayer(SymbolLayer(L_SPOTS, SRC_SPOTS).withFilter(not(has("point_count"))).withProperties(
            iconImage(get("icon")), iconSize(get("size")), symbolSortKey(get("order")),
            iconAllowOverlap(true), iconIgnorePlacement(true),
        ))
        s.addLayer(SymbolLayer(L_ME, SRC_ME).withProperties(
            iconImage(get("icon")), iconRotate(get("bearing")),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconAllowOverlap(true), iconIgnorePlacement(true),
        ))
        pushSpots()
        pushMe()
    }

    // ---------------------------------------------------------------- data

    fun setSpots(list: List<MapSpot>) {
        spots = list
        pushSpots()
    }

    /** Your position (blue dot, or an arrow when [bearing] is known); NaN lat hides it. */
    fun setMe(lat: Double, lon: Double, bearing: Double = Double.NaN) {
        me = if (lat.isNaN()) null else doubleArrayOf(lat, lon, bearing)
        pushMe()
    }

    private fun pushSpots() {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_SPOTS) ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(spots.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                for ((k, v) in SpotMarks.properties(s)) when (v) {
                    is String -> addStringProperty(k, v)
                    is Number -> addNumberProperty(k, v)
                    is Boolean -> addBooleanProperty(k, v)
                }
            }
        }))
    }

    private fun pushMe() {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_ME) ?: return
        val m = me
        src.setGeoJson(FeatureCollection.fromFeatures(if (m == null) emptyList() else listOf(
            Feature.fromGeometry(Point.fromLngLat(m[1], m[0])).apply {
                addStringProperty("icon", if (m[2].isNaN()) ME_DOT else ME_ARROW)
                addNumberProperty("bearing", if (m[2].isNaN()) 0.0 else m[2])
            },
        )))
    }

    private fun tapped(m: MapLibreMap, at: LatLng): Boolean {
        val p = m.projection.toScreenLocation(at)
        val r = dp(18).toFloat()
        val hit = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), L_SPOTS, L_CLUSTERS).firstOrNull()
            ?: return false
        if (hit.hasProperty("point_count")) {
            // A bubble of several spots: zoom in on it until they come apart.
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(at, m.cameraPosition.zoom + 2))
            return true
        }
        val key = if (hit.hasProperty("key")) hit.getStringProperty("key") else return false
        val spot = spots.firstOrNull { it.key == key } ?: return false
        onSpot(spot)
        return true
    }

    // ---------------------------------------------------------------- camera

    /** Moves the camera; null keeps that part as it is. */
    fun moveTo(lat: Double, lon: Double, zoom: Double? = null, bearing: Double? = null, animateMs: Int = 0) {
        val m = map
        val base = m?.cameraPosition ?: pendingCamera
        val pos = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom ?: base?.zoom ?: DEFAULT_ZOOM)
            .bearing(bearing ?: base?.bearing ?: 0.0)
            .tilt(0.0)
            .build()
        when {
            m == null -> pendingCamera = pos
            animateMs > 0 -> m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), animateMs)
            else -> m.cameraPosition = pos
        }
    }

    fun zoomBy(delta: Double) {
        val m = map ?: return
        m.animateCamera(CameraUpdateFactory.zoomTo(m.cameraPosition.zoom + delta), 300)
    }

    fun northUp() {
        map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
    }

    /** Where the camera is now: lat, lon, zoom (null before the map is ready). */
    fun camera(): DoubleArray? = map?.cameraPosition?.let { c -> c.target?.let { doubleArrayOf(it.latitude, it.longitude, c.zoom) } }

    /** Fewer frames when nothing moves (e.g. parked) saves battery. */
    fun setMaxFps(fps: Int) {
        mapView?.setMaximumFps(fps)
    }

    // ---------------------------------------------------------------- lifecycle

    fun resume() {
        val mv = mapView ?: return
        if (!started) { mv.onStart(); started = true }
        if (!resumed) { mv.onResume(); resumed = true }
        // Last time there was no network: try the real map again.
        if (offlineStyle) { offlineStyle = false; style = null; map?.setStyle(Style.Builder().fromUri(STYLE_URL)) { styleReady(it) } }
    }

    /** Stops drawing (tab hidden, screen off, app in the background). */
    fun pause() {
        val mv = mapView ?: return
        if (resumed) { mv.onPause(); resumed = false }
        if (started) { mv.onStop(); started = false }
    }

    fun lowMemory() { mapView?.onLowMemory() }

    fun destroy() {
        pause()
        mapView?.onDestroy()
        mapView = null
        map = null
        style = null
    }

    // ---------------------------------------------------------------- icons

    private fun spotIcon(i: SpotIcon): Bitmap {
        val size = dp(26)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        val faded = i == SpotIcon.MUTED
        p.style = Paint.Style.FILL
        p.color = if (faded) (i.color and 0x00FFFFFF) or 0x99000000.toInt() else i.color
        c.drawCircle(r, r, r - dp(2), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(2).toFloat()
        p.color = if (faded) 0x99FFFFFF.toInt() else 0xFFFFFFFF.toInt()
        c.drawCircle(r, r, r - dp(2), p)
        if (i.mark.isNotEmpty()) {
            p.style = Paint.Style.FILL
            p.color = if (i == SpotIcon.UNSURE) 0xFF1B1300.toInt() else 0xFFFFFFFF.toInt()
            p.textSize = dp(15).toFloat()
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            c.drawText(i.mark, r, r - (p.descent() + p.ascent()) / 2, p)
        }
        return bmp
    }

    private fun meIcon(arrow: Boolean): Bitmap {
        val size = dp(if (arrow) 34 else 22)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        if (arrow) {
            // A navigation arrow pointing up (the map turns it to the heading).
            val path = Path().apply {
                moveTo(r, dp(2).toFloat()); lineTo(size - dp(5).toFloat(), size - dp(4).toFloat())
                lineTo(r, size * 0.7f); lineTo(dp(5).toFloat(), size - dp(4).toFloat()); close()
            }
            p.style = Paint.Style.FILL; p.color = Ui.BLUE
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = 0xFFFFFFFF.toInt()
            c.drawPath(path, p)
        } else {
            p.style = Paint.Style.FILL; p.color = Ui.BLUE
            c.drawCircle(r, r, r - dp(3), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(r, r, r - dp(3), p)
        }
        return bmp
    }

    companion object {
        /** OpenFreeMap's dark style (free, no key, no account), close to the app's dark theme. */
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
        const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
        const val CACHE_BYTES = 50L * 1024 * 1024
        const val DEFAULT_ZOOM = 15.0
        private const val FONT = "Noto Sans Regular"   // the only font the dark style's glyph server is asked for
        private const val SRC_SPOTS = "bb-spots"
        private const val SRC_ME = "bb-me"
        private const val L_CLUSTERS = "bb-clusters"
        private const val L_COUNT = "bb-cluster-count"
        private const val L_SPOTS = "bb-spot-icons"
        private const val L_ME = "bb-me"
        private const val ME_ARROW = "bb-me-arrow"
        private const val ME_DOT = "bb-me-dot"
        private const val OFFLINE_STYLE =
            """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#0E1116"}}]}"""

        @Volatile private var cacheSet = false

        /** MapLibre keeps tiles it has shown in an "ambient" cache; about 50 MB is plenty for the roads you drive. */
        private fun setCacheSizeOnce(app: Context) {
            if (cacheSet) return
            cacheSet = true
            OfflineManager.getInstance(app).setMaximumAmbientCacheSize(CACHE_BYTES, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) { cacheSet = false }
            })
        }
    }
}
