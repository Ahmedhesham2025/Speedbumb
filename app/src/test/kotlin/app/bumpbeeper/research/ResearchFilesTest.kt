package app.bumpbeeper.research

import android.content.Context
import app.bumpbeeper.sync.RestoreReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/** Where research files live, how they are named and kept (14 days, 2 GB), and the zip for sharing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchFilesTest {
    private lateinit var ctx: Context
    private lateinit var dir: File
    private val day = 24 * 3600_000L

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        dir = ResearchFiles.dir(ctx).apply { deleteRecursively(); mkdirs() }
    }

    private fun file(name: String, bytes: Int, ageMs: Long, now: Long): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(now - ageMs) }

    @Test fun filesStayOutOfBackupsAndPhoneMoves() {
        assertEquals(File(ctx.noBackupFilesDir, "research"), ResearchFiles.dir(ctx))
    }

    @Test fun namesAreUtcWithAThreeDigitSegmentEvenOnAnArabicPhone() {
        val start = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.OCTOBER, 7, 20, 15, 30) }.timeInMillis
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            assertEquals("rr_1a2b3c4d_20261007T201530_000.csv.gz", ResearchFiles.name("1a2b3c4d", start, 0))
            assertEquals("rr_1a2b3c4d_20261007T201530_012.csv.gz", ResearchFiles.name("1a2b3c4d", start, 12))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun theResearchIdIsRandomUsedForNothingElseAndRenewedOnRequest() {
        RestoreReset.marker(ctx).writeText("3f2a9c1e-7b4d-4e2a-9f1c-0123456789ab")
        val id = ResearchFiles.researchId(ctx)
        assertTrue(id, Regex("[0-9a-f]{8}").matches(id))
        assertNotEquals("not taken from the install id", "3f2a9c1e", id)
        assertEquals("the same until renewed", id, ResearchFiles.researchId(ctx))
        val next = ResearchFiles.renewId(ctx)
        assertNotEquals(id, next)
        assertEquals(next, ResearchFiles.researchId(ctx))
    }

    private fun idFile() = File(ctx.noBackupFilesDir, "research_id")

    @Test fun aSavedIdThatCantBeReadIsNeverReplaced() {
        val id = ResearchFiles.renewId(ctx)
        idFile().setReadable(false)   // a read failure: a new id would make this opt-in's unsent trips look withdrawn
        try {
            ResearchFiles.researchId(ctx)
            fail("expected IOException: the recorder then records nothing this trip")
        } catch (_: IOException) {
        } finally {
            idFile().setReadable(true)
        }
        assertEquals("still this opt-in's id", id, ResearchFiles.researchId(ctx))
        assertEquals(id, ResearchFiles.storedId(ctx))
    }

    @Test fun anIdThatCantBeSavedLeavesNoOldIdBehind() {
        ResearchFiles.renewId(ctx)
        idFile().setWritable(false)   // writing fails (say a full disk); deleting the file still works
        ResearchFiles.renewId(ctx)
        assertFalse("no old id left to count as current, even if the flag is lost with a restart", idFile().exists())
        assertNull(ResearchFiles.storedId(ctx))
        val next = ResearchFiles.researchId(ctx)   // the next recording saves one
        assertEquals(next, ResearchFiles.storedId(ctx))
    }

    @Test fun noIdYetMakesOne() {
        idFile().delete()
        val id = ResearchFiles.researchId(ctx)
        assertTrue(idFile().exists())
        assertEquals(id, ResearchFiles.storedId(ctx))
    }

    @Test fun tidyRecoversLeftoversThenPrunesByAgeThenBySize() {
        val now = System.currentTimeMillis()
        file("rr_a_20261001T080000_000.csv.gz", 100, 15 * day, now)        // older than 14 days
        file("rr_a_20261003T080000_000.csv.gz", 400, 5 * day, now)         // oldest of the rest: over the size cap
        file("rr_a_20261004T080000_000.csv.gz", 300, 4 * day, now)
        file("rr_a_20261005T080000_000.csv.gz", 0, 3 * day, now)           // empty: the server refuses it
        file("rr_a_20261006T080000_001.csv.gz.part", 200, 1 * day, now)   // the app was killed while writing it
        file("rr_a_20261007T080000_000.csv.gz.part", 50, 1000, now)        // being written right now: left alone
        file("notes.txt", 10, 30 * day, now)                               // not a research file: left alone
        ResearchFiles.tidy(dir, now, maxBytes = 600)
        assertEquals(
            listOf("rr_a_20261004T080000_000.csv.gz", "rr_a_20261006T080000_001.csv.gz"),
            ResearchFiles.list(ctx).map { it.name },
        )
        assertEquals(500L, ResearchFiles.size(ctx))
        assertTrue(File(dir, "rr_a_20261007T080000_000.csv.gz.part").exists())
        assertTrue(File(dir, "notes.txt").exists())
    }

    @Test fun tidyLaterKeepsTheLimitsInTheBackground() {
        val old = file("rr_a_20260901T080000_000.csv.gz", 10, 20 * day, System.currentTimeMillis())
        ResearchFiles.tidyLater(ctx)   // what app start and every trip end call, research on or off
        val until = System.currentTimeMillis() + 5000
        while (old.exists() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(old.exists())
    }

    @Test fun theDefaultsAreTwoWeeksAndTwoGigabytes() {
        val now = System.currentTimeMillis()
        file("rr_a_20261001T080000_000.csv.gz", 10, 13 * day, now)
        file("rr_a_20260920T080000_000.csv.gz", 10, 15 * day, now)
        ResearchFiles.tidy(dir, now)
        assertEquals(listOf("rr_a_20261001T080000_000.csv.gz"), ResearchFiles.list(ctx).map { it.name })
        assertEquals(2L * 1024 * 1024 * 1024, ResearchFiles.MAX_BYTES)
    }

    @Test fun listLeavesOutTheFileBeingWritten() {
        val now = System.currentTimeMillis()
        file("rr_a_20261006T080000_000.csv.gz", 10, 2000, now)
        file("rr_a_20261006T080000_001.csv.gz.part", 10, 1000, now)
        assertEquals(listOf("rr_a_20261006T080000_000.csv.gz"), ResearchFiles.list(ctx).map { it.name })
    }

    @Test fun theShareZipKeepsEveryFileAsItIs() {
        val a = File(dir, "rr_a_20261006T080000_000.csv.gz").apply { writeBytes(ByteArray(3000) { (it * 31).toByte() }) }
        val out = ByteArrayOutputStream()
        ResearchFiles.zipTo(listOf(a), out)
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            assertEquals(a.name, z.nextEntry!!.name)
            assertTrue(a.readBytes().contentEquals(z.readBytes()))
        }
        assertTrue("stored, not squeezed a second time", out.size() > 3000)
    }
}
