package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** "Delete my shared data" withdraws every online consent, road speed limits included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForgetMeConsentTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        Prefs.sp(ctx).edit().clear().commit()
        Prefs.setSyncChoice(ctx, Prefs.SYNC_SHARE)
        SpeedLimitSync.setEnabled(ctx, true)
    }

    @Test fun withdrawConsentTurnsSpeedLimitsOff() {
        val d = SpeedLimitSync.dir(ctx).apply { mkdirs() }
        File(d, "7.route").writeText("x")
        assertTrue(SpeedLimitSync.allowed(ctx))

        Sync.withdrawConsent(ctx)

        assertFalse("asked again before any new lookup", Prefs.speedLimits(ctx))
        assertEquals(Prefs.SYNC_UNSET, Prefs.syncChoice(ctx))
        assertFalse(SpeedLimitSync.allowed(ctx))
        SpeedLimitSync.clearPending(ctx)   // the async delete may still be running; leave the folder empty
    }

    @Test fun forgetMeSwitchesSpeedLimitsOffAtOnce() {
        Sync.forgetMe(ctx) { }
        // Set before the background part starts, so nothing can look up under the new anonymous ID.
        assertFalse(Prefs.speedLimits(ctx))
        assertEquals(Prefs.SYNC_UNSET, Prefs.syncChoice(ctx))
    }
}
