package app.bumpbeeper

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** The dashboard: one big Start/Stop button, speed, this trip's numbers and score, and what just happened. */
class DrivePage(private val a: MainActivity) : Page {
    private fun dp(v: Int) = Ui.dp(a, v)

    private lateinit var status: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var startBtn: TextView
    private lateinit var speed: TextView
    private lateinit var gps: TextView
    private lateinit var tDist: TextView
    private lateinit var tTime: TextView
    private lateinit var tScore: TextView
    private lateinit var tWarn: TextView
    private lateinit var tBumps: TextView
    private lateinit var tHoles: TextView
    private lateinit var lastEvent: TextView
    private lateinit var driveEvent: TextView
    private lateinit var ignored: TextView
    private lateinit var muteBtn: View
    private lateinit var meterCard: LinearLayout
    private lateinit var meterToggle: TextView
    private lateinit var graph: JoltGraphView
    private lateinit var afterTrip: TextView
    private lateinit var updateCard: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var labelCard: LinearLayout
    private lateinit var labelStatus: TextView
    private lateinit var labelLater: TextView
    private var wasRecording: Boolean? = null
    private var updateShown: String? = null

    override val view: View = build()

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        fun add(v: View, top: Int = 0, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
            col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply { topMargin = dp(top) })

        // Header: app name + status chip.
        status = Ui.text(a, 13f, Ui.DIM, bold = true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = Ui.rounded(a, Ui.SURFACE2, 20)
        }
        add(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(a, 22f, Ui.TEXT, bold = true, value = "Bump Beeper"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(status)
        })

        // "New version available" banner; filled in by tick() once MainActivity's update check answers.
        updateCard = Ui.card(a).apply {
            background = Ui.rounded(a, Ui.SURFACE, 18, Ui.ACCENT)
            visibility = View.GONE
        }
        updateText = Ui.text(a, 16f, Ui.TEXT, bold = true)
        updateCard.addView(updateText)
        updateCard.addView(Ui.text(a, 13f, Ui.DIM, value = "Opens the release page on GitHub. Install it over this app; your map and trips stay."))
        updateCard.addView(Ui.row(a,
            Ui.button(a, "Download", Ui.Style.PRIMARY) { a.openUpdate() },
            Ui.button(a, "Not now", Ui.Style.QUIET) { a.dismissUpdate(); updateCard.visibility = View.GONE },
        ).apply { setPadding(0, dp(10), 0, 0) })
        add(updateCard, 12)

        setupCard = Ui.card(a)
        add(setupCard, 12)

