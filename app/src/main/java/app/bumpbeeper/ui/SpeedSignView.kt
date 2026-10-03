package app.bumpbeeper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/** A round speed-limit sign like the real ones: white disc, red ring, black number. Drawn in code, no images. */
class SpeedSignView(ctx: Context) : View(ctx) {
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt(); style = Paint.Style.STROKE }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    var value: String = LiveLimitText.UNKNOWN
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val ringW = size * 0.11f
        ring.strokeWidth = ringW
        canvas.drawCircle(cx, cy, size / 2f - 1f, disc)
        canvas.drawCircle(cx, cy, size / 2f - ringW / 2f - 1f, ring)
        num.textSize = size * LiveLimitText.textScale(value)
        // Centre the digits vertically on the disc.
        canvas.drawText(value, cx, cy - (num.descent() + num.ascent()) / 2f, num)
    }
}
