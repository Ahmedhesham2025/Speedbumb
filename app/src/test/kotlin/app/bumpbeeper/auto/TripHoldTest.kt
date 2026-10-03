package app.bumpbeeper.auto

import android.app.job.JobScheduler
import android.content.Context
import app.bumpbeeper.Bump
import app.bumpbeeper.BumpDb
import app.bumpbeeper.BumpEvent
import app.bumpbeeper.Fix
import app.bumpbeeper.Prefs
import app.bumpbeeper.sync.SpeedLimitSync
import app.bumpbeeper.sync.TripRoute
import org.junit.After
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

/** The generic hold for trips that started by themselves (#49): listeners, the speed-limit route, the spots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripHoldTest {
    private lateinit var ctx: Context
    private val now = 1_790_000_000_000L

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
        Prefs.sp(ctx).edit().clear().commit()
        SpeedLimitSync.clearPending(ctx)
        TripHold.installBuiltIns()
    }

    @After fun tearDown() {
        SpeedLimitSync.clearPending(ctx)
    }

    // ---------------------------------------------------------------- generic API

    @Test fun listenersRunOnceAndOnlyForHeldTrips() {
        val seen = ArrayList<String>()
        TripHold.onConfirmed { _, id -> seen += "yes $id" }
        TripHold.onRejected { _, id -> seen += "no $id" }
        TripHold.onExpired { _, id -> seen += "late $id" }
        TripHold.hold(ctx, 101, now)
        TripHold.hold(ctx, 102, now)
        TripHold.hold(ctx, 103, now - TripHold.MAX_AGE_MS)
        assertTrue(TripHold.isHeld(ctx, 101))
        assertFalse(TripHold.isHeld(ctx, 999))
        TripHold.confirm(ctx, 101)
        TripHold.confirm(ctx, 101)          // a second tap: nothing more
        TripHold.reject(ctx, 102)
        assertEquals(1, TripHold.expire(ctx, now))
        TripHold.confirm(ctx, 999)          // never held (a car-confirmed trip): nothing
        assertEquals(listOf("yes 101", "no 102", "late 103"), seen.filter { it.split(' ')[1] in setOf("101", "102", "103", "999") })
        assertTrue(TripHold.heldTrips(ctx).isEmpty())
    }

    // ---------------------------------------------------------------- speed-limit route

    private val m = 1.0 / 111_195.0

    private fun routeFor(tripId: Long) {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        Prefs.sp(ctx).edit().putBoolean(Prefs.SPEED_LIMITS, true).commit()
        val r = TripRoute().apply { for (s in 0 until 600) add(Fix(1_000_000L + s * 1000L, 30.09 + s * 22.2 * m, 31.32, 22.2, 0.0, 5.0)) }
        SpeedLimitSync.afterTrip(ctx, tripId, r, now, now - 1_000_000L)
    }

    private fun jobs() = ctx.getSystemService(JobScheduler::class.java).allPendingJobs.filter { it.id == SpeedLimitSync.JOB_ID }
    private fun routeFile(tripId: Long) = File(SpeedLimitSync.dir(ctx), "$tripId.route")

    @Test fun heldRouteIsKeptButNotScheduledUntilYes() {
        TripHold.hold(ctx, 7, now)
        routeFor(7)
        assertTrue(routeFile(7).exists())
        assertTrue(jobs().isEmpty())
        SpeedLimitSync.onAppStart(ctx, now)          // an app start doesn't schedule it either
        assertTrue(jobs().isEmpty())
        TripHold.confirm(ctx, 7)
        assertEquals(1, jobs().size)
        assertTrue(routeFile(7).exists())
    }

    @Test fun noDeletesTheRoute() {
        TripHold.hold(ctx, 8, now)
        routeFor(8)
        TripHold.reject(ctx, 8)
        assertFalse(routeFile(8).exists())
        assertTrue(jobs().isEmpty())
    }

    @Test fun unansweredRouteIsDeletedUnsentAfterADay() {
        TripHold.hold(ctx, 9, now)
        routeFor(9)
        SpeedLimitSync.onAppStart(ctx, now + TripHold.MAX_AGE_MS)
        assertFalse(routeFile(9).exists())
        assertFalse(TripHold.isHeld(ctx, 9))
        assertTrue(jobs().isEmpty())
    }

    @Test fun confirmedTripsRouteIsScheduledAsBefore() {
        routeFor(10)
        assertEquals(1, jobs().size)
    }

    // ---------------------------------------------------------------- spots of a rejected trip

    @Test fun noDeletesOnlySpotsThatTripAloneFound() {
        val db = BumpDb(ctx)
        try {
            val before = db.startTrip(now - 100_000)
            val trip = db.startTrip(now)
            val later = db.startTrip(now + 100_000)
            fun spot(): Long = db.insertBump(Bump(0, 30.0, 31.0, 0.0, 1, 1, 0, 1, now, now))
            fun event(t: Long, type: String, b: Long) =
                db.logEvent(BumpEvent(now, t, type, b, 30.0, 31.0, 20.0, 0.0, 3.0, 0.0, 0.0, ""))
            val onlyThisTrip = spot().also { event(trip, "new_bump", it); event(trip, "hit_repeat", it) }
            val hitLater = spot().also { event(trip, "new_bump", it); event(later, "hit", it) }
            val existedBefore = spot().also { event(before, "new_bump", it); event(trip, "hit", it) }
            val unrelated = spot().also { event(later, "new_bump", it) }
            TripHold.hold(ctx, trip, now)

            TripHold.reject(ctx, trip)

            val left = db.loadBumps().map { it.id }.toSet()
            assertEquals(setOf(hitLater, existedBefore, unrelated), left)
            assertFalse(onlyThisTrip in left)
            assertTrue(db.trips().none { it.id == trip })
            assertTrue(db.tripEvents(trip).isEmpty())
        } finally {
            db.close()
        }
    }
}