        // Big round Start / Stop.
        startBtn = TextView(a).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isClickable = true
            setOnClickListener { a.toggleRecording() }
        }
        add(LinearLayout(a).apply {
            gravity = Gravity.CENTER
            addView(startBtn, LinearLayout.LayoutParams(dp(184), dp(184)))
        }, 16)

        speed = Ui.text(a, 56f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        add(speed, 12)
        gps = Ui.text(a, 13f, Ui.DIM).apply { gravity = Gravity.CENTER }
        add(gps)
        afterTrip = Ui.text(a, 15f, Ui.TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Ui.rounded(a, Ui.SURFACE, 14)
            isClickable = true
            setOnClickListener { a.select(MainActivity.TAB_TRIPS) }
        }
        add(afterTrip, 10)
        add(buildLabelPanel(), 12)

        // This trip.
        add(Ui.section(a, "This trip"))
        val tiles = listOf("Distance", "Time", "Driving score", "Warnings", "Bumps hit", "Potholes").map { Ui.tile(a, it) }
        tDist = tiles[0].second; tTime = tiles[1].second; tScore = tiles[2].second
        tWarn = tiles[3].second; tBumps = tiles[4].second; tHoles = tiles[5].second
        add(Ui.grid(a, tiles.map { it.first }, 3))

        // What just happened.
        val ev = Ui.card(a)
        lastEvent = Ui.text(a, 17f, Ui.TEXT, bold = true)
        driveEvent = Ui.text(a, 14f, Ui.ORANGE)
        ignored = Ui.text(a, 13f, Ui.DIM)
        ev.addView(lastEvent)
        ev.addView(driveEvent, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        ev.addView(ignored, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        add(ev, 12)
        muteBtn = Ui.button(a, "Mute last warning (false alarm)", Ui.Style.SECONDARY) { a.muteLast() }
        add(muteBtn, 8)

        // Jolt meter, folded away by default.
        meterCard = Ui.card(a)
        meterToggle = Ui.text(a, 15f, Ui.TEXT, bold = true).apply {
            isClickable = true
            setOnClickListener { toggleMeter() }
        }
        graph = JoltGraphView(a)
        meterCard.addView(meterToggle)
        meterCard.addView(graph, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)).apply { topMargin = dp(10) })
        meterCard.addView(Ui.text(a, 12f, Ui.DIM, value = "Bumps should poke above the dashed line; normal driving should stay below it. " +
            "If not, change Sensitivity in Settings."))
        add(meterCard, 12)
        showMeter(Prefs.sp(a).getBoolean(KEY_METER, false))

        return ScrollView(a).apply {
            isFillViewport = true
            addView(col)
        }
    }

    // ---------------------------------------------------------------- label mode

    /** Big buttons for the passenger who marks what the car just drove over (Settings → Road testing). */
    private fun buildLabelPanel(): View {
        labelCard = Ui.card(a, 12).apply {
            background = Ui.rounded(a, Ui.SURFACE, 18, Ui.LINE)
            visibility = View.GONE
        }
        labelCard.addView(Ui.text(a, 12f, Ui.ACCENT, bold = true, value = "LABEL WHAT YOU JUST DROVE OVER").apply {
            setPadding(dp(4), 0, 0, dp(8))
        })
        fun btn(kind: String, bg: Int, fg: Int) = Ui.bigButton(a, labelName(kind), bg, fg) { v -> tapLabel(v, kind) }
        fun gap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        labelCard.addView(Ui.row(a, btn(Labels.BUMP, Ui.ACCENT, Ui.ON_ACCENT), btn(Labels.ROUGH, 0xFFB0BEC5.toInt(), 0xFF101418.toInt())))
        labelCard.addView(Ui.row(a,
            btn(Labels.POTHOLE_LEFT, Ui.BLUE, 0xFF06121F.toInt()), btn(Labels.POTHOLE_RIGHT, Ui.BLUE, 0xFF06121F.toInt()),
        ), gap())
        labelCard.addView(btn(Labels.UNDO, 0xFF3A1F22.toInt(), Ui.RED), gap())
        labelStatus = Ui.text(a, 15f, Ui.TEXT, bold = true).apply { setPadding(dp(4), dp(10), 0, 0) }
        labelCard.addView(labelStatus)
        // Turned on mid-trip: the service only accepts labels from the next recording on.
        labelLater = Ui.text(a, 13f, Ui.DIM, value = "Label mode is on. It starts with the next recording: stop and start again.").apply {
            setPadding(dp(4), dp(4), 0, 0)
        }
        labelCard.addView(labelLater)
        return labelCard
    }

    private fun tapLabel(v: View, kind: String) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (!BumpService.label(a, kind)) Toast.makeText(a, "Label not saved: recording isn't in label mode", Toast.LENGTH_SHORT).show()
        updateLabelStatus()
    }

    private fun updateLabelStatus() {
        val n = LiveState.labelCount
        val last = LiveState.lastLabel
        labelStatus.text = if (last.isEmpty()) "No labels yet"
            else "Last: ${labelName(last)} · $n label${if (n == 1) "" else "s"}"
    }

    private fun labelName(kind: String): String = when (kind) {
        Labels.BUMP -> "Bump"
        Labels.POTHOLE_LEFT -> "Pothole left"
        Labels.POTHOLE_RIGHT -> "Pothole right"
        Labels.ROUGH -> "Rough road"
        Labels.UNDO -> "Undo"
        else -> kind
    }

    private fun toggleMeter() {
        val on = !Prefs.sp(a).getBoolean(KEY_METER, false)
        Prefs.sp(a).edit().putBoolean(KEY_METER, on).apply()
        showMeter(on)
    }

    private fun showMeter(on: Boolean) {
        meterToggle.text = if (on) "Jolt meter  ▾" else "Jolt meter  ▸"
        for (i in 1 until meterCard.childCount) meterCard.getChildAt(i).visibility = if (on) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- setup checklist

    override fun onShow() {
        setupCard.removeAllViews()
        var rows = 0
        fun item(title: String, why: String, action: String, onClick: () -> Unit) {
            if (rows > 0) setupCard.addView(Ui.divider(a))
            setupCard.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(a, 15f, Ui.TEXT, bold = true, value = title))
                    addView(Ui.text(a, 13f, Ui.DIM, value = why))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.button(a, action, Ui.Style.PRIMARY, onClick), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            })
            rows++
        }
        setupCard.addView(Ui.text(a, 12f, Ui.ACCENT, bold = true, value = "FINISH SETTING UP").apply { setPadding(0, 0, 0, dp(8)) })
        if (a.missingBasics().isNotEmpty()) item("Location & notifications", "Needed to know where bumps are and to keep running.", "Allow") { a.requestBasics() }
        if (!a.batteryOk()) item("Run in the background", "So recording doesn't stop when the screen is off.", "Allow") { a.batterySettings() }
        if (!a.autoStartOn() && !Prefs.sp(a).getBoolean(KEY_HIDE_AUTO, false)) {
            item("Auto start with your car", "Starts and stops by itself with the car's Bluetooth.", "Set up") { a.setupAuto() }
            setupCard.addView(Ui.button(a, "Not now", Ui.Style.QUIET) {
                Prefs.sp(a).edit().putBoolean(KEY_HIDE_AUTO, true).apply()
                onShow()
            })
        }
        setupCard.visibility = if (rows == 0) View.GONE else View.VISIBLE
        tick()
    }

    // ---------------------------------------------------------------- live

    private fun styleStart(rec: Boolean) {
        val color = if (rec) Ui.RED else Ui.GREEN
        val face = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(6), (color and 0x00FFFFFF) or 0x55000000)
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
        startBtn.background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), face, mask)
        startBtn.text = if (rec) "STOP" else "START"
        startBtn.setTextColor(0xFFFFFFFF.toInt())
        startBtn.contentDescription = if (rec) "Stop recording" else "Start recording"
    }

    override fun tick() {
        val rec = LiveState.recording
        if (wasRecording != rec) {
            styleStart(rec)
            wasRecording = rec
        }
        val gpsOk = rec && LiveState.lastFixAtMs > 0 && SystemClock.elapsedRealtime() - LiveState.lastFixAtMs < 5000
        status.text = when {
            !rec -> "● Stopped"
            gpsOk -> "● Recording"
            else -> "● Waiting for GPS"
        }
        status.setTextColor(when { !rec -> Ui.DIM; gpsOk -> Ui.GREEN; else -> Ui.ORANGE })

        speed.text = if (gpsOk) String.format(Locale.US, "%.0f", LiveState.speedKmh) + " km/h" else if (rec) "– km/h" else ""
        speed.visibility = if (rec) View.VISIBLE else View.GONE
        gps.text = when {
            !rec -> if (a.autoStartOn()) "Starts by itself when ${Prefs.carName(a)} connects" else "Tap START before you drive"
            gpsOk -> String.format(Locale.US, "GPS ±%.0f m · %s", LiveState.accuracyM, detectionText())
            else -> "Looking for GPS… (go outside or near a window)"
        }

        val last = LiveState.lastTripScore
        afterTrip.visibility = if (!rec && last >= 0) View.VISIBLE else View.GONE
        if (!rec && last >= 0) {
            afterTrip.text = "Last trip: $last/100 · ${DrivingStats.grade(last)}  ›  see Trips"
            afterTrip.setTextColor(Ui.scoreColor(last))
        }

        tDist.text = if (rec) String.format(Locale.US, "%.1f km", LiveState.tripKm) else "–"
        tTime.text = if (rec) Ui.duration(LiveState.tripMovingS.toLong()) else "–"
        val s = LiveState.liveScore
        tScore.text = if (rec && s >= 0) s.toString() else "–"
        tScore.setTextColor(if (rec) Ui.scoreColor(s) else Ui.TEXT)
        tWarn.text = if (rec) LiveState.tripBeeps.toString() else "–"
        tBumps.text = if (rec) LiveState.tripHits.toString() else "–"
        val harsh = if (LiveState.tripHarshPotholes > 0) " (${LiveState.tripHarshPotholes} harsh)" else ""
        tHoles.text = if (rec) "${LiveState.tripPotholes}$harsh" else "–"

        lastEvent.text = LiveState.lastEvent.ifEmpty { "Nothing yet" }
        driveEvent.text = LiveState.lastDriveEvent
        driveEvent.visibility = if (LiveState.lastDriveEvent.isEmpty()) View.GONE else View.VISIBLE
        ignored.text = LiveState.lastIgnored
        ignored.visibility = if (LiveState.lastIgnored.isEmpty()) View.GONE else View.VISIBLE
        muteBtn.visibility = if (rec) View.VISIBLE else View.GONE

        val labelsLive = rec && LiveState.labelMode
        val labelsPending = rec && !LiveState.labelMode && Prefs.labelMode(a)
        labelCard.visibility = if (labelsLive || labelsPending) View.VISIBLE else View.GONE
        for (i in 1 until labelCard.childCount - 1) labelCard.getChildAt(i).visibility = if (labelsLive) View.VISIBLE else View.GONE
        labelLater.visibility = if (labelsPending) View.VISIBLE else View.GONE
        if (labelsLive) updateLabelStatus()

        val up = a.pendingUpdate()
        if (up == null) {
            updateCard.visibility = View.GONE
        } else {
            if (updateShown != up.version) {
                updateText.text = "New version ${up.version} available"
                updateShown = up.version
            }
            updateCard.visibility = View.VISIBLE
        }

        graph.threshold = Prefs.thresholdFor(Prefs.sensitivity(a)).toFloat()
        if (graph.visibility == View.VISIBLE) graph.invalidate()
    }

    private fun detectionText(): String = when {
        !LiveState.hasGyro -> "pothole sides: no gyroscope"
        LiveState.forwardKnown -> "pothole detection ready"
        else -> "learning (speed up / brake once)"
    }

    companion object {
        private const val KEY_METER = "ui_show_meter"
        private const val KEY_HIDE_AUTO = "ui_hide_auto_tip"
    }
}
