package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.BumpDb
import app.bumpbeeper.Prefs
import app.bumpbeeper.crash.CrashLog
import org.json.JSONArray
import org.json.JSONObject
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

/** Nothing goes online before the first-run answer; "receive" only downloads; "share" uploads too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncGatingTest {
    private lateinit var ctx: Context

    /** Answers like the backend would and records which endpoints were called. */
    private class FakeBackend : Transport {
        val calls = ArrayList<String>()
        val bodies = HashMap<String, String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            bodies[name] = body
            return when (name) {
                "signup", "token" -> HttpResult(200, """{"access_token":"a","expires_in":3600,"refresh_token":"r","user":{"id":"u"}}""")
                "register_device" -> HttpResult(200, "\"u\"")
                "submit_observations" -> {
                    val batch = JSONObject(body).getJSONArray("batch")
                    HttpResult(200, JSONArray().apply { for (i in 0 until batch.length()) put(batch.getJSONObject(i).getString("client_obs_id")) }.toString())
                }
                "spots_near" -> HttpResult(200, """[{"id":5,"latitude":30.05,"longitude":31.24,"heading":90,"kind":"bump","side":null,"severity":3.0,"n_devices":2}]""")
                "spots_near_v2" -> HttpResult(200, """[{"id":5,"lat":30.05,"lon":31.24,"heading":90,"severity":3.0,"severity_band":"mild","confidence":"full","n_devices":2,"n_hits":3,"legacy":false}]""")
                else -> HttpResult(204, "")
            }
        }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        CrashLog.dir(ctx).deleteRecursively()
        // Something to upload: one queued observation and one saved crash.
        val db = BumpDb(ctx)
        try {
            SyncStore(db).outboxAdd(listOf("obs-1" to """{"client_obs_id":"obs-1","kind":"jolt","lat":30.06,"lon":31.25}"""), 1, System.currentTimeMillis())
        } finally {
            db.close()
        }
        CrashLog.dir(ctx).mkdirs()
        File(CrashLog.dir(ctx), "crash_2026-10-01_120000_000.txt").writeText("app_version=1.4.0\ndevice=Acme/Phone\n\nboom")
    }

    private fun pending(): Int {
        val db = BumpDb(ctx)
        try { return SyncStore(db).outboxCount() } finally { db.close() }
    }

    @Test fun unsetChoiceMakesNoCallsAtAll() {
        assertEquals(Prefs.SYNC_UNSET, Prefs.syncChoice(ctx))
        assertFalse(Prefs.shareBumps(ctx))
        val net = FakeBackend()
        assertFalse(Sync.run(ctx, false, 30.0444, 31.2357, net))
        assertFalse(Sync.run(ctx, true, 30.0444, 31.2357, net))
        assertTrue(net.calls.isEmpty())
        assertEquals(1, pending())
    }

    @Test fun receiveOnlyRegistersWithoutSharingAndDownloads() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
        assertFalse(Prefs.shareBumps(ctx))
        val net = FakeBackend()
        assertFalse(Sync.run(ctx, false, 30.0444, 31.2357, net))
        assertEquals(listOf("signup", "register_device", "spots_near_v2"), net.calls)
        assertFalse(JSONObject(net.bodies["register_device"]!!).getBoolean("share_enabled"))
        // The download position is rounded to about 1 km.
        assertEquals(30.04, JSONObject(net.bodies["spots_near_v2"]!!).getDouble("lat"), 0.0)
        assertEquals(0, pending())   // not sharing: queued points are dropped, never sent
        assertEquals(1, CrashLog.list(ctx).size)   // crash reports stay on the phone
    }

    @Test fun shareUploadsObservationsAndCrashes() {
        Prefs.setSyncChoice(ctx, Prefs.SYNC_SHARE)
        assertTrue(Prefs.shareBumps(ctx))
        val net = FakeBackend()
        assertFalse(Sync.run(ctx, false, 30.0444, 31.2357, net))
        assertEquals(listOf("signup", "register_device", "submit_observations", "submit_crash_report", "spots_near_v2"), net.calls)
        assertTrue(JSONObject(net.bodies["register_device"]!!).getBoolean("share_enabled"))
        assertEquals(0, pending())
        assertTrue(CrashLog.list(ctx).isEmpty())
    }

    @Test fun unknownChoiceCountsAsUnset() {
        Prefs.setSyncChoice(ctx, "everything")
        assertEquals(Prefs.SYNC_UNSET, Prefs.syncChoice(ctx))
    }
}
