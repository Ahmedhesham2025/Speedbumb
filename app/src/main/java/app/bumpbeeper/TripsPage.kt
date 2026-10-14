package app.bumpbeeper

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
class TripsPage(private val a: MainActivity) : Page {
    private fun dp(v: Int) = Ui.dp(a, v)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var ring: ScoreRingView
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

    override val view: View = build()

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        fun add(v: View, top: Int = 0, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
            col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply { topMargin = dp(top) })

        add(Ui.text(a, 22f, Ui.TEXT, bold = true, value = a.getString(R.string.trips_title)))

        // Score card.
        val card = Ui.card(a)
        ring = ScoreRingView(a).apply { caption = a.getString(R.string.trips_out_of_100) }
        card.addView(ring, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
        grade = Ui.text(a, 20f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        basis = Ui.text(a, 13f, Ui.DIM).apply { gravity = Gravity.CENTER }
        card.addView(grade)
        card.addView(basis)
        card.addView(Ui.divider(a))
        breakdown = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        card.addView(breakdown)
        limitsNote = Ui.text(a, 12f, Ui.DIM).apply { setPadding(0, dp(4), 0, 0) }
        card.addView(limitsNote)
        card.addView(Ui.divider(a))
        tips = Ui.text(a, 14f, Ui.TEXT)
        card.addView(tips)
        add(card, 12)

        add(Ui.section(a, a.getString(R.string.trips_section_trend)))
        val tc = Ui.card(a)
        trend = TrendChartView(a)
        tc.addView(trend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))
        add(tc)

        add(Ui.section(a, a.getString(R.string.trips_section_all_time)))
        val t = listOf(
            R.string.trips_tile_distance, R.string.trips_tile_time, R.string.trips_tile_trips,
            R.string.trips_tile_bumps, R.string.trips_tile_strong, R.string.trips_tile_warnings,
        ).map { Ui.tile(a, a.getString(it)) }
        totals = t.map { it.second }
        add(Ui.grid(a, t.map { it.first }, 3))

        add(Ui.section(a, a.getString(R.string.trips_section_trips)))
        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        add(list)
        add(Ui.row(a,
            Ui.button(a, a.getString(R.string.trips_share_csv)) { Sharing.shareTripsCsv(a) },
            Ui.button(a, a.getString(R.string.trips_how_scored)) { explain() },
        ), 12)

        return ScrollView(a).apply { addView(col) }
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
        ring.score = overall
        grade.text = DrivingStats.grade(overall).let { if (overall < 0) a.getString(R.string.trips_no_score) else it }
        grade.setTextColor(Ui.scoreColor(overall))
        basis.text = if (scored.isEmpty()) a.getString(R.string.trips_basis_none)
            else a.getString(if (scored.size > 1) R.string.trips_basis_many else R.string.trips_basis_one, scored.size, String.format(Locale.US, "%.0f", km))

        // Breakdown: combine the trips into one set of numbers.
        // Road limits count for the summary when they are known for most of these trips' distance.
        val sum = SpeedLimitText.combine(scored.map { it.drive })
        breakdown.removeAllViews()
        for ((name, v) in sum.breakdown()) breakdown.addView(meterRow(name, if (scored.isEmpty()) -1 else v))
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
        if (trips.isEmpty()) list.addView(Ui.text(a, 14f, Ui.DIM, value = a.getString(R.string.trips_none_yet)))
        val fmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.US)
        for (tr in trips.take(50)) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.rounded(a, Ui.SURFACE, 14)
                isClickable = true
                setOnClickListener { details(tr) }
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(a, 15f, Ui.TEXT, bold = true, value = fmt.format(Date(tr.startTs))))
                    val ev = tr.drive.let { it.harshBrakes + it.harshAccels + it.harshCorners + it.swerves + it.bumpsFast + it.phoneUse }
                    addView(Ui.text(a, 13f, Ui.DIM, value = "${Ui.km(tr.drive.distanceM)} · ${Ui.duration(tr.durationS)} · " +
                        (if (ev == 0) a.getString(R.string.trips_no_harsh) else a.getString(if (ev > 1) R.string.trips_events_many else R.string.trips_events_one, ev))))
                    SpeedLimitText.tripLine(a, tr.drive, tr.id in waiting)?.let { addView(Ui.text(a, 12f, Ui.DIM, value = it)) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.text(a, 20f, Ui.scoreColor(tr.score), bold = true, value = if (tr.score >= 0) tr.score.toString() else "–").apply {
                    gravity = Gravity.CENTER
                    background = Ui.rounded(a, Ui.SURFACE2, 12)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                })
            }
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
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

    private fun details(tr: BumpDb.TripRow) {
        Thread {
            val db = BumpDb(a.applicationContext)
            val events = try { db.tripEvents(tr.id) } finally { db.close() }
            ui.post { showDetails(tr, events) }
        }.start()
    }

    private fun showDetails(tr: BumpDb.TripRow, events: List<BumpEvent>) {
        val d = tr.drive
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        box.addView(ScoreRingView(a).apply { score = tr.score; caption = DrivingStats.grade(tr.score) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))
        val facts = listOf(
            a.getString(R.string.trips_fact_distance) to Ui.km(d.distanceM),
            a.getString(R.string.trips_fact_time) to Ui.duration(tr.durationS),
            a.getString(R.string.trips_fact_speed) to a.getString(R.string.trips_fact_speed_value, d.avgSpeedKmh.roundToInt(), d.maxSpeedKmh.roundToInt()),
            a.getString(R.string.trips_fact_over_limit) to a.getString(R.string.trips_fact_over_limit_value, String.format(Locale.US, "%.0f", d.speedingShare * 100)),
            a.getString(R.string.trips_fact_braking) to d.harshBrakes.toString(),
            a.getString(R.string.trips_fact_accel) to d.harshAccels.toString(),
            a.getString(R.string.trips_fact_cornering) to d.harshCorners.toString(),
            a.getString(R.string.trips_fact_swerves) to d.swerves.toString(),
            a.getString(R.string.trips_fact_bumps_fast) to d.bumpsFast.toString(),
            a.getString(R.string.trips_fact_phone) to d.phoneUse.toString(),
            a.getString(R.string.trips_fact_totals) to "${tr.hits} · ${tr.potholes} · ${tr.beeps}",
        ) + if (d.usesSpeedLimits) listOf(
            a.getString(R.string.limits_fact_bands) to SpeedLimitText.bands(d),
            a.getString(R.string.limits_fact_max_over) to SpeedLimitText.maxOver(a, d),
        ) else emptyList()
        for ((k, v) in facts) box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            addView(Ui.text(a, 14f, Ui.DIM, value = k), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(a, 14f, Ui.TEXT, bold = true, value = v))
        })
        SpeedLimitText.tripLine(a, d, tr.id in waiting)?.let {
            box.addView(Ui.text(a, 13f, Ui.DIM, value = it).apply { setPadding(0, dp(6), 0, 0) })
        }
        if (events.isNotEmpty()) {
            box.addView(Ui.section(a, a.getString(R.string.trips_section_happened)))
            val tf = SimpleDateFormat("HH:mm", Locale.US)
            for (e in events.take(30)) box.addView(Ui.text(a, 13f, Ui.TEXT,
                value = "${tf.format(Date(e.wallTime))}  ${DriveText.event(a, e.type, e.note)}" +
                    if (!e.speedKmh.isNaN()) a.getString(R.string.trips_event_speed, e.speedKmh.roundToInt()) else ""))
        }
        box.addView(Ui.section(a, a.getString(R.string.trips_section_tips)))
        box.addView(Ui.text(a, 14f, Ui.TEXT, value = DriveText.tips(a, d).joinToString("\n") { "• $it" }))
        AlertDialog.Builder(a)
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(R.string.trips_share) { _, _ -> Sharing.shareTrip(a, tr) }
            .setNegativeButton(R.string.common_close, null)
            .show()
    }

    private fun explain() {
        AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.trips_explain_title))
            .setMessage(a.getString(R.string.trips_explain_msg))
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }
}
