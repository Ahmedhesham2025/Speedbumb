package app.bumpbeeper.research

import android.content.Context
import app.bumpbeeper.sync.RestoreReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
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

    @Test fun installShortIsTheStartOfTheInstallId() {
        RestoreReset.marker(ctx).writeText("3F2A9C1E-7B4D-4E2A-9F1C-0123456789AB")
        assertEquals("3f2a9c1e", ResearchFiles.installShort(ctx))
    }

    @Test fun withoutAnInstallIdItKeepsOneOfItsOwn() {
        RestoreReset.marker(ctx).delete()
        val id = ResearchFiles.installShort(ctx)
        assertTrue(id, Regex("[0-9a-f]{8}").matches(id))
        assertEquals("the same id every time", id, ResearchFiles.installShort(ctx))
        assertFalse("restore detection is left alone", RestoreReset.marker(ctx).exists())
    }

    @Test fun tidyRecoversLeftoversThenPrunesByAgeThenBySize() {
        val now = System.currentTimeMillis()
        file("rr_a_20261001T080000_000.csv.gz", 100, 15 * day, now)        // older than 14 days
        file("rr_a_20261003T080000_000.csv.gz", 400, 5 * day, now)         // oldest of the rest: over the size cap
        file("rr_a_20261004T080000_000.csv.gz", 300, 4 * day, now)
        file("rr_a_20261006T080000_001.csv.gz.part", 200, 1 * day, now)   // the app was killed while writing it
        file("notes.txt", 10, 30 * day, now)                               // not a research file: left alone
        ResearchFiles.tidy(dir, now, maxBytes = 600)
        assertEquals(
            listOf("rr_a_20261004T080000_000.csv.gz", "rr_a_20261006T080000_001.csv.gz"),
            ResearchFiles.list(ctx).map { it.name },
        )
        assertEquals(500L, ResearchFiles.size(ctx))
        assertTrue(File(dir, "notes.txt").exists())
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
