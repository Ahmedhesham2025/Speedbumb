package app.bumpbeeper.research

import android.Manifest
import android.app.Application
import android.content.Intent
import app.bumpbeeper.BumpService
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

/** BumpService and research recording: a file only when it is on, finished at the trip's end, or at once when switched off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BumpServiceResearchTest {
    private lateinit var app: Application
    private lateinit var controller: ServiceController<BumpService>
    private lateinit var svc: BumpService

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        Prefs.sp(app).edit().clear().commit()
        ResearchFiles.dir(app).deleteRecursively()
        LiveState.recording = false
        controller = Robolectric.buildService(BumpService::class.java).create()
        svc = controller.get()
    }

    @After fun tearDown() {
        controller.destroy()
        Thread.sleep(300)   // let the engine thread finish closing the trip
    }

    private fun send(action: String) = svc.onStartCommand(Intent(app, BumpService::class.java).setAction(action), 0, 1)

    /** Research files once the writer is done with them (none left as `.part`), or empty after 5 s. */
    private fun finishedFiles(): List<File> {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) {
            val names = ResearchFiles.dir(app).list().orEmpty()
            if (names.isNotEmpty() && names.none { it.endsWith(ResearchWriter.PART) }) return ResearchFiles.list(app)
            Thread.sleep(20)
        }
        return emptyList()
    }

    @Test fun offATripLeavesNoResearchFile() {
        send("app.bumpbeeper.START")
        send("app.bumpbeeper.STOP")
        Thread.sleep(500)
        assertTrue(ResearchFiles.dir(app).list().isNullOrEmpty())
    }

    @Test fun onATripLeavesOneFinishedFile() {
        Prefs.setResearchRecording(app, true)
        send("app.bumpbeeper.START")
        assertTrue(LiveState.recording)
        send("app.bumpbeeper.STOP")
        val f = finishedFiles().single()
        assertTrue(f.name, f.name.endsWith("_000.csv.gz"))
        assertEquals("0", ResearchReader.read(f).meta[ResearchFormat.END_DROPPED])
    }

    @Test fun switchedOffMidTripTheFileIsFinishedAtOnce() {
        Prefs.setResearchRecording(app, true)
        send("app.bumpbeeper.START")
        Prefs.setResearchRecording(app, false)   // the user's privacy switch, while the trip goes on
        val f = finishedFiles().single()
        assertTrue("the trip is still recording", LiveState.recording)
        assertEquals("rr2", ResearchReader.read(f).meta["format"])
        send("app.bumpbeeper.STOP")
    }
}
