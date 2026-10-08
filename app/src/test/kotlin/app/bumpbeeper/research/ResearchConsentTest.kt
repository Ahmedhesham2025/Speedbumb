package app.bumpbeeper.research

import android.content.Context
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.Prefs
import app.bumpbeeper.sync.HttpResult
import app.bumpbeeper.sync.RestoreReset
import app.bumpbeeper.sync.SupabaseAuth
import app.bumpbeeper.sync.Sync
import app.bumpbeeper.sync.Transport
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
import java.io.IOException

/** The research consent and the server's copy: on/off ordering while offline, registration, forget me, restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchConsentTest {
    private lateinit var ctx: Context

    /** Answers like the backend. [registered] = false: set_research_consent says 42501 until register_device. */
    private class Backend(var registered: Boolean = true, var offline: Boolean = false) : Transport {
        val calls = ArrayList<String>()
        /** The set_research_consent bodies taken, in order. */
        val consents = ArrayList<JSONObject>()
        val bodies = HashMap<String, JSONObject>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            if (offline) throw IOException("offline")
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            bodies[name] = JSONObject(body)
            return when (name) {
                "signup", "token" -> HttpResult(200, """{"access_token":"a","expires_in":3600,"refresh_token":"r","user":{"id":"$UID"}}""")
                "register_device" -> { registered = true; HttpResult(200, "\"$UID\"") }
                "set_research_consent" -> if (!registered) HttpResult(403, """{"code":"42501","message":"device not registered"}""") else {
                    consents.add(JSONObject(body))
                    HttpResult(200, JSONObject(body).getBoolean("enabled").toString())
                }
                else -> HttpResult(200, "true")
            }
        }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        ResearchQueue.clear(ctx)
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!ok()) {
            assertTrue(what, System.currentTimeMillis() < until)
            Thread.sleep(10)
        }
    }

    @Test fun onIsToldWithItsVersionBeforeAnyUpload() {
        ResearchConsent.setEnabled(ctx, true)
        assertTrue(Prefs.researchRecording(ctx))
        assertFalse("not before the server has it", ResearchConsent.active(ctx))
        val net = Backend()
        assertFalse(ResearchConsent.run(ctx, net))
        assertEquals(1, net.consents.size)
        assertTrue(net.consents[0].getBoolean("enabled"))
        assertEquals(ResearchConsent.RESEARCH_CONSENT_VERSION, net.consents[0].getInt("version"))
        assertTrue(ResearchConsent.active(ctx))
        assertFalse(ResearchConsent.run(ctx, net))
        assertEquals("nothing pending: no call", 1, net.consents.size)
    }

    @Test fun offThenOnWhileOfflineSendsOffFirst() {
        ResearchConsent.setEnabled(ctx, true)
        ResearchConsent.run(ctx, Backend())
        ResearchConsent.setEnabled(ctx, false)
        ResearchConsent.setEnabled(ctx, true)
        assertTrue("offline: retried later", ResearchConsent.run(ctx, Backend(offline = true)))
        assertTrue(ResearchConsent.pending(ctx))
        val net = Backend()
        assertFalse(ResearchConsent.run(ctx, net))
        assertEquals(listOf(false, true), net.consents.map { it.getBoolean("enabled") })
        assertTrue("off carries no version", net.consents[0].isNull("version"))
        assertTrue(ResearchConsent.active(ctx))
    }

    @Test fun switchedOffWhileOnWasStillPendingStillSendsOff() {
        SupabaseAuth(ctx, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, Backend()).accessToken()   // signed in (shared map)
        ResearchConsent.setEnabled(ctx, true)
        ResearchConsent.setEnabled(ctx, false)   // the "on" may already be on its way
        val net = Backend()
        ResearchConsent.run(ctx, net)
        assertEquals(listOf(false), net.consents.map { it.getBoolean("enabled") })
        assertFalse(ResearchConsent.pending(ctx))
        assertFalse(Prefs.researchRecording(ctx))
    }

    @Test fun aNoThatNeverWasAYesCreatesNothingOnTheServer() {
        ResearchConsent.setEnabled(ctx, false)
        assertFalse(ResearchConsent.pending(ctx))
        val net = Backend()
        assertFalse(ResearchConsent.run(ctx, net))
        assertTrue(net.calls.isEmpty())
    }

    @Test fun notRegisteredYetRegistersHonestlyThenRetries() {
        ResearchConsent.setEnabled(ctx, true)
        val net = Backend(registered = false)
        assertFalse(ResearchConsent.run(ctx, net))
        assertEquals(listOf("signup", "set_research_consent", "register_device", "set_research_consent"), net.calls)
        val reg = net.bodies["register_device"]!!
        assertEquals("the shared-map question is unanswered: no map consent", 0, reg.getInt("consent_version"))
        assertFalse(reg.getBoolean("share_enabled"))
        assertTrue(ResearchConsent.active(ctx))
    }

    @Test fun offForADeviceTheServerDoesNotKnowNeedsNoRegistration() {
        ResearchConsent.setEnabled(ctx, true)
        ResearchConsent.run(ctx, Backend())
        ResearchConsent.setEnabled(ctx, false)
        val net = Backend(registered = false)
        assertFalse(ResearchConsent.run(ctx, net))
        assertFalse(net.calls.contains("register_device"))
        assertFalse(ResearchConsent.pending(ctx))
    }

    @Test fun offWithoutASessionSignsNothingUp() {
        Prefs.setResearchState(ctx, false, 1, offPending = true, onPending = false, serverOn = true, note = "")
        val net = Backend()
        assertFalse(ResearchConsent.run(ctx, net))
        assertTrue("no new anonymous device just to say off", net.calls.isEmpty())
        assertFalse(ResearchConsent.pending(ctx))
    }

    @Test fun forgetMeSwitchesResearchOffHereWithoutACall() {
        ResearchConsent.setEnabled(ctx, true)
        ResearchConsent.run(ctx, Backend())
        ResearchQueue.tripStarted(ctx, "20261001T080000", 1, guessed = false)
        Sync.withdrawConsent(ctx)
        assertFalse(Prefs.researchRecording(ctx))
        assertEquals(0, Prefs.researchConsentVersion(ctx))
        assertFalse(ResearchConsent.pending(ctx))
        val net = Backend()
        assertFalse(ResearchConsent.run(ctx, net))
        assertTrue("forget_me itself tells the server", net.calls.isEmpty())
        waitFor("the queue is withdrawn") { ResearchQueue.read(ctx) { it.trip("20261001T080000")?.has("skip") == true } }
    }

    @Test fun aRestoreSwitchesResearchOffAndForgetsTheQueueWithoutACall() {
        ResearchConsent.setEnabled(ctx, true)
        ResearchConsent.run(ctx, Backend())
        Prefs.setResearchAsked(ctx, ResearchConsent.RESEARCH_CONSENT_VERSION)
        ResearchQueue.tripStarted(ctx, "20261001T080000", 1, guessed = false)
        RestoreReset.marker(ctx).delete()   // a restored phone has settings but no install id yet

        assertTrue(RestoreReset.check(ctx))

        assertFalse(Prefs.researchRecording(ctx))
        assertEquals(0, Prefs.researchConsentVersion(ctx))
        assertFalse(ResearchConsent.pending(ctx))
        assertFalse(Prefs.researchServerOn(ctx))
        assertEquals("asked again on the new phone", 0, Prefs.researchAsked(ctx))
        assertFalse(ResearchQueue.file(ctx).exists())
        val net = Backend()
        assertFalse(ResearchConsent.run(ctx, net))
        assertTrue(net.calls.isEmpty())
    }

    companion object {
        const val UID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
