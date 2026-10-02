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
            LiveState.lastTripScore = 92
            listOf(
                MainActivity.TAB_MAP to "02-map",
                MainActivity.TAB_TRIPS to "03-trips",
                MainActivity.TAB_SETTINGS to "04-settings",
                MainActivity.TAB_DRIVE to "05-drive-parked",
            ).forEach { (tab, name) ->
                scenario.onActivity { it.select(tab) }
                settle(1500)   // map and trips load from the database on a background thread
                shot(dir, name)
            }

            // The first-run question: unanswered again, then a fresh resume asks it.
            resetSettings(Prefs.SYNC_UNSET)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            settle(1500)
            shot(dir, "06-sharing-question")
        }
        val files = dir.listFiles { f -> f.name.endsWith(".png") }.orEmpty()
        assertTrue("expected 6 screenshots in $dir, got ${files.size}", files.size == 6)
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
            spots.forEachIndexed { i, s ->
                db.insertBump(Bump(
                    0, s.lat, s.lon, s.heading, s.hits, s.passes, s.passes - s.hits, s.hits,
                    now - (30 - i) * day, now - i * day / 2, s.muted,
                    s.kind, if (s.kind == 0.0) 0 else s.hits, s.side, if (s.side == 0.0) 0 else s.hits, s.jolt,
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
        LiveState.lastEvent = Phrases.pothole(if (lang == "ar") "ar" else "en", Side.RIGHT)
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
