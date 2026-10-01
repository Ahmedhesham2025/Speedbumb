package app.bumpbeeper

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import java.util.Locale

/**
 * Scrolling line of the vertical jolt over the last 30 s, with the trigger level as a dashed line.
 * Red dots = readings above the trigger. Use it to pick the right sensitivity for your car.
 */
class JoltGraphView(ctx: Context) : View(ctx) {
    var threshold = 3f

    private val density = resources.displayMetrics.density
    private val path = Path()
    private val bgPaint = Paint().apply { color = 0xFF1C1F24.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB74D.toInt()
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5252.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 6f * density
        val maxV = 8f   // m/s² at the top of the chart
        fun y(v: Float) = h - pad - (v.coerceAtMost(maxV) / maxV) * (h - 2 * pad)

        canvas.drawRoundRect(0f, 0f, w, h, 10f * density, 10f * density, bgPaint)
        val ty = y(threshold)
        canvas.drawLine(pad, ty, w - pad, ty, thresholdPaint)
        canvas.drawText(String.format(Locale.US, "trigger %.1f m/s²", threshold), pad + 4 * density, ty - 4 * density, textPaint)

        val data = LiveState.graphSnapshot()
        if (data.isEmpty()) {
            canvas.drawText("Moves while recording", pad + 4 * density, h - pad - 4 * density, textPaint)
            return
        }
        val n = LiveState.GRAPH_POINTS
        val step = (w - 2 * pad) / (n - 1)
        val x0 = pad + (n - data.size) * step
        path.reset()
        for (i in data.indices) {
            val x = x0 + i * step
            if (i == 0) path.moveTo(x, y(data[i])) else path.lineTo(x, y(data[i]))
        }
        canvas.drawPath(path, linePaint)
        for (i in data.indices) {
            if (data[i] >= threshold) canvas.drawCircle(x0 + i * step, y(data[i]), 3f * density, hitPaint)
        }
    }
}
