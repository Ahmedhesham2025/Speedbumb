package app.bumpbeeper

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.bumpbeeper.ui.SyncChoice
import java.util.Locale

/** The dashboard: one big Start/Stop button, speed, this trip's numbers and score, and what just happened. */
class DrivePage(private val a: MainActivity) : Page {
    private fun dp(v: Int) = Ui.dp(a, v)

    private lateinit var status: TextView
    private lateinit var syncLine: TextView
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
            addView(Ui.text(a, 22f, Ui.TEXT, bold = true, value = a.getString(R.string.app_name)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(status)
        })
        // Shared map, one quiet line (never a popup here: this screen is used while driving).
        syncLine = Ui.text(a, 12f, Ui.DIM).apply {
            gravity = Gravity.END
            setPadding(0, dp(4), dp(4), 0)
            isClickable = true
            setOnClickListener { if (!LiveState.recording) a.select(MainActivity.TAB_SETTINGS) }
        }
        add(syncLine)

        // "New version available" banner; filled in by tick() once MainActivity's update check answers.
        updateCard = Ui.card(a).apply {
            background = Ui.rounded(a, Ui.SURFACE, 18, Ui.ACCENT)
            visibility = View.GONE
        }
        updateText = Ui.text(a, 16f, Ui.TEXT, bold = true)
        updateCard.addView(updateText)
        updateCard.addView(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.drive_update_hint)))
        updateCard.addView(Ui.row(a,
            Ui.button(a, a.getString(R.string.drive_download), Ui.Style.PRIMARY) { a.openUpdate() },
            Ui.button(a, a.getString(R.string.common_not_now), Ui.Style.QUIET) { a.dismissUpdate(); updateCard.visibility = View.GONE },
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
        add(Ui.section(a, a.getString(R.string.drive_section_this_trip)))
        val tiles = listOf(
            R.string.drive_tile_distance, R.string.drive_tile_time, R.string.drive_tile_score,
            R.string.drive_tile_warnings, R.string.drive_tile_bumps, R.string.drive_tile_potholes,
        ).map { Ui.tile(a, a.getString(it)) }
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
        muteBtn = Ui.button(a, a.getString(R.string.drive_mute_last), Ui.Style.SECONDARY) { a.muteLast() }
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
        meterCard.addView(Ui.text(a, 12f, Ui.DIM, value = a.getString(R.string.drive_meter_hint)))
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
        labelCard.addView(Ui.text(a, 12f, Ui.ACCENT, bold = true, value = a.getString(R.string.drive_label_header)).apply {
            setPadding(dp(4), 0, 0, 0)
        })
        labelCard.addView(Ui.text(a, 12f, Ui.DIM, value = a.getString(R.string.drive_label_driver_note)).apply {
            setPadding(dp(4), dp(2), 0, dp(8))
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
        labelLater = Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.drive_label_later)).apply {
            setPadding(dp(4), dp(4), 0, 0)
        }
        labelCard.addView(labelLater)
        return labelCard
    }

    private fun tapLabel(v: View, kind: String) {
        // A firm buzz, so the passenger knows the tap counted without looking.
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        if (!BumpService.label(a, kind)) Toast.makeText(a, a.getString(R.string.drive_label_not_saved), Toast.LENGTH_SHORT).show()
        updateLabelStatus()
    }

    private fun updateLabelStatus() {
        val n = LiveState.labelCount
        val last = LiveState.lastLabel
        val t = if (last.isEmpty()) a.getString(R.string.drive_label_none)
            else a.getString(if (n == 1) R.string.drive_label_last_one else R.string.drive_label_last_many, labelName(last), n)
        if (labelStatus.text.toString() != t) labelStatus.text = t
    }

    private fun labelName(kind: String): String = when (kind) {
        Labels.BUMP -> a.getString(R.string.drive_label_bump)
        Labels.POTHOLE_LEFT -> a.getString(R.string.drive_label_pothole_left)
        Labels.POTHOLE_RIGHT -> a.getString(R.string.drive_label_pothole_right)
        Labels.ROUGH -> a.getString(R.string.drive_label_rough)
        Labels.UNDO -> a.getString(R.string.drive_label_undo)
        else -> kind
    }

    private fun toggleMeter() {
        val on = !Prefs.sp(a).getBoolean(KEY_METER, false)
        Prefs.sp(a).edit().putBoolean(KEY_METER, on).apply()
        showMeter(on)
    }

    private fun showMeter(on: Boolean) {
        meterToggle.text = a.getString(if (on) R.string.drive_meter_open else R.string.drive_meter_closed)
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
        setupCard.addView(Ui.text(a, 12f, Ui.ACCENT, bold = true, value = a.getString(R.string.drive_setup_header)).apply { setPadding(0, 0, 0, dp(8)) })
        if (a.missingBasics().isNotEmpty()) item(a.getString(R.string.drive_setup_location_title), a.getString(R.string.drive_setup_location_why), a.getString(R.string.common_allow)) { a.requestBasics() }
        if (!a.batteryOk()) item(a.getString(R.string.drive_setup_background_title), a.getString(R.string.drive_setup_background_why), a.getString(R.string.common_allow)) { a.batterySettings() }
        if (!a.autoStartOn() && !Prefs.sp(a).getBoolean(KEY_HIDE_AUTO, false)) {
            item(a.getString(R.string.drive_setup_auto_title), a.getString(R.string.drive_setup_auto_why), a.getString(R.string.drive_setup_auto_action)) { a.setupAuto() }
            setupCard.addView(Ui.button(a, a.getString(R.string.common_not_now), Ui.Style.QUIET) {
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
        startBtn.text = a.getString(if (rec) R.string.drive_stop else R.string.drive_start)
        startBtn.setTextColor(0xFFFFFFFF.toInt())
        startBtn.contentDescription = a.getString(if (rec) R.string.drive_stop_desc else R.string.drive_start_desc)
    }

    override fun tick() {
        val rec = LiveState.recording
        if (wasRecording != rec) {
            styleStart(rec)
            wasRecording = rec
        }
        val gpsOk = rec && LiveState.lastFixAtMs > 0 && SystemClock.elapsedRealtime() - LiveState.lastFixAtMs < 5000
        status.text = when {
            !rec -> a.getString(R.string.drive_status_stopped)
            gpsOk -> a.getString(R.string.drive_status_recording)
            else -> a.getString(R.string.drive_status_waiting_gps)
        }
        status.setTextColor(when { !rec -> Ui.DIM; gpsOk -> Ui.GREEN; else -> Ui.ORANGE })
        val line = SyncChoice.driveLine(a)
        if (syncLine.text.toString() != line) syncLine.text = line
        syncLine.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE

        speed.text = if (gpsOk) a.getString(R.string.drive_speed_kmh, String.format(Locale.US, "%.0f", LiveState.speedKmh))
            else if (rec) a.getString(R.string.drive_speed_unknown) else ""
        speed.visibility = if (rec) View.VISIBLE else View.GONE
        gps.text = when {
            !rec -> if (a.autoStartOn()) a.getString(R.string.drive_gps_auto, Prefs.carName(a)) else a.getString(R.string.drive_gps_tap_start)
            gpsOk -> a.getString(R.string.drive_gps_accuracy, String.format(Locale.US, "%.0f", LiveState.accuracyM), detectionText())
            else -> a.getString(R.string.drive_gps_looking)
        }

        val last = LiveState.lastTripScore
        afterTrip.visibility = if (!rec && last >= 0) View.VISIBLE else View.GONE
        if (!rec && last >= 0) {
            afterTrip.text = a.getString(R.string.drive_last_trip, last, DrivingStats.grade(last))
            afterTrip.setTextColor(Ui.scoreColor(last))
        }

        tDist.text = if (rec) a.getString(R.string.drive_km, String.format(Locale.US, "%.1f", LiveState.tripKm)) else "–"
        tTime.text = if (rec) Ui.duration(LiveState.tripMovingS.toLong()) else "–"
        val s = LiveState.liveScore
        tScore.text = if (rec && s >= 0) s.toString() else "–"
        tScore.setTextColor(if (rec) Ui.scoreColor(s) else Ui.TEXT)
        tWarn.text = if (rec) LiveState.tripBeeps.toString() else "–"
        tBumps.text = if (rec) LiveState.tripHits.toString() else "–"
        val harsh = if (LiveState.tripHarshPotholes > 0) a.getString(R.string.drive_potholes_harsh, LiveState.tripHarshPotholes) else ""
        tHoles.text = if (rec) "${LiveState.tripPotholes}$harsh" else "–"

        lastEvent.text = LiveState.lastEvent.ifEmpty { a.getString(R.string.drive_nothing_yet) }
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

        // No distractions while driving: the banner waits until recording stops.
        val up = if (rec) null else a.pendingUpdate()
        if (up == null) {
            updateCard.visibility = View.GONE
        } else {
            if (updateShown != up.version) {
                updateText.text = a.getString(R.string.drive_update_available, up.version)
                updateShown = up.version
            }
            updateCard.visibility = View.VISIBLE
        }

        graph.threshold = Prefs.thresholdFor(Prefs.sensitivity(a)).toFloat()
        if (graph.visibility == View.VISIBLE) graph.invalidate()
    }

    private fun detectionText(): String = when {
        !LiveState.hasGyro -> a.getString(R.string.drive_detect_no_gyro)
        LiveState.forwardKnown -> a.getString(R.string.drive_detect_ready)
        else -> a.getString(R.string.drive_detect_learning)
    }

    companion object {
        private const val KEY_METER = "ui_show_meter"
        private const val KEY_HIDE_AUTO = "ui_hide_auto_tip"
    }
}
