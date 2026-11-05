package app.bumpbeeper.auto

import android.Manifest
import android.app.Application
import android.app.job.JobScheduler
import android.content.Intent
import app.bumpbeeper.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Owner decisions: a clear drive started by itself is kept and sent without asking; a short or slow one still asks
 * (and goes unsent when unanswered). After a restart of the phone, auto start is set up again without opening the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepAndBootTest {
    private lateinit var ctx: Application
    private val day = 24 * 60 * 60 * 1000L
    private val realApply = BootReceiver.apply

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        Prefs.sp(ctx).edit().clear().commit()
        ctx.getSharedPreferences("start_log", 0).edit().clear().commit()
        ctx.getSharedPreferences("trip_hold", 0).edit().clear().commit()
        ctx.getSharedPreferences("trip_hold_done", 0).edit().clear().commit()
    }

    @After fun tearDown() { BootReceiver.apply = realApply }

    /** An automatic trip: confirmed by the speed gate, then [s] seconds at [kmh], a fix a second. */
    private fun drive(s: Int, kmh: Double): TripConfirm {
        val tc = TripConfirm(automatic = true)
        for (t in 0..s) tc.onFix(t * 1000L, kmh)
        return tc
    }

    @Test fun aClearDriveIsKeptWithoutAsking() {
        assertTrue(drive(120, 40.0).clearDrive(1300.0))          // 2 min at 40 km/h, 1.3 km
        assertFalse("a false start at home", drive(6, 0.0).clearDrive(0.0))
        assertFalse("1 km, but too slow (a walk, a bike)", drive(300, 15.0).clearDrive(1200.0))
        assertFalse("fast, but under 1 km", drive(80, 40.0).clearDrive(800.0))
        assertFalse("never confirmed", TripConfirm(automatic = true).clearDrive(5000.0))
        // A tunnel: a 60 s gap between fixes counts 5 s, not 60.
        val tc = TripConfirm(automatic = true)
        listOf(0L, 1000L, 61_000L, 62_000L).forEach { tc.onFix(it, 50.0) }
        assertEquals(7_000L, tc.fastMs)
    }

    @Test fun aKeptDriveIsNeverDeletedUnanswered() {
        // BumpService: held at trip end, released through "Yes" at once (TripHold.confirm) when it is a clear drive.
        TripHold.hold(ctx, 7L, now = 0L)
        TripHold.confirm(ctx, 7L, now = 1000L)
        assertEquals(0, TripHold.expire(ctx, now = 3 * day))
        assertFalse(TripHold.isHeld(ctx, 7L))
        assertFalse(TripHold.wasDiscarded(ctx, 7L))
    }

    @Test fun aDoubtfulTripStillAsksAndGoesWhenUnanswered() {
        TripHold.hold(ctx, 8L, now = 0L)
        assertEquals(1, TripHold.expire(ctx, now = 2 * day))
        assertTrue(TripHold.wasDiscarded(ctx, 8L))
        assertTrue(StartLog.read(ctx).list.any { it.second == StartLog.Reason.HELD_DELETED })
    }

    @Test fun afterARestartDetectionIsSetUpAgainWithoutTheApp() {
        Prefs.sp(ctx).edit().putBoolean(Prefs.AUTO_DETECT, true).commit()
        shadowOf(ctx).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        // The real path: AutoDetect.apply → BumpService.watch, a foreground start of the watching service.
        val started = generateSequence { shadowOf(ctx).nextStartedService }.toList()
        assertTrue(started.toString(), started.any { it.action == "app.bumpbeeper.WATCH" })
        assertEquals(StartLog.Reason.BOOT_REARMED, StartLog.read(ctx).list.last().second)
        assertNotNull("the hourly check survives the restart", ctx.getSystemService(JobScheduler::class.java).getPendingJob(4201))
    }

    @Test fun aFailingRestartIsCaughtAndLogged() {
        Prefs.sp(ctx).edit().putBoolean(Prefs.AUTO_DETECT, true).commit()
        BootReceiver.apply = { throw IllegalStateException("startForegroundService not allowed") }
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))   // must not throw
        assertEquals(StartLog.Reason.START_REFUSED, StartLog.read(ctx).list.last().second)
    }

    @Test fun switchedOffNothingStarts() {
        var called = false
        BootReceiver.apply = { called = true; true }
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(called)
    }
}
