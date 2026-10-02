package app.bumpbeeper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.bumpbeeper.crash.CrashLog
import app.bumpbeeper.sync.UpdateCheck
import java.net.URI
import java.util.Locale

/** One part of the app shown under the bottom tabs. */
interface Page {
    val view: View
    /** Called when the tab is opened (refresh data here). */
    fun onShow() {}
    /** Called 4 times a second while the tab is visible. */
    fun tick() {}
}

/**
 * The app's single screen: four tabs at the bottom (Drive · Map · Trips · Settings).
 * Also handles permissions, auto-start setup, and bump files opened from other apps.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private val pages = arrayOfNulls<Page>(4)
    private val tabs = ArrayList<Pair<ImageView, TextView>>()
    private var current = -1
    private var mapPage: MapPage? = null
    private var update: UpdateCheck.Update? = null
    private var updateAsked = false
    private var updateDismissed = false

    private val ticker = object : Runnable {
        override fun run() {
            pages.getOrNull(current)?.tick()
            // Keep the screen on while recording with the Drive tab open (it's a dashboard).
            if (LiveState.recording && current == TAB_DRIVE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)   // first, so a crash while building the screen is kept too
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.SURFACE
        setContentView(buildShell())
        select(savedInstanceState?.getInt("tab") ?: TAB_DRIVE)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        // Opened from the "your car connected, tap to start" notification.
        if (i?.getBooleanExtra(EXTRA_START, false) == true) {
            i.removeExtra(EXTRA_START)
            select(TAB_DRIVE)
            if (!LiveState.recording) toggleRecording()
            return
        }
        // A bump file opened or shared from another app.
        if (Sharing.handleIncoming(this, i) { refreshMap() }) select(TAB_MAP)
    }

    override fun onResume() {
        super.onResume()
        pages.getOrNull(current)?.onShow()
        ui.post(ticker)
        // Once per run; UpdateCheck itself only goes online once a day.
        if (!updateAsked) {
            updateAsked = true
            UpdateCheck.latest(this) { u -> update = u }
        }
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        (pages[TAB_SETTINGS] as? SettingsPage)?.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tabs

    private fun buildShell(): View {
        content = FrameLayout(this)
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6))
        }
        listOf(
            R.string.tab_drive to R.drawable.ic_nav_drive, R.string.tab_map to R.drawable.ic_nav_map,
            R.string.tab_trips to R.drawable.ic_nav_trips, R.string.tab_settings to R.drawable.ic_nav_settings,
        ).forEachIndexed { i, (nameRes, icon) ->
            val name = getString(nameRes)
            val img = ImageView(this).apply { setImageResource(icon) }
            val label = TextView(this).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { select(i) }
                addView(img, LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24)))
                addView(label)
                contentDescription = name
            }
            tabs.add(img to label)
            nav.addView(item, LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    fun select(tab: Int) {
        val page = pages[tab] ?: when (tab) {
            TAB_DRIVE -> DrivePage(this)
            TAB_MAP -> MapPage(this).also { mapPage = it }
            TAB_TRIPS -> TripsPage(this)
            else -> SettingsPage(this)
        }.also { pages[tab] = it }
        if (current != tab) {
            content.removeAllViews()
            content.addView(page.view)
            current = tab
        }
        tabs.forEachIndexed { i, (img, label) ->
            val c = if (i == tab) Ui.ACCENT else Ui.DIM
            img.imageTintList = ColorStateList.valueOf(c)
            label.setTextColor(c)
        }
        page.onShow()
    }

    fun refreshMap() { mapPage?.onShow() }

    // ---------------------------------------------------------------- update banner

    /** The newer release to show on the Drive tab, or null (none, or the banner was dismissed). */
    fun pendingUpdate(): UpdateCheck.Update? = if (updateDismissed) null else update

    fun dismissUpdate() { updateDismissed = true }

    /** Opens the release page (or the file) in the browser, but only if it really points at GitHub. */
    fun openUpdate() {
        val u = update ?: return
        val url = listOfNotNull(u.htmlUrl, u.downloadUrl).firstOrNull { trustedUpdateUrl(it) } ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Exception) {
            toast(getString(R.string.main_no_browser))
        }
    }

    private fun trustedUpdateUrl(s: String): Boolean {
        val uri = try { URI(s) } catch (e: Exception) { return false }
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.userInfo != null) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host == "github.com" || host.endsWith(".github.com") || host == "objects.githubusercontent.com"
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- start / stop

    fun toggleRecording() {
        if (LiveState.recording) {
            BumpService.stop(this)
            return
        }
        val missing = missingBasics()
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        startRecording()
    }

    /** Permissions recording can't do without: precise location, and (Android 13+) notifications. */
    fun missingBasics(): List<String> {
        val missing = ArrayList<String>()
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        return missing
    }

    fun requestBasics() {
        val missing = missingBasics()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_BASICS)
    }

    fun batteryOk(): Boolean = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERMS -> if (has(Manifest.permission.ACCESS_FINE_LOCATION)) startRecording()
                else toast(getString(R.string.main_need_location))
            REQ_BASICS -> pages.getOrNull(current)?.onShow()
            REQ_AUTO_FINE, REQ_AUTO_BT, REQ_AUTO_BG -> setupAuto(afterRequest = requestCode)
        }
    }

    private fun startRecording() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            toast(getString(R.string.main_turn_on_location))
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        LiveState.lastEvent = getString(R.string.main_starting)
        BumpService.start(this)
    }

    fun muteLast() {
        if (!LiveState.recording) { toast(getString(R.string.main_mute_needs_recording)); return }
        BumpService.muteLastBeep(this)
    }

    fun batterySettings() {
        if (batteryOk()) {
            toast(getString(R.string.main_battery_already))
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------- auto start with the car

    fun autoStartOn(): Boolean = Prefs.autoStart(this) && Prefs.carAddress(this) != null

    private fun autoChanged() { pages.getOrNull(current)?.onShow() }

    fun disableAuto() {
        Prefs.sp(this).edit().putBoolean(Prefs.AUTO_START, false).apply()
        autoChanged()
    }

    private fun cancelAuto(msg: String) {
        Prefs.sp(this).edit().putBoolean(Prefs.AUTO_START, false).apply()
        autoChanged()
        toast(msg)
    }

    /**
     * Walks through what auto start needs, one step at a time:
     * precise location → Bluetooth (Android 12+) → pick the car → location "all the time" → battery hint.
     */
    fun setupAuto(afterRequest: Int = 0) {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (afterRequest == REQ_AUTO_FINE) return cancelAuto(getString(R.string.main_auto_need_fine))
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_AUTO_FINE)
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            if (afterRequest == REQ_AUTO_BT) return cancelAuto(getString(R.string.main_auto_need_bt))
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_AUTO_BT)
            return
        }
        if (afterRequest == REQ_AUTO_BG) {
            if (has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) finishAuto()
            else cancelAuto(getString(R.string.main_auto_need_bg))
            return
        }
        pickCar()
    }

    @SuppressLint("MissingPermission")
    private fun pickCar() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return cancelAuto(getString(R.string.main_no_bluetooth))
        val devices = try { adapter.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
        if (devices.isEmpty()) return cancelAuto(getString(R.string.main_no_paired))
        val names = devices.map { d -> (try { d.name } catch (e: SecurityException) { null }) ?: d.address }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_pick_car))
            .setItems(names.toTypedArray()) { _, i ->
                Prefs.sp(this).edit()
                    .putString(Prefs.CAR_ADDRESS, devices[i].address)
                    .putString(Prefs.CAR_NAME, names[i])
                    .apply()
                askBackgroundLocation()
            }
            .setOnCancelListener { cancelAuto(getString(R.string.main_auto_not_set_up)) }
            .show()
    }

    private fun askBackgroundLocation() {
        if (has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return finishAuto()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_one_more_title))
            .setMessage(getString(R.string.main_bg_location_msg))
            .setPositiveButton(R.string.common_continue) { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_AUTO_BG)
            }
            .setNegativeButton(R.string.common_cancel) { _, _ -> cancelAuto(getString(R.string.main_auto_not_set_up)) }
            .setCancelable(false)
            .show()
    }

    private fun finishAuto() {
        Prefs.sp(this).edit().putBoolean(Prefs.AUTO_START, true).apply()
        autoChanged()
        if (!batteryOk()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_auto_on_title))
                .setMessage(getString(R.string.main_auto_on_battery_msg))
                .setPositiveButton(R.string.main_battery_settings) { _, _ -> batterySettings() }
                .setNegativeButton(R.string.common_later, null)
                .show()
        } else {
            toast(getString(R.string.main_auto_on_toast, Prefs.carName(this)))
        }
    }

    // ---------------------------------------------------------------- import from a file picker

    fun pickImportFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"   // CSV files arrive with many different types (WhatsApp, email, Drive…)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_IMPORT)
        } catch (e: Exception) {
            toast(getString(R.string.main_no_file_picker))
        }
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Sharing.importUri(this, uri, confirm = false) { refreshMap() }
    }

    companion object {
        const val EXTRA_START = "start_recording"
        const val TAB_DRIVE = 0
        const val TAB_MAP = 1
        const val TAB_TRIPS = 2
        const val TAB_SETTINGS = 3
        private const val REQ_PERMS = 1
        private const val REQ_AUTO_FINE = 2
        private const val REQ_AUTO_BT = 3
        private const val REQ_AUTO_BG = 4
        private const val REQ_BASICS = 5
        private const val REQ_IMPORT = 10
    }
}
