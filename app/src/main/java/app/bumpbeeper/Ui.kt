package app.bumpbeeper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * The app's look in one place: a dark theme (easy on the eyes in a car at night), cards, big touch targets.
 * Everything is built in code, no extra libraries.
 */
object Ui {
    const val BG = 0xFF0E1116.toInt()
    const val SURFACE = 0xFF181D24.toInt()
    const val SURFACE2 = 0xFF232A33.toInt()
    const val LINE = 0xFF2E3640.toInt()
    const val TEXT = 0xFFECEFF1.toInt()
    const val DIM = 0xFF9AA5B1.toInt()
    const val ACCENT = 0xFFFFB300.toInt()      // the app's amber
    const val ON_ACCENT = 0xFF1B1300.toInt()
    const val GREEN = 0xFF4CAF50.toInt()
    const val RED = 0xFFEF5350.toInt()
    const val ORANGE = 0xFFFFA726.toInt()
    const val BLUE = 0xFF42A5F5.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun scoreColor(score: Int): Int = when {
        score < 0 -> DIM
        score >= 90 -> GREEN
        score >= 75 -> 0xFF9CCC65.toInt()
        score >= 60 -> ORANGE
        else -> RED
    }

    fun rounded(ctx: Context, color: Int, radiusDp: Int = 16, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(ctx, radiusDp.toFloat())
            if (stroke != null) setStroke(dp(ctx, 1), stroke)
        }

    fun text(ctx: Context, sizeSp: Float, color: Int = TEXT, bold: Boolean = false, value: String = "") = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text = value
    }

    /** Small grey caps title above a group. */
    fun section(ctx: Context, title: String) = text(ctx, 12f, DIM, bold = true, value = title.uppercase(Locale.US)).apply {
        letterSpacing = 0.08f
        setPadding(dp(ctx, 4), dp(ctx, 20), 0, dp(ctx, 8))
    }

    fun card(ctx: Context, padDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, SURFACE, 18)
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
    }

    enum class Style { PRIMARY, SECONDARY, DANGER, QUIET }

    /** A big, rounded button with a touch ripple. */
    fun button(ctx: Context, label: String, style: Style = Style.SECONDARY, onClick: () -> Unit): TextView {
        val (bg, fg) = when (style) {
            Style.PRIMARY -> ACCENT to ON_ACCENT
            Style.SECONDARY -> SURFACE2 to TEXT
            Style.DANGER -> 0xFF3A1F22.toInt() to RED
            Style.QUIET -> 0x00000000 to ACCENT
        }
        return TextView(ctx).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 48)
            val h = dp(ctx, 16)
            setPadding(h, dp(ctx, 10), h, dp(ctx, 10))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(ctx, bg, 14), rounded(ctx, 0xFFFFFFFF.toInt(), 14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /** An extra-large, high-contrast button for tapping while the car moves (label mode). */
    fun bigButton(ctx: Context, label: String, bg: Int, fg: Int, onClick: (View) -> Unit): TextView = TextView(ctx).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(ctx, 76)
        setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), rounded(ctx, bg, 16), rounded(ctx, 0xFFFFFFFF.toInt(), 16))
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick(it) }
    }

    /** Lays views side by side with equal width. */
    fun row(ctx: Context, vararg views: View, gapDp: Int = 8): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(ctx, gapDp)
            })
        }
    }

    /** A number with a small label under it. Returns the tile and the number's TextView. */
    fun tile(ctx: Context, label: String): Pair<LinearLayout, TextView> {
        val value = text(ctx, 22f, TEXT, bold = true, value = "–")
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, SURFACE2, 14)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            addView(value)
            addView(text(ctx, 12f, DIM, value = label).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }
        return box to value
    }

    /** Grid of tiles, [cols] per row. */
    fun grid(ctx: Context, tiles: List<View>, cols: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        tiles.chunked(cols).forEachIndexed { r, chunk ->
            val padded = chunk + List(cols - chunk.size) { View(ctx) }
            addView(row(ctx, *padded.toTypedArray()), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { if (r > 0) topMargin = dp(ctx, 8) })
        }
    }

    /** A setting that is on or off, with an explanation under it. */
    fun toggle(ctx: Context, title: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val box = CheckBox(ctx).apply {
            text = title
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            buttonTintList = ColorStateList.valueOf(ACCENT)
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        if (hint == null) return box
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(box)
            addView(text(ctx, 13f, DIM, value = hint).apply { setPadding(dp(ctx, 32), 0, 0, dp(ctx, 6)) })
        }
    }

    /** A slider with its current value spelled out above it. */
    fun slider(ctx: Context, min: Int, max: Int, step: Int, current: Int, format: (Int) -> String, onChange: (Int) -> Unit): View {
        val title = text(ctx, 15f, TEXT, value = format(current))
        val bar = SeekBar(ctx).apply {
            this.max = (max - min) / step
            progress = ((current - min) / step).coerceIn(0, this.max)
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    title.text = format(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            addView(title)
            addView(bar)
        }
    }

    /** A thin divider line inside a card. */
    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
            topMargin = dp(ctx, 10); bottomMargin = dp(ctx, 10)
        }
    }

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h} h ${m} min" else "${m} min"
    }

    fun km(meters: Double): String = if (meters >= 10_000) String.format(Locale.US, "%.0f km", meters / 1000)
        else String.format(Locale.US, "%.1f km", meters / 1000)
}

