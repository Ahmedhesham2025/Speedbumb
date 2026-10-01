package app.bumpbeeper

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private lateinit var tips: TextView
    private lateinit var trend: TrendChartView
    private lateinit var totals: List<TextView>
    private lateinit var list: LinearLayout

    override val view: View = build()

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        fun add(v: View, top: Int = 0, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
            col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply { topMargin = dp(top) })

        add(Ui.text(a, 22f, Ui.TEXT, bold = true, value = "Your driving"))

        // Score card.
        val card = Ui.card(a)
        ring = ScoreRingView(a).apply { caption = "out of 100" }
        card.addView(ring, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
        grade = Ui.text(a, 20f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        basis = Ui.text(a, 13f, Ui.DIM).apply { gravity = Gravity.CENTER }
        card.addView(grade)
        card.addView(basis)
        card.addView(Ui.divider(a))
        breakdown = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        card.addView(breakdown)
        card.addView(Ui.divider(a))
        tips = Ui.text(a, 14f, Ui.TEXT)
        card.addView(tips)
        add(card, 12)

        add(Ui.section(a, "Score per trip"))
        val tc = Ui.card(a)
        trend = TrendChartView(a)
        tc.addView(trend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))
        add(tc)

        add(Ui.section(a, "All time"))
        val t = listOf("Distance", "Driving time", "Trips", "Bumps found", "Potholes found", "Warnings given").map { Ui.tile(a, it) }
        totals = t.map { it.second }
        add(Ui.grid(a, t.map { it.first }, 3))

        add(Ui.section(a, "Trips"))
        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        add(list)
        add(Ui.row(a,
            Ui.button(a, "Share trips (CSV)") { Sharing.shareTripsCsv(a) },
            Ui.button(a, "How it's scored") { explain() },
        ), 12)

        return ScrollView(a).apply { addView(col) }
    }

    override fun onShow() {
        Thread {
            val db = BumpDb(a.applicationContext)
            val (trips, counts) = try { db.trips() to db.counts(Prefs.engineConfig(a)) } finally { db.close() }
            ui.post { show(trips, counts) }
        }.start()
    }

    private fun show(trips: List<BumpDb.TripRow>, counts: BumpDb.Counts) {
        // Overall: distance-weighted average of the last 20 scored trips.
        val scored = trips.filter { it.score >= 0 }.take(20)
        val km = scored.sumOf { it.drive.distanceM } / 1000
        val overall = if (scored.isEmpty()) -1 else (scored.sumOf { it.score * it.drive.distanceM } / scored.sumOf { it.drive.distanceM }).roundToInt()
        ring.score = overall
        grade.text = DrivingStats.grade(overall).let { if (overall < 0) "No score yet" else it }
        grade.setTextColor(Ui.scoreColor(overall))
        basis.text = if (scored.isEmpty()) "Drive at least 0.5 km with recording on to get a score."
            else String.format(Locale.US, "From your last %d trip%s (%.0f km)", scored.size, if (scored.size > 1) "s" else "", km)

        // Breakdown: combine the trips into one set of numbers.
        val sum = DrivingStats()
        for (tr in scored) {
            val d = tr.drive
            sum.movingS += d.movingS; sum.distanceM += d.distanceM; sum.speedingS += d.speedingS
            sum.speedingExcess += d.speedingExcess; sum.maxSpeedKmh = maxOf(sum.maxSpeedKmh, d.maxSpeedKmh)
            sum.harshBrakes += d.harshBrakes; sum.harshAccels += d.harshAccels; sum.harshCorners += d.harshCorners
            sum.swerves += d.swerves; sum.bumpsFast += d.bumpsFast; sum.phoneUse += d.phoneUse
        }
        breakdown.removeAllViews()
        for ((name, v) in sum.breakdown()) breakdown.addView(meterRow(name, if (scored.isEmpty()) -1 else v))
        tips.text = if (scored.isEmpty()) "Your score looks at speeding, harsh braking and acceleration, harsh cornering, swerving, " +
            "speed bumps taken fast, and handling the phone while driving."
            else DriveText.tips(sum).joinToString("\n") { "• $it" }

        trend.scores = trips.filter { it.score >= 0 }.take(20).map { it.score }.reversed()

        totals[0].text = Ui.km(trips.sumOf { it.drive.distanceM })
        totals[1].text = Ui.duration(trips.sumOf { it.durationS })
        totals[2].text = trips.size.toString()
        totals[3].text = counts.bumps.toString()
        totals[4].text = counts.potholes.toString()
        totals[5].text = trips.sumOf { it.beeps }.toString()

        list.removeAllViews()
        if (trips.isEmpty()) list.addView(Ui.text(a, 14f, Ui.DIM, value = "No trips yet."))
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
                        (if (ev == 0) "no harsh events" else "$ev event${if (ev > 1) "s" else ""}")))
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
            addView(Ui.text(a, 14f, Ui.TEXT, value = name), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(a, 14f, Ui.scoreColor(v), bold = true, value = if (v >= 0) v.toString() else "–"))
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
            "Distance" to Ui.km(d.distanceM),
            "Time" to Ui.duration(tr.durationS),
            "Average / top speed" to "${d.avgSpeedKmh.roundToInt()} / ${d.maxSpeedKmh.roundToInt()} km/h",
            "Over your limit" to String.format(Locale.US, "%.0f%% of the time", d.speedingShare * 100),
            "Harsh braking" to d.harshBrakes.toString(),
            "Harsh acceleration" to d.harshAccels.toString(),
            "Harsh cornering" to d.harshCorners.toString(),
            "Swerves" to d.swerves.toString(),
            "Speed bumps taken fast" to d.bumpsFast.toString(),
            "Phone handled while driving" to d.phoneUse.toString(),
            "Bumps hit · potholes · warnings" to "${tr.hits} · ${tr.potholes} · ${tr.beeps}",
        )
        for ((k, v) in facts) box.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            addView(Ui.text(a, 14f, Ui.DIM, value = k), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(a, 14f, Ui.TEXT, bold = true, value = v))
        })
        if (events.isNotEmpty()) {
            box.addView(Ui.section(a, "What happened"))
            val tf = SimpleDateFormat("HH:mm", Locale.US)
            for (e in events.take(30)) box.addView(Ui.text(a, 13f, Ui.TEXT,
                value = "${tf.format(Date(e.wallTime))}  ${DriveText.event(e.type, e.note)}" +
                    if (!e.speedKmh.isNaN()) " (${e.speedKmh.roundToInt()} km/h)" else ""))
        }
        box.addView(Ui.section(a, "Tips"))
        box.addView(Ui.text(a, 14f, Ui.TEXT, value = DriveText.tips(d).joinToString("\n") { "• $it" }))
        AlertDialog.Builder(a)
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Share") { _, _ -> Sharing.shareTrip(a, tr) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun explain() {
        AlertDialog.Builder(a)
            .setTitle("How the score works")
            .setMessage(
                "Every trip starts at 100. Points come off for:\n\n" +
                    "• Speeding: time above your limit (Settings → Driving score), more the further over.\n" +
                    "• Harsh braking (over ≈0.35 g) and harsh acceleration (over ≈0.3 g).\n" +
                    "• Harsh cornering (over ≈0.4 g sideways) and swerves (a sudden left-right).\n" +
                    "• Speed bumps from your map taken faster than 25 km/h.\n" +
                    "• Picking up the phone while moving.\n\n" +
                    "Events are counted per 10 km, so long trips aren't punished for being long; trips under 5 km count as 5 km. " +
                    "Trips under 0.5 km get no score. Your overall score is the distance-weighted average of your last 20 trips.\n\n" +
                    "90+ Excellent · 75+ Good · 60+ Fair · below 60 Needs work.\n\n" +
                    "Measured with the phone's sensors and GPS. The phone works offline and doesn't know real speed limits, " +
                    "so set your usual limit in Settings."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
