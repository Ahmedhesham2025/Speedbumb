package app.bumpbeeper

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The Drive screen's "Last trip" row comes from the saved trips, so it is there after the app restarts (it used to
 * read only the in-memory score of a trip that ended in this process, which is gone after every restart).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveLastTripTest {
    private lateinit var app: Application
    private var activity: ActivityController<MainActivity>? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        Prefs.sp(app).edit().clear().putString(Prefs.SYNC_CHOICE, Prefs.SYNC_RECEIVE).commit()
        LiveState.recording = false
        LiveState.lastTripScore = -1   // a fresh process: nothing in memory
        val db = BumpDb(app)
        try {
            db.clearAll()
            val start = System.currentTimeMillis() - 3_600_000L
            db.writableDatabase.insert("trips", null, ContentValues().apply {
                put("start_ts", start); put("end_ts", start + 20 * 60_000L)
                put("distance_m", 9_400.0); put("moving_s", 1_100.0); put("score", 82)
            })
        } finally { db.close() }
    }

    @After fun tearDown() {
        activity?.pause()?.stop()?.destroy()
        LiveState.lastTripScore = -1
    }

    private fun texts(v: View): List<TextView> =
        (if (v is TextView) listOf(v) else emptyList()) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { texts(g.getChildAt(it)) } } ?: emptyList())

    private fun shown(v: View): Boolean {
        var p: View? = v
        while (p != null) { if (p.visibility != View.VISIBLE) return false; p = p.parent as? View }
        return true
    }

    @Test fun lastTripShowsAfterARestart() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().also { activity = it }.get()
        val want = app.getString(R.string.drive_last_trip_title, app.getString(R.string.grade_good))
        // The trips load on a background thread, then the 4-per-second refresh shows the row.
        var row: TextView? = null
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            row = texts(a.window.decorView).firstOrNull { it.text.toString() == want }
            if (row != null && shown(row)) break
            Thread.sleep(50)
        }
        assertNotNull("no \"$want\" on the Drive screen", row)
        assertTrue("the last-trip row is hidden", shown(row!!))
        assertTrue(texts(a.window.decorView).any { it.text.toString() == "82" && shown(it) })
    }
}
