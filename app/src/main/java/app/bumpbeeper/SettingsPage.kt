package app.bumpbeeper

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import app.bumpbeeper.auto.AutoDetect
import app.bumpbeeper.sync.SpeedLimitSync
import app.bumpbeeper.sync.Sync
import app.bumpbeeper.ui.AutoDetectText
import app.bumpbeeper.ui.SpeedLimitText
import app.bumpbeeper.ui.SyncChoice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All settings, grouped. Every change takes effect right away, even during a trip. */
class SettingsPage(private val a: MainActivity) : Page {
    private fun dp(v: Int) = Ui.dp(a, v)
    private val ui = Handler(Looper.getMainLooper())
    private val sp = Prefs.sp(a)
    private var voice: Voice? = null

    private lateinit var autoText: TextView
    private lateinit var autoBtn: TextView
    private lateinit var traceInfo: TextView
    private var detectBox: CheckBox? = null
    private lateinit var detectStatus: TextView
    private lateinit var detectFix: TextView
    /** True while the screen itself moves the auto-detect switch. */
    private var settingDetect = false
    private var debugBox: CheckBox? = null
    private var limitsBox: CheckBox? = null
    private lateinit var limitsStatus: TextView
    /** True while the screen itself moves the speed-limit switch (so it isn't taken as the user's choice). */
    private var settingLimits = false
    private lateinit var syncGroup: RadioGroup
    private lateinit var syncLast: TextView
    private lateinit var syncSpots: TextView
    private lateinit var syncPending: TextView
    private lateinit var syncError: TextView
    /** True while the screen itself moves the radio (so it isn't taken as the user's choice). */
    private var settingRadio = false

    override val view: View = build()

    private var forgetDialog: AlertDialog? = null
    private var limitsDialog: AlertDialog? = null

    fun release() {
        voice?.shutdown()
        forgetDialog?.dismiss()
        forgetDialog = null
        limitsDialog?.dismiss()
        limitsDialog = null
    }

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        fun add(v: View, top: Int = 0) =
            col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        fun card(title: String, vararg items: View) {
            add(Ui.section(a, title))
            val c = Ui.card(a)
            items.forEach { c.addView(it) }
            add(c)
        }
        fun hint(s: String) = Ui.text(a, 13f, Ui.DIM, value = s).apply { setPadding(0, dp(2), 0, dp(6)) }

        add(Ui.text(a, 22f, Ui.TEXT, bold = true, value = a.getString(R.string.settings_title)))

        card(a.getString(R.string.settings_section_warnings),
            Ui.slider(a, 4, 12, 1, Prefs.leadSeconds(a), { a.getString(R.string.settings_warn_before, it) }) { sp.edit().putInt(Prefs.LEAD_SECONDS, it).apply() },
            Ui.slider(a, 0, 40, 5, Prefs.quietBelowKmh(a), {
                if (it == 0) a.getString(R.string.settings_always_warn) else a.getString(R.string.settings_quiet_below, it)
            }) { sp.edit().putInt(Prefs.QUIET_BELOW_KMH, it).apply() },
            Ui.toggle(a, a.getString(R.string.settings_loud_title), a.getString(R.string.settings_loud_desc), Prefs.loud(a)) {
                sp.edit().putBoolean(Prefs.LOUD, it).apply()
            },
            Ui.toggle(a, a.getString(R.string.settings_click_new), null, Prefs.clickOnNew(a)) { sp.edit().putBoolean(Prefs.CLICK_ON_NEW, it).apply() },
            Ui.row(a, Ui.button(a, a.getString(R.string.settings_test_beep)) { Beeper(a).beep(2) }, Ui.button(a, a.getString(R.string.settings_test_voice)) { testVoice() }),
        )

        val lang = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("en" to "English", "ar" to "العربية (مصري)").forEach { (code, name) ->
            val rb = RadioButton(a).apply {
                text = name; tag = code; id = View.generateViewId()
                setTextColor(Ui.TEXT); buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            }
            lang.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (code == Prefs.voiceLang(a)) rb.isChecked = true
        }
        lang.setOnCheckedChangeListener { g, id -> sp.edit().putString(Prefs.VOICE_LANG, g.findViewById<RadioButton>(id)?.tag as? String ?: "en").apply() }
        card(a.getString(R.string.settings_section_potholes),
            hint(a.getString(R.string.settings_potholes_hint)),
            Ui.toggle(a, a.getString(R.string.settings_potholes_voice), null, Prefs.warnPotholes(a)) { sp.edit().putBoolean(Prefs.WARN_POTHOLES, it).apply() },
            Ui.slider(a, 4, 10, 1, Prefs.harshMs2(a), {
                a.getString(R.string.settings_harsh, it, a.getString(when { it <= 5 -> R.string.settings_harsh_most; it <= 7 -> R.string.settings_harsh_feel; else -> R.string.settings_harsh_worst }))
            }) { sp.edit().putInt(Prefs.HARSH_MS2, it).apply() },
            Ui.text(a, 15f, Ui.TEXT, value = a.getString(R.string.settings_voice_language)),
            lang,
        )

