package app.bumpbeeper.research

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/** The recorder end to end: sensors and what BumpService hands it reach one finished file, and stop leaves nothing behind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchRecorderTest {
    private lateinit var app: Application
    private lateinit var sm: SensorManager

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        Prefs.sp(app).edit().clear().commit()
        ResearchFiles.dir(app).deleteRecursively()
    }

    private fun sensor(type: Int): Sensor = ShadowSensor.newInstance(type).also { shadowOf(sm).addSensor(it) }

    private fun event(s: Sensor, timestampNs: Long, vararg v: Float): SensorEvent =
        ShadowSensorManager.createSensorEvent(v.size).apply { sensor = s; timestamp = timestampNs; v.copyInto(values) }

    private fun stopAndRead(r: ResearchRecorder): ResearchFile {
        r.stop()
        assertTrue("finished", r.awaitStopped(5000))
        return ResearchReader.read(ResearchFiles.list(app).single())
    }

    @Test fun offUnlessSwitchedOn() {
        assertNull(ResearchRecorder.startIfEnabled(app, null, 1))
        assertTrue(ResearchFiles.list(app).isEmpty())
    }

    @Test fun sensorEventsBecomeLinesAtTheirOwnTimeAndScale() {
        Prefs.setResearchRecording(app, true)
        val accel = sensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensor(Sensor.TYPE_GYROSCOPE)
        val start = SystemClock.elapsedRealtimeNanos()   // Robolectric's clock stands still: the recorder starts here too
        val r = ResearchRecorder.startIfEnabled(app, null, 7)!!
        r.onResearchThread {   // where Android delivers them, after the start-up lines
            r.onSensorChanged(event(accel, start + 12_345_600, 0.1234f, -9.80665f, 0f))   // 12.3456 ms after the start
            r.onSensorChanged(event(gyro, start + 17_345_600, 0.0123f, 0f, 1.5f))
        }
        val f = stopAndRead(r)
        val a = f.records.single { it.code == "a" }
        val g = f.records.single { it.code == "g" }
        assertEquals(123L, a.tDms)                                   // 0.1 ms units, rounded down
        assertEquals(listOf("123", "-9807", "0"), a.fields)          // mm/s²
        assertEquals(173L, g.tDms)
        assertEquals(listOf("12", "0", "1500"), g.fields)            // mrad/s
        assertTrue(f.meta["sensor.a"], f.meta["sensor.a"]!!.endsWith("batch_us=0;asked_us=5000"))
        assertEquals("absent", f.meta["sensor.gr"])
        assertEquals("7", f.meta[ResearchFormat.TRIP_ID])
    }

    @Test fun afterStopNothingIsLeftRegistered() {
        Prefs.setResearchRecording(app, true)
        sensor(Sensor.TYPE_ACCELEROMETER)
        val r = ResearchRecorder.startIfEnabled(app, null, 1)!!
        val broadcasts = listOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT, Intent.ACTION_BATTERY_CHANGED).map { Intent(it) }
        assertTrue(shadowOf(sm).hasListener(r))
        for (i in broadcasts) assertEquals(i.action, 1, shadowOf(app).getReceiversForIntent(i).size)
        r.stop()
        assertTrue(r.awaitStopped(5000))
        assertFalse("a 200 Hz listener left behind would run all day", shadowOf(sm).hasListener(r))
        for (i in broadcasts) assertTrue(i.action, shadowOf(app).getReceiversForIntent(i).isEmpty())
    }

    @Test fun aTripsEventsReachOneFinishedFile() {
        Prefs.setResearchRecording(app, true)
        val r = ResearchRecorder.startIfEnabled(app, BumpService.SOURCE_CAR, 42)!!
        r.onLocation(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 30.0444
            longitude = 31.2357
            speed = 12.5f
            accuracy = 4f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        r.label("bump")
        r.carBluetooth(false)
        r.stop()   // stopAndRead stops again: twice is harmless
        val f = stopAndRead(r)
        val file = ResearchFiles.list(app).single()
        assertTrue(file.name, Regex("rr_[0-9a-f]{8}_\\d{8}T\\d{6}_000\\.csv\\.gz").matches(file.name))
        assertTrue("named by the research id", file.name.startsWith("rr_${f.meta[ResearchFormat.RESEARCH_ID]}_"))
        assertFalse(f.truncated)
        assertEquals("rr2", f.meta["format"])
        assertEquals("car", f.meta["start_source"])
        assertEquals("42", f.meta[ResearchFormat.TRIP_ID])
        assertTrue("each sensor is listed, present or absent", f.meta.containsKey("sensor.a") && f.meta.containsKey("sensor.sd"))
        assertEquals("the car was connected at the start, then left", listOf(1.0, 0.0), f.records.filter { it.code == "bt" }.map { it.value(0) })
        assertEquals("keyguard state at the start", 0.0, f.records.single { it.code == "lk" }.value(0), 0.0)
        val fix = f.records.single { it.code == "G" }
        assertEquals(30.0444, fix.value(0), 1e-7)
        assertEquals(12.5, fix.value(3), 1e-9)
        assertTrue("no altitude", fix.value(2).isNaN())
        assertEquals("bump", f.records.single { it.code == "lbl" }.text(0))
        assertEquals("0", f.meta[ResearchFormat.END_DROPPED])
    }
}
