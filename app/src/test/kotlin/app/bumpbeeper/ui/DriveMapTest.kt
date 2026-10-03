package app.bumpbeeper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Drive screen's map rules (v1.8): when it shows, zoom by speed, frame rate, when the heading turns it. */
class DriveMapTest {

    @Test fun shownOnlyWhileRecordingWithTheSettingOn() {
        assertTrue(DriveMap.shown(recording = true, enabled = true))
        assertFalse(DriveMap.shown(recording = true, enabled = false))
        assertFalse(DriveMap.shown(recording = false, enabled = true))
    }

    @Test fun zoomsOutWithSpeed() {
        assertEquals(17.0, DriveMap.zoomFor(0.0), 1e-9)
        assertEquals(16.0, DriveMap.zoomFor(40.0), 1e-9)
        assertEquals(14.5, DriveMap.zoomFor(100.0), 1e-9)
        assertEquals(14.5, DriveMap.zoomFor(180.0), 1e-9)
        assertEquals(16.0, DriveMap.zoomFor(Double.NaN), 1e-9)
        var last = DriveMap.zoomFor(0.0)
        for (kmh in 5..160 step 5) {
            val z = DriveMap.zoomFor(kmh.toDouble())
            assertTrue("zoom must not grow with speed at $kmh km/h", z <= last)
            last = z
        }
    }

    @Test fun fewerFramesWhenStopped() {
        assertEquals(5, DriveMap.fpsFor(0.0))
        assertEquals(5, DriveMap.fpsFor(Double.NaN))
        assertEquals(5, DriveMap.fpsFor(DriveMap.STOPPED_KMH - 0.1))
        assertEquals(30, DriveMap.fpsFor(DriveMap.STOPPED_KMH))
        assertEquals(30, DriveMap.fpsFor(90.0))
    }

    @Test fun headingOnlyWhenMoving() {
        assertTrue(DriveMap.headingUsable(30.0, hasBearing = true))
        assertFalse(DriveMap.headingUsable(30.0, hasBearing = false))
        assertFalse(DriveMap.headingUsable(1.0, hasBearing = true))
        assertFalse(DriveMap.headingUsable(Double.NaN, hasBearing = true))
    }

    @Test fun spotsReloadedBeforeTheCarLeavesThem() {
        assertTrue(DriveMap.RELOAD_M < DriveMap.SPOTS_RADIUS_M / 2)
    }
}
