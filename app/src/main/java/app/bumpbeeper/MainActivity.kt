package app.bumpbeeper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The main screen: Start/Stop, live numbers, jolt meter, settings, export. Built in code (no XML layouts). */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var stats: TextView
    private lateinit var lastEvent: TextView
    private lateinit var lastIgnored: TextView
    private lateinit var graph: JoltGraphView
    private lateinit var autoBox: CheckBox
    private lateinit var traceInfo: TextView

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
        handleStartExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartExtra(intent)
    }

    /** Opened from the "your car connected, tap to start" notification. */
    private fun handleStartExtra(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_START, false) == true && !LiveState.recording) {
            i.removeExtra(EXTRA_START)
            toggle()
        }
    }

    override fun onResume() {
        super.onResume()
        loadMapCounts()
        updateAutoBox()
        updateTraceInfo()
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

    private fun checkbox(text: String, key: String, value: Boolean) = CheckBox(this).apply {
        this.text = text
        isChecked = value
        setOnCheckedChangeListener { _, on -> Prefs.sp(this@MainActivity).edit().putBoolean(key, on).apply() }
    }

    /** A labelled slider that saves to [key]. [format] turns the value into the text shown. */
    private fun slider(key: String, min: Int, max: Int, step: Int, current: Int, format: (Int) -> String): View {
        val title = label(14f).apply { text = format(current) }
        val bar = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = ((current - min) / step).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    title.text = format(v)
                    if (fromUser) Prefs.sp(this@MainActivity).edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(bar)
        }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(36))
        }
        fun add(v: View, topDp: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
            col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(topDp) })
        }
        fun section(name: String) = add(label(12f, bold = true, dim = true).apply { text = name }, 24)

        add(label(26f, bold = true).apply { text = "Bump Beeper" })
        add(label(14f, dim = true).apply {
            text = "Records speed bumps and potholes while you drive. From the second time you pass one, it warns you before you reach it."
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
        add(button("Your bumps: map & list") { startActivity(Intent(this, BumpsActivity::class.java)) }, 8)

        add(label(12f, bold = true, dim = true).apply { text = "JOLT METER · LAST 30 SECONDS" }, 20)
        graph = JoltGraphView(this)
        add(graph, 6, dp(120))
        lastEvent = label(15f, bold = true)
        add(lastEvent, 10)
        lastIgnored = label(13f, dim = true)
        add(lastIgnored, 2)

        // ---- sensitivity
        section("SENSITIVITY")
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
                "Low ignores rough roads; High also catches small bumps."
        }, 2)

        // ---- warnings
        section("WARNINGS")
        add(slider(Prefs.LEAD_SECONDS, 4, 12, 1, Prefs.leadSeconds(this)) { "Warn $it seconds before the bump" }, 4)
        add(slider(Prefs.QUIET_BELOW_KMH, 0, 40, 5, Prefs.quietBelowKmh(this)) {
            if (it == 0) "Always warn, even when driving slowly" else "Don't warn when I'm already below $it km/h"
        }, 8)
        add(slider(Prefs.MAX_BUMP_KMH, 30, 80, 5, Prefs.maxBumpKmh(this)) {
            "Jolts above $it km/h aren't speed bumps (potholes still count)"
        }, 8)
        add(checkbox("Warn for potholes too (falling two-tone sound)", Prefs.WARN_POTHOLES, Prefs.warnPotholes(this)), 8)
        add(checkbox("Loud warnings (alarm volume, phone speaker)", Prefs.LOUD, Prefs.loud(this)))
        add(checkbox("Soft tick when a new bump is recorded", Prefs.CLICK_ON_NEW, Prefs.clickOnNew(this)))

        add(row(button("Test bump beep") { Beeper(this).beep(2) }, button("Test pothole sound") { Beeper(this).pothole() }), 12)
        add(button("Mute last warning") { muteLast() }, 4)
        add(label(12f, dim = true).apply {
            text = "Warned for nothing? Tap Mute last warning and that spot stays silent from now on."
        }, 2)

        // ---- auto start
        section("AUTO START")
        autoBox = CheckBox(this).apply {
            setOnClickListener {
                if (isChecked) setupAuto() else {
                    Prefs.sp(this@MainActivity).edit().putBoolean(Prefs.AUTO_START, false).apply()
                    updateAutoBox()
                }
            }
        }
        add(autoBox, 4)
        add(label(12f, dim = true).apply {
            text = "Starts recording when your phone connects to the car's Bluetooth and stops a minute after it disconnects. " +
                "Needs location \"Allow all the time\" so it can start while the app is closed."
        }, 2)

        // ---- data
        section("YOUR DATA")
        add(row(button("Export CSV") { export() }, button("Battery settings") { batterySettings() }), 4)
        add(button("Clear map") { confirmClear() }, 8)

        section("DEBUG RECORDING")
        add(checkbox("Record raw sensor data while driving (about 12 MB per hour)", Prefs.DEBUG_RECORDING, Prefs.debugRecording(this)), 4)
        traceInfo = label(12f, dim = true)
        add(traceInfo, 2)
        add(row(button("Export recordings") { exportTraces() }, button("Delete recordings") { deleteTraces() }), 4)

        add(label(13f, dim = true).apply {
            setLineSpacing(0f, 1.2f)
            text = HELP
        }, 24)

        return ScrollView(this).apply { addView(col) }
    }

    // ---------------------------------------------------------------- live refresh

    private fun refresh() {
        val rec = LiveState.recording
        if (wasRecording && !rec) { loadMapCounts(); updateTraceInfo() }
        wasRecording = rec

        startBtn.text = if (rec) "Stop" else "Start recording"
        if (rec) {
            val fixAge = SystemClock.elapsedRealtime() - LiveState.lastFixAtMs
            val gpsOk = LiveState.lastFixAtMs > 0 && fixAge < 5000
            status.text = if (gpsOk) "● Recording" else "● Recording, waiting for GPS…"
            status.setTextColor(if (gpsOk) GREEN else AMBER)
            val speed = if (gpsOk) String.format(Locale.US, "%.0f km/h (GPS ±%.0f m)", LiveState.speedKmh, LiveState.accuracyM) else "–"
            val holes = when {
                !LiveState.hasGyro -> "no gyroscope: less sure"
                LiveState.forwardKnown -> "ready"
                else -> "learning (speed up / brake once)"
            }
            stats.text = String.format(
                Locale.US,
                "Speed      %s\nOn map     %d (%d muted)\nThis trip  %d hit · %d new · %d warnings\n           %d missed · %.1f km\nPotholes   %s",
                speed, LiveState.bumpsOnMap, LiveState.mutedBumps,
                LiveState.tripHits, LiveState.tripNew, LiveState.tripBeeps, LiveState.tripMisses, LiveState.tripKm, holes,
            )
        } else {
            status.text = "Stopped"
            status.setTextColor(GRAY)
            stats.text = String.format(Locale.US, "On map     %d (%d muted)", mapCount, mapMuted)
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
        when (requestCode) {
            REQ_PERMS -> if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startRecording()
            } else {
                toast("Bump Beeper needs precise location to know where the bumps are.")
            }
            REQ_AUTO_FINE, REQ_AUTO_BT, REQ_AUTO_BG -> setupAuto(afterRequest = requestCode)
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

    // ---------------------------------------------------------------- auto start with the car

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun updateAutoBox() {
        val car = Prefs.carName(this)
        val on = Prefs.autoStart(this) && Prefs.carAddress(this) != null
        autoBox.isChecked = on
        autoBox.text = if (on) "Auto start with car Bluetooth: $car" else "Auto start with car Bluetooth"
    }

    private fun cancelAuto(msg: String) {
        Prefs.sp(this).edit().putBoolean(Prefs.AUTO_START, false).apply()
        updateAutoBox()
        toast(msg)
    }

    /**
     * Walks through what auto start needs, one step at a time:
     * precise location → Bluetooth (Android 12+) → pick the car → location "all the time" → battery hint.
     * Called again after each permission answer.
     */
    private fun setupAuto(afterRequest: Int = 0) {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (afterRequest == REQ_AUTO_FINE) return cancelAuto("Auto start needs precise location")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_AUTO_FINE)
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            if (afterRequest == REQ_AUTO_BT) return cancelAuto("Auto start needs the Nearby devices (Bluetooth) permission")
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_AUTO_BT)
            return
        }
        if (afterRequest == REQ_AUTO_BG) {
            if (has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) finishAuto()
            else cancelAuto("Auto start needs location set to \"Allow all the time\"")
            return
        }
        pickCar()
    }

    @SuppressLint("MissingPermission")
    private fun pickCar() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) return cancelAuto("This phone has no Bluetooth")
        val devices = try { adapter.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
        if (devices.isEmpty()) return cancelAuto("No paired Bluetooth devices. Pair the phone with your car first.")
        val names = devices.map { d -> (try { d.name } catch (e: SecurityException) { null }) ?: d.address }
        AlertDialog.Builder(this)
            .setTitle("Which one is your car?")
            .setItems(names.toTypedArray()) { _, i ->
                Prefs.sp(this).edit()
                    .putString(Prefs.CAR_ADDRESS, devices[i].address)
                    .putString(Prefs.CAR_NAME, names[i])
                    .apply()
                askBackgroundLocation()
            }
            .setOnCancelListener { cancelAuto("Auto start not set up") }
            .show()
    }

    private fun askBackgroundLocation() {
        if (has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return finishAuto()
        AlertDialog.Builder(this)
            .setTitle("One more permission")
            .setMessage(
                "To start recording while the app is closed, Android needs location set to \"Allow all the time\". " +
                    "On the next screen choose Allow all the time. Bump Beeper only uses location while it is recording, " +
                    "and nothing leaves your phone."
            )
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_AUTO_BG)
            }
            .setNegativeButton("Cancel") { _, _ -> cancelAuto("Auto start not set up") }
            .setCancelable(false)
            .show()
    }

    private fun finishAuto() {
        Prefs.sp(this).edit().putBoolean(Prefs.AUTO_START, true).apply()
        updateAutoBox()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Auto start is on")
                .setMessage("For it to work reliably, also allow Bump Beeper to run in the background (no battery restrictions).")
                .setPositiveButton("Battery settings") { _, _ -> batterySettings() }
                .setNegativeButton("Later", null)
                .show()
        } else {
            toast("Auto start is on: it starts when ${Prefs.carName(this)} connects")
        }
    }

    // ---------------------------------------------------------------- buttons

    private fun muteLast() {
        if (!LiveState.recording) {
            toast("Works while recording: it mutes the spot that warned last.")
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

    private fun updateTraceInfo() {
        val files = TraceWriter.list(this)
        val mb = files.sumOf { it.length() } / 1_000_000.0
        traceInfo.text = if (files.isEmpty()) "No recordings yet. Turn this on before a drive you want to check afterwards."
        else String.format(Locale.US, "%d recording(s), %.1f MB. The last %d drives are kept.", files.size, mb, TraceWriter.KEEP)
    }

    private fun exportTraces() {
        if (LiveState.recording) { toast("Stop recording first, so the last recording is complete"); return }
        val files = TraceWriter.list(this)
        if (files.isEmpty()) { toast("No recordings yet"); return }
        toast("Exporting ${files.size} recording(s)…")
        Thread {
            val ok = files.count { CsvExport.saveFile(this, it.name, it, "recordings") }
            ui.post { toast("Saved $ok of ${files.size} to Downloads/BumpBeeper/recordings") }
        }.start()
    }

    private fun deleteTraces() {
        if (LiveState.recording) { toast("Stop recording first"); return }
        AlertDialog.Builder(this)
            .setTitle("Delete all debug recordings?")
            .setMessage("Exported copies in Downloads stay. The bump map is not affected.")
            .setPositiveButton("Delete") { _, _ ->
                TraceWriter.list(this).forEach { it.delete() }
                updateTraceInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        const val EXTRA_START = "start_recording"
        private const val REQ_PERMS = 1
        private const val REQ_AUTO_FINE = 2
        private const val REQ_AUTO_BT = 3
        private const val REQ_AUTO_BG = 4
        private const val GREEN = 0xFF43A047.toInt()
        private const val AMBER = 0xFFFB8C00.toInt()
        private const val GRAY = 0xFF9E9E9E.toInt()

        private const val HELP = "How to use\n" +
            "• Put the phone in a holder. A loose phone gives noisy readings.\n" +
            "• Tap Start before you drive (or turn on Auto start). It keeps running with the screen off.\n" +
            "• First pass over a bump: recorded silently. Every pass after that: a warning before you reach it. " +
            "Speed bumps: 2 high beeps (3 above 50 km/h). Potholes: a falling two-tone sound.\n" +
            "• Bump or pothole is judged from how the car moves: a speed bump lifts the car and tips it nose-up; " +
            "a pothole drops one wheel and rocks it sideways. Each pass makes it surer. You can correct it in Your bumps.\n" +
            "• A spot you've passed 3+ times but felt less than half the time is muted automatically. " +
            "Crawling over a bump without feeling it doesn't count against it.\n" +
            "• Warnings use media volume and go through car Bluetooth like navigation voice. Use the Test buttons to check.\n" +
            "• If recording stops by itself, tap Battery settings and allow the app to run in the background.\n" +
            "• Export CSV saves the bump map and event log to Downloads/BumpBeeper. " +
            "Debug recording saves every sensor reading of each drive, to check afterwards what happened at a spot."
    }
}
