package app.bumpbeeper

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.bumpbeeper.research.ResearchFiles
import app.bumpbeeper.research.ResearchFormat
import app.bumpbeeper.research.ResearchReader
import app.bumpbeeper.research.ResearchWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration

/** Settings → Diagnostics: the text shows no positions or tokens, and "Mark" writes a mark into the research file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsTest {
    private lateinit var app: Application
    private var service: ServiceController<BumpService>? = null
    private var activity: ActivityController<MainActivity>? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        Prefs.sp(app).edit().clear().putString(Prefs.SYNC_CHOICE, Prefs.SYNC_RECEIVE).commit()
        ResearchFiles.dir(app).deleteRecursively()
        LiveState.recording = false
        LiveState.researchRunning = false
        LiveState.labelMode = false
        LiveState.resetTrip()
        ShadowToast.reset()
    }

    @After fun tearDown() {
        activity?.pause()?.stop()?.destroy()
        service?.destroy()
        Thread.sleep(300)   // let the engine thread finish closing the trip
        LiveState.resetTrip()
    }

    private fun send(svc: BumpService, action: String) = svc.onStartCommand(Intent(app, BumpService::class.java).setAction(action), 0, 1)

    private fun finishedFile(): File? {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) {
            val names = ResearchFiles.dir(app).list().orEmpty()
            if (names.isNotEmpty() && names.none { it.endsWith(ResearchWriter.PART) }) return ResearchFiles.list(app).single()
            Thread.sleep(20)
        }
        return null
    }

    private fun marks(f: File) = ResearchReader.read(f).records.filter { it.code == ResearchFormat.LABEL.code }.map { it.text(0) }

    private fun views(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

    @Test fun theMarkButtonWritesAMarkIntoTheResearchFile() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().also { activity = it }.get()
        val page = DiagnosticsPage(a)
        val btn = views(page.view).filterIsInstance<TextView>().single { it.text.toString() == app.getString(R.string.diag_mark) }
        page.tick()
        assertFalse("no drive yet: off", btn.isEnabled)
        assertFalse(BumpService.mark(app))
        Prefs.setResearchRecording(app, true)   // after the screen's start-up checks

        val svc = Robolectric.buildService(BumpService::class.java).create().also { service = it }.get()
        send(svc, "app.bumpbeeper.START")
        assertTrue(LiveState.researchRunning)
        page.tick()
        assertTrue(btn.isEnabled)
        btn.performClick()
        assertEquals(app.getString(R.string.diag_mark_saved), ShadowToast.getTextOfLatestToast())
        assertTrue(BumpService.mark(app))
        send(svc, "app.bumpbeeper.STOP")
        assertFalse(LiveState.researchRunning)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("one lbl line per tap", listOf(Labels.MARK, Labels.MARK), marks(finishedFile()!!))
    }

    @Test fun withoutResearchOrLabelModeTheButtonSaysWhy() {
        val svc = Robolectric.buildService(BumpService::class.java).create().also { service = it }.get()
        send(svc, "app.bumpbeeper.START")
        assertTrue(LiveState.recording)
        assertFalse("nothing to write a mark into", BumpService.markable())
        assertFalse(BumpService.mark(app))
        Prefs.setResearchRecording(app, true)   // after the screen's start-up checks
        send(svc, "app.bumpbeeper.STOP")
        assertTrue(ResearchFiles.dir(app).list().isNullOrEmpty())
    }

    @Test fun inTheTraceAMarkIsItsOwnEventNotALabel() {
        val dir = File(app.cacheDir, "trace-test").apply { deleteRecursively() }
        val tw = TraceWriter(dir)
        tw.accel(1000, 0.0, 0.0, 9.8, Double.NaN, Double.NaN, Double.NaN, 0.0)
        tw.label(Labels.BUMP, 30.0, 31.0, 40.0)
        tw.mark(30.0, 31.0, 40.0)
        tw.label(Labels.UNDO, 30.0, 31.0, 40.0)
        tw.close()
        val samples = TraceReader.read(tw.file.readLines().asSequence())
        assertEquals(listOf("label", "mark", "label"), samples.filterIsInstance<TraceSample.Event>().map { it.type })
        assertTrue("the undo takes back the bump, not the mark", TraceReader.labels(samples).isEmpty())
    }

    @Test fun theTextShowsAgesAndAccuracyOnly() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))   // the clock starts near 0
        LiveState.lastFixAtMs = SystemClock.elapsedRealtime() - 3_000
        LiveState.accuracyM = 7.4
        val t = DiagnosticsPage.lines(app, "").joinToString("\n")
        assertTrue(t, t.contains(app.getString(R.string.diag_gps, 3L, 7)))
        assertTrue(t.contains(app.getString(R.string.diag_perm_fine, app.getString(R.string.diag_yes))))
        assertTrue(t.contains(app.getString(R.string.diag_last_error, app.getString(R.string.diag_none))))
        assertTrue(t.contains(BuildConfig.FLAVOR))
        assertFalse("no coordinates", Regex("""\d+\.\d{4,}""").containsMatchIn(t))
    }

    @Test fun theLastErrorIsShortWithoutPositionsPathsOrTokens() {
        val crash = "time=2026-10-08 09:15:02.123 +0300\napp_version=1.8.0\ndevice=x/y\nandroid=34\nthread=main\n\n" +
            "java.lang.IllegalStateException: fix 30.0444123,31.2357456 in /data/user/0/app.bumpbeeper/files/trace_1.csv " +
            "token eyJhbGciOiJIUzI1NiJ9abc123def456ghi\n\tat app.bumpbeeper.BumpService.onLocationChanged(BumpService.kt:1)\n"
        val s = DiagnosticsPage.shortError(crash)
        assertTrue(s, s.startsWith("2026-10-08 09:15 java.lang.IllegalStateException: fix"))
        for (gone in listOf("30.0444", "31.2357", "/data/user", "eyJhbGci", "\tat")) assertFalse("$gone in $s", s.contains(gone))
        assertTrue(s.length <= 160)
    }
}
