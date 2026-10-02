package app.bumpbeeper.sync

import android.app.job.JobScheduler
import android.content.Context
import app.bumpbeeper.BumpDb
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
import app.bumpbeeper.crash.CrashLog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** The sync review findings of PR #47: outbox on choice change, forget-me, rounding, crash scrubbing, clock skew. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncReviewFixesTest {
    private lateinit var ctx: Context
    private val hour = 60 * 60 * 1000L

    /** Answers like the backend; [serverOffsetMs] sets the server clock sent in the Date header (null = no header). */
    private class FakeBackend(
        val serverOffsetMs: Long? = null,
        val tokenCode: Int = 200,
    ) : Transport {
        val calls = ArrayList<String>()
        val bodies = HashMap<String, String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            bodies[name] = body
            val date = serverOffsetMs?.let { System.currentTimeMillis() + it } ?: 0L
            val session = """{"access_token":"a2","expires_in":3600,"refresh_token":"r2","user":{"id":"u"}}"""
            return when (name) {
                "signup" -> HttpResult(200, session, date)
                "token" -> if (tokenCode == 200) HttpResult(200, session, date) else HttpResult(tokenCode, """{"error":"invalid_grant"}""", date)
                "register_device" -> HttpResult(200, "\"u\"", date)
                "submit_observations" -> {
                    val batch = JSONObject(body).getJSONArray("batch")
                    HttpResult(200, JSONArray().apply { for (i in 0 until batch.length()) put(batch.getJSONObject(i).getString("client_obs_id")) }.toString(), date)
                }
                "spots_near" -> HttpResult(200, "[]", date)
                else -> HttpResult(204, "", date)
            }
        }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        CrashLog.dir(ctx).deleteRecursively()
        withStore { it.outboxAdd(listOf("obs-1" to """{"client_obs_id":"obs-1","kind":"jolt","lat":30.06,"lon":31.25,"observed_at":"2026-10-01T12:00:00Z"}"""), 1, System.currentTimeMillis()) }
    }

    private fun <T> withStore(block: (SyncStore) -> T): T {
        val db = BumpDb(ctx)
        try { return block(SyncStore(db)) } finally { db.close() }
    }

    private fun pending(): Int = withStore { it.outboxCount() }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!cond() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(what, cond())
    }

    // ---------------------------------------------------------------- (1) outbox cleared when leaving "share"

    @Test fun leavingShareEmptiesTheOutboxRightAway() {
        Sync.setChoice(ctx, Prefs.SYNC_SHARE)
        Thread.sleep(50)
        assertEquals("share keeps the queue", 1, pending())
        Sync.setChoice(ctx, Prefs.SYNC_RECEIVE)
        waitFor("receive empties the queue") { pending() == 0 }
    }

    @Test fun unsetAlsoEmptiesTheOutbox() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_SHARE)
        Sync.setChoice(ctx, Prefs.SYNC_UNSET)
        waitFor("unset empties the queue") { pending() == 0 }
        // Switching sharing back on later never sends the old points.
        Prefs.setSyncChoice(ctx, Prefs.SYNC_SHARE)
        val net = FakeBackend()
        Sync.run(ctx, false, Double.NaN, Double.NaN, net)
        assertFalse(net.calls.contains("submit_observations"))
    }

    // ---------------------------------------------------------------- (2) forget me with a dead session

    private fun storeSession(refresh: String) {
        SupabaseAuth.prefs(ctx).edit().putString("access_token", "old").putLong("expires_at", 0L)
            .putString("refresh_token", refresh).putString("user_id", "u-old").commit()
    }

    @Test fun forgetMeWithDeadSessionReportsFailureAndNeverSignsUp() {
        storeSession("dead")
        val net = FakeBackend(tokenCode = 400)
        assertFalse(Sync.forgetNow(ctx, net))
        assertEquals(listOf("token"), net.calls)   // no signup, no forget_me for a new empty device
        assertTrue(LiveState.syncLastError, LiveState.syncLastError.contains("sign-in expired"))
        // The old sign-in is kept (a later retry can still try it); local data is gone either way.
        assertEquals("dead", SupabaseAuth.prefs(ctx).getString("refresh_token", null))
        assertEquals(0, pending())
    }

    @Test fun forgetMeWithLiveSessionDeletesAndSignsOut() {
        storeSession("alive")
        val net = FakeBackend()
        assertTrue(Sync.forgetNow(ctx, net))
        assertEquals(listOf("token", "forget_me"), net.calls)
        assertNull(SupabaseAuth.prefs(ctx).getString("refresh_token", null))
    }

    @Test fun forgetMeWithoutSessionHasNothingToDelete() {
        val net = FakeBackend()
        assertTrue(Sync.forgetNow(ctx, net))
        assertTrue(net.calls.isEmpty())
    }

    // ---------------------------------------------------------------- (4) positions rounded before scheduling

    private fun scheduledLatLon(): Pair<Double, Double> {
        val job = ctx.getSystemService(JobScheduler::class.java).allPendingJobs
            .firstOrNull { it.extras.containsKey(Sync.EXTRA_LAT) && !it.extras.getDouble(Sync.EXTRA_LAT).isNaN() }
        assertNotNull("a sync job with a position", job)
        return job!!.extras.getDouble(Sync.EXTRA_LAT) to job.extras.getDouble(Sync.EXTRA_LON)
    }

    @Test fun tripEndPositionIsRoundedBeforeItIsScheduled() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        Sync.afterTrip(ctx, 30.044912, 31.236789)
        val (lat, lon) = scheduledLatLon()
        assertEquals(30.04, lat, 0.0)
        assertEquals(31.24, lon, 0.0)
    }

    @Test fun tripStartPositionIsRoundedBeforeItIsScheduled() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        Sync.pullAround(ctx, 29.987654, 31.211111)
        val (lat, lon) = scheduledLatLon()
        assertEquals(29.99, lat, 0.0)
        assertEquals(31.21, lon, 0.0)
    }

    // ---------------------------------------------------------------- (6) crash reports scrubbed

    private val crashText = """
        app_version=1.4.0
        device=Acme/Phone

        java.lang.IllegalStateException: bad fix Location[fused 30.044412,31.235745 hAcc=5] lon=-0.1234567
        while reading /data/user/0/app.bumpbeeper/files/traces/trace_2026-10-01_0815.csv
        	at app.bumpbeeper.BumpService.onFix(BumpService.kt:470)
        	at android.os.Handler.dispatchMessage(Handler.java:106)
    """.trimIndent()

    @Test fun scrubRemovesCoordinatesAndPathsButKeepsTheStack() {
        val s = CrashLog.scrub(crashText)
        assertFalse(s, s.contains("30.0444"))
        assertFalse(s, s.contains("31.2357"))
        assertFalse(s, s.contains("0.1234567"))
        assertFalse(s, s.contains("/data/user"))
        assertFalse(s, s.contains("trace_2026"))
        assertTrue(s, s.contains("<num>"))
        assertTrue(s, s.contains("<path>"))
        assertTrue(s, s.contains("at app.bumpbeeper.BumpService.onFix(BumpService.kt:470)"))
        assertTrue(s, s.contains("Handler.java:106"))
        assertTrue(s, s.contains("device=Acme/Phone"))
        assertTrue(s, s.contains("app_version=1.4.0"))
    }

    @Test fun uploadedCrashReportIsScrubbed() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_SHARE)
        CrashLog.dir(ctx).mkdirs()
        File(CrashLog.dir(ctx), "crash_2026-10-01_120000_000.txt").writeText(crashText)
        val net = FakeBackend()
        assertFalse(Sync.run(ctx, false, Double.NaN, Double.NaN, net))
        val stack = JSONObject(net.bodies["submit_crash_report"]!!).getString("stack")
        assertFalse(stack, stack.contains("30.044412"))
        assertFalse(stack, stack.contains("/data/user"))
        assertTrue(stack, stack.contains("BumpService.kt:470"))
    }

    // ---------------------------------------------------------------- (7) clock skew

    private fun parseIso(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time

    private fun uploadedObservedAt(net: FakeBackend): Long =
        parseIso(JSONObject(net.bodies["submit_observations"]!!).getJSONArray("batch").getJSONObject(0).getString("observed_at"))

    @Test fun phoneClockHoursAheadIsCorrectedToServerTime() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_SHARE)
        val net = FakeBackend(serverOffsetMs = -3 * hour)   // the phone runs 3 h ahead of the server
        assertFalse(Sync.run(ctx, false, Double.NaN, Double.NaN, net))
        val expected = parseIso("2026-10-01T09:00:00Z")
        val sent = uploadedObservedAt(net)
        assertTrue("sent ${ObservationJson.isoUtc(sent)}", abs(sent - expected) <= 5_000)
    }

    @Test fun smallSkewIsLeftAlone() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_SHARE)
        val net = FakeBackend(serverOffsetMs = -20 * 60 * 1000L)   // 20 min ahead: within tolerance
        assertFalse(Sync.run(ctx, false, Double.NaN, Double.NaN, net))
        assertEquals(parseIso("2026-10-01T12:00:00Z"), uploadedObservedAt(net))
    }

    @Test fun phoneBehindOrNoDateHeaderIsLeftAlone() {
        val behind = ClockWatch({ _, _, _ -> HttpResult(200, "", 10 * hour) }, now = { 7 * hour })
        behind.post("u", emptyMap(), "")
        assertEquals(0L, behind.correctionMs())
        val noDate = ClockWatch({ _, _, _ -> HttpResult(200, "") }, now = { 7 * hour })
        noDate.post("u", emptyMap(), "")
        assertEquals(0L, noDate.correctionMs())
        val ahead = ClockWatch({ _, _, _ -> HttpResult(200, "", 5 * hour) }, now = { 7 * hour })
        ahead.post("u", emptyMap(), "")
        assertEquals(2 * hour, ahead.correctionMs())
    }
}
