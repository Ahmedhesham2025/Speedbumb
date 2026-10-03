package app.bumpbeeper.ui

import app.bumpbeeper.R
import app.bumpbeeper.auto.AutoDetect.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Settings words for auto-detect and auto-stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoDetectTextTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun everyStatusHasItsOwnText() {
        val texts = Status.values().map { ctx.getString(AutoDetectText.statusRes(it)) }
        assertEquals(Status.values().size, texts.toSet().size)
        assertEquals("Off", ctx.getString(AutoDetectText.statusRes(Status.OFF)))
        assertEquals(R.string.auto_status_needs_permission, AutoDetectText.statusRes(Status.NEEDS_PERMISSION))
        assertEquals(R.string.auto_status_google, AutoDetectText.statusRes(Status.GOOGLE_ACTIVITY))
        assertEquals(R.string.auto_status_motion, AutoDetectText.statusRes(Status.MOTION_SENSOR))
        assertEquals(R.string.auto_status_passive, AutoDetectText.statusRes(Status.PASSIVE_ONLY))
        assertEquals(R.string.auto_status_not_running, AutoDetectText.statusRes(Status.NOT_RUNNING))
    }

    @Test fun watchingLineOnlyWhenOn() {
        assertEquals("On: motion sensor and short GPS checks\nWatching for driving",
            AutoDetectText.status(ctx, Status.MOTION_SENSOR, watching = true))
        assertEquals("On: motion sensor and short GPS checks", AutoDetectText.status(ctx, Status.MOTION_SENSOR, watching = false))
        assertEquals("Off", AutoDetectText.status(ctx, Status.OFF, watching = true))
    }

    @Test fun fixButtonOnlyWhenSomethingIsWrong() {
        assertTrue(AutoDetectText.showFix(Status.NEEDS_PERMISSION))
        assertTrue(AutoDetectText.showFix(Status.NOT_RUNNING))
        for (s in listOf(Status.OFF, Status.GOOGLE_ACTIVITY, Status.MOTION_SENSOR, Status.PASSIVE_ONLY)) assertFalse(AutoDetectText.showFix(s))
    }

    @Test fun autoStopLabel() {
        assertEquals("Never stop by itself when parked", AutoDetectText.autoStop(ctx, 0))
        assertEquals("Stop after parked for 5 min", AutoDetectText.autoStop(ctx, 5))
    }
}
