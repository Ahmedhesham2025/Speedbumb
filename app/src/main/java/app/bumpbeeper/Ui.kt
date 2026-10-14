package app.bumpbeeper

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * The app's look in one place: Material 3 style tokens in a light and a dark set (the system's dark theme picks one),
 * cards, big touch targets. Everything is built in code, no extra libraries. Map-first redesign: blue primary,
 * severity colours for bumps, the amber only for the launcher icon.
 */
object Ui {
    /**
     * Dark or light tokens. MainActivity sets it from the system setting before any view is built (Android recreates
     * the activity when that setting changes). Dark by default, as the app always was (services, unit tests).
     */
    @Volatile var dark = true

    private fun pick(light: Long, darkColor: Long): Int = (if (dark) darkColor else light).toInt()

    // ---------------------------------------------------------------- colour tokens (light, dark)
    val PRIMARY: Int get() = pick(0xFF1A6BE0, 0xFF8AB4FF)
    val ON_PRIMARY: Int get() = pick(0xFFFFFFFF, 0xFF06204F)
    val PRIMARY_CONT: Int get() = pick(0xFFDCE7FF, 0xFF1E3A6E)
    val ON_PRIMARY_CONT: Int get() = pick(0xFF0A2A63, 0xFFDCE7FF)
    val BG: Int get() = pick(0xFFF3F4F7, 0xFF0F1216)
    val SURFACE: Int get() = pick(0xFFFFFFFF, 0xFF1A1E24)
    val SURFACE2: Int get() = pick(0xFFF1F3F6, 0xFF232830)
    val SURFACE3: Int get() = pick(0xFFE6E9EE, 0xFF2C323B)
    /** Outlines and dividers. */
    val LINE: Int get() = pick(0xFFD5DAE1, 0xFF38404B)
    val TEXT: Int get() = pick(0xFF14181F, 0xFFECEFF3)
    /** Secondary text. */
    val DIM: Int get() = pick(0xFF5A6472, 0xFFA9B2BE)
    /** Tertiary text: captions, attributions. */
    val FAINT: Int get() = pick(0xFF6B7584, 0xFF8C96A4)
    val OK: Int get() = pick(0xFF137333, 0xFF5BD07A)
    val OK_CONT: Int get() = pick(0xFFE3F4E7, 0xFF173A22)
    val WARN: Int get() = pick(0xFF8A5300, 0xFFFFB84D)
    val WARN_CONT: Int get() = pick(0xFFFFF1D6, 0xFF3D2C0C)
    val ERR: Int get() = pick(0xFFC62828, 0xFFFF7A70)
    val ERR_CONT: Int get() = pick(0xFFFDE3E1, 0xFF43191A)
    /** Touch ripple over surfaces. */
    val RIPPLE: Int get() = pick(0x1F000000, 0x33FFFFFF)

    /** The app's amber: launcher icon only, so it never competes with the bump severity colours. */
    const val BRAND = 0xFFFFB300.toInt()

    // Older names, kept so every screen follows the tokens without touching each call.
    val ACCENT: Int get() = PRIMARY
    val ON_ACCENT: Int get() = ON_PRIMARY
    val GREEN: Int get() = OK
    val RED: Int get() = ERR
    val ORANGE: Int get() = WARN
    val BLUE: Int get() = PRIMARY

    // ---------------------------------------------------------------- severity (map pins, next-bump card, labels)
    val SEV_MILD: Int get() = pick(0xFFF2B200, 0xFFFFC93C)
    val SEV_MILD_INK: Int get() = pick(0xFF3B2B00, 0xFF2E2200)
    val SEV_MOD: Int get() = pick(0xFFF27A00, 0xFFFF9A3D)
    val SEV_MOD_INK: Int get() = pick(0xFF2A1400, 0xFF2A1400)
    val SEV_STRONG: Int get() = pick(0xFFD93025, 0xFFFF5A4E)
    val SEV_STRONG_INK: Int get() = pick(0xFFFFFFFF, 0xFF2A0503)
    /** A "maybe" spot: a dashed ring in this colour on a surface fill, never a solid pin. */
    val SEV_MAYBE: Int get() = pick(0xFF7A8699, 0xFF9AA5B1)
    val SEV_MUTED: Int get() = pick(0xFFB4BCC8, 0xFF5A6371)

