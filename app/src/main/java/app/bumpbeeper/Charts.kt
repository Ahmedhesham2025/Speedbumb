package app.bumpbeeper

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** The driving score as a ring that fills up to the score (out of 100). */
class ScoreRingView(ctx: Context) : View(ctx) {
    var score = -1
        set(v) { field = v; invalidate() }
    var caption = "score"
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12 * d; color = Ui.SURFACE2; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12 * d; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = Ui.TEXT }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Ui.DIM }
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        val pad = 10 * d
        box.set((width - size) / 2 + pad, (height - size) / 2 + pad, (width + size) / 2 - pad, (height + size) / 2 - pad)
        c.drawArc(box, 135f, 270f, false, track)
        if (score >= 0) {
            arc.color = Ui.scoreColor(score)
            c.drawArc(box, 135f, 270f * score / 100f, false, arc)
        }
        big.textSize = size * 0.28f
        small.textSize = size * 0.09f
        c.drawText(if (score >= 0) score.toString() else "–", width / 2f, height / 2f + big.textSize * 0.3f, big)
        c.drawText(caption, width / 2f, height / 2f + big.textSize * 0.3f + small.textSize * 1.6f, small)
    }
}

/** Scores of recent trips as a line with a dot per trip (in its score colour), oldest first, and the 75 ("good") line. */
class TrendChartView(ctx: Context) : View(ctx) {
    var scores: List<Int> = emptyList()
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.PRIMARY; strokeWidth = 2.5f * d; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.LINE; strokeWidth = d; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.DIM; textSize = 11 * d }
    private val path = Path()
    private val older = ctx.getString(R.string.trips_trend_older)
    private val latest = ctx.getString(R.string.trips_trend_latest)
    private val empty = ctx.getString(R.string.trips_trend_empty)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val pad = 8 * d
        val h = height.toFloat() - 16 * d
        // 40 at the bottom, 100 at the top: the scores that matter get the room.
        fun y(s: Int) = pad + (h - pad) * (1f - (s.coerceIn(40, 100) - 40) / 60f)
        val good = y(75)
        c.drawLine(0f, good, w, good, line)
        c.drawText("75", w - label.measureText("75"), good - 3 * d, label)
        val list = scores.filter { it >= 0 }
        if (list.isEmpty()) {
            c.drawText(empty, 0f, h / 2, label)
            return
        }
        val n = maxOf(list.size, 10)
        val slot = (w - 2 * pad) / (n - 1).coerceAtLeast(1)
        fun x(i: Int) = pad + (n - list.size + i) * slot
        path.reset()
        list.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(i), y(s)) else path.lineTo(x(i), y(s)) }
        c.drawPath(path, stroke)
        list.forEachIndexed { i, s ->
            val r = if (i == list.size - 1) 6 * d else 4.5f * d
            c.drawCircle(x(i), y(s), r + 2 * d, halo)
            dot.color = Ui.scoreColor(s)
            c.drawCircle(x(i), y(s), r, dot)
        }
        c.drawText(older, 0f, height - 2 * d, label)
        c.drawText(latest, w - label.measureText(latest), height - 2 * d, label)
    }
}

/** A thin horizontal bar 0–100 (for the score breakdown). */
class MeterView(ctx: Context) : View(ctx) {
    var value = 0
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE2 }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(r, h / 2, h / 2, bg)
        fg.color = Ui.scoreColor(value)
        r.set(0f, 0f, width * value.coerceIn(0, 100) / 100f, h)
        if (value > 0) c.drawRoundRect(r, h / 2, h / 2, fg)
    }
}
