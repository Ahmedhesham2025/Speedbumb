package app.bumpbeeper.auto

import android.app.NotificationManager
import android.content.Context
import app.bumpbeeper.BumpDb
import app.bumpbeeper.Observation
import app.bumpbeeper.TripStats
import app.bumpbeeper.sync.OutboxSink
import app.bumpbeeper.sync.SyncStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Trips that started by themselves (#49): their shared-map points wait for "Was this a drive?". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnconfirmedTripTest {
    private lateinit var ctx: Context
    private val opened = ArrayList<BumpDb>()
    private val home = doubleArrayOf(30.0900, 31.3200)
    private val work = doubleArrayOf(30.0444, 31.2357)
    private val day = SyncStore.HELD_MAX_AGE_MS

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
    }

    @After fun tearDown() {
        opened.forEach { it.close() }
    }

    private fun db() = BumpDb(ctx).also { opened.add(it) }

    /** A home → work trip with one point half-way (outside the privacy zone), flushed at [now]. */
    private fun trip(s: SyncStore, tripId: Long, held: Boolean, now: Long): Int {
        val sink = OutboxSink(s, tripId, held)
        sink.onFix(home[0], home[1], 5.0)
        sink.onFix(30.0670, 31.2780, 5.0)   // about 6 km driven before the point: outside the privacy zone
        sink.record(Observation("middle-$tripId", "jolt", 30.0670, 31.2780, 90.0, 30.0, 4.0, 0.5, 0.0, 1_790_000_000_000L))
        sink.onFix(work[0], work[1], 5.0)
        return sink.flush(share = true, now = now)
    }

    @Test fun unconfirmedTripIsHeldNotQueued() {
        val s = SyncStore(db())
        assertEquals(1, trip(s, 4, held = true, now = 1_000))
        assertEquals(0, s.outboxCount())
        assertEquals(listOf(4L to 1_000L), s.heldTrips())
    }

    @Test fun confirmedTripQueuesAsBefore() {
        val s = SyncStore(db())
        trip(s, 4, held = false, now = 1_000)
        assertEquals(1, s.outboxCount())
        assertTrue(s.heldTrips().isEmpty())
    }

    @Test fun carBluetoothDuringTheTripConfirmsIt() {
        val s = SyncStore(db())
        val sink = OutboxSink(s, 5, held = true)
        sink.held = false   // what BumpService does when the car connects mid-trip
        sink.onFix(home[0], home[1], 5.0)
        sink.onFix(30.0670, 31.2780, 5.0)
        sink.record(Observation("m", "jolt", 30.0670, 31.2780, 90.0, 30.0, 4.0, 0.5, 0.0, 1L))
        sink.onFix(work[0], work[1], 5.0)
        sink.flush(true, 1)
        assertEquals(1, s.outboxCount())
    }

    @Test fun yesReleasesTheHeldPoints() {
        val d = db()
        val tripId = d.startTrip(1_000)
        trip(SyncStore(d), tripId, held = true, now = 2_000)
        TripCheck.answer(ctx, tripId, drove = true)
        val s = SyncStore(d)
        assertEquals("middle-$tripId", s.outboxBatch(10).single().first)
        assertTrue(s.heldTrips().isEmpty())
        assertNotNull(d.trip(tripId))
    }

    @Test fun noDeletesTheTripAndItsHeldPoints() {
        val d = db()
        val tripId = d.startTrip(1_000)
        d.endTrip(tripId, 2_000, TripStats())
        trip(SyncStore(d), tripId, held = true, now = 2_000)
        TripCheck.answer(ctx, tripId, drove = false)
        val s = SyncStore(d)
        assertEquals(0, s.outboxCount())
        assertTrue(s.heldTrips().isEmpty())
        assertNull(d.trip(tripId))
    }

    @Test fun unansweredForADayIsDroppedButTheTripStays() {
        val d = db()
        val s = SyncStore(d)
        val tripId = d.startTrip(1_000)
        trip(s, tripId, held = true, now = 10_000)
        trip(s, tripId + 1, held = true, now = 10_000 + day / 2)
        assertEquals(1, s.heldExpire(10_000 + day, day))
        assertEquals(listOf((tripId + 1) to (10_000 + day / 2)), s.heldTrips())
        assertEquals(0, s.outboxCount())
        assertNotNull(d.trip(tripId))
        TripCheck.answer(ctx, tripId, drove = true)          // a late "yes" finds nothing left to send
        assertEquals(0, s.outboxCount())
    }

    @Test fun switchingSharingOffDropsHeldPointsToo() {
        val s = SyncStore(db())
        trip(s, 7, held = true, now = 1)
        s.outboxClear()
        assertTrue(s.heldTrips().isEmpty())
    }

    @Test fun theQuestionIsPostedAndAnsweringRemovesIt() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tripId = db().startTrip(1_000)
        TripCheck.ask(ctx, tripId)
        val n = shadowOf(nm).getNotification(TripCheck.notificationId(tripId))
        assertNotNull(n)
        assertEquals(2, n.actions.size)
        assertEquals("Yes, I drove", n.actions[0].title.toString())
        TripCheck.answer(ctx, tripId, drove = true)
        assertNull(shadowOf(nm).getNotification(TripCheck.notificationId(tripId)))
    }
}
