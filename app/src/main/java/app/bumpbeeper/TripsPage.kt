package app.bumpbeeper

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bumpbeeper.sync.SpeedLimitSync
import app.bumpbeeper.ui.SpeedLimitText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** How you drive: overall score out of 100, what it's made of, the trend, totals, and every trip. */
class TripsPage(private val a: MainActivity) : Page, BackHandler {
    private fun dp(v: Int) = Ui.dp(a, v)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var grade: TextView
    private lateinit var basis: TextView
    private lateinit var breakdown: LinearLayout
    private lateinit var limitsNote: TextView
    private lateinit var tips: TextView
    private lateinit var trend: TrendChartView
    private lateinit var totals: List<TextView>
    private lateinit var list: LinearLayout
    /** Trips whose route is waiting on the phone for a speed-limit lookup. */
    private var waiting: Set<Long> = emptySet()

    private lateinit var listView: View
    private val summary = TripSummaryPage(a) { closeSummary() }

    override val view: View = FrameLayout(a).apply {
        listView = build()
        addView(listView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(summary.view.apply { visibility = View.GONE },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun closeSummary() {
        summary.view.visibility = View.GONE
        listView.visibility = View.VISIBLE
    }

    override fun back(): Boolean {
        if (summary.view.visibility != View.VISIBLE) return false
        closeSummary()
        return true
    }

    private lateinit var overallNum: TextView
    private lateinit var delta: TextView

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.Space.GUTTER), 0, dp(Ui.Space.GUTTER), dp(24))
        }
        fun add(v: View, top: Int = 0) = col.addView(v, full(top))

        // App bar: title and Share trips (CSV).
        add(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            addView(Ui.text(a, Ui.Type.HEADLINE, Ui.TEXT, bold = true, value = a.getString(R.string.trips_title)),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_share)
                imageTintList = ColorStateList.valueOf(Ui.DIM)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = a.getString(R.string.trips_share_csv)
                isClickable = true
                setOnClickListener { Sharing.shareTripsCsv(a) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })

