package app.bumpbeeper

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bumpbeeper.ui.SpeedLimitText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One trip, full screen inside the Trips tab (it was a dialog): the score ring and grade, the key facts, where every
 * point went (the engine's [ScoreBreakdown], one row per area, a green tick where nothing was lost), what happened,
 * tips, Share and "How it's scored". [onBack] returns to the list.
 */
class TripSummaryPage(private val a: MainActivity, private val onBack: () -> Unit) {
    private fun dp(v: Int) = Ui.dp(a, v)

    private val title = Ui.text(a, 18f, Ui.TEXT, bold = true)
    private val body = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Ui.Space.GUTTER), 0, dp(Ui.Space.GUTTER), dp(24))
    }
    private var trip: BumpDb.TripRow? = null

    val view: View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.BG)
        // App bar: back, the trip's date, share.
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(iconButton(R.drawable.ic_back, a.getString(R.string.trips_back)) { onBack() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            addView(iconButton(R.drawable.ic_share, a.getString(R.string.trips_share)) { trip?.let { Sharing.shareTrip(a, it) } },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
        addView(ScrollView(a).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(a).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Ui.DIM)
        scaleType = ImageView.ScaleType.CENTER
        background = Ui.rounded(a, 0, 24)
        contentDescription = desc
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    fun show(tr: BumpDb.TripRow, events: List<BumpEvent>, waiting: Boolean) {
        trip = tr
        val d = tr.drive
        title.text = SimpleDateFormat("EEE d MMM · HH:mm", Ui.locale(a)).format(Date(tr.startTs))
        body.removeAllViews()

        // Hero: ring, grade, then distance · time · average / top speed.
        val hero = Ui.card(a, 0)
        hero.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(ScoreRingView(a).apply { score = tr.score; caption = a.getString(R.string.trips_out_of_100) },
                LinearLayout.LayoutParams(dp(120), dp(120)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), 0, 0, 0)
                addView(Ui.text(a, Ui.Type.HEADLINE, Ui.scoreColor(tr.score), bold = true,
                    value = if (tr.score >= 0) Ui.grade(a, tr.score) else a.getString(R.string.trips_no_score)))
                SpeedLimitText.tripLine(a, d, waiting)?.let { addView(Ui.text(a, 13f, Ui.DIM, value = it).apply { setPadding(0, dp(6), 0, 0) }) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        hero.addView(facts(listOf(
            Ui.km(d.distanceM) to a.getString(R.string.trips_fact_distance),
            Ui.duration(tr.durationS) to a.getString(R.string.trips_fact_time),
            a.getString(R.string.trips_fact_speed_value, d.avgSpeedKmh.roundToInt(), d.maxSpeedKmh.roundToInt()) to a.getString(R.string.trips_fact_speed),
        )))
        body.addView(hero, full(4))

        // Where the points went.
        body.addView(Ui.section(a, a.getString(R.string.trips_section_breakdown)))
        val bd = tr.breakdown
        val card = Ui.card(a, 0).apply { setPadding(0, dp(6), 0, dp(6)) }
        if (bd == null) {
            card.addView(Ui.text(a, 14f, Ui.DIM, value = a.getString(R.string.trips_basis_none)).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) })
        } else {
            val most = bd.items.maxOf { it.points }.coerceAtLeast(1)
            for (item in bd.items) card.addView(row(item, bd, d, most))
        }
        body.addView(card)

        // Totals: bumps hit, strong, warnings.
        body.addView(Ui.card(a, 0).apply {
            addView(facts(listOf(
                tr.hits.toString() to a.getString(R.string.drive_tile_bumps),
                tr.potholes.toString() to a.getString(R.string.drive_tile_strong),   // strong bumps (the old column name)
                tr.beeps.toString() to a.getString(R.string.drive_tile_warnings),
            )))
        }, full(12))

        if (events.isNotEmpty()) {
            body.addView(Ui.section(a, a.getString(R.string.trips_section_happened)))
            val box = Ui.card(a)
            val tf = SimpleDateFormat("HH:mm", Ui.locale(a))
            for (e in events.take(30)) box.addView(Ui.text(a, 13f, Ui.TEXT,
                value = "${tf.format(Date(e.wallTime))}  ${DriveText.event(a, e.type, e.note)}" +
                    if (!e.speedKmh.isNaN()) a.getString(R.string.trips_event_speed, e.speedKmh.roundToInt()) else "").apply {
                setPadding(0, dp(3), 0, dp(3))
            })
            body.addView(box)
        }

        body.addView(Ui.section(a, a.getString(R.string.trips_section_tips)))
        val tips = Ui.card(a)
        for (t in DriveText.tips(a, d)) tips.addView(Ui.text(a, 14f, Ui.TEXT, value = t).apply {
            setCompoundDrawablesRelative(Ui.icon(a, R.drawable.ic_bulb, Ui.PRIMARY), null, null, null)
            compoundDrawablePadding = dp(12)
            setPadding(0, dp(6), 0, dp(6))
        })
        body.addView(tips)

        body.addView(Ui.button(a, a.getString(R.string.trips_how_scored), Ui.Style.QUIET) { explain(a) }, full(12))
    }

    /** Three numbers side by side with a caption under each. */
    private fun facts(items: List<Pair<String, String>>): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Ui.rounded(a, Ui.SURFACE2, Ui.Shape.CARD)
        setPadding(0, dp(12), 0, dp(12))
        for ((value, label) in items) addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(Ui.text(a, 17f, Ui.TEXT, bold = true, value = value).apply { gravity = Gravity.CENTER })
            addView(Ui.text(a, 11f, Ui.DIM, value = label).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** One area of the score: icon, name, detail, a bar for the points lost, and "−6" (or a green tick). */
    private fun row(item: ScoreBreakdown.Item, bd: ScoreBreakdown, d: DrivingStats, most: Int): View {
        val (icon, name) = when (item.category) {
            ScoreCategory.SPEEDING -> R.drawable.ic_speed to R.string.event_speeding
            ScoreCategory.HARSH_BRAKING -> R.drawable.ic_brake to R.string.trips_fact_braking
            ScoreCategory.HARSH_ACCEL -> R.drawable.ic_accel to R.string.trips_fact_accel
            ScoreCategory.CORNERING -> R.drawable.ic_turn to R.string.trips_fact_cornering
            ScoreCategory.SWERVES -> R.drawable.ic_swerve to R.string.trips_fact_swerves
            ScoreCategory.PHONE_USE -> R.drawable.ic_phone to R.string.trips_fact_phone
            ScoreCategory.BUMPS_FAST -> R.drawable.ic_bump to R.string.trips_fact_bumps_fast
        }
        val detail = if (item.category == ScoreCategory.SPEEDING) {
            val pct = a.getString(R.string.trips_fact_over_limit_value, String.format(Locale.US, "%.0f", bd.speedingPct))
            if (bd.maxOverKmh >= 1) pct + " · " + a.getString(R.string.trips_bd_max_over,
                a.getString(R.string.limits_max_over_value, String.format(Locale.US, "%.0f", bd.maxOverKmh))) else pct
        } else if (item.count == 0) a.getString(R.string.trips_bd_none) else a.getString(R.string.trips_bd_count, item.count)
        val text = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, 15f, Ui.TEXT, value = a.getString(name)).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
            addView(Ui.text(a, 12f, Ui.DIM, value = detail).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
            if (item.points > 0) addView(bar(item.points, most), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
                .apply { topMargin = dp(6) })
        }
        val pts: View = if (item.points > 0) Ui.text(a, 15f, Ui.ERR, bold = true, value = Ui.ltr("−${item.points}"))
            else ImageView(a).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(Ui.OK)
                contentDescription = a.getString(R.string.trips_bd_ok)
            }
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Ui.Space.ROW)
            setPadding(dp(16), dp(8), dp(20), dp(8))
            addView(ImageView(a).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(Ui.DIM)
                scaleType = ImageView.ScaleType.CENTER
                background = Ui.rounded(a, Ui.SURFACE2, 12)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14); marginEnd = dp(12) })
            addView(pts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** The points lost as a red bar, against the area that lost the most on this trip. */
    private fun bar(points: Int, most: Int): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Ui.rounded(a, Ui.SURFACE3, 3)
        addView(View(a).apply { background = Ui.rounded(a, Ui.ERR, 3) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, points.toFloat()))
        if (most > points) addView(View(a), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (most - points).toFloat()))
    }

    companion object {
        fun explain(a: MainActivity) {
            AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.trips_explain_title))
                .setMessage(a.getString(R.string.trips_explain_msg))
                .setPositiveButton(R.string.common_ok, null)
                .show()
        }
    }
}

/** A page that can go back inside itself (a trip's summary back to the list) before the app closes. */
interface BackHandler {
    /** True when it went back; false lets Android do the usual. */
    fun back(): Boolean
}
