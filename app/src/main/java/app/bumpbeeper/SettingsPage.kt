package app.bumpbeeper

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
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

    override val view: View = build()

    fun release() { voice?.shutdown() }

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

        add(Ui.text(a, 22f, Ui.TEXT, bold = true, value = "Settings"))

        card("Warnings",
            Ui.slider(a, 4, 12, 1, Prefs.leadSeconds(a), { "Warn $it seconds before a bump" }) { sp.edit().putInt(Prefs.LEAD_SECONDS, it).apply() },
            Ui.slider(a, 0, 40, 5, Prefs.quietBelowKmh(a), {
                if (it == 0) "Always warn, even when driving slowly" else "Stay quiet when I'm already below $it km/h"
            }) { sp.edit().putInt(Prefs.QUIET_BELOW_KMH, it).apply() },
            Ui.toggle(a, "Loud beeps", "Alarm volume through the phone speaker, instead of media volume / car audio.", Prefs.loud(a)) {
                sp.edit().putBoolean(Prefs.LOUD, it).apply()
            },
            Ui.toggle(a, "Soft tick when a new bump is recorded", null, Prefs.clickOnNew(a)) { sp.edit().putBoolean(Prefs.CLICK_ON_NEW, it).apply() },
            Ui.row(a, Ui.button(a, "Test beep") { Beeper(a).beep(2) }, Ui.button(a, "Test pothole voice") { testVoice() }),
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
        card("Potholes",
            hint("Every pothole is recorded and counted. Only harsh ones get a voice warning that says which side it's on, " +
                "e.g. \"Pothole on the right. Keep left.\" (keep left within your lane)."),
            Ui.toggle(a, "Voice warning for harsh potholes", null, Prefs.warnPotholes(a)) { sp.edit().putBoolean(Prefs.WARN_POTHOLES, it).apply() },
            Ui.slider(a, 4, 10, 1, Prefs.harshMs2(a), {
                "Harsh = jolt of $it m/s² or more · " + when { it <= 5 -> "most potholes"; it <= 7 -> "ones you really feel"; else -> "only the worst" }
            }) { sp.edit().putInt(Prefs.HARSH_MS2, it).apply() },
            Ui.text(a, 15f, Ui.TEXT, value = "Voice language"),
            lang,
        )

        card("Driving score",
            Ui.slider(a, 50, 140, 10, Prefs.speedLimit(a), { "Count speeding above $it km/h" }) { sp.edit().putInt(Prefs.SPEED_LIMIT, it).apply() },
            hint("The app works offline and can't see road signs, so set the limit you usually drive under (e.g. 60 in town, 90–120 on highways)."),
        )

        val sens = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Low", "Normal", "High").forEachIndexed { i, name ->
            val rb = RadioButton(a).apply {
                text = name; tag = i; id = View.generateViewId()
                setTextColor(Ui.TEXT); buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            }
            sens.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i == Prefs.sensitivity(a)) rb.isChecked = true
        }
        sens.setOnCheckedChangeListener { g, id -> sp.edit().putInt(Prefs.SENSITIVITY, g.findViewById<RadioButton>(id)?.tag as? Int ?: 1).apply() }
        card("Detection",
            sens,
            hint("Low ignores rough roads; High also catches small bumps. Check with the jolt meter on the Drive tab."),
            Ui.slider(a, 30, 80, 5, Prefs.maxBumpKmh(a), { "Jolts above $it km/h aren't speed bumps (potholes still count)" }) {
                sp.edit().putInt(Prefs.MAX_BUMP_KMH, it).apply()
            },
        )

        autoText = Ui.text(a, 15f, Ui.TEXT)
        autoBtn = Ui.button(a, "", Ui.Style.PRIMARY) { if (a.autoStartOn()) a.disableAuto() else a.setupAuto() }
        card("Auto start",
            autoText,
            hint("Starts recording when the phone connects to your car's Bluetooth and stops a minute after it disconnects."),
            autoBtn,
            Ui.button(a, "Battery: allow running in the background") { a.batterySettings() }.also {
                (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            },
        )

        card("Your data",
            Ui.row(a, Ui.button(a, "Share…", Ui.Style.PRIMARY) { Sharing.chooseAndShare(a) }, Ui.button(a, "Import a file") { a.pickImportFile() }),
            hint("Share your bump map with friends (they import it into theirs), open it in Google Earth / My Maps, or share your trips."),
            Ui.button(a, "Save everything to Downloads") { exportAll() },
            Ui.button(a, "Clear the map", Ui.Style.DANGER) { confirmClear() },
        )

        val place = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("mounted" to "Mounted", "cupholder" to "Cup holder", "pocket" to "Pocket").forEach { (code, name) ->
            val rb = RadioButton(a).apply {
                text = name; tag = code; id = View.generateViewId()
                setTextColor(Ui.TEXT); buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            }
            place.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (code == Prefs.placement(a)) rb.isChecked = true
        }
        place.setOnCheckedChangeListener { g, id -> Prefs.setPlacement(a, g.findViewById<RadioButton>(id)?.tag as? String ?: "unknown") }
        card("Road testing",
            Ui.toggle(a, "Label mode",
                "For a passenger helping test the app: while recording, the Drive tab shows big buttons to mark each bump, " +
                    "pothole (left / right) and rough patch as you drive over it. Saved with a sensor recording to tune detection.",
                Prefs.labelMode(a)) { Prefs.setLabelMode(a, it) },
            Ui.text(a, 15f, Ui.TEXT, value = "Where is the phone?"),
            place,
            hint("A phone in a holder feels the road differently from one in a cup holder or pocket. Saved with recordings."),
        )

        traceInfo = hint("")
        card("Debug recording",
            Ui.toggle(a, "Record raw sensor data while driving", "About 12 MB per hour. The last ${TraceWriter.KEEP} drives are kept.", Prefs.debugRecording(a)) {
                sp.edit().putBoolean(Prefs.DEBUG_RECORDING, it).apply()
            },
            traceInfo,
            Ui.row(a, Ui.button(a, "Export recordings") { exportTraces() }, Ui.button(a, "Delete recordings") { deleteTraces() }),
        )

        card("Help", Ui.text(a, 14f, Ui.TEXT, value = HELP).apply { setLineSpacing(0f, 1.2f) })
        return ScrollView(a).apply { addView(col) }
    }

    override fun onShow() {
        val on = a.autoStartOn()
        autoText.text = if (on) "On: starts with ${Prefs.carName(a)}" else "Off"
        autoText.setTextColor(if (on) Ui.GREEN else Ui.TEXT)
        autoBtn.text = if (on) "Turn auto start off" else "Set up auto start"
        val files = TraceWriter.list(a)
        traceInfo.text = if (files.isEmpty()) "No recordings yet."
            else String.format(Locale.US, "%d recording(s), %.1f MB.", files.size, files.sumOf { it.length() } / 1_000_000.0)
    }

    private fun testVoice() {
        val first = voice == null
        val v = voice ?: Voice(a) { ui.post { a.toast("No text-to-speech voice ready on this phone: potholes will use the two-tone sound") } }
            .also { voice = it }
        ui.postDelayed({ v.pothole(Side.RIGHT) }, if (first) 1500L else 0L)
    }

    private fun exportAll() {
        a.toast("Saving…")
        Thread {
            val db = BumpDb(a.applicationContext)
            val ok = try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
                CsvExport.save(a, "bumps_$stamp.csv", db.bumpsCsv(Prefs.engineConfig(a))) &&
                    CsvExport.save(a, "events_$stamp.csv", db.eventsCsv()) &&
                    CsvExport.save(a, "trips_$stamp.csv", db.tripsCsv())
            } finally { db.close() }
            ui.post { a.toast(if (ok) "Saved bumps, events and trips to Downloads/BumpBeeper" else "Saving failed") }
        }.start()
    }

    private fun exportTraces() {
        if (LiveState.recording) { a.toast("Stop recording first, so the last recording is complete"); return }
        val files = TraceWriter.list(a)
        if (files.isEmpty()) { a.toast("No recordings yet"); return }
        a.toast("Exporting ${files.size} recording(s)…")
        Thread {
            val ok = files.count { CsvExport.saveFile(a, it.name, it, "recordings") }
            ui.post { a.toast("Saved $ok of ${files.size} to Downloads/BumpBeeper/recordings") }
        }.start()
    }

    private fun deleteTraces() {
        if (LiveState.recording) { a.toast("Stop recording first"); return }
        AlertDialog.Builder(a)
            .setTitle("Delete all debug recordings?")
            .setMessage("Exported copies in Downloads stay. The bump map is not affected.")
            .setPositiveButton("Delete") { _, _ -> TraceWriter.list(a).forEach { it.delete() }; onShow() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear() {
        if (LiveState.recording) { a.toast("Stop recording first"); return }
        AlertDialog.Builder(a)
            .setTitle("Clear everything?")
            .setMessage("Deletes every recorded bump and pothole, the event log and all trips and scores. This can't be undone. " +
                "Tip: Share… → Bump file first to keep a copy.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    val db = BumpDb(a.applicationContext)
                    try { db.clearAll() } finally { db.close() }
                    ui.post { LiveState.lastEvent = "Map cleared"; LiveState.lastTripScore = -1; a.toast("Cleared") }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val HELP =
            "• Put the phone in a holder. A loose phone gives noisy readings.\n" +
                "• Tap START before you drive (or set up Auto start). It keeps running with the screen off.\n" +
                "• First pass over a bump: recorded silently. After that: 2 beeps before you reach it (3 above 50 km/h). " +
                "Harsh potholes: a voice says which side they're on.\n" +
                "• Bump or pothole is judged from how the car moves; each pass makes it surer. Correct any spot on the Map tab.\n" +
                "• A spot you pass 3+ times but rarely feel is muted automatically. \"Mute last warning\" silences a false alarm.\n" +
                "• Your driving score (Trips tab) looks at speeding, harsh braking, cornering, swerving, speed bumps taken fast " +
                "and phone use.\n" +
                "• Everything stays on your phone. Share only what you choose."
    }
}