/** Human words for driving events. */
object DriveText {
    fun label(type: String): String = when (type) {
        "harsh_brake" -> "Harsh braking"
        "harsh_accel" -> "Harsh acceleration"
        "harsh_corner" -> "Harsh cornering"
        "swerve" -> "Swerve"
        "speeding" -> "Speeding"
        "bump_fast" -> "Speed bump taken fast"
        "phone_use" -> "Phone handled while driving"
        else -> type
    }

    fun event(type: String, note: String): String = if (note.isEmpty()) label(type) else "${label(type)}: $note"

    // Same as above but looked up in strings.xml. (The service still calls the plain-English version.)
    fun label(c: Context, type: String): String = when (type) {
        "harsh_brake" -> c.getString(R.string.event_harsh_brake)
        "harsh_accel" -> c.getString(R.string.event_harsh_accel)
        "harsh_corner" -> c.getString(R.string.event_harsh_corner)
        "swerve" -> c.getString(R.string.event_swerve)
        "speeding" -> c.getString(R.string.event_speeding)
        "bump_fast" -> c.getString(R.string.event_bump_fast)
        "phone_use" -> c.getString(R.string.event_phone_use)
        else -> type
    }

    fun event(c: Context, type: String, note: String): String = if (note.isEmpty()) label(c, type) else "${label(c, type)}: $note"

    fun tips(c: Context, d: DrivingStats): List<String> {
        val out = ArrayList<String>()
        if (d.speedingShare > 0.05) out.add(c.getString(R.string.tip_speeding, String.format(Locale.US, "%.0f", d.speedingShare * 100)))
        if (d.harshBrakes > 0) out.add(c.getString(if (d.harshBrakes > 1) R.string.tip_brakes_many else R.string.tip_brakes_one, d.harshBrakes))
        if (d.harshAccels > 0) out.add(c.getString(if (d.harshAccels > 1) R.string.tip_starts_many else R.string.tip_starts_one, d.harshAccels))
        if (d.harshCorners + d.swerves > 0) out.add(c.getString(R.string.tip_curves))
        if (d.bumpsFast > 0) out.add(c.getString(R.string.tip_bumps_fast))
        if (d.phoneUse > 0) out.add(c.getString(R.string.tip_phone))
        if (out.isEmpty()) out.add(c.getString(R.string.tip_smooth))
        return out
    }
}