    fun sevColor(s: Severity): Int = when (s) {
        Severity.MILD -> SEV_MILD
        Severity.MODERATE -> SEV_MOD
        Severity.STRONG -> SEV_STRONG
    }

    /** Text and icons drawn on [sevColor]. */
    fun sevInk(s: Severity): Int = when (s) {
        Severity.MILD -> SEV_MILD_INK
        Severity.MODERATE -> SEV_MOD_INK
        Severity.STRONG -> SEV_STRONG_INK
    }

    /** Beeps per warning, as Beeper plays them: mild 1, moderate 2, strong 3 (a "maybe" is one soft beep). */
    fun beepsOf(s: Severity): Int = when (s) {
        Severity.MILD -> 1
        Severity.MODERATE -> 2
        Severity.STRONG -> 3
    }

    // ---------------------------------------------------------------- type scale (sp), shapes and spacing (dp)
    object Type {
        const val SPEED = 56f
        const val NEXT = 34f
        const val NEXT_NEAR = 48f
        const val SCORE = 38f
        const val HEADLINE = 22f
        const val TITLE = 17f
        const val BODY = 14f
        const val LABEL = 13f
        const val CAPTION = 11f
    }

    object Shape {
        const val CARD = 20
        const val SHEET = 28
        const val CHIP = 16
        const val FAB = 16
        const val PILL = 28
        const val TILE = 14
    }

    object Space {
        const val GUTTER = 16
        const val OVERLAY = 12
        /** Minimum list row and touch target. */
        const val ROW = 56
        /** Anything used while the car moves. */
        const val DRIVE_TARGET = 56
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    /** Score colours follow the grades: Excellent and Good green, Fair amber, Needs work red. */
    fun scoreColor(score: Int): Int = when {
        score < 0 -> DIM
        score >= 75 -> OK
        score >= 60 -> WARN
        else -> ERR
    }

    /** The soft background behind a score badge, same bands as [scoreColor]. */
    fun scoreContainer(score: Int): Int = when {
        score < 0 -> SURFACE2
        score >= 75 -> OK_CONT
        score >= 60 -> WARN_CONT
        else -> ERR_CONT
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

    /** Group title above a card, Material 3 style: primary colour, sentence case. */
    fun section(ctx: Context, title: String) = text(ctx, Type.LABEL, PRIMARY, bold = true, value = title).apply {
        setPadding(dp(ctx, 4), dp(ctx, 20), dp(ctx, 4), dp(ctx, 8))
    }

    fun card(ctx: Context, padDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, SURFACE, Shape.CARD)
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
    }

    enum class Style { PRIMARY, SECONDARY, DANGER, QUIET }

