package app.bumpbeeper.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.bumpbeeper.Fix
import app.bumpbeeper.Geo
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Live speed limit on the phone: consent gating, request format, the shared daily quota, 429 and 503. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSpeedLimitTest {
    private lateinit var ctx: Context
    private var t = 1_000_000L
    private val offset = 1_789_990_000_000L
    private val wall get() = t + offset
    private var lat = 30.0

    /** Anonymous sign-in, and `speed-limits` answering [status] with [kmh] for every point. */
    private class FakeBackend(var status: Int = 200, val kmh: Int = 60) : Transport {
        val bodies = ArrayList<JSONObject>()
        val headers = ArrayList<Map<String, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            if (!url.endsWith("/speed-limits")) return HttpResult(200, """{"access_token":"jwt","expires_in":3600,"refresh_token":"r","user":{"id":"u"}}""")
            bodies.add(JSONObject(body)); this.headers.add(headers)
            if (status != 200) return HttpResult(status, """{"error":"x"}""")
            val n = JSONObject(body).getJSONArray("points").length()
            val limits = JSONArray().apply { for (i in 0 until n) put(JSONObject().put("i", i).put("kmh", kmh)) }
            return HttpResult(200, JSONObject().put("limits", limits).toString())
        }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        LiveState.resetTrip()
    }

    private fun consent(on: Boolean = true, version: Int = 1, sync: String = Prefs.SYNC_RECEIVE) {
        Prefs.sp(ctx).edit().putBoolean(Prefs.LIVE_LIMITS, on).putInt(Prefs.LIVE_LIMITS_CONSENT_VERSION, version)
            .putString(Prefs.SYNC_CHOICE, sync).commit()
    }

    private fun live(net: Transport, exec: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }) =
        LiveSpeedLimit(ctx, Handler(Looper.getMainLooper()), { false }, net, exec, { t }, { wall })

    /** Holds the network calls until [run] is called: an answer that arrives late. */
    private class LateExec : java.util.concurrent.Executor {
        val queued = ArrayList<Runnable>()
        override fun execute(r: Runnable) { queued.add(r) }
        fun run() { queued.forEach { it.run() }; queued.clear(); shadowOf(Looper.getMainLooper()).idle() }
    }

    /** [seconds] of driving north at 72 km/h, one fix a second. */
    private fun drive(l: LiveSpeedLimit, seconds: Int) = repeat(seconds) {
        t += 1000
        lat = Geo.move(lat, 31.0, 0.0, 20.0)[0]
        l.onFix(Fix(t, lat, 31.0, 20.0, 0.0, 5.0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun nothingLeavesThePhoneWithoutAllThreeConsents() {
        for ((on, version, sync) in listOf(Triple(false, 1, Prefs.SYNC_SHARE), Triple(true, 0, Prefs.SYNC_SHARE), Triple(true, 1, Prefs.SYNC_UNSET))) {
            consent(on, version, sync)
            val net = FakeBackend()
            val l = live(net)
            drive(l, 120)
            assertTrue(net.bodies.isEmpty())
            assertNull(LiveState.speedLimitKmh)
            assertFalse(LiveState.liveLimitsOn)
        }
    }

    @Test fun looksUpTheLastPointAndShowsIt() {
        consent()
        val net = FakeBackend()
        val l = live(net)
        drive(l, 25)
        assertEquals(1, net.bodies.size)
        val pts = net.bodies[0].getJSONArray("points")
        assertTrue(pts.length() in LiveLimitPlanner.MIN_POINTS..LiveLimitPlanner.MAX_POINTS)
        // Epoch ms of the newest fix when the call went out (the 21st: the first 300 m are never sent).
        assertEquals(1_021_000L + offset, pts.getJSONObject(pts.length() - 1).getLong("t"))
        assertEquals(SpeedLimitSync.REGION, net.headers[0][SpeedLimitSync.REGION_HEADER])
        assertEquals("Bearer jwt", net.headers[0]["Authorization"])
        assertEquals(60, LiveState.speedLimitKmh)
        assertTrue(LiveState.liveLimitsOn)
        assertEquals(1, SpeedLimitQuota.used(ctx, wall))
        // Switched off mid-trip: the limit is gone at once.
        Prefs.sp(ctx).edit().putBoolean(Prefs.LIVE_LIMITS, false).commit()
        drive(l, 1)
        assertNull(LiveState.speedLimitKmh)
        l.close()
    }

    @Test fun liveLeavesTheLastCallsOfTheDayToTheAfterTripScore() {
        consent()
        val net = FakeBackend()
        // The after-trip lookups already used some of today's shared calls.
        repeat(SpeedLimitQuota.MAX_PER_DAY - SpeedLimitQuota.KEEP_FOR_AFTER_TRIP - 1) { assertTrue(SpeedLimitQuota.take(ctx, wall)) }
        val l = live(net)
        drive(l, 600)
        assertEquals(1, net.bodies.size)   // one call, then fewer than 4 were left
        assertEquals(SpeedLimitQuota.MAX_PER_DAY - SpeedLimitQuota.KEEP_FOR_AFTER_TRIP, SpeedLimitQuota.used(ctx, wall))
        // The after-trip lookup still gets the last 4, and no more.
        repeat(SpeedLimitQuota.KEEP_FOR_AFTER_TRIP) { assertTrue(SpeedLimitQuota.take(ctx, wall)) }
        assertFalse(SpeedLimitQuota.take(ctx, wall))
        // A new UTC day starts from zero.
        assertEquals(0, SpeedLimitQuota.used(ctx, wall + 24 * 60 * 60 * 1000L))
    }

    @Test fun quota429EndsBothKindsOfLookupForTheDay() {
        consent()
        val net = FakeBackend(status = 429)
        val l = live(net)
        drive(l, 600)
        assertEquals(1, net.bodies.size)
        assertEquals(SpeedLimitQuota.MAX_PER_DAY, SpeedLimitQuota.used(ctx, wall))
        assertFalse(SpeedLimitQuota.take(ctx, wall))
        // A new trip the same day doesn't try again either.
        net.status = 200
        drive(live(net), 600)
        assertEquals(1, net.bodies.size)
    }

    @Test fun serverTroubleWaitsFiveMinutes() {
        consent()
        val net = FakeBackend(status = 503)
        val l = live(net)
        drive(l, 25)
        assertEquals(1, net.bodies.size)
        drive(l, 295)   // 300 s after the call is the next fix
        assertEquals(1, net.bodies.size)
        drive(l, 1)
        assertEquals(2, net.bodies.size)
        assertNull(LiveState.speedLimitKmh)
    }

    @Test fun forgetMeTurnsLiveLimitsOffAndAsksAgain() {
        consent(sync = Prefs.SYNC_SHARE)
        assertTrue(LiveSpeedLimit.allowed(ctx))
        Sync.withdrawConsent(ctx)
        assertFalse(Prefs.liveLimits(ctx))
        assertEquals(0, Prefs.liveLimitsConsentVersion(ctx))
        assertFalse(LiveSpeedLimit.allowed(ctx))
    }

    @Test fun lateAnswerAfterTripEndShowsNothing() {
        consent()
        val exec = LateExec()
        val l = live(FakeBackend(), exec)
        drive(l, 25)
        assertEquals(1, exec.queued.size)
        LiveState.overLimit = 2
        l.close()
        exec.run()
        assertNull(LiveState.speedLimitKmh)
        assertEquals(0, LiveState.overLimit)
        assertTrue(l.closed)
        assertNull(l.planner.limit(t))
    }

    @Test fun lateAnswerAfterConsentLostShowsNothing() {
        consent()
        val exec = LateExec()
        val l = live(FakeBackend(), exec)
        drive(l, 25)
        Prefs.sp(ctx).edit().putInt(Prefs.LIVE_LIMITS_CONSENT_VERSION, 0).commit()   // no fix in between
        exec.run()
        assertNull(LiveState.speedLimitKmh)
        assertNull(l.planner.limit(t))
        l.tick()
        assertNull(LiveState.speedLimitKmh)
        assertTrue(l.closed)
    }

    @Test fun switchedBackOnStartsAfresh() {
        consent()
        val l = live(FakeBackend())
        drive(l, 25)
        assertEquals(60, LiveState.speedLimitKmh)
        val before = l.planner
        Prefs.sp(ctx).edit().putBoolean(Prefs.LIVE_LIMITS, false).commit()
        drive(l, 1)
        assertTrue(l.closed)
        Prefs.sp(ctx).edit().putBoolean(Prefs.LIVE_LIMITS, true).commit()
        drive(l, 1)
        assertFalse(l.closed)
        assertTrue(before !== l.planner)
        assertNull(LiveState.speedLimitKmh)
    }

    @Test fun anyFailureCountsAsOfflineNeverACrash() {
        consent()
        var calls = 0
        val l = live(Transport { url, _, _ ->
            if (!url.endsWith("/speed-limits")) HttpResult(200, """{"access_token":"jwt","expires_in":3600,"refresh_token":"r","user":{"id":"u"}}""")
            else { calls++; throw IllegalStateException("boom") }
        })
        drive(l, 25)
        assertEquals(1, calls)
        drive(l, 295)
        assertEquals(1, calls)
        drive(l, 1)
        assertEquals(2, calls)   // 5 min later, like offline
        assertNull(LiveState.speedLimitKmh)
    }
}
