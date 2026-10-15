package app.bumpbeeper

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * A simple offline map: your bumps as dots around you (no streets: the app has no internet access).
 * Pins in the severity colours (strong ones bigger, with "!"), a dashed ring for a "maybe", hollow = muted. The little tick shows the travel direction.
 * Drag to move, +/- buttons (outside this view) to zoom, tap a dot to select it.
 */
class BumpMapView(ctx: Context) : View(ctx) {
    var bumps: List<Bump> = emptyList()
        set(v) { field = v; invalidate() }
    var selectedId = -1L
        set(v) { field = v; invalidate() }
    var onSelect: (Bump) -> Unit = {}
    private val cfg = EngineConfig()

    /** Map centre. */
    var centerLat = 0.0
    var centerLon = 0.0
    /** Where you are (blue dot), or NaN. */
    var meLat = Double.NaN
    var meLon = Double.NaN
    /** Metres from the centre to the top/bottom edge. */
    var radiusM = 1000.0
        set(v) { field = v.coerceIn(50.0, 50_000.0); invalidate() }

    private val density = resources.displayMetrics.density
    private val bg = Paint().apply { color = Ui.SURFACE2 }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE; strokeWidth = density; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Ui.DIM }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.DIM
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    /** The band edges [colorOf] judges by (not a setting). */
    private val bands = EngineConfig()

    /** The severity colours: mild, moderate, strong; the "maybe" grey (drawn as a dashed ring); muted grey. */
    fun colorOf(b: Bump): Int = when {
        b.isMuted(cfg) -> Ui.SEV_MUTED
        b.confidence(bands) == Confidence.SOFT -> Ui.SEV_MAYBE
        else -> Ui.sevColor(b.severity(bands))
    }

    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val bang = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Ui.font(ctx, 700)
        textSize = 12f * density
    }
    private val rings = context.getString(R.string.map_rings)

    private fun pxPerM(): Double = (height / 2.0) / radiusM
    private fun toX(lon: Double): Float =
        (width / 2.0 + (lon - centerLon) * 111_320.0 * cos(centerLat * PI / 180) * pxPerM()).toFloat()
    private fun toY(lat: Double): Float = (height / 2.0 - (lat - centerLat) * 110_540.0 * pxPerM()).toFloat()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 10f * density, 10f * density, bg)

        // Rings every "nice" distance, around the centre.
        val step = niceStep(radiusM / 2)
        var r = step
        while (r <= radiusM * 1.5) {
            canvas.drawCircle(w / 2, h / 2, (r * pxPerM()).toFloat(), grid)
            r += step
        }
        canvas.drawText(
            String.format(Locale.US, rings, if (step >= 1000) "${(step / 1000).toInt()} km" else "${step.toInt()} m"),
            8 * density, h - 8 * density, text,
        )

        val dot = 7f * density
        for (b in bumps) {
            val x = toX(b.lon); val y = toY(b.lat)
            if (x < -dot * 2 || x > w + dot * 2 || y < -dot * 2 || y > h + dot * 2) continue
            val c = colorOf(b)
            val strong = b.severity(bands) == Severity.STRONG && b.confidence(bands) == Confidence.FULL && !b.isMuted(cfg)
            val r = if (strong) dot * 1.25f else dot
            // Direction tick: the way you drive when it beeps.
            val a = b.heading * PI / 180
            canvas.drawLine(x, y, (x + sin(a) * r * 2.2).toFloat(), (y - cos(a) * r * 2.2).toFloat(), tick)
            if (b.id == selectedId) {
                fill.color = (c and 0x00FFFFFF) or 0x40000000
                canvas.drawCircle(x, y, r * 2.2f, fill)
            }
            when {
                b.isMuted(cfg) -> {
                    ring.color = c
                    canvas.drawCircle(x, y, r, ring)
                }
                b.confidence(bands) == Confidence.SOFT -> {
                    // A "maybe": a dashed ring on the surface colour, never a solid pin.
                    fill.color = Ui.SURFACE
                    canvas.drawCircle(x, y, r, fill)
                    dash.color = c
                    canvas.drawCircle(x, y, r, dash)
                }
                else -> {
                    fill.color = c
                    canvas.drawCircle(x, y, r, fill)
                    ring.color = Ui.SURFACE
                    canvas.drawCircle(x, y, r, ring)
                    if (strong) {
                        bang.color = Ui.SEV_STRONG_INK
                        canvas.drawText("!", x, y - (bang.descent() + bang.ascent()) / 2, bang)
                    }
                }
            }
        }

        if (!meLat.isNaN()) {
            val x = toX(meLon); val y = toY(meLat)
            fill.color = Ui.PRIMARY
            canvas.drawCircle(x, y, dot * 1.1f, fill)
            ring.color = Ui.SURFACE
            canvas.drawCircle(x, y, dot * 1.1f, ring)
        }
    }

    private fun niceStep(x: Double): Double {
        val p = 10.0.pow(kotlin.math.floor(kotlin.math.log10(x)))
        val m = x / p
        return p * when { m < 2 -> 1.0; m < 5 -> 2.0; else -> 5.0 }
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; dragged = false
                parent?.requestDisallowInterceptTouchEvent(true)   // don't scroll the page while moving the map
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) + abs(e.y - downY) > 10 * density) dragged = true
                if (dragged) {
                    val k = pxPerM()
                    centerLon -= (e.x - lastX) / k / (111_320.0 * cos(centerLat * PI / 180))
                    centerLat += (e.y - lastY) / k / 110_540.0
                    lastX = e.x; lastY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragged) {
                    val hit = bumps.minByOrNull { hypot((toX(it.lon) - e.x).toDouble(), (toY(it.lat) - e.y).toDouble()) }
                    if (hit != null && hypot((toX(hit.lon) - e.x).toDouble(), (toY(hit.lat) - e.y).toDouble()) < 24 * density) {
                        selectedId = hit.id
                        onSelect(hit)
                    }
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
