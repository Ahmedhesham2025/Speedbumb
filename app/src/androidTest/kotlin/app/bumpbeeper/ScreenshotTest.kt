package app.bumpbeeper

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Real screenshots of the app for the store listings and the owner, taken on the CI emulator
 * (.github/workflows/screenshots.yml). Not a behaviour test: it seeds demo data, opens every tab and saves
 * PNGs to <external files>/screenshots/<language>/. The workflow runs it once per language and pulls the folder.
 * Every screen is reached through the app's own code (no test-only layouts): the redesign's states too (spots
 * ahead, label mode, a trip's summary, Diagnostics, the research settings page), the street map with the network on,
 * and the dark theme.
 *
 * It wipes the app's bumps, trips and settings first, so only run it on an emulator, never on a phone in use.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext

    @Test
    fun takeScreenshots() {
        grantPermissions()
        fakeFix(true)   // a position near the demo bumps, driving north-east: the puck, heading-up, the red road
        resetSettings(Prefs.SYNC_SHARE)
        seedDemoData()
        liveDemo(recording = true)

        val lang = ctx.resources.configuration.locales[0].language.ifEmpty { "en" }
        val dir = File(ctx.getExternalFilesDir(null), "screenshots/$lang").apply { deleteRecursively(); mkdirs() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle(2000)
            liveDemo(recording = true)   // GPS counts as live only for 5 s after the last fix
            settle(800)                  // let the 4-per-second screen refresh pick it up
            shot(dir, "01-drive")

            // The rest are taken with recording stopped, as a parked phone would show them.
            LiveState.recording = false
            listOf(
                MainActivity.TAB_MAP to "02-map",
                MainActivity.TAB_TRIPS to "03-trips",
                MainActivity.TAB_SETTINGS to "04-settings",
                MainActivity.TAB_DRIVE to "05-drive-parked",   // "Last trip" comes from the saved trips, as on a phone
            ).forEach { (tab, name) ->
                scenario.onActivity { it.select(tab) }
                settle(1500)   // map and trips load from the database on a background thread
                shot(dir, name)
            }

            // The Map's "OpenStreetMap" filter: the downloaded OSM bumps as grey dots, with their credit.
            osmFilter(scenario, true)
            shot(dir, "16-map-osm")
            osmFilter(scenario, false)

            redesignStates(scenario, dir, "")

            // The street map needs the internet: on for these shots only. The shared-map choice goes back to "not
            // answered" first, so nothing is sent to our server (Sync stays offline until it is answered).
            Prefs.sp(ctx).edit().putString(Prefs.SYNC_CHOICE, Prefs.SYNC_UNSET).commit()
            network(true)
            try {
                for ((tab, name) in listOf(MainActivity.TAB_MAP to "13-map-streets", MainActivity.TAB_DRIVE to "14-drive-streets")) {
                    scenario.onActivity { it.select(tab) }
                    waitForMap(scenario, tab)   // style, tiles and the label fonts from OpenFreeMap
                    shot(dir, name)
                    mapCheck(scenario, tab, File(dir, "$name.txt"))
                }
                osmFilter(scenario, true)
                waitForMap(scenario, MainActivity.TAB_MAP)
                shot(dir, "17-map-osm-streets")
                mapCheck(scenario, MainActivity.TAB_MAP, File(dir, "17-map-osm-streets.txt"))
                osmFilter(scenario, false)
                scenario.onActivity { it.select(MainActivity.TAB_DRIVE) }   // the next shots are the Drive tab's
                // Driving on the street map with a strong bump close ahead (the screen the owner asked for).
                val demo = BumpDb(ctx).let { db -> try { db.loadBumps().first() } finally { db.close() } }
                liveDemo(recording = true)
                LiveState.spotsAhead = listOf(SpotAhead(demo, 120.0, Severity.STRONG, Confidence.FULL, WarnSound.STRONG),
                    SpotAhead(demo, 640.0, Severity.MILD, Confidence.FULL, WarnSound.MILD))
                seedRoadName(scenario, "Qasr El Nil St")   // only used when the emulator's tiles name no road at the puck
                waitForMap(scenario, MainActivity.TAB_DRIVE)
                liveDemo(recording = true)
                LiveState.setSpeedLimit(50, SystemClock.elapsedRealtime())
                LiveState.spotsAhead = listOf(SpotAhead(demo, 120.0, Severity.STRONG, Confidence.FULL, WarnSound.STRONG),
                    SpotAhead(demo, 640.0, Severity.MILD, Confidence.FULL, WarnSound.MILD))
                settle(1500)   // short: the GPS counts as live for 5 s after the demo's fix
                shot(dir, "15-drive-streets-strong")
                mapCheck(scenario, MainActivity.TAB_DRIVE, File(dir, "15-drive-streets-strong.txt"))
                // 20 s of driving on the street map (a new fix each second, a strong bump ahead): frame times and
                // how often the maps were updated, for the smoothness work.
                perfDrive(scenario, File(dir, "perf-drive-streets.txt"))
                // The map engine's own log lines (requests for tiles and label fonts), to explain missing labels.
                instr.uiAutomation.executeShellCommand("logcat -d -t 5000").use { fd ->
                    val all = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { String(it.readBytes()) }
                    val keep = Regex("(?i)mbgl|maplibre|mln|glyph|font|openfreemap|http")
                    File(dir, "map-log.txt").writeText(all.lineSequence().filter { keep.containsMatchIn(it) }.toList().takeLast(300).joinToString(System.lineSeparator()))
                }
                LiveState.spotsAhead = emptyList()
                LiveState.recording = false
            } finally {
                network(false)
            }

            // The same screens in the dark theme (the activity is recreated, as Android does on a theme change).
            instr.uiAutomation.executeShellCommand("cmd uimode night yes").close()
            try {
                Prefs.sp(ctx).edit().putString(Prefs.SYNC_CHOICE, Prefs.SYNC_SHARE).commit()
                scenario.recreate()
                settle(2000)
                liveDemo(recording = true)
                settle(800)
                shot(dir, "01-drive-dark")
                LiveState.recording = false
                listOf(
                    MainActivity.TAB_MAP to "02-map-dark",
                    MainActivity.TAB_TRIPS to "03-trips-dark",
                    MainActivity.TAB_SETTINGS to "04-settings-dark",
                    MainActivity.TAB_DRIVE to "05-drive-parked-dark",
                ).forEach { (tab, name) ->
                    scenario.onActivity { it.select(tab) }
                    settle(1500)
                    shot(dir, name)
                }
                redesignStates(scenario, dir, "-dark")
            } finally {
                instr.uiAutomation.executeShellCommand("cmd uimode night no").close()
                scenario.recreate()
                settle(1500)
            }

            // The first-run question: unanswered again, then a fresh resume asks it.
            resetSettings(Prefs.SYNC_UNSET)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            settle(1500)
            shot(dir, "06-sharing-question")
        }
        fakeFix(false)
        val files = dir.listFiles { f -> f.name.endsWith(".png") }.orEmpty()
        assertTrue("expected 28 screenshots in $dir, got ${files.size}: ${files.map { it.name }.sorted()}", files.size == 28)
    }

    /**
     * The redesign's states, each reached through the app's own code: the next-bump card from the engine's spots
     * ahead (set as BumpService publishes them), label mode, a trip's summary page, Diagnostics and the research and
     * recordings settings page. [suffix] "-dark" for the dark pass.
     */
    private fun redesignStates(scenario: ActivityScenario<MainActivity>, dir: File, suffix: String) {
        val demo = BumpDb(ctx).let { db -> try { db.loadBumps().first() } finally { db.close() } }
        scenario.onActivity { it.select(MainActivity.TAB_DRIVE) }
        for ((ahead, name) in listOf(
            listOf(SpotAhead(demo, 120.0, Severity.STRONG, Confidence.FULL, WarnSound.STRONG),
                SpotAhead(demo, 640.0, Severity.MILD, Confidence.FULL, WarnSound.MILD)) to "07-drive-strong",
            listOf(SpotAhead(demo, 280.0, Severity.MODERATE, Confidence.FULL, WarnSound.MODERATE),
                SpotAhead(demo, 700.0, Severity.MILD, Confidence.SOFT, WarnSound.SOFT)) to "08-drive-next",
        )) {
            liveDemo(recording = true)
            LiveState.setSpeedLimit(50, SystemClock.elapsedRealtime())
            LiveState.spotsAhead = ahead
            settle(1200)
            shot(dir, name + suffix)
        }
        LiveState.labelMode = true
        LiveState.lastLabel = Labels.BUMP
        LiveState.labelCount = 7
        settle(1200)
        shot(dir, "11-label-mode$suffix")
        LiveState.labelMode = false
        LiveState.lastLabel = ""
        LiveState.labelCount = 0
        LiveState.spotsAhead = emptyList()
        LiveState.recording = false

        val trip = BumpDb(ctx).let { db -> try { db.trips().first { it.score in 0..89 } } finally { db.close() } }
        scenario.onActivity { a ->
            a.select(MainActivity.TAB_TRIPS)
            (a.pages[MainActivity.TAB_TRIPS] as TripsPage).details(trip)
        }
        settle(1500)
        shot(dir, "09-trip-summary$suffix")
        scenario.onActivity { (it.pages[MainActivity.TAB_TRIPS] as TripsPage).back() }

        scenario.onActivity { a ->
            a.select(MainActivity.TAB_SETTINGS)
            (a.pages[MainActivity.TAB_SETTINGS] as SettingsPage).openDiagnostics()
        }
        settle(1500)
        shot(dir, "10-diagnostics$suffix")
        instr.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        settle(800)

        scenario.onActivity { (it.pages[MainActivity.TAB_SETTINGS] as SettingsPage).openResearch() }
        settle(1200)
        shot(dir, "12-settings-research$suffix")
        scenario.onActivity { (it.pages[MainActivity.TAB_SETTINGS] as SettingsPage).back() }
        scenario.onActivity { it.select(MainActivity.TAB_DRIVE) }
        settle(800)
    }

    /**
     * Drives 20 s on the Drive tab: the fake GPS moves north-east at 47 km/h (a fix a second) with a strong bump
     * coming closer, as BumpService would publish it. Writes the frame-time summary (`dumpsys gfxinfo`) and the map
     * updates per second ([app.bumpbeeper.ui.MapStats]) to [out].
     */
    private fun perfDrive(scenario: ActivityScenario<MainActivity>, out: File) {
        val demo = BumpDb(ctx).let { db -> try { db.loadBumps().first() } finally { db.close() } }
        scenario.onActivity { it.select(MainActivity.TAB_DRIVE) }
        liveDemo(recording = true)
        settle(1500)
        fun sh(cmd: String): String = instr.uiAutomation.executeShellCommand(cmd).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { String(it.readBytes()) }
        }
        sh("dumpsys gfxinfo ${ctx.packageName} reset")
        val before = app.bumpbeeper.ui.MapStats.snapshot()
        val t0 = SystemClock.elapsedRealtime()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        for (i in 0 until 20) {
            val step = i * 13.0   // metres driven
            try {
                lm.setTestProviderLocation(android.location.LocationManager.GPS_PROVIDER, android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                    latitude = 30.0490 + step * Math.cos(Math.toRadians(40.0)) / 111_000.0
                    longitude = 31.2360 + step * Math.sin(Math.toRadians(40.0)) / 96_000.0
                    bearing = 40f; speed = 13f; accuracy = 4f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            } catch (_: Exception) {}
            LiveState.lastFixAtMs = SystemClock.elapsedRealtime()
            LiveState.spotsAhead = listOf(SpotAhead(demo, 300.0 - step, Severity.STRONG, Confidence.FULL, WarnSound.STRONG))
            SystemClock.sleep(1000)
        }
        val s = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val after = app.bumpbeeper.ui.MapStats.snapshot()
        val names = listOf("geojson", "camera", "queries", "offlineDraws")
        val rates = names.indices.joinToString(" ") { "${names[it]}/s=" + String.format(java.util.Locale.US, "%.1f", (after[it] - before[it]) / s) }
        val keep = Regex("(?i)total frames|janky|percentile|slow ui|slow bitmap|slow issue|frame deadline|gpu")
        val gfx = sh("dumpsys gfxinfo ${ctx.packageName}").lineSequence().map { it.trim() }.filter { keep.containsMatchIn(it) }.take(16)
        out.writeText((listOf("seconds=" + String.format(java.util.Locale.US, "%.1f", s), rates) + gfx.toList()).joinToString(System.lineSeparator()) + System.lineSeparator())
        LiveState.spotsAhead = emptyList()
    }

    /** The Map tab with its "OpenStreetMap" filter chip on ([on]) or back to "All", through the page's own reload. */
    private fun osmFilter(scenario: ActivityScenario<MainActivity>, on: Boolean) {
        scenario.onActivity { a ->
            a.select(MainActivity.TAB_MAP)
            val page = a.pages[MainActivity.TAB_MAP]!!
            page.javaClass.getDeclaredField("filter").apply { isAccessible = true }.setInt(page, if (on) 5 else 0)
            page.onShow()
        }
        settle(1500)
    }

    /**
     * Next to a street-map shot, a line saying what the map had (style loaded, spots, your position, camera, pins
     * drawn), so a blank or pin-less shot can be explained. Read through the page's map, as it was drawn.
     */
    private fun mapCheck(scenario: ActivityScenario<MainActivity>, tab: Int, out: File) {
        var line = "no map"
        scenario.onActivity { a ->
            line = try {
                val page = a.pages[tab]!!
                val map = page.javaClass.getDeclaredField("map").apply { isAccessible = true }.get(page)!!
                map.javaClass.methods.first { it.name.startsWith("debug") && it.returnType == String::class.java && it.parameterCount == 0 }.invoke(map).toString()
            } catch (e: Exception) { "no map check: $e" }
        }
        out.writeText(line + System.lineSeparator())
    }

    /** Waits (up to 25 s) until the street map has drawn everything, labels included, and stopped moving. */
    private fun waitForMap(scenario: ActivityScenario<MainActivity>, tab: Int) {
        val tmp = File(ctx.cacheDir, "map-check.txt")
        val until = SystemClock.elapsedRealtime() + 25_000
        settle(3000)
        while (SystemClock.elapsedRealtime() < until) {
            mapCheck(scenario, tab, tmp)
            if (tmp.readText().contains("idle=true")) break
            SystemClock.sleep(500)
        }
        settle(1000)
    }

    /** The road name the Drive screen shows when the tiles give none at the puck (the map's test-only fallback). */
    private fun seedRoadName(scenario: ActivityScenario<MainActivity>, name: String) {
        scenario.onActivity { a ->
            try {
                val page = a.pages[MainActivity.TAB_DRIVE]!!
                val map = page.javaClass.getDeclaredField("map").apply { isAccessible = true }.get(page)!!
                map.javaClass.methods.first { it.name.startsWith("setRoadNameForTest") }.invoke(map, name)
            } catch (_: Exception) {}
        }
    }

    /**
     * A fake GPS position (the app reads the newest one Android has), through Android's mock-location provider:
     * near the demo bumps in Cairo, heading north-east at 47 km/h. [on] false removes it.
     */
    @Suppress("DEPRECATION")
    private fun fakeFix(on: Boolean) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
        try {
            if (!on) { providers.forEach { try { lm.removeTestProvider(it) } catch (_: Exception) {} }; return }
            instr.uiAutomation.executeShellCommand("appops set ${ctx.packageName} android:mock_location allow").close()
            SystemClock.sleep(500)
            for (p in providers) {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
                lm.addTestProvider(p, false, false, false, false, true, true, true, 1, 1)
                lm.setTestProviderEnabled(p, true)
                lm.setTestProviderLocation(p, android.location.Location(p).apply {
                    latitude = 30.0490; longitude = 31.2360; bearing = 40f; speed = 13f; accuracy = 4f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }
        } catch (e: Exception) {
            android.util.Log.w("ScreenshotTest", "no fake position", e)
        }
    }

    /** Wi-Fi and mobile data on or off (the workflow starts with both off). */
    private fun network(on: Boolean) {
        val v = if (on) "enable" else "disable"
        instr.uiAutomation.executeShellCommand("svc wifi $v").close()
        instr.uiAutomation.executeShellCommand("svc data $v").close()
        settle(if (on) 4000 else 1000)
    }

    // ---------------------------------------------------------------- setup

    private fun grantPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        for (p in perms) instr.uiAutomation.grantRuntimePermission(ctx.packageName, p)
        // "Run in the background" done too, so the Drive tab's setup checklist stays hidden.
        instr.uiAutomation.executeShellCommand("dumpsys deviceidle whitelist +${ctx.packageName}").close()
    }

    /** Default settings, the given shared-map choice, and the jolt meter open on the Drive tab. */
    private fun resetSettings(choice: String) {
        Prefs.sp(ctx).edit().clear()
            .putString(Prefs.SYNC_CHOICE, choice)
            .putBoolean("ui_show_meter", true)
            .putBoolean("ui_hide_auto_tip", true)   // DrivePage: "Auto start with your car" answered "Not now"
            .commit()
        // The first-run question's own bookkeeping (ui/SyncChoice.kt): never asked yet.
        ctx.getSharedPreferences("ui_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** A dozen bumps and potholes around central Cairo, and a few finished trips with scores. */
    private fun seedDemoData() {
        val db = BumpDb(ctx)
        try {
            db.clearAll()
            val now = System.currentTimeMillis()
            val day = 24 * 60 * 60 * 1000L
            // lat, lon, heading, hits, passes, kind (-1 bump … +1 pothole), side (-1 left … +1 right), jolt m/s², muted
            val spots = listOf(
                Spot(30.0444, 31.2357, 90.0, 9, 10, -0.8, 0.0, 4.1),
                Spot(30.0478, 31.2336, 180.0, 6, 7, -0.7, 0.0, 3.8),
                Spot(30.0512, 31.2290, 45.0, 12, 12, -0.9, 0.0, 4.6),
                Spot(30.0539, 31.2412, 270.0, 4, 5, -0.6, 0.0, 3.5),
                Spot(30.0571, 31.2268, 0.0, 7, 8, -0.75, 0.0, 4.0),
                Spot(30.0425, 31.2441, 135.0, 3, 4, -0.5, 0.0, 3.3),
                Spot(30.0463, 31.2485, 300.0, 5, 6, 0.8, 0.9, 7.4),
                Spot(30.0497, 31.2378, 90.0, 4, 5, 0.7, -0.8, 5.2),
                Spot(30.0553, 31.2331, 210.0, 6, 6, 0.9, 0.8, 8.1),
                Spot(30.0588, 31.2455, 20.0, 2, 3, 0.6, 0.0, 4.9),
                Spot(30.0410, 31.2295, 250.0, 3, 3, 0.85, -0.9, 6.6),
                Spot(30.0525, 31.2489, 160.0, 2, 2, 0.0, 0.0, 3.1),
                Spot(30.0599, 31.2372, 75.0, 1, 8, -0.4, 0.0, 3.0, muted = true),
            )
            // OpenStreetMap bumps as the sync caches them: some where the demo knows a spot, most where it doesn't.
            val osm = listOf(
                30.0445 to 31.2359, 30.0512 to 31.2292, 30.0462 to 31.2310, 30.0490 to 31.2420, 30.0530 to 31.2365,
                30.0560 to 31.2405, 30.0438 to 31.2392, 30.0505 to 31.2252, 30.0575 to 31.2320, 30.0470 to 31.2455,
            ).mapIndexed { i, (lat, lon) -> OsmBump("n${9_000_000 + i}", lat, lon, "hump") }
            app.bumpbeeper.sync.SyncStore(db).replaceOsmBumps(30.05, 31.237, 10_000.0, osm, now)
            spots.forEachIndexed { i, s ->
                db.insertBump(Bump(
                    0, s.lat, s.lon, s.heading, s.hits, s.passes, s.passes - s.hits, s.hits,
                    now - (30 - i) * day, now - i * day / 2, s.muted,
                    peakAvg = s.jolt, sevIndex = s.jolt, legacy = s.kind >= 0.25,   // the demo potholes: old pothole spots
                ))
            }
            // start (days ago), minutes, km, max km/h, speeding s, brakes, accels, corners, bumps fast, score
            val trips = listOf(
                Trip(0.2, 34, 18.6, 82.0, 0.0, 0, 1, 0, 0, 92),
                Trip(1.1, 22, 9.4, 96.0, 140.0, 2, 1, 1, 1, 78),
                Trip(2.3, 41, 23.8, 88.0, 40.0, 1, 0, 1, 0, 85),
                Trip(4.0, 15, 6.1, 71.0, 0.0, 0, 0, 0, 0, 97),
            )
            for (t in trips) {
                val start = now - (t.daysAgo * day).toLong()
                db.writableDatabase.insert("trips", null, ContentValues().apply {
                    put("start_ts", start); put("end_ts", start + t.minutes * 60_000L)
                    put("hits", t.bumpsFast + 5); put("new_bumps", 1); put("beeps", 6); put("misses", 0)
                    put("rejected", 2); put("distance_m", t.km * 1000); put("potholes", 2)
                    put("moving_s", t.minutes * 60.0 * 0.85); put("speeding_s", t.speedingS)
                    put("speeding_excess", t.speedingS * 8); put("max_speed", t.maxKmh)
                    put("harsh_brakes", t.brakes); put("harsh_accels", t.accels); put("harsh_corners", t.corners)
                    put("swerves", 0); put("bumps_fast", t.bumpsFast); put("phone_use", 0); put("score", t.score)
                })
            }
        } finally {
            db.close()
        }
    }

    /** What the recording service would show mid-trip (nothing is actually recording). */
    private fun liveDemo(recording: Boolean) {
        LiveState.resetTrip()
        LiveState.recording = recording
        LiveState.speedKmh = 47.0
        LiveState.accuracyM = 4.0
        LiveState.lastFixAtMs = SystemClock.elapsedRealtime()
        LiveState.bumpsOnMap = 13
        LiveState.potholesOnMap = 5
        LiveState.harshOnMap = 3
        LiveState.tripKm = 12.4
        LiveState.tripMovingS = 1380.0
        LiveState.liveScore = 88
        LiveState.tripBeeps = 9
        LiveState.tripHits = 7
        LiveState.tripPotholes = 3
        LiveState.tripHarshPotholes = 1
        LiveState.hasGyro = true
        LiveState.forwardKnown = true
        val lang = ctx.resources.configuration.locales[0].language
        LiveState.lastEvent = Phrases.strongBump(if (lang == "ar") "ar" else "en")
        // A calm road with two jolts in the last 30 s, for the jolt meter.
        for (i in 0 until LiveState.GRAPH_POINTS) {
            val bump = when (i) { in 120..126 -> 4.2f - (i - 123) * (i - 123) * 0.3f; in 230..234 -> 3.4f; else -> 0f }
            LiveState.pushGraph(0.4f + 0.35f * kotlin.math.sin(i * 0.7f) + bump)
        }
    }

    // ---------------------------------------------------------------- capture

    private fun settle(ms: Long) {
        instr.waitForIdleSync()
        SystemClock.sleep(ms)
        instr.waitForIdleSync()
    }

    private fun shot(dir: File, name: String) {
        val bmp: Bitmap? = instr.uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name failed", bmp)
        FileOutputStream(File(dir, "$name.png")).use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp!!.recycle()
    }

    private class Spot(
        val lat: Double, val lon: Double, val heading: Double, val hits: Int, val passes: Int,
        val kind: Double, val side: Double, val jolt: Double, val muted: Boolean = false,
    )

    private class Trip(
        val daysAgo: Double, val minutes: Int, val km: Double, val maxKmh: Double, val speedingS: Double,
        val brakes: Int, val accels: Int, val corners: Int, val bumpsFast: Int, val score: Int,
    )
}
