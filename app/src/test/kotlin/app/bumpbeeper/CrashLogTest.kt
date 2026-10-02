package app.bumpbeeper

import app.bumpbeeper.crash.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The on-phone crash log: what a crash file holds, and that only the newest [CrashLog.KEEP] are kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLogTest {

    @Test fun listIsNewestFirstAndIgnoresOtherFiles() {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = CrashLog.dir(ctx).apply { deleteRecursively(); mkdirs() }
        File(dir, "crash_2001-01-01_000000_000.txt").writeText("a")
        File(dir, "crash_2003-01-01_000000_000.txt").writeText("c")
        File(dir, "crash_2002-01-01_000000_000.txt").writeText("b")
        File(dir, "notes.txt").writeText("x")
        File(dir, "crash_2004-01-01_000000_000.log").writeText("x")
        assertEquals(listOf("c", "b", "a"), CrashLog.list(ctx).map { it.readText() })
    }

    /**
     * Pruning is private and runs after a crash is written, so this goes through the real path: install the
     * handler, let it record a crash, and check the oldest files went. (A no-op handler stands in for Android's,
     * so the test JVM is not killed.)
     */
    @Test fun crashIsWrittenAndOnlyTheNewestTenAreKept() {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = CrashLog.dir(ctx).apply { deleteRecursively(); mkdirs() }
        // 12 old crashes, in name (= time) order; all older than the one about to be written.
        val old = (1..12).map { i -> File(dir, String.format(java.util.Locale.US, "crash_2000-01-%02d_000000_000.txt", i)).apply { writeText("old $i") } }

        val original = Thread.getDefaultUncaughtExceptionHandler()
        var passedOn: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
            CrashLog.install(ctx)
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            val boom = IllegalStateException("boom for the crash log test")
            handler.uncaughtException(Thread.currentThread(), boom)
            assertTrue("Android's handler still gets the crash", passedOn === boom)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }

        val kept = CrashLog.list(ctx)
        assertEquals(CrashLog.KEEP, kept.size)
        val newest = kept.first()
        assertFalse("the new crash is the newest file", newest.name.startsWith("crash_2000-"))
        val text = newest.readText()
        assertTrue(text.contains("app_version="))
        assertTrue(text.contains("thread=" + Thread.currentThread().name))
        assertTrue(text.contains("IllegalStateException: boom for the crash log test"))
        // 13 files, keep 10: the three oldest went, the nine newest old ones stayed.
        old.take(3).forEach { assertFalse("${it.name} pruned", it.exists()) }
        old.drop(3).forEach { assertTrue("${it.name} kept", it.exists()) }
    }
}
