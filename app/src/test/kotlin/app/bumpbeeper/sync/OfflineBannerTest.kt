package app.bumpbeeper.sync

import android.content.Context
import android.net.ConnectivityManager
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Offline — shared bumps not updated" on the Drive screen: when it shows, and what the sync tells it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineBannerTest {
    private lateinit var ctx: Context
    private val now = 1_790_000_000_000L
    private val hour = 3_600_000L

    /** Answers spots_near_v2 with [spots], or fails with [code]. */
    private class Backend(var code: Int = 200) : Transport {
        val calls = ArrayList<String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            return when (name) {
                "signup", "token" -> HttpResult(200, """{"access_token":"a","expires_in":3600,"refresh_token":"r","user":{"id":"u"}}""")
                "spots_near_v2" -> if (code == 200) HttpResult(200, """[{"id":5,"lat":30.05,"lon":31.24,"heading":90,"severity":3.0,"n_devices":2,"n_hits":2,"legacy":false}]""")
                    else HttpResult(code, """{"message":"busy"}""")
                else -> HttpResult(204, "")
            }
        }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        LiveState.spotsOkAt = 0L
        LiveState.spotsFailed = false
    }

    @After fun tearDown() {
        LiveState.recording = false
        LiveState.spotsOkAt = 0L
        LiveState.spotsFailed = false
    }

    private fun show(rec: Boolean = true, syncOn: Boolean = true, okAt: Long = now - hour, failed: Boolean = false, online: Boolean = false) =
        OfflineBanner.show(rec, syncOn, okAt, failed, now) { online }

    @Test fun showsOnlyWhileRecordingOfflineWithOutOfDateSpots() {
        assertFalse("fresh spots: nothing to say", show())
        assertTrue("the last download failed", show(failed = true))
        assertTrue("older than the refresh interval", show(okAt = now - Sync.SPOTS_STALE_MS - 1))
        assertTrue("never downloaded", show(okAt = 0L))
        assertFalse("not recording", show(rec = false, failed = true))
        assertFalse("shared map off", show(syncOn = false, failed = true))
        assertFalse("there is a network: the download will come", show(failed = true, online = true))
    }

    @Test fun theNetworkIsOnlyAskedWhenTheSpotsAreOutOfDate() {
        var asked = 0
        assertFalse(OfflineBanner.show(true, true, now - hour, false, now) { asked++; false })
        assertEquals(0, asked)
        assertTrue(OfflineBanner.show(true, true, now - hour, true, now) { asked++; false })
        assertEquals(1, asked)
    }

    @Test fun noActiveNetworkIsOffline() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setDefaultNetworkActive(false)
        assertFalse(OfflineBanner.networkUp(ctx))
    }

    @Test fun aSuccessfulDownloadHidesItAndAFailedOneShowsIt() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        LiveState.recording = true
        val net = Backend(code = 503)
        assertTrue("retried later", Sync.run(ctx, true, 30.0444, 31.2357, net))
        assertTrue(LiveState.spotsFailed)
        assertEquals(0L, LiveState.spotsOkAt)
        assertTrue(OfflineBanner.show(true, true, LiveState.spotsOkAt, LiveState.spotsFailed, System.currentTimeMillis()) { false })
        net.code = 200
        val before = System.currentTimeMillis()
        assertFalse(Sync.run(ctx, true, 30.0444, 31.2357, net))
        assertFalse(LiveState.spotsFailed)
        assertTrue(LiveState.spotsOkAt >= before)
        assertFalse("hidden as soon as a download succeeds", OfflineBanner.show(ctx))
        assertEquals(listOf("signup", "spots_near_v2", "spots_near_v2"), net.calls)
    }
}
