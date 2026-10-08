package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.BumpDb
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
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

/** "Help improve detection": consent gating, the consent call, upload and every server answer, expiry, forget me. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrainingUploadTest {
    private lateinit var ctx: Context
    private val day = 24 * 60 * 60 * 1000L

    /** Answers like the backend. [submit] = error code for submit_training_samples, or null to hold [keep] (all if null). */
    private class FakeBackend(val submit: String? = null, val keep: Set<String>? = null, val consent: String? = null) : Transport {
        val calls = ArrayList<String>()
        /** `enabled` of every set_training_consent call, in order. */
        val consents = ArrayList<Boolean>()
        val bodies = HashMap<String, String>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            bodies[name] = body
            return when (name) {
                "signup", "token" -> HttpResult(200, """{"access_token":"a","expires_in":3600,"refresh_token":"r","user":{"id":"u"}}""")
                "register_device" -> HttpResult(200, "\"u\"")
                "set_training_consent" -> if (consent != null) HttpResult(400, """{"code":"$consent","message":"no"}""") else {
                    consents.add(JSONObject(body).getBoolean("enabled"))
                    HttpResult(200, JSONObject(body).getBoolean("enabled").toString())
                }
                "submit_training_samples" -> when {
                    submit == "503" -> HttpResult(503, "busy")
                    submit != null -> HttpResult(400, """{"code":"$submit","message":"no"}""")
                    else -> {
                        val b = JSONObject(body).getJSONObject("batch")
                        fun ids(k: String, id: String) = JSONArray().apply {
                            val a = b.getJSONArray(k)
                            for (i in 0 until a.length()) a.getJSONObject(i).getString(id).let { if (keep == null || it in keep) put(it) }
                        }
                        HttpResult(200, JSONObject().put("samples", ids("samples", "client_sample_id")).put("trips", ids("trips", "client_trip_id")).toString())
                    }
                }
                "spots_near" -> HttpResult(200, "[]")
                else -> HttpResult(204, "")
            }
        }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.deleteDatabase("bumps.db")
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        Prefs.setSyncChoice(ctx, Prefs.SYNC_RECEIVE)
    }

    private fun <T> db(block: (BumpDb) -> T): T = Sync.withDb(ctx, block)

    private fun queue(vararg ids: String, at: Long = System.currentTimeMillis()) = db {
        TrainingStore(it).add(ids.map { id ->
            if (id.startsWith("t")) TrainingStore.Item(id, TrainingStore.TRIP, """{"client_trip_id":"$id"}""")
            else TrainingStore.Item(id, TrainingStore.SAMPLE, """{"client_sample_id":"$id"}""")
        }, at)
    }

    private fun queued(): Set<String> = db { TrainingStore(it).batch(1000, 1000, Int.MAX_VALUE).map { i -> i.id }.toSet() }

    /** Consent on and the server already told (as after a successful first run). */
    private fun consented() = Prefs.setTrainingState(ctx, true, TrainingConsent.TRAINING_CONSENT_VERSION, wipe = false, sendOn = false, note = "")

    @Test fun offByDefaultAndGatedOnTheNetworkChoice() {
        assertFalse(TrainingConsent.status(ctx).enabled)
        queue("s1")
        val net = FakeBackend()
        assertFalse(Sync.run(ctx, false, 30.0, 31.0, net))
        assertFalse("no training call without consent", net.calls.any { it.contains("training") })
        consented()
        assertTrue(TrainingConsent.active(ctx))
        Prefs.setSyncChoice(ctx, Prefs.SYNC_UNSET)
        assertFalse("consent alone isn't enough: the network must be allowed", TrainingConsent.active(ctx))
        assertFalse(Prefs.trainingActive(ctx))
    }

    @Test fun setEnabledTellsTheServerThenUploads() {
        TrainingConsent.setEnabled(ctx, true, TrainingConsent.TRAINING_CONSENT_VERSION)
        assertTrue(TrainingConsent.status(ctx).serverPending)
        queue("s1", "s2", "t1")
        val net = FakeBackend()
        assertFalse(TrainingConsent.run(ctx, net))
        assertEquals(listOf("signup", "register_device", "set_training_consent", "submit_training_samples"), net.calls)
        val consent = JSONObject(net.bodies["set_training_consent"]!!)
        assertTrue(consent.getBoolean("enabled"))
        assertEquals(TrainingConsent.TRAINING_CONSENT_VERSION, consent.getInt("version"))
        val batch = JSONObject(net.bodies["submit_training_samples"]!!).getJSONObject("batch")
        assertEquals(2, batch.getJSONArray("samples").length())
        assertEquals(1, batch.getJSONArray("trips").length())
        assertTrue(batch.has("app_version") && batch.has("sdk") && batch.has("brand"))
        assertTrue(queued().isEmpty())
        assertFalse(TrainingConsent.status(ctx).serverPending)
        assertEquals(0, LiveState.trainingQueued)
    }

    @Test fun onlyAcknowledgedElementsLeaveTheOutbox() {
        consented()
        queue("s1", "s2")
        TrainingConsent.run(ctx, FakeBackend(keep = setOf("s1")))
        assertEquals(setOf("s2"), queued())
    }

    @Test fun invalidBatchIsDropped() {
        consented()
        queue("s1", "t1")
        assertFalse(TrainingConsent.run(ctx, FakeBackend(submit = "22023")))
        assertTrue(queued().isEmpty())
        assertTrue(Prefs.trainingConsent(ctx))
    }

    @Test fun noConsentOnTheServerSwitchesItOffHereAndSaysSo() {
        consented()
        queue("s1")
        TrainingConsent.run(ctx, FakeBackend(submit = "42501"))
        assertTrue(queued().isEmpty())
        assertFalse(Prefs.trainingConsent(ctx))
        assertFalse(TrainingConsent.active(ctx))
        assertEquals(TrainingConsent.SESSION_RESET, TrainingConsent.status(ctx).lastError)
    }

    @Test fun unregisteredDeviceWhileSendingConsentIsRetriedWithAClearMessage() {
        TrainingConsent.setEnabled(ctx, true)
        assertTrue(TrainingConsent.run(ctx, FakeBackend(consent = "42501")))
        assertTrue(TrainingConsent.status(ctx).serverPending)
        assertTrue(TrainingConsent.status(ctx).lastError.contains("registered"))
        assertTrue(Prefs.trainingConsent(ctx))
    }

    @Test fun offThenOnWhileOfflineSendsTheWipeFirst() {
        consented()
        TrainingConsent.setEnabled(ctx, false)
        TrainingConsent.setEnabled(ctx, true)   // no run in between (offline)
        val net = FakeBackend()
        assertFalse(TrainingConsent.run(ctx, net))
        assertEquals(listOf(false, true), net.consents)
        assertFalse(TrainingConsent.status(ctx).serverPending)
        // A failed wipe keeps the flag: the "on" waits for it.
        TrainingConsent.setEnabled(ctx, false)
        TrainingConsent.setEnabled(ctx, true)
        assertTrue(TrainingConsent.run(ctx, FakeBackend(consent = "503")))
        assertTrue(Prefs.trainingWipePending(ctx) && Prefs.trainingOnPending(ctx))
        val later = FakeBackend()
        TrainingConsent.run(ctx, later)
        assertEquals(listOf(false, true), later.consents)
    }

    @Test fun consent2UploadsRightAfterTheTrip() {
        consented()
        assertTrue(TrainingConsent.uploadsRightAway(ctx))
        val now = System.currentTimeMillis()
        TrainingSink.queue(ctx, listOf(TrainingStore.Item("s1", TrainingStore.SAMPLE, """{"client_sample_id":"s1"}""")), 3L, now)
        val atTripEnd = FakeBackend()
        Sync.run(ctx, false, 30.0, 31.0, atTripEnd)
        assertTrue("the trip-end sync sends it", atTripEnd.calls.contains("submit_training_samples"))
        assertTrue(queued().isEmpty())
    }

    @Test fun onlyAnOlderConsentIsAskedAgainAndAcceptingItUploadsRightAway() {
        assertFalse("off: nothing to ask", TrainingConsent.needsUpdate(ctx))
        Prefs.setTrainingState(ctx, true, 1, wipe = false, sendOn = false, note = "")
        assertTrue(TrainingConsent.needsUpdate(ctx))
        assertFalse("consent 1 keeps its delay", TrainingConsent.uploadsRightAway(ctx))
        TrainingConsent.setEnabled(ctx, true)   // "Keep it on"
        assertFalse(TrainingConsent.needsUpdate(ctx))
        val net = FakeBackend()
        TrainingConsent.run(ctx, net)
        assertEquals("the same opt-in, the new text", listOf(true), net.consents)
        assertEquals(TrainingConsent.TRAINING_CONSENT_VERSION, JSONObject(net.bodies["set_training_consent"]!!).getInt("version"))
        assertTrue(TrainingConsent.uploadsRightAway(ctx))
    }

    @Test fun consent1TripsStillUploadHoursLaterNotAtTripEnd() {
        Prefs.setTrainingState(ctx, true, 1, wipe = false, sendOn = false, note = "")
        val now = System.currentTimeMillis()
        TrainingConsent.uploadLater(ctx, now)   // what TrainingSink.flush does on the engine thread
        TrainingSink.queue(ctx, listOf(TrainingStore.Item("s1", TrainingStore.SAMPLE, """{"client_sample_id":"s1"}""")), 3L, now)
        val after = Prefs.trainingUploadAfter(ctx)
        assertTrue(after >= now + 3_600_000L && after <= now + 6 * 3_600_000L)
        val atTripEnd = FakeBackend()
        Sync.run(ctx, false, 30.0, 31.0, atTripEnd)
        assertFalse(atTripEnd.calls.contains("submit_training_samples"))
        val later = FakeBackend()
        TrainingConsent.run(ctx, later, now = after + 1)
        assertTrue(later.calls.contains("submit_training_samples"))
        assertTrue(queued().isEmpty())
    }

    @Test fun dailyCapPausesUntilTomorrowAndStorageFullForThreeDays() {
        consented()
        queue("s1")
        val now = System.currentTimeMillis()
        TrainingConsent.run(ctx, FakeBackend(submit = "54000"), now)
        assertEquals(setOf("s1"), queued())
        val until = db { SyncStore(it).getLong(TrainingConsent.PAUSED_UNTIL) }
        // The server's caps count per UTC day: paused until the next UTC midnight, whatever the phone's time zone.
        assertEquals(0L, until % day)
        assertTrue(until > now && until <= now + day)
        assertEquals(TrainingConsent.nextUtcMidnight(now), until)
        val again = FakeBackend()
        TrainingConsent.run(ctx, again)
        assertFalse("paused: no upload today", again.calls.contains("submit_training_samples"))

        db { SyncStore(it).put(TrainingConsent.PAUSED_UNTIL, 0) }
        TrainingConsent.run(ctx, FakeBackend(submit = "53100"))
        val full = db { SyncStore(it).getLong(TrainingConsent.PAUSED_UNTIL) }
        assertTrue(full >= System.currentTimeMillis() + 3 * day - 60_000)
        assertEquals(setOf("s1"), queued())
    }

    @Test fun serverBusyIsRetriedAndKeepsTheOutbox() {
        consented()
        queue("s1")
        assertTrue(TrainingConsent.run(ctx, FakeBackend(submit = "503")))
        assertEquals(setOf("s1"), queued())
    }

    @Test fun unsentSamplesExpireAfterAWeek() {
        consented()
        queue("old", at = System.currentTimeMillis() - 8 * day)
        queue("new")
        val net = FakeBackend()
        TrainingConsent.run(ctx, net)
        val sent = JSONObject(net.bodies["submit_training_samples"]!!).getJSONObject("batch").getJSONArray("samples")
        assertEquals(1, sent.length())
        assertEquals("new", sent.getJSONObject(0).getString("client_sample_id"))
    }

    @Test fun switchingOffClearsTheOutboxAndTellsTheServer() {
        consented()
        queue("s1")
        TrainingConsent.setEnabled(ctx, false, TrainingConsent.TRAINING_CONSENT_VERSION)
        assertFalse(TrainingConsent.active(ctx))
        val deadline = System.currentTimeMillis() + 5_000
        while (queued().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(queued().isEmpty())
        val net = FakeBackend()
        assertFalse(TrainingConsent.run(ctx, net))
        assertFalse(JSONObject(net.bodies["set_training_consent"]!!).getBoolean("enabled"))
        assertFalse(net.calls.contains("submit_training_samples"))
    }

    @Test fun forgetMeTurnsTrainingOffAndClearsIt() {
        consented()
        queue("s1", "t1")
        Sync.withdrawConsent(ctx)
        assertFalse(Prefs.trainingConsent(ctx))
        assertFalse("the deleted device isn't told again", TrainingConsent.status(ctx).serverPending)
        // The blocking part empties it for sure (not signed in: nothing to delete on the server).
        assertTrue(Sync.forgetNow(ctx, FakeBackend()))
        assertTrue(queued().isEmpty())
    }
}
