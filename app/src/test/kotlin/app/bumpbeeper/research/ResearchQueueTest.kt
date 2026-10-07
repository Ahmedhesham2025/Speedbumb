package app.bumpbeeper.research

import android.content.Context
import app.bumpbeeper.auto.TripHold
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
import java.util.Locale

/** The upload queue on the phone: which files wait, retention with uploads, held trips, switching off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchQueueTest {
    private lateinit var ctx: Context
    private lateinit var dir: File
    private val day = 24 * 3600_000L
    private val now = System.currentTimeMillis()
    private val a = "20261001T080000"
    private val b = "20261002T080000"

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        dir = ResearchFiles.dir(ctx).apply { deleteRecursively(); mkdirs() }
        ResearchQueue.clear(ctx)
        TripHold.forgetAll(ctx)
        TripHold.reset()            // listeners outlive a test in Robolectric
        TripHold.installResearch()
    }

    @After fun tearDown() {
        TripHold.reset()
        TripHold.installBuiltIns()
        TripHold.installTraining(ctx)
        TripHold.installResearch()
    }

    private fun name(stamp: String, seg: Int) = String.format(Locale.US, "rr_0a1b2c3d_%s_%03d.csv.gz", stamp, seg)

    private fun file(name: String, bytes: Int, ageMs: Long): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(now - ageMs) }

    private fun names() = ResearchFiles.list(ctx).map { it.name }.toSet()
    private fun waiting(name: String) = ResearchQueue.read(ctx) { it.waiting(name) }

    @Test fun onlyTripsRecordedWithResearchOnWait() {
        file(name(a, 0), 10, 0)
        assertFalse("no record: never uploaded", waiting(name(a, 0)))
        ResearchQueue.tripStarted(ctx, a, 1, guessed = false)
        assertTrue(waiting(name(a, 0)))
        assertEquals(1 to 10L, ResearchQueue.waiting(ctx))
        ResearchQueue.edit(ctx) { it.uploaded(name(a, 0), now) }
        assertFalse(waiting(name(a, 0)))
    }

    @Test fun waitingFilesAreNeverPrunedForSizeBefore14Days() {
        ResearchQueue.tripStarted(ctx, a, 1, guessed = false, now = now - 6 * day)
        file(name(a, 0), 400, 6 * day)
        file(name(a, 1), 300, 5 * day)
        file(name(b, 0), 400, 7 * day)   // a trip without a record (recorded before consent): local only
        ResearchQueue.tidy(ctx, now, maxBytes = 500)
        assertEquals(setOf(name(a, 0), name(a, 1)), names())
        // At 14 days even a file still waiting goes.
        File(dir, name(a, 0)).setLastModified(now - 15 * day)
        ResearchQueue.tidy(ctx, now, maxBytes = 500)
        assertEquals(setOf(name(a, 1)), names())
    }

    @Test fun uploadedFilesGoSevenDaysAfterTheirUploadAndFirstForSize() {
        ResearchQueue.tripStarted(ctx, a, 1, guessed = false)
        file(name(a, 0), 10, 9 * day)
        file(name(a, 1), 400, 3 * day)
        file(name(a, 2), 300, 2 * day)
        file(name(a, 3), 300, 1 * day)
        ResearchQueue.edit(ctx) {
            it.uploaded(name(a, 0), now - 8 * day)
            it.uploaded(name(a, 1), now - 1 * day)
            it.drop(name(a, 2), "too_big")
        }
        ResearchQueue.tidy(ctx, now, maxBytes = 400)
        // a0: uploaded 8 days ago. a1 (uploaded) and a2 (never uploadable) go for size; a3 still waits.
        assertEquals(setOf(name(a, 3)), names())
        ResearchQueue.read(ctx) { s -> assertEquals("records of deleted files go", 0L, s.uploadedAt(name(a, 1))) }
    }

    @Test fun aHeldTripsFilesAreDeletedOnNo() {
        ResearchQueue.tripStarted(ctx, a, 7, guessed = true)
        ResearchQueue.tripEnded(ctx, a)
        file(name(a, 0), 10, 0)
        file(name(a, 1) + ResearchWriter.PART, 10, 0)
        File(ResearchQueue.cacheDir(ctx).apply { mkdirs() }, name(a, 0)).writeText("trimmed")
        ResearchQueue.tripStarted(ctx, b, 8, guessed = false)
        file(name(b, 0), 10, 0)
        TripHold.hold(ctx, 7)

        TripHold.reject(ctx, 7)

        assertEquals(setOf(name(b, 0)), names())
        assertFalse(File(dir, name(a, 1) + ResearchWriter.PART).exists())
        assertFalse(File(ResearchQueue.cacheDir(ctx), name(a, 0)).exists())
        assertFalse(waiting(name(a, 0)))
        assertTrue("another trip is untouched", waiting(name(b, 0)))
    }

    @Test fun anUnansweredHeldTripsFilesAreDeletedAtExpiry() {
        ResearchQueue.tripStarted(ctx, a, 9, guessed = true)
        ResearchQueue.tripEnded(ctx, a)
        file(name(a, 0), 10, 0)
        TripHold.hold(ctx, 9, now - TripHold.MAX_AGE_MS - 1)

        TripHold.expire(ctx, now)

        assertTrue(names().isEmpty())
        assertFalse(TripHold.isHeld(ctx, 9))
    }

    @Test fun aFailedDeleteKeepsTheTripHeld() {
        ResearchQueue.tripStarted(ctx, a, 11, guessed = true)
        // A directory with something in it can't be deleted like a file.
        File(File(dir, name(a, 0)).apply { mkdirs() }, "x").writeText("x")
        TripHold.hold(ctx, 11)

        TripHold.reject(ctx, 11)

        assertTrue("nothing of it may be sent", TripHold.isHeld(ctx, 11))
        assertFalse(waiting(name(a, 0)))
    }

    @Test fun switchingOffStopsTheQueueButKeepsTheFiles() {
        ResearchQueue.tripStarted(ctx, a, 1, guessed = false)
        file(name(a, 0), 10, 0)
        ResearchQueue.withdraw(ctx)
        assertEquals(0 to 0L, ResearchQueue.waiting(ctx))
        assertTrue(File(dir, name(a, 0)).exists())
        // A later opt-in queues only trips recorded after it.
        ResearchQueue.tripStarted(ctx, b, 2, guessed = false)
        file(name(b, 0), 10, 0)
        assertEquals(1 to 10L, ResearchQueue.waiting(ctx))
    }

    @Test fun recordsGoWithTheirFilesButNotWhileATripRuns() {
        ResearchQueue.tripStarted(ctx, a, 1, guessed = false)
        ResearchQueue.tripEnded(ctx, a)
        ResearchQueue.tripStarted(ctx, b, 2, guessed = false)   // just started: no file yet
        ResearchQueue.tidy(ctx, now)
        ResearchQueue.read(ctx) { s ->
            assertEquals(null, s.trip(a))
            assertTrue(s.trip(b) != null)
        }
    }

    @Test fun aBrokenQueueFileStartsOverAndNothingOfItUploads() {
        ResearchQueue.tripStarted(ctx, a, 1, guessed = false)
        file(name(a, 0), 10, 0)
        ResearchQueue.file(ctx).writeText("{not json")
        assertEquals(0 to 0L, ResearchQueue.waiting(ctx))
        ResearchQueue.tripStarted(ctx, b, 2, guessed = false)
        assertTrue(ResearchQueue.read(ctx) { it.trip(b) != null })
    }
}