        card(a.getString(R.string.settings_section_score),
            Ui.slider(a, 50, 140, 10, Prefs.speedLimit(a), { a.getString(R.string.settings_speeding_above, it) }) { sp.edit().putInt(Prefs.SPEED_LIMIT, it).apply() },
            hint(a.getString(R.string.settings_speed_hint)),
            Ui.toggle(a, a.getString(R.string.limits_switch), a.getString(R.string.limits_switch_hint), Prefs.speedLimits(a)) { on ->
                if (settingLimits) return@toggle
                if (on) { showLimitsSwitch(false); askLimits() }   // only the consent dialog turns it on
                else { SpeedLimitSync.setEnabled(a, false); showLimitsSwitch(false) }
            }.also { limitsBox = ((it as? ViewGroup)?.getChildAt(0) ?: it) as? CheckBox },
            Ui.text(a, 13f, Ui.ORANGE).also { limitsStatus = it; it.setPadding(dp(32), 0, 0, dp(6)) },
        )

        val sens = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(R.string.settings_sens_low, R.string.settings_sens_normal, R.string.settings_sens_high).forEachIndexed { i, nameRes ->
            val rb = RadioButton(a).apply {
                text = a.getString(nameRes); tag = i; id = View.generateViewId()
                setTextColor(Ui.TEXT); buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            }
            sens.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i == Prefs.sensitivity(a)) rb.isChecked = true
        }
        sens.setOnCheckedChangeListener { g, id -> sp.edit().putInt(Prefs.SENSITIVITY, g.findViewById<RadioButton>(id)?.tag as? Int ?: 1).apply() }
        card(a.getString(R.string.settings_section_detection),
            sens,
            hint(a.getString(R.string.settings_detection_hint)),
            Ui.slider(a, 30, 80, 5, Prefs.maxBumpKmh(a), { a.getString(R.string.settings_max_bump, it) }) {
                sp.edit().putInt(Prefs.MAX_BUMP_KMH, it).apply()
            },
        )

        autoText = Ui.text(a, 15f, Ui.TEXT)
        autoBtn = Ui.button(a, "", Ui.Style.PRIMARY) { if (a.autoStartOn()) a.disableAuto() else a.setupAuto() }
        detectStatus = Ui.text(a, 14f, Ui.DIM).apply { setPadding(dp(32), 0, 0, dp(6)) }
        detectFix = Ui.button(a, a.getString(R.string.auto_fix), Ui.Style.PRIMARY) { a.fixAutoDetect() }
        card(a.getString(R.string.settings_section_auto),
            Ui.toggle(a, a.getString(R.string.auto_detect_switch), a.getString(R.string.auto_detect_hint), AutoDetect.enabled(a)) { on ->
                if (settingDetect) return@toggle
                if (on) { showDetect(false); a.turnOnAutoDetect { showDetect() } }   // only "Continue" turns it on
                else { AutoDetect.setEnabled(a, false); showDetect() }
            }.also { detectBox = ((it as? ViewGroup)?.getChildAt(0) ?: it) as? CheckBox },
            detectStatus,
            detectFix,
            Ui.slider(a, 0, 30, 1, Prefs.autoStopMinutes(a), { AutoDetectText.autoStop(a, it) }) {
                sp.edit().putInt(Prefs.AUTO_STOP_MIN, it).apply()
            },
            hint(a.getString(R.string.auto_stop_hint)),
            Ui.divider(a),
            autoText,
            hint(a.getString(R.string.settings_auto_hint)),
            autoBtn,
            Ui.button(a, a.getString(R.string.settings_battery)) { a.batterySettings() }.also {
                (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            },
        )

        buildSharedMap { title, items -> card(title, *items) }

        card(a.getString(R.string.settings_section_data),
            Ui.row(a, Ui.button(a, a.getString(R.string.settings_share), Ui.Style.PRIMARY) { Sharing.chooseAndShare(a) }, Ui.button(a, a.getString(R.string.settings_import)) { a.pickImportFile() }),
            hint(a.getString(R.string.settings_data_hint)),
            Ui.button(a, a.getString(R.string.settings_save_all)) { exportAll() },
            Ui.button(a, a.getString(R.string.settings_clear_map), Ui.Style.DANGER) { confirmClear() },
        )

        val place = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("mounted" to R.string.settings_place_mounted, "cupholder" to R.string.settings_place_cupholder, "pocket" to R.string.settings_place_pocket).forEach { (code, nameRes) ->
            val rb = RadioButton(a).apply {
                text = a.getString(nameRes); tag = code; id = View.generateViewId()
                setTextColor(Ui.TEXT); buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            }
            place.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (code == Prefs.placement(a)) rb.isChecked = true
        }
        place.setOnCheckedChangeListener { g, id -> Prefs.setPlacement(a, g.findViewById<RadioButton>(id)?.tag as? String ?: "unknown") }
        card(a.getString(R.string.settings_section_road_testing),
            Ui.toggle(a, a.getString(R.string.settings_label_mode_title),
                a.getString(R.string.settings_label_mode_desc),
                Prefs.labelMode(a)) { Prefs.setLabelMode(a, it) },
            Ui.text(a, 15f, Ui.TEXT, value = a.getString(R.string.settings_phone_where)),
            place,
            hint(a.getString(R.string.settings_phone_hint)),
        )

        traceInfo = hint("")
        card(a.getString(R.string.settings_section_debug),
            Ui.toggle(a, a.getString(R.string.settings_debug_title), a.getString(R.string.settings_debug_desc, TraceWriter.KEEP), Prefs.debugRecording(a)) {
                sp.edit().putBoolean(Prefs.DEBUG_RECORDING, it).apply()
                showTraces()
            }.also { debugBox = ((it as? ViewGroup)?.getChildAt(0) ?: it) as? CheckBox },
            traceInfo,
            Ui.row(a, Ui.button(a, a.getString(R.string.settings_export_recordings)) { exportTraces() }, Ui.button(a, a.getString(R.string.settings_share_recordings)) { shareTraces() }),
            Ui.button(a, a.getString(R.string.settings_delete_recordings), Ui.Style.QUIET) { deleteTraces() }.also {
                (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            },
        )

        card(a.getString(R.string.settings_section_help), Ui.text(a, 14f, Ui.TEXT, value = a.getString(R.string.settings_help)).apply { setLineSpacing(0f, 1.2f) })
        return ScrollView(a).apply { addView(col) }
    }

    /** Settings → Shared map: the choice, how the sync is doing, and "delete my shared data". */
    private fun buildSharedMap(card: (String, Array<View>) -> Unit) {
        syncGroup = RadioGroup(a).apply { orientation = RadioGroup.VERTICAL }
        listOf(Prefs.SYNC_SHARE, Prefs.SYNC_RECEIVE, Prefs.SYNC_UNSET).forEach { choice ->
            syncGroup.addView(RadioButton(a).apply {
                text = SyncChoice.choiceName(a, choice); tag = choice; id = View.generateViewId()
                minHeight = dp(48)
                setTextColor(Ui.TEXT); buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
        }
        syncGroup.setOnCheckedChangeListener { g, id ->
            if (settingRadio) return@setOnCheckedChangeListener
            val choice = g.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            SyncChoice.markAnswered(a)
            Sync.setChoice(a, choice)
            showLimitsSwitch()
            tick()
        }
        fun status(color: Int = Ui.DIM) = Ui.text(a, 14f, color).apply { setPadding(0, dp(2), 0, dp(2)) }
        syncLast = status(); syncSpots = status(); syncPending = status(); syncError = status(Ui.ORANGE)
        card(a.getString(R.string.settings_section_shared_map), arrayOf(
            Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.settings_shared_map_hint)).apply { setPadding(0, dp(2), 0, dp(6)) },
            syncGroup,
            syncLast, syncSpots, syncPending, syncError,
            Ui.button(a, a.getString(R.string.settings_sync_delete), Ui.Style.DANGER) { confirmForget() }.also {
                (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            },
        ))
    }

    /** Moves the switch to [on] without asking, and shows why it can't work yet if so. */
    private fun showLimitsSwitch(on: Boolean = Prefs.speedLimits(a)) {
        limitsBox?.let { if (it.isChecked != on) { settingLimits = true; it.isChecked = on; settingLimits = false } }
        val why = SpeedLimitText.settingsStatus(a, Prefs.speedLimits(a), SpeedLimitSync.allowed(a))
        limitsStatus.text = why
        limitsStatus.visibility = if (why.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Turning road speed limits on: the route leaves the phone, so the user agrees first. While the shared-map
     * question is unanswered nothing goes to our server (only the GitHub update check runs), so that comes first.
     */
    private fun askLimits() {
        limitsDialog?.dismiss()
        val d = AlertDialog.Builder(a)
        if (Prefs.syncChoice(a) == Prefs.SYNC_UNSET) {
            d.setTitle(a.getString(R.string.limits_need_map_title))
                .setMessage(a.getString(R.string.limits_need_map_msg))
                .setPositiveButton(R.string.limits_need_map_open) { _, _ ->
                    // The question is never shown during a trip (it would cover the Drive screen).
                    if (LiveState.recording) a.toast(a.getString(R.string.limits_need_map_after_trip)) else a.showSyncChoice()
                }
                .setNegativeButton(R.string.common_not_now, null)
        } else {
            d.setTitle(a.getString(R.string.limits_consent_title))
                .setMessage(a.getString(R.string.limits_consent_msg))
                .setPositiveButton(R.string.limits_consent_on) { _, _ -> SpeedLimitSync.setEnabled(a, true); showLimitsSwitch() }
                .setNegativeButton(R.string.common_cancel, null)
        }
        limitsDialog = d.setOnDismissListener { limitsDialog = null }.show()
    }

    /** The auto-detect switch, what actually runs, and the Fix button. Cheap; runs with every tick. */
    private fun showDetect(on: Boolean = AutoDetect.enabled(a)) {
        detectBox?.let { if (it.isChecked != on) { settingDetect = true; it.isChecked = on; settingDetect = false } }
        val st = AutoDetect.status(a)
        val text = AutoDetectText.status(a, st, LiveState.watching)
        if (detectStatus.text.toString() != text) detectStatus.text = text
        detectStatus.setTextColor(AutoDetectText.color(st))
        detectStatus.visibility = if (on) View.VISIBLE else View.GONE
        detectFix.visibility = if (on && AutoDetectText.showFix(st)) View.VISIBLE else View.GONE
    }

    private fun showSyncChoice() {
        val choice = Prefs.syncChoice(a)
        for (i in 0 until syncGroup.childCount) {
            val rb = syncGroup.getChildAt(i) as RadioButton
            if (rb.tag == choice && !rb.isChecked) {
                settingRadio = true
                rb.isChecked = true
                settingRadio = false
            }
        }
    }

    /** Status lines from LiveState; cheap, so it runs with every tick while Settings is open. */
    override fun tick() {
        val choice = Prefs.syncChoice(a)
        val on = choice != Prefs.SYNC_UNSET
        fun set(v: TextView, s: String, show: Boolean = s.isNotEmpty()) {
            if (v.text.toString() != s) v.text = s
            v.visibility = if (show) View.VISIBLE else View.GONE
        }
        set(syncLast, SyncChoice.lastSynced(a, LiveState.syncLastAt), on)
        set(syncSpots, SyncChoice.spots(a, LiveState.syncRemoteSpots), on)
        set(syncPending, SyncChoice.pending(a, LiveState.syncPending), on && choice == Prefs.SYNC_SHARE && LiveState.syncPending > 0)
        set(syncError, SyncChoice.error(a, LiveState.syncLastError), on && LiveState.syncLastError.isNotEmpty())
        showDetect()
    }

    private fun confirmForget() {
        forgetDialog?.dismiss()
        forgetDialog = AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.settings_sync_delete_title))
            .setMessage(a.getString(R.string.settings_sync_delete_msg))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                Sync.forgetMe(a) { ok ->
                    a.toast(a.getString(if (ok) R.string.settings_sync_deleted else R.string.settings_sync_deleted_local))
                    showSyncChoice(); showLimitsSwitch(); tick()
                }
                showSyncChoice(); showLimitsSwitch(); tick()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .setOnDismissListener { forgetDialog = null }
            .show()
    }

    override fun onShow() {
        showSyncChoice()
        showLimitsSwitch()
        tick()
        val on = a.autoStartOn()
        autoText.text = if (on) a.getString(R.string.settings_auto_on, Prefs.carName(a)) else a.getString(R.string.settings_auto_off)
        autoText.setTextColor(if (on) Ui.GREEN else Ui.TEXT)
        autoBtn.text = a.getString(if (on) R.string.settings_auto_turn_off else R.string.settings_auto_set_up)
        showTraces()
    }

    /** How many recordings there are, and whether new drives get recorded at all (off by default). */
    private fun showTraces() {
        val files = TraceWriter.list(a)
        val on = Prefs.recordTrace(a)
        traceInfo.text = when {
            files.isEmpty() -> a.getString(if (on) R.string.settings_traces_none else R.string.settings_traces_none_off)
            else -> a.getString(R.string.settings_traces_info, files.size, String.format(Locale.US, "%.1f", files.sumOf { it.length() } / 1_000_000.0)) +
                if (on) "" else " " + a.getString(R.string.settings_traces_off)
        }
        traceInfo.setTextColor(if (on || files.isNotEmpty()) Ui.DIM else Ui.ORANGE)
    }

    private fun testVoice() {
        val first = voice == null
        val v = voice ?: Voice(a) { ui.post { a.toast(a.getString(R.string.settings_toast_no_tts)) } }
            .also { voice = it }
        ui.postDelayed({ v.pothole(Side.RIGHT) }, if (first) 1500L else 0L)
    }

    private fun exportAll() {
        a.toast(a.getString(R.string.settings_toast_saving))
        Thread {
            val db = BumpDb(a.applicationContext)
            val ok = try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
                CsvExport.save(a, "bumps_$stamp.csv", db.bumpsCsv(Prefs.engineConfig(a))) &&
                    CsvExport.save(a, "events_$stamp.csv", db.eventsCsv()) &&
                    CsvExport.save(a, "trips_$stamp.csv", db.tripsCsv())
            } finally { db.close() }
            ui.post { a.toast(a.getString(if (ok) R.string.settings_toast_saved_all else R.string.settings_toast_saving_failed)) }
        }.start()
    }

    /** The recordings to export or share, or null (after telling the user why) when there are none to give. */
    private fun tracesToSend(): List<java.io.File>? {
        if (LiveState.recording) { a.toast(a.getString(R.string.settings_toast_stop_first_complete)); return null }
        val files = TraceWriter.list(a)
        if (files.isNotEmpty()) return files
        // The usual reason for "nothing was exported": debug recording is off by default, so no drive was recorded.
        val off = !Prefs.recordTrace(a)
        val d = AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.settings_no_recordings_title))
            .setMessage(a.getString(if (off) R.string.settings_no_recordings_off else R.string.settings_no_recordings_on))
        if (off) {
            d.setPositiveButton(R.string.settings_turn_on_recording) { _, _ ->
                debugBox?.isChecked = true
                sp.edit().putBoolean(Prefs.DEBUG_RECORDING, true).apply()
                showTraces()
            }.setNegativeButton(R.string.common_not_now, null)
        } else {
            d.setPositiveButton(R.string.common_ok, null)
        }
        d.show()
        return null
    }

    private fun exportTraces() {
        val files = tracesToSend() ?: return
        a.toast(a.getString(R.string.settings_toast_exporting, files.size))
        Thread {
            val ok = files.count { CsvExport.saveFile(a, it.name, it, "recordings") != null }
            ui.post {
                if (a.isFinishing) return@post
                // A dialog, not a toast: the result is easy to miss otherwise, and Share is the quickest way to Drive.
                AlertDialog.Builder(a)
                    .setTitle(a.getString(if (ok > 0) R.string.settings_export_done_title else R.string.settings_export_failed_title))
                    .setMessage(when (ok) {
                        files.size -> a.getString(R.string.settings_export_done_all, ok)
                        0 -> a.getString(R.string.settings_export_failed)
                        else -> a.getString(R.string.settings_export_done_some, ok, files.size)
                    })
                    .setPositiveButton(R.string.settings_share_recordings) { _, _ -> shareTraces() }
                    .setNegativeButton(R.string.common_close, null)
                    .show()
            }
        }.start()
    }

    private fun shareTraces() {
        val files = tracesToSend() ?: return
        Sharing.shareRecordings(a, files)
    }

    private fun deleteTraces() {
        if (LiveState.recording) { a.toast(a.getString(R.string.settings_toast_stop_first)); return }
        AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.settings_delete_traces_title))
            .setMessage(a.getString(R.string.settings_delete_traces_msg))
            .setPositiveButton(R.string.common_delete) { _, _ -> TraceWriter.list(a).forEach { it.delete() }; showTraces() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmClear() {
        if (LiveState.recording) { a.toast(a.getString(R.string.settings_toast_stop_first)); return }
        AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.settings_clear_title))
            .setMessage(a.getString(R.string.settings_clear_msg))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                Thread {
                    val db = BumpDb(a.applicationContext)
                    try { db.clearAll() } finally { db.close() }
                    SpeedLimitSync.clearPending(a.applicationContext)   // routes waiting for a lookup go too
                    ui.post { LiveState.lastEvent = a.getString(R.string.settings_map_cleared); LiveState.lastTripScore = -1; a.toast(a.getString(R.string.settings_toast_cleared)) }
                }.start()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
