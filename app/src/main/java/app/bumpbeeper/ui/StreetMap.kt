package app.bumpbeeper.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
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
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
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
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/**
 * The street map: MapLibre Native with free OpenFreeMap tiles (Positron in the light theme, Dark in the dark one),
 * your spots as severity pins on the roads, and you as a dot or a heading arrow. Based on #104's StreetMap, moved onto
 * the redesign's tokens. [SpotMap] puts it over the old offline map and falls back to that one when this can't load
 * ([onFailed]: no network and nothing cached, or no map engine). The owner calls [resume] / [pause] / [destroy].
 */
class StreetMap(private val ctx: Context, private val interactive: Boolean) {
    private fun dp(v: Int) = Ui.dp(ctx, v)

    /** Interactive (Map tab): takes the touches first. Not interactive (Drive): never takes a touch. */
    val view: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (interactive && e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            return super.dispatchTouchEvent(e)
        }
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean = !interactive || super.onInterceptTouchEvent(e)
        override fun onTouchEvent(e: MotionEvent): Boolean = interactive && super.onTouchEvent(e)
    }

    /** A spot pin was tapped: its key ("m<id>"). */
    var onSpot: (String) -> Unit = {}
    /** The style loaded: streets are on screen. */
    var onReady: () -> Unit = {}
    /** The style could not load (offline and not cached): show the offline map instead. */
    var onFailed: () -> Unit = {}
    /** Someone moved the map with a finger (Drive: stop following until Recenter). */
    var onUserMove: () -> Unit = {}
    /** Space at the top of the map, px: the camera's centre (your puck) sits lower, so more road ahead shows. */
    var topPaddingPx = 0.0

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var features: List<Feature> = emptyList()
    private var me: DoubleArray? = null          // lat, lon, bearing (NaN = unknown)
    private var pendingCamera: CameraPosition? = null
    private var started = false
    private var resumed = false
    private var failed = false
    /** The map has drawn everything it was asked for (tiles, labels) and is not moving. */
    @Volatile var idle = false
        private set

    /** False when the map engine can't start here (unit tests, a phone without OpenGL ES 3). */
    val available: Boolean get() = mapView != null

    init {
        mapView = try { create() } catch (t: Throwable) { null }
        mapView?.let { view.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        // OpenStreetMap (ODbL), OpenMapTiles and OpenFreeMap ask for this line on the map.
        view.addView(Ui.text(ctx, 10f, Ui.DIM, value = ctx.getString(R.string.map_attribution)).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = Ui.rounded(ctx, (Ui.SURFACE and 0x00FFFFFF) or 0xCC000000.toInt(), 6)
            isClickable = true
            setOnClickListener {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COPYRIGHT_URL))) } catch (_: Exception) {}
            }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
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
            if (style == null && !failed) { failed = true; onFailed() }
        }
        mv.addOnDidBecomeIdleListener { idle = true }
        mv.addOnCameraWillChangeListener { idle = false }
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
            setCompassGravity(Gravity.TOP or Gravity.START)
            setCompassMargins(dp(8), dp(8), 0, 0)
            if (!interactive) setAllGesturesEnabled(false)
        }
        m.setMinZoomPreference(2.0)
        m.setMaxZoomPreference(19.0)
        pendingCamera?.let { m.cameraPosition = it; pendingCamera = null }
        if (interactive) m.addOnMapClickListener { tapped(m, it) }
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserMove()
        }
        m.setStyle(Style.Builder().fromUri(styleUrl())) { styleReady(it) }
    }

    private fun styleUrl() = if (Ui.dark) STYLE_DARK else STYLE_LIGHT

    private fun styleReady(s: Style) {
        style = s
        failed = false
        s.addSource(GeoJsonSource(SRC_SPOTS, FeatureCollection.fromFeatures(emptyList<Feature>()), GeoJsonOptions()
            .withCluster(true)
            .withClusterMaxZoom(SpotMarks.CLUSTER_MAX_ZOOM)
            .withClusterRadius(SpotMarks.CLUSTER_RADIUS_DP)))
        s.addSource(GeoJsonSource(SRC_ME))
        // The road just before a strong bump, tinted (under the pins).
        s.addSource(GeoJsonSource(SRC_AHEAD))
        s.addLayer(LineLayer(L_AHEAD, SRC_AHEAD).withProperties(
            lineColor(Ui.SEV_STRONG), lineWidth(9f), lineOpacity(0.55f), lineCap(Property.LINE_CAP_ROUND),
        ))
        val z = SpotMarks.CLUSTER_SIZES
        s.addLayer(CircleLayer(L_CLUSTERS, SRC_SPOTS).withFilter(has("point_count")).withProperties(
            circleColor(Ui.PRIMARY), circleStrokeColor(Ui.SURFACE), circleStrokeWidth(3f),
            circleRadius(step(get("point_count"), literal(z[0].toFloat()), stop(10, z[1].toFloat()), stop(100, z[2].toFloat()))),
        ))
        s.addLayer(SymbolLayer(L_COUNT, SRC_SPOTS).withFilter(has("point_count")).withProperties(
            textField(get("point_count_abbreviated")), textFont(arrayOf(FONT)), textSize(13f), textColor(Ui.ON_PRIMARY),
            textAllowOverlap(true), textIgnorePlacement(true),
        ))
        // Pins as circles in the band colour (drawn by the map engine itself, no images), a ring for "maybe" and
        // muted, and "!" / "?" on top.
        s.addLayer(CircleLayer(L_SPOTS, SRC_SPOTS).withFilter(not(has("point_count"))).withProperties(
            circleRadius(get("r")), circleColor(toColor(get("fill"))), circleStrokeColor(toColor(get("ring"))), circleStrokeWidth(2.5f),
        ))
        s.addLayer(SymbolLayer(L_MARKS, SRC_SPOTS).withFilter(not(has("point_count"))).withProperties(
            textField(get("mark")), textFont(arrayOf(FONT)), textSize(13f), textColor(toColor(get("ink"))),
            textAllowOverlap(true), textIgnorePlacement(true),
        ))
        // You: a blue puck with a white ring and a soft halo, drawn by the map engine (no images). Driving, the map
        // itself turns heading-up, so the puck needs no arrow.
        s.addLayer(CircleLayer(L_ME_HALO, SRC_ME).withProperties(
            circleRadius(24f), circleColor(Ui.PRIMARY), circleOpacity(0.18f),
        ))
        s.addLayer(CircleLayer(L_ME, SRC_ME).withProperties(
            circleRadius(10f), circleColor(Ui.PRIMARY), circleStrokeColor(0xFFFFFFFF.toInt()), circleStrokeWidth(3.5f),
        ))
        pushSpots()
        pushMe()
        pushAhead()
        onReady()
    }

    // ---------------------------------------------------------------- data

    /** The pins a spot can have (see [SpotMap.iconOf]). */
    enum class Icon(val id: String) { MILD("bb-mild"), MODERATE("bb-moderate"), STRONG("bb-strong"), MAYBE("bb-maybe"), MUTED("bb-muted") }

    /** Spots as (key, lat, lon, icon, selected); a selected one is drawn on top. */
    fun setSpots(list: List<SpotMap.Pin>) {
        features = list.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                addStringProperty("key", s.key)
                addStringProperty("icon", s.icon.id)
                val (fill, ring) = colors(s.icon)
                addStringProperty("fill", hex(fill))
                addStringProperty("ring", hex(ring))
                addStringProperty("ink", hex(if (s.icon == Icon.STRONG) Ui.SEV_STRONG_INK else Ui.SEV_MAYBE))
                addStringProperty("mark", when (s.icon) { Icon.STRONG -> "!"; Icon.MAYBE -> "?"; else -> "" })
                addNumberProperty("r", if (s.icon == Icon.STRONG) 11 else 9)
                addNumberProperty("order", if (s.selected) 10 else s.icon.ordinal)
            }
        }
        pushSpots()
    }

    /** Your position (dot, or an arrow when [bearing] is known); NaN lat hides it. */
    fun setMe(lat: Double, lon: Double, bearing: Double = Double.NaN) {
        me = if (lat.isNaN()) null else doubleArrayOf(lat, lon, bearing)
        pushMe()
    }

    private var ahead: DoubleArray? = null   // from lat, lon, to lat, lon

    /** Tints the road from you to a strong bump ahead; null clears it. */
    fun setAhead(from: Pair<Double, Double>?, to: Pair<Double, Double>?) {
        ahead = if (from == null || to == null) null else doubleArrayOf(from.first, from.second, to.first, to.second)
        pushAhead()
    }

    private fun pushAhead() {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_AHEAD) ?: return
        val a = ahead
        src.setGeoJson(FeatureCollection.fromFeatures(if (a == null) emptyList() else listOf(Feature.fromGeometry(
            LineString.fromLngLats(listOf(Point.fromLngLat(a[1], a[0]), Point.fromLngLat(a[3], a[2]))),
        ))))
    }

    /**
     * The name of the road under a point (your puck), read from the map tiles already on screen: no extra request,
     * nothing sent anywhere. Arabic names in Arabic when the tiles have them. Null when there is none.
     */
    fun roadNameAt(lat: Double, lon: Double, arabic: Boolean): String? {
        val m = map ?: return null
        if (style == null) return null
        val p = m.projection.toScreenLocation(LatLng(lat, lon))
        val r = dp(24).toFloat()
        val keys = if (arabic) listOf("name:ar", "name") else listOf("name:latin", "name:en", "name")
        return try {
            m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r))
                .filter { it.geometry() is LineString || it.geometry() is MultiLineString }
                .firstNotNullOfOrNull { f -> keys.firstNotNullOfOrNull { k -> if (f.hasProperty(k)) f.getStringProperty(k)?.takeIf { it.isNotBlank() } else null } }
        } catch (_: Exception) { null }
    }

    private fun pushSpots() {
        style?.getSourceAs<GeoJsonSource>(SRC_SPOTS)?.setGeoJson(FeatureCollection.fromFeatures(features))
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
        if (!hit.hasProperty("key")) return false
        onSpot(hit.getStringProperty("key"))
        return true
    }

    // ---------------------------------------------------------------- camera

    /** Moves the camera; [bearing] turns the map (heading-up while driving), 0 is north up. */
    fun moveTo(lat: Double, lon: Double, zoom: Double, bearing: Double = 0.0, animateMs: Int = 0) {
        val pos = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).bearing(bearing).tilt(0.0)
            .padding(0.0, topPaddingPx, 0.0, 0.0).build()
        val m = map
        when {
            m == null -> pendingCamera = pos
            animateMs > 0 -> m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), animateMs)
            else -> m.cameraPosition = pos
        }
    }

    /** Where the camera is: lat, lon, zoom (null before the map is ready). */
    fun camera(): DoubleArray? = map?.cameraPosition?.let { c -> c.target?.let { doubleArrayOf(it.latitude, it.longitude, c.zoom) } }

    /** What the street map has, for the screenshot test's check file: style, spots, you, camera, pins drawn. */
    internal fun debug(): String {
        val m = map
        val drawn = try {
            m?.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), L_SPOTS, L_CLUSTERS, L_ME)?.size
        } catch (_: Exception) { null }
        return "idle=$idle style=${style != null} failed=$failed spots=${features.size} me=${me?.toList()} " +
            "camera=${camera()?.toList()} drawn=$drawn"
    }

    /** Fewer frames when nothing moves saves battery. */
    fun setMaxFps(fps: Int) { mapView?.setMaximumFps(fps) }

    // ---------------------------------------------------------------- lifecycle

    fun resume() {
        val mv = mapView ?: return
        if (!started) { mv.onStart(); started = true }
        if (!resumed) { mv.onResume(); resumed = true }
        // Last time there was no network: try the streets again.
        if (failed && style == null) { failed = false; map?.setStyle(Style.Builder().fromUri(styleUrl())) { styleReady(it) } }
    }

    fun pause() {
        val mv = mapView ?: return
        if (resumed) { mv.onPause(); resumed = false }
        if (started) { mv.onStop(); started = false }
    }

    fun destroy() {
        pause()
        mapView?.onDestroy()
        mapView = null
        map = null
        style = null
    }

    // ---------------------------------------------------------------- pins

    /** Fill and ring of a pin: the band colour with a surface ring; "maybe" and muted the other way round. */
    private fun colors(i: Icon): Pair<Int, Int> = when (i) {
        Icon.MILD -> Ui.SEV_MILD to Ui.SURFACE
        Icon.MODERATE -> Ui.SEV_MOD to Ui.SURFACE
        Icon.STRONG -> Ui.SEV_STRONG to Ui.SURFACE
        Icon.MAYBE -> Ui.SURFACE to Ui.SEV_MAYBE
        Icon.MUTED -> Ui.SURFACE to Ui.SEV_MUTED
    }

    private fun hex(c: Int): String = String.format(java.util.Locale.US, "#%06X", c and 0xFFFFFF)

    /** The same pins as the offline map: severity fill with a surface ring, "!" on strong, a dashed "maybe". */
    private fun pin(i: Icon): Bitmap {
        val size = dp(if (i == Icon.STRONG) 30 else 24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f - dp(2)
        val color = when (i) {
            Icon.MILD -> Ui.SEV_MILD
            Icon.MODERATE -> Ui.SEV_MOD
            Icon.STRONG -> Ui.SEV_STRONG
            Icon.MAYBE -> Ui.SEV_MAYBE
            Icon.MUTED -> Ui.SEV_MUTED
        }
        p.style = Paint.Style.FILL
        p.color = if (i == Icon.MAYBE || i == Icon.MUTED) Ui.SURFACE else color
        c.drawCircle(size / 2f, size / 2f, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(2).toFloat()
        p.color = if (i == Icon.MAYBE || i == Icon.MUTED) color else Ui.SURFACE
        if (i == Icon.MAYBE) p.pathEffect = DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(3).toFloat()), 0f)
        c.drawCircle(size / 2f, size / 2f, r, p)
        if (i == Icon.STRONG || i == Icon.MAYBE) {
            p.pathEffect = null
            p.style = Paint.Style.FILL
            p.color = if (i == Icon.STRONG) Ui.SEV_STRONG_INK else color
            p.textSize = dp(14).toFloat()
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            c.drawText(if (i == Icon.STRONG) "!" else "?", size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        }
        return bmp
    }

    private fun meIcon(arrow: Boolean): Bitmap {
        val size = dp(if (arrow) 36 else 22)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        if (arrow) {
            // The car's puck: an arrow pointing up (the map turns it to the heading).
            val path = Path().apply {
                moveTo(r, dp(2).toFloat()); lineTo(size - dp(5).toFloat(), size - dp(4).toFloat())
                lineTo(r, size * 0.7f); lineTo(dp(5).toFloat(), size - dp(4).toFloat()); close()
            }
            p.style = Paint.Style.FILL; p.color = Ui.PRIMARY
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = 0xFFFFFFFF.toInt()
            c.drawPath(path, p)
        } else {
            p.style = Paint.Style.FILL; p.color = Ui.PRIMARY
            c.drawCircle(r, r, r - dp(3), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(3).toFloat(); p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(r, r, r - dp(3), p)
        }
        return bmp
    }

    companion object {
        /** OpenFreeMap (free, no key, no account): Positron for the light theme, Dark for the dark one. */
        const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
        const val CACHE_BYTES = 50L * 1024 * 1024
        private const val FONT = "Noto Sans Regular"
        private const val SRC_SPOTS = "bb-spots"
        private const val SRC_ME = "bb-me"
        private const val L_CLUSTERS = "bb-clusters"
        private const val L_COUNT = "bb-cluster-count"
        private const val L_SPOTS = "bb-spot-pins"
        private const val L_MARKS = "bb-spot-marks"
        private const val L_ME = "bb-me"
        private const val L_ME_HALO = "bb-me-halo"
        private const val SRC_AHEAD = "bb-ahead"
        private const val L_AHEAD = "bb-ahead"
        private const val ME_ARROW = "bb-me-arrow"
        private const val ME_DOT = "bb-me-dot"

        @Volatile private var cacheSet = false

        /** Tiles you have seen stay in MapLibre's ambient cache (about 50 MB), so the roads you drive work offline. */
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
