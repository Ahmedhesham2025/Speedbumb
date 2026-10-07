package app.bumpbeeper.research

import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import app.bumpbeeper.BumpService
import app.bumpbeeper.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The recorder end to end: what BumpService hands it during a trip reaches one finished rr1 file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchRecorderTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Prefs.sp(app).edit().clear().commit()
        ResearchFiles.dir(app).deleteRecursively()
    }

    @Test fun offUnlessSwitchedOn() {
        assertNull(ResearchRecorder.startIfEnabled(app, null))
        assertTrue(ResearchFiles.list(app).isEmpty())
    }

    @Test fun aTripsEventsReachOneFinishedFile() {
        Prefs.setResearchRecording(app, true)
        val r = ResearchRecorder.startIfEnabled(app, BumpService.SOURCE_CAR)!!
        r.onLocation(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 30.0444
            longitude = 31.2357
            speed = 12.5f
            accuracy = 4f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        r.label("bump")
        r.carBluetooth(false)
        r.stop()
        r.stop()   // again is harmless
        assertTrue("finished", r.awaitStopped(5000))

        val files = ResearchFiles.list(app)
        assertEquals(1, files.size)
        assertTrue(files[0].name, Regex("rr_[0-9a-f]{8}_\\d{8}T\\d{6}_000\\.csv\\.gz").matches(files[0].name))
        val f = ResearchReader.read(files[0])
        assertFalse(f.truncated)
        assertEquals("rr1", f.meta["format"])
        assertEquals("car", f.meta["start_source"])
        assertTrue("each sensor is listed, present or absent", f.meta.containsKey("sensor.a") && f.meta.containsKey("sensor.sd"))
        assertEquals("the car was connected at the start, then left", listOf(1.0, 0.0), f.records.filter { it.code == "bt" }.map { it.value(0) })
        val fix = f.records.single { it.code == "G" }
        assertEquals(30.0444, fix.value(0), 1e-7)
        assertEquals(12.5, fix.value(3), 1e-9)
        assertTrue("no altitude", fix.value(2).isNaN())
        assertEquals("bump", f.records.single { it.code == "lbl" }.text(0))
        assertEquals("0", f.meta["end_dropped"])
    }
}
