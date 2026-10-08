package app.bumpbeeper.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.bumpbeeper.Bump
import app.bumpbeeper.BumpDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Database version 7: the training outbox (upgrade from v6 keeps everything; batches respect the server limits). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrainingDbTest {
    private lateinit var ctx: Context
    private val opened = ArrayList<BumpDb>()

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
    }

    @After fun tearDown() {
        opened.forEach { it.close() }
    }

    private fun db() = BumpDb(ctx).also { opened.add(it) }

    private fun hasTable(db: SQLiteDatabase, name: String) =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    @Test fun freshInstallHasTheTrainingOutbox() {
        val sql = db().writableDatabase
        assertTrue("v7 or later", sql.version >= 7)
        assertTrue(hasTable(sql, TrainingStore.TABLE))
    }

    @Test fun upgradeFromV6KeepsDataAndAddsTheOutbox() {
        // A v6 database: today's schema without the training table.
        db().apply {
            insertBump(Bump(0, 30.0, 31.0, 90.0, 2, 3, 1, 2, 1L, 2L))
            SyncStore(this).outboxAdd(listOf("o1" to "{}"), 1, 5)
            writableDatabase.execSQL("DROP TABLE ${TrainingStore.TABLE}")
            writableDatabase.version = 6
            close()
        }
        val db = db()
        assertTrue("v7 or later", db.writableDatabase.version >= 7)
        assertTrue(hasTable(db.writableDatabase, TrainingStore.TABLE))
        assertEquals(1, db.loadBumps().size)
        assertEquals(1, SyncStore(db).outboxCount())
        assertEquals(0, TrainingStore(db).count())
    }

    @Test fun batchesRespectTheServerLimits() {
        val store = TrainingStore(db())
        store.add((1..150).map { TrainingStore.Item("s%03d".format(it), TrainingStore.SAMPLE, "{\"n\":$it}") }, 1000)
        store.add((1..12).map { TrainingStore.Item("t%02d".format(it), TrainingStore.TRIP, "{}") }, 1000)
        val b = store.batch()
        assertEquals(100, b.count { it.kind == TrainingStore.SAMPLE })
        assertEquals(10, b.count { it.kind == TrainingStore.TRIP })
        // Byte budget: big elements stop the batch early (but one always goes, so nothing gets stuck).
        val big = TrainingStore(db()).apply { clear() }
        big.add((1..5).map { TrainingStore.Item("b$it", TrainingStore.SAMPLE, "x".repeat(300_000)) }, 1000)
        assertEquals(2, big.batch().size)
        assertEquals(1, big.batch(maxBytes = 10).size)
        big.delete(listOf("b1", "b2"))
        assertEquals(3, big.count())
    }

    @Test fun unsentSamplesExpireAfterAWeekHeldOnesAfterADay() {
        val store = TrainingStore(db())
        val now = 100 * TrainingStore.KEEP_MS
        fun item(id: String) = listOf(TrainingStore.Item(id, TrainingStore.SAMPLE, "{}"))
        store.add(item("old"), now - TrainingStore.KEEP_MS - 1)
        store.add(item("week"), now - TrainingStore.KEEP_MS + 60_000)
        store.add(item("held-old"), now - TrainingStore.HELD_MS - 1, "7", held = true)
        store.add(item("held-new"), now - 60_000, "8", held = true)
        assertEquals(2, store.prune(now))
        assertEquals(listOf("week"), store.batch().map { it.id })
        assertEquals(2, store.count())
    }

    @Test fun heldTripsWaitForReleaseOrDiscard() {
        val store = TrainingStore(db())
        store.add(listOf(TrainingStore.Item("s1", TrainingStore.SAMPLE, "{\"client_sample_id\":\"s1\"}"),
            TrainingStore.Item("t1", TrainingStore.TRIP, "{}")), 1000, "5", held = true)
        store.add(listOf(TrainingStore.Item("s2", TrainingStore.SAMPLE, "{}")), 1000, "6", held = true)
        assertTrue("held rows are never uploaded", store.batch().isEmpty())
        store.release("5")
        assertEquals(setOf("s1", "t1"), store.batch().map { it.id }.toSet())
        // Local bookkeeping never reaches the upload JSON.
        assertEquals("{\"client_sample_id\":\"s1\"}", store.batch().first { it.id == "s1" }.json)
        store.discard("6")
        assertEquals(2, store.count())
    }
}
