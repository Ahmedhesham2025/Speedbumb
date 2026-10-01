package app.bumpbeeper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The one screen: Start/Stop, live numbers, jolt meter, settings, export. Built in code (no XML layouts). */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var stats: TextView
    private lateinit var lastEvent: TextView
    private lateinit var lastIgnored: TextView
    private lateinit var graph: JoltGraphView

    private val ui = Handler(Looper.getMainLooper())
    private var mapCount = 0
    private var mapMuted = 0
    private var wasRecording = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        loadMapCounts()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------- layout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(sizeSp: Float, bold: Boolean = false, dim: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (dim) alpha = 0.72f
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(36))
        }
        fun add(v: View, topDp: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
            col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(topDp) })
        }

        add(label(26f, bold = true).apply { text = "Bump Beeper" })
        add(label(14f, dim = true).apply {
            text = "Records speed bumps while you drive. From the second time you pass one, it beeps before you reach it."
        }, 4)

        status = label(18f, bold = true)
        add(status, 20)
        startBtn = Button(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isAllCaps = false
            setOnClickListener { toggle() }
        }
        add(startBtn, 8, dp(64))

        stats = label(15f).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
        }
        add(stats, 12)

        add(label(12f, bold = true, dim = true).apply { text = "JOLT METER · LAST 30 SECONDS" }, 20)
        graph = JoltGraphView(this)
        add(graph, 6, dp(120))
        lastEvent = label(15f, bold = true)
        add(lastEvent, 10)
        lastIgnored = label(13f, dim = true)
        add(lastIgnored, 2)

        add(label(12f, bold = true, dim = true).apply { text = "SENSITIVITY" }, 24)
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val current = Prefs.sensitivity(this)
        arrayOf("Low", "Normal", "High").forEachIndexed { i, name ->
            val rb = RadioButton(this).apply {
                text = name
                id = View.generateViewId()
                tag = i
            }
            group.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i == current) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val level = g.findViewById<RadioButton>(checkedId)?.tag as? Int ?: 1
            Prefs.sp(this).edit().putInt(Prefs.SENSITIVITY, level).apply()
        }
        add(group, 4)
        add(label(12f, dim = true).apply {
            text = "Watch the jolt meter: bumps should poke above the dashed line, normal driving should stay below it. " +
                "Low ignores rough roads; High also catches small bumps (and more potholes)."
        }, 2)

        add(CheckBox(this).apply {
            text = "Loud beeps (alarm volume, phone speaker)"
            isChecked = Prefs.loud(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.sp(this@MainActivity).edit().putBoolean(Prefs.LOUD, on).apply() }
        }, 16)
        add(CheckBox(this).apply {
            text = "Soft tick when a new bump is recorded"
            isChecked = Prefs.clickOnNew(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.sp(this@MainActivity).edit().putBoolean(Prefs.CLICK_ON_NEW, on).apply() }
        })

        add(row(button("Test beep") { Beeper(this).beep(2) }, button("Mute last beep") { muteLast() }), 16)
        add(label(12f, dim = true).apply {
            text = "Beeped for nothing? Tap Mute last beep and that spot stays silent from now on."
        }, 2)
        add(row(button("Export CSV") { export() }, button("Battery settings") { batterySettings() }), 12)
        add(button("Clear map") { confirmClear() }, 8)

        add(label(13f, dim = true).apply {
            setLineSpacing(0f, 1.2f)
            text = HELP
        }, 24)

        return ScrollView(this).apply { addView(col) }
    }

    // ---------------------------------------------------------------- live refresh

    private fun refresh() {
        val rec = LiveState.recording
        if (wasRecording && !rec) loadMapCounts()
        wasRecording = rec

        startBtn.text = if (rec) "Stop" else "Start recording"
        if (rec) {
            val fixAge = SystemClock.elapsedRealtime() - LiveState.lastFixAtMs
            val gpsOk = LiveState.lastFixAtMs > 0 && fixAge < 5000
            status.text = if (gpsOk) "● Recording" else "● Recording, waiting for GPS…"
            status.setTextColor(if (gpsOk) GREEN else AMBER)
            val speed = if (gpsOk) String.format(Locale.US, "%.0f km/h (GPS ±%.0f m)", LiveState.speedKmh, LiveState.accuracyM) else "–"
            stats.text = String.format(
                Locale.US,
                "Speed      %s\nOn map     %d bumps (%d muted)\nThis trip  %d hit · %d new · %d beeps\n           %d missed · %.1f km",
                speed, LiveState.bumpsOnMap, LiveState.mutedBumps,
                LiveState.tripHits, LiveState.tripNew, LiveState.tripBeeps, LiveState.tripMisses, LiveState.tripKm,
            )
        } else {
            status.text = "Stopped"
            status.setTextColor(GRAY)
            stats.text = String.format(Locale.US, "On map     %d bumps (%d muted)", mapCount, mapMuted)
        }
        lastEvent.text = LiveState.lastEvent
        lastIgnored.text = LiveState.lastIgnored
        graph.threshold = Prefs.thresholdFor(Prefs.sensitivity(this)).toFloat()
        graph.invalidate()
    }

    private fun loadMapCounts() {
        Thread {
            val db = BumpDb(applicationContext)
            try {
                val (n, m) = db.counts()
                ui.post { mapCount = n; mapMuted = m }
            } finally {
                db.close()
            }
        }.start()
    }

    // ---------------------------------------------------------------- start / stop

    private fun toggle() {
        if (LiveState.recording) {
            BumpService.stop(this)
            return
        }
        val missing = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        startRecording()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            toast("Bump Beeper needs precise location to know where the bumps are.")
        }
    }

    private fun startRecording() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            toast("Turn on Location first")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        LiveState.lastEvent = "Starting…"
        BumpService.start(this)
    }

    // ---------------------------------------------------------------- buttons

    private fun muteLast() {
        if (!LiveState.recording) {
            toast("Works while recording: it mutes the bump that beeped last.")
            return
        }
        BumpService.muteLastBeep(this)
    }

    private fun batterySettings() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Already allowed. If recording still stops, check your phone's own battery / auto-launch settings for this app.")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun export() {
        toast("Exporting…")
        Thread {
            val db = BumpDb(applicationContext)
            val ok = try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
                CsvExport.save(this, "bumps_$stamp.csv", db.bumpsCsv()) &&
                    CsvExport.save(this, "events_$stamp.csv", db.eventsCsv())
            } finally {
                db.close()
            }
            ui.post { toast(if (ok) "Saved to Downloads/BumpBeeper" else "Export failed") }
        }.start()
    }

    private fun confirmClear() {
        if (LiveState.recording) {
            toast("Stop recording first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear the whole map?")
            .setMessage("Deletes every recorded bump and the event log. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    val db = BumpDb(applicationContext)
                    try { db.clearAll() } finally { db.close() }
                    ui.post {
                        mapCount = 0
                        mapMuted = 0
                        LiveState.lastEvent = "Map cleared"
                        toast("Map cleared")
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val GREEN = 0xFF43A047.toInt()
        private const val AMBER = 0xFFFB8C00.toInt()
        private const val GRAY = 0xFF9E9E9E.toInt()

        private const val HELP = "How to use\n" +
            "• Put the phone in a holder. A loose phone gives noisy readings.\n" +
            "• Tap Start before you drive. It keeps running with the screen off. Tap Stop (here or in the notification) when you arrive.\n" +
            "• First pass over a bump: recorded silently. Every pass after that: 2 beeps about 6 seconds before you reach it (3 beeps above 50 km/h).\n" +
            "• A spot you've passed 3+ times but felt less than half the time is muted automatically. " +
            "Crawling over a bump without feeling it doesn't count against it.\n" +
            "• Beeps use media volume and go through car Bluetooth like navigation voice. Tap Test beep to check.\n" +
            "• If recording stops by itself, tap Battery settings and allow the app to run in the background.\n" +
            "• Export CSV saves the bump map and event log to Downloads/BumpBeeper. Import the bumps file into Google My Maps to see them on a map."
    }
}