        // Overall: the big number, the change against the trips before, the trend.
        val card = Ui.card(a)
        overallNum = Ui.text(a, 44f, Ui.TEXT, bold = true).apply { includeFontPadding = false }
        delta = Ui.text(a, 12f, bold = true).apply { setPadding(dp(10), dp(4), dp(10), dp(4)) }
        grade = Ui.text(a, 16f, Ui.TEXT, bold = true)
        basis = Ui.text(a, 13f, Ui.DIM)
        card.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(overallNum)
                addView(grade, full(4))
                addView(basis, full(2))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(delta, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        trend = TrendChartView(a)
        card.addView(trend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)).apply { topMargin = dp(12) })
        add(card, 4)

        add(Ui.section(a, a.getString(R.string.trips_section_trips)))
        list = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(a, Ui.SURFACE, Ui.Shape.CARD)
        }
        add(list)

        // What the overall score is made of, and tips.
        add(Ui.section(a, a.getString(R.string.trips_section_made_of)))
        val made = Ui.card(a)
        breakdown = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        made.addView(breakdown)
        limitsNote = Ui.text(a, 12f, Ui.DIM).apply { setPadding(0, dp(4), 0, 0) }
        made.addView(limitsNote)
        made.addView(Ui.divider(a))
        tips = Ui.text(a, 14f, Ui.TEXT)
        made.addView(tips)
        add(made)

        add(Ui.section(a, a.getString(R.string.trips_section_all_time)))
        val t = listOf(
            R.string.trips_tile_distance, R.string.trips_tile_time, R.string.trips_tile_trips,
            R.string.trips_tile_bumps, R.string.trips_tile_strong, R.string.trips_tile_warnings,
        ).map { Ui.tile(a, a.getString(it)) }
        totals = t.map { it.second }
        add(Ui.grid(a, t.map { it.first }, 3))
        add(Ui.button(a, a.getString(R.string.trips_how_scored), Ui.Style.QUIET) { TripSummaryPage.explain(a) }, 12)

        return ScrollView(a).apply {
            setBackgroundColor(Ui.BG)
            addView(col)
        }
    }

    /** One trip in the list: score badge, date, then distance · time · the main thing that happened. */
    private fun tripRow(tr: BumpDb.TripRow, fmt: SimpleDateFormat): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(16), dp(10), dp(12), dp(10))
        isClickable = true
        setOnClickListener { details(tr) }
        val badge = Ui.scoreBadge(a).also { Ui.setScore(it, tr.score) }
        addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(Ui.text(a, 15f, Ui.TEXT, bold = true, value = fmt.format(Date(tr.startTs))))
            val ev = tr.drive.let { it.harshBrakes + it.harshAccels + it.harshCorners + it.swerves + it.bumpsFast + it.phoneUse }
            addView(Ui.text(a, 13f, Ui.DIM, value = "${Ui.km(tr.drive.distanceM)} · ${Ui.duration(tr.durationS)} · " +
                (if (ev == 0) a.getString(R.string.trips_no_harsh) else a.getString(if (ev > 1) R.string.trips_events_many else R.string.trips_events_one, ev))))
            SpeedLimitText.tripLine(a, tr.drive, tr.id in waiting)?.let { addView(Ui.text(a, 12f, Ui.DIM, value = it)) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(Ui.DIM)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    override fun onShow() {
        Thread {
            val db = BumpDb(a.applicationContext)
            val (trips, counts) = try { db.trips() to db.counts(Prefs.engineConfig(a)) } finally { db.close() }
            val pending = if (SpeedLimitSync.allowed(a)) SpeedLimitSync.pendingFiles(a)
                // A route older than SpeedLimitSync.MAX_AGE_MS is never sent (deleted on the next run): no "Looking up…".
                .filter { System.currentTimeMillis() - it.lastModified() <= SpeedLimitSync.MAX_AGE_MS }
                .mapNotNull { it.name.substringBefore('.').toLongOrNull() }.toSet() else emptySet()
            ui.post { waiting = pending; show(trips, counts) }
        }.start()
    }

    private fun show(trips: List<BumpDb.TripRow>, counts: BumpDb.Counts) {
        // Overall: distance-weighted average of the last 20 scored trips.
        val scored = trips.filter { it.score >= 0 }.take(20)
        val km = scored.sumOf { it.drive.distanceM } / 1000
        val overall = if (scored.isEmpty()) -1 else (scored.sumOf { it.score * it.drive.distanceM } / scored.sumOf { it.drive.distanceM }).roundToInt()
        overallNum.text = if (overall >= 0) SpannableString(Ui.ltr("$overall /100")).apply {
            // After the isolate mark and the number: " /100" smaller and dimmer.
            setSpan(RelativeSizeSpan(0.4f), overall.toString().length + 1, length, 0)
            setSpan(ForegroundColorSpan(Ui.DIM), overall.toString().length + 1, length, 0)
        } else "–"
        // The change: the last 5 scored trips against the 5 before them.
        val recent = scored.take(5).map { it.score }
        val before = scored.drop(5).take(5).map { it.score }
        val change = if (recent.isEmpty() || before.isEmpty()) null else (recent.average() - before.average()).roundToInt()
        delta.visibility = if (change == null) View.GONE else View.VISIBLE
        if (change != null) {
            delta.text = Ui.ltr(when { change > 0 -> "▲ $change"; change < 0 -> "▼ ${-change}"; else -> "= 0" })
            delta.setTextColor(if (change >= 0) Ui.OK else Ui.ERR)
            delta.background = Ui.rounded(a, if (change >= 0) Ui.OK_CONT else Ui.ERR_CONT, 12)
        }
        grade.text = if (overall < 0) a.getString(R.string.trips_no_score) else Ui.grade(a, overall)
        grade.setTextColor(Ui.scoreColor(overall))
        basis.text = if (scored.isEmpty()) a.getString(R.string.trips_basis_none)
            else a.getString(if (scored.size > 1) R.string.trips_basis_many else R.string.trips_basis_one, scored.size, String.format(Locale.US, "%.0f", km))

        // Breakdown: combine the trips into one set of numbers.
        // Road limits count for the summary when they are known for most of these trips' distance.
        val sum = SpeedLimitText.combine(scored.map { it.drive })
        breakdown.removeAllViews()
        for ((name, v) in sum.breakdown()) breakdown.addView(meterRow(partName(name), if (scored.isEmpty()) -1 else v))
        val note = SpeedLimitText.summaryLine(a, sum)
        limitsNote.text = note ?: ""
        limitsNote.visibility = if (note == null) View.GONE else View.VISIBLE
        tips.text = if (scored.isEmpty()) a.getString(R.string.trips_tips_none)
            else DriveText.tips(a, sum).joinToString("\n") { "• $it" }

        trend.scores = trips.filter { it.score >= 0 }.take(20).map { it.score }.reversed()

        totals[0].text = Ui.km(trips.sumOf { it.drive.distanceM })
        totals[1].text = Ui.duration(trips.sumOf { it.durationS })
        totals[2].text = trips.size.toString()
        totals[3].text = counts.bumps.toString()
        totals[4].text = counts.potholes.toString()
        totals[5].text = trips.sumOf { it.beeps }.toString()

        list.removeAllViews()
        if (trips.isEmpty()) list.addView(Ui.text(a, 14f, Ui.DIM, value = a.getString(R.string.trips_none_yet)).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
        })
        val fmt = SimpleDateFormat("EEE d MMM · HH:mm", Ui.locale(a))
        for ((i, tr) in trips.take(50).withIndex()) {
            if (i > 0) list.addView(View(a).apply { setBackgroundColor(Ui.SURFACE3) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(74) })
            list.addView(tripRow(tr, fmt))
        }
    }

    /** The overall breakdown's parts come from the engine in English; shown in the app's language. */
    private fun partName(name: String): String = when (name) {
        "Speed" -> a.getString(R.string.trips_part_speed)
        "Braking & acceleration" -> a.getString(R.string.trips_part_braking)
        "Steering & swerving" -> a.getString(R.string.trips_part_steering)
        "Speed bumps" -> a.getString(R.string.trips_part_bumps)
        "Phone use" -> a.getString(R.string.trips_part_phone)
        else -> name
    }

    private fun meterRow(name: String, v: Int): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            // Aligned to the screen's start, not the text's: an English label in Arabic would sit against the number (#65).
            addView(Ui.text(a, 14f, Ui.TEXT, value = name).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(a, 14f, Ui.scoreColor(v), bold = true, value = if (v >= 0) v.toString() else "–"),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        })
        addView(MeterView(a).apply { value = maxOf(v, 0) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(4) })
    }

    /** Opens a trip's summary page (also used by the screenshot test). */
    internal fun details(tr: BumpDb.TripRow) {
        Thread {
            val db = BumpDb(a.applicationContext)
            val events = try { db.tripEvents(tr.id) } finally { db.close() }
            ui.post {
                summary.show(tr, events, tr.id in waiting)
                summary.view.visibility = View.VISIBLE
                listView.visibility = View.GONE
            }
        }.start()
    }
}