    /** A big, rounded button with a touch ripple. */
    fun button(ctx: Context, label: String, style: Style = Style.SECONDARY, onClick: () -> Unit): TextView {
        val (bg, fg) = when (style) {
            Style.PRIMARY -> ACCENT to ON_ACCENT
            Style.SECONDARY -> SURFACE2 to TEXT
            Style.DANGER -> ERR_CONT to ERR
            Style.QUIET -> 0x00000000 to PRIMARY
        }
        return TextView(ctx).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 48)
            val h = dp(ctx, 20)
            setPadding(h, dp(ctx, 10), h, dp(ctx, 10))
            // Pill shape, as Material 3 buttons.
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), rounded(ctx, bg, Shape.PILL), rounded(ctx, 0xFFFFFFFF.toInt(), Shape.PILL))
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
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), rounded(ctx, bg, 24), rounded(ctx, 0xFFFFFFFF.toInt(), 24))
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick(it) }
    }

    /** A vector icon from res/drawable, tinted and sized (for chips and buttons). */
    fun icon(ctx: Context, res: Int, color: Int, sizeDp: Int = 20): Drawable? = ctx.getDrawable(res)?.mutate()?.apply {
        setTint(color)
        setBounds(0, 0, dp(ctx, sizeDp), dp(ctx, sizeDp))
    }

    private fun dotDrawable(ctx: Context, color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setBounds(0, 0, dp(ctx, 10), dp(ctx, 10))
    }

    /**
     * A chip: [floating] on the map (surface, small shadow) or flat inside a card. A leading icon or a colour dot
     * (severity, status), never both. The lead sits on the start side, so it mirrors in Arabic.
     */
    fun chip(ctx: Context, label: String, iconRes: Int = 0, dot: Int? = null, floating: Boolean = true): TextView = TextView(ctx).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL)
        setTextColor(TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(ctx, 36)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(ctx, 12), 0, dp(ctx, 14), 0)
        background = rounded(ctx, if (floating) SURFACE else SURFACE2, Shape.CHIP)
        if (floating) elevation = dpf(ctx, 3f)
        setChipLead(this, iconRes, dot)
    }

    /**
     * A chip someone taps: the touch target grows to 48 dp while the chip still looks 36 dp (the background is inset).
     */
    fun tappable(chip: TextView, onClick: () -> Unit): TextView = chip.apply {
        val ctx = context
        val inset = dp(ctx, 6)
        background = InsetDrawable(background, 0, inset, 0, inset)
        minHeight = dp(ctx, 48)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Changes a chip's leading icon or dot (status chips change colour as the state changes). */
    fun setChipLead(chip: TextView, iconRes: Int = 0, dot: Int? = null) {
        val ctx = chip.context
        val lead = if (iconRes != 0) icon(ctx, iconRes, DIM, 16) else dot?.let { dotDrawable(ctx, it) }
        chip.setCompoundDrawablesRelative(lead, null, null, null)
        chip.compoundDrawablePadding = dp(ctx, 6)
    }

    /** A square floating button on the map (my location, sound): 48 dp, an icon only, described for TalkBack. */
    fun fab(ctx: Context, iconRes: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(TEXT)
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), rounded(ctx, SURFACE, Shape.FAB), rounded(ctx, 0xFFFFFFFF.toInt(), Shape.FAB))
        elevation = dpf(ctx, 6f)
        contentDescription = desc
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A bottom sheet over the map: rounded top, surface, with the small grab handle. */
    fun sheet(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val r = dpf(ctx, Shape.SHEET.toFloat())
        background = GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        elevation = dpf(ctx, 8f)
        setPadding(dp(ctx, Space.GUTTER), dp(ctx, 10), dp(ctx, Space.GUTTER), dp(ctx, Space.GUTTER))
        addView(View(ctx).apply { background = rounded(ctx, LINE, 2) }, LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(ctx, 12)
        })
    }

    /**
     * The one primary action of a state (Start drive, Stop): a 64 dp pill with an icon, big enough to hit without
     * looking twice.
     */
    fun bigPill(ctx: Context, label: String, iconRes: Int, bg: Int, fg: Int, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(ctx, 64)
        setPadding(dp(ctx, 24), dp(ctx, 10), dp(ctx, 24), dp(ctx, 10))
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), rounded(ctx, bg, 32), rounded(ctx, 0xFFFFFFFF.toInt(), 32))
        setCompoundDrawablesRelative(icon(ctx, iconRes, fg, 24), null, null, null)
        compoundDrawablePadding = dp(ctx, 10)
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
    }

    /** A round score badge: the number on its soft score colour ([setScore]). */
    fun scoreBadge(ctx: Context, sizeDp: Int = 44): TextView = TextView(ctx).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        minWidth = dp(ctx, sizeDp)
        minHeight = dp(ctx, sizeDp)
    }

    fun setScore(badge: TextView, score: Int) {
        badge.text = if (score >= 0) score.toString() else "–"
        badge.setTextColor(scoreColor(score))
        badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(scoreContainer(score)) }
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
            background = rounded(ctx, SURFACE2, Shape.TILE)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            addView(value)
            // Two lines: Arabic labels are longer ("المطبّات اللي عدّيت عليها").
            addView(text(ctx, 12f, DIM, value = label).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
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
        // Looks like a Material 3 switch at the end of the row, but stays a CheckBox (tests and callers rely on it):
        // the box is hidden and a switch drawable sits at the end; its checked state comes from the CheckBox.
        val box = CheckBox(ctx).apply {
            text = title
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minHeight = dp(ctx, Space.ROW)
            buttonDrawable = null
            setPadding(0, 0, 0, 0)
            setCompoundDrawablesRelative(null, null, switchDrawable(ctx), null)
            compoundDrawablePadding = dp(ctx, 16)
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        if (hint == null) return box
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(box)
            addView(text(ctx, 13f, DIM, value = hint).apply { setPadding(0, 0, dp(ctx, 64), dp(ctx, 8)) })
        }
    }

    /** A 52 x 32 dp switch: the track and the thumb, on (primary, thumb at the end) or off (surface, outlined). */
    private fun switchDrawable(ctx: Context): Drawable {
        fun layer(on: Boolean): Drawable {
            val track = GradientDrawable().apply {
                cornerRadius = dpf(ctx, 16f)
                setColor(if (on) PRIMARY else SURFACE3)
                if (!on) setStroke(dp(ctx, 2), FAINT)
            }
            val thumbSize = dp(ctx, if (on) 24 else 16)
            val thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) ON_PRIMARY else FAINT)
                setSize(thumbSize, thumbSize)
            }
            return LayerDrawable(arrayOf(track, thumb)).apply {
                val inset = (dp(ctx, 32) - thumbSize) / 2
                setLayerGravity(1, (if (on) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL)
                setLayerInsetRelative(1, inset, 0, inset, 0)
                setLayerSize(1, thumbSize, thumbSize)
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), layer(true))
            addState(intArrayOf(), layer(false))
            setBounds(0, 0, dp(ctx, 52), dp(ctx, 32))
        }
    }

    /**
     * Material 3 segmented buttons: one choice of a few ([labels]), the chosen one on the primary container. Each
     * segment is at least 48 dp tall. Returns the row; [onSelect] gets the index.
     */
    fun segmented(ctx: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(ctx, 0, 24, LINE)
            setPadding(dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
        }
        val cells = labels.map { label ->
            TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                minHeight = dp(ctx, 48)
                maxLines = 2
                setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
                isClickable = true
                isFocusable = true
            }
        }
        fun show(sel: Int) = cells.forEachIndexed { i, c ->
            val on = i == sel
            c.background = if (on) rounded(ctx, PRIMARY_CONT, 24) else null
            c.setTextColor(if (on) ON_PRIMARY_CONT else TEXT)
            c.isSelected = on
        }
        cells.forEachIndexed { i, c ->
            c.setOnClickListener { show(i); onSelect(i) }
            row.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        show(selected)
        return row
    }

    /** A list row that opens a sub-page: icon, title, a line of status under it, chevron. 56 dp or more. */
    fun navRow(ctx: Context, iconRes: Int, title: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val sub = text(ctx, 13f, DIM)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, Space.ROW)
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), null, rounded(ctx, 0xFFFFFFFF.toInt(), 12))
            setOnClickListener { onClick() }
            addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(DIM)
            }, LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 16), 0, dp(ctx, 8), 0)
                addView(text(ctx, 16f, TEXT, value = title))
                addView(sub)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(DIM)
            }, LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)))
        }
        return row to sub
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

    /**
     * The app's resources in the language it runs in (MainActivity sets them), so "23 min" and "12.4 km" read
     * "23 دقيقة" and "12.4 كم" in Arabic. Null in unit tests and services: then the English units.
     */
    @Volatile var res: Resources? = null

    fun duration(seconds: Long): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val r = res
        return when {
            // Western digits in every language, as elsewhere in the app ("23 دقيقة", not Arabic-Indic digits).
            r != null && h > 0 -> r.getString(R.string.unit_h_min, h.toString(), m.toString())
            r != null -> r.getString(R.string.unit_min, m.toString())
            h > 0 -> "${h} h ${m} min"
            else -> "${m} min"
        }
    }

    /** Digits always Western (Locale.US), as everywhere in the app; only the unit follows the language. */
    fun km(meters: Double): String {
        val n = if (meters >= 10_000) String.format(Locale.US, "%.0f", meters / 1000) else String.format(Locale.US, "%.1f", meters / 1000)
        return res?.getString(R.string.unit_km, n) ?: "$n km"
    }
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
