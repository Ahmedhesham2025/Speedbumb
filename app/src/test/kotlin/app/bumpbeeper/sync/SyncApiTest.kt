package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.Observation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** Upload format, error mapping, sign-in and token refresh, all against a fake transport (no network). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncApiTest {
    private lateinit var ctx: Context
    private val base = "https://example.supabase.co"

    /** Records every request and answers from a queue. */
    private class FakeTransport(vararg answers: HttpResult) : Transport {
        val queue = ArrayDeque(answers.toList())
        val urls = ArrayList<String>()
        val headers = ArrayList<Map<String, String>>()
        val bodies = ArrayList<String>()
        var offline = false
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            if (offline) throw IOException("offline")
            urls.add(url); this.headers.add(headers); bodies.add(body)
            return queue.removeFirst()
        }
    }

    private fun session(access: String, refresh: String = "r-$access", expiresIn: Long = 3600) =
        HttpResult(200, """{"access_token":"$access","token_type":"bearer","expires_in":$expiresIn,"refresh_token":"$refresh","user":{"id":"u-1"}}""")

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
    }

    // ---------------------------------------------------------------- observation JSON

    private fun obs(heading: Double = 359.6, speed: Double = 42.5, kindScore: Double = -0.6, time: Long = 1_790_000_000_123L) =
        Observation("6f1c2a3e-1111-4222-8333-944455556666", "jolt", 30.0444123, 31.2357456, heading, speed, 3.75, kindScore, 0.4, time)

    @Test fun observationJsonHasServerFormat() {
        val j = ObservationJson.toJson(obs())
        assertEquals(
            setOf("schema", "client_obs_id", "kind", "lat", "lon", "heading", "speed_kmh", "peak", "kind_score", "side_score", "observed_at"),
            j.keySet(),
        )
        assertEquals("6f1c2a3e-1111-4222-8333-944455556666", j.getString("client_obs_id"))
        assertEquals("jolt", j.getString("kind"))
        assertEquals("schema 2, an int", 2, j.get("schema"))
        assertEquals(30.0444123, j.getDouble("lat"), 0.0)   // positions are not rounded on the phone
        assertEquals(31.2357456, j.getDouble("lon"), 0.0)
        assertEquals(0, j.get("heading"))                    // 359.6 rounds to 360 → 0, an int
        assertEquals(43, j.get("speed_kmh"))                 // rounded to an int
        assertEquals(3.75, j.getDouble("peak"), 1e-9)
        assertEquals(0.2, j.getDouble("kind_score"), 1e-9)   // -0.6 on the engine's -1..1 scale → 0.2 on the server's 0..1
        assertEquals(0.4, j.getDouble("side_score"), 1e-9)
        assertEquals("2026-09-21T14:13:20Z", j.getString("observed_at"))   // ISO-8601 UTC, seconds kept
    }

    @Test fun observationJsonFitsServerRanges() {
        val j = ObservationJson.toJson(obs(heading = -10.2, speed = 300.0, kindScore = 3.0))
        assertEquals(350, j.getInt("heading"))
        assertEquals(250, j.getInt("speed_kmh"))
        assertEquals(1.0, j.getDouble("kind_score"), 0.0)
        val noDir = ObservationJson.toJson(obs(heading = Double.NaN, speed = Double.NaN))
        assertFalse(noDir.has("heading"))
        assertFalse(noDir.has("speed_kmh"))
    }

    @Test fun serviceAreaIsTheServerBox() {
        assertTrue(ObservationJson.inServiceArea(30.04, 31.23))      // Cairo
        assertFalse(ObservationJson.inServiceArea(48.85, 2.35))      // Paris
        assertFalse(ObservationJson.inServiceArea(30.0, 61.0))
    }

    // ---------------------------------------------------------------- error mapping

    @Test fun errorCodesMapToActions() {
        fun pg(code: String) = """{"code":"$code","details":null,"hint":null,"message":"x"}"""
        assertEquals(Outcome.DROP, ApiErrors.classify(400, pg("22023")))
        assertEquals(Outcome.NOT_ALLOWED, ApiErrors.classify(403, pg("42501")))
        assertEquals(Outcome.LIMIT, ApiErrors.classify(500, pg("54000")))
        assertEquals(Outcome.AUTH, ApiErrors.classify(401, pg("PGRST301")))
        assertEquals(Outcome.AUTH, ApiErrors.classify(401, ""))
        assertEquals(Outcome.RETRY, ApiErrors.classify(503, "<html>busy</html>"))
        assertEquals(Outcome.RETRY, ApiErrors.classify(404, pg("PGRST202")))
    }

    // ---------------------------------------------------------------- auth + rpc

    @Test fun signsInAnonymouslyThenCallsRpcWithBearer() {
        val t = FakeTransport(session("a1"), HttpResult(200, """["6f1c2a3e-1111-4222-8333-944455556666"]"""))
        val auth = SupabaseAuth(ctx, base, "pk", t)
        val api = SupabaseApi(base, "pk", auth, t)
        val ids = api.submitObservations(JSONArray().put(ObservationJson.toJson(obs())))
        assertEquals(setOf("6f1c2a3e-1111-4222-8333-944455556666"), ids)
        assertEquals("$base/auth/v1/signup", t.urls[0])
        assertEquals("{}", t.bodies[0])
        assertEquals("pk", t.headers[0]["apikey"])
        assertEquals("$base/rest/v1/rpc/submit_observations", t.urls[1])
        assertEquals("Bearer a1", t.headers[1]["Authorization"])
        assertEquals(1, JSONObject(t.bodies[1]).getJSONArray("batch").length())
        assertEquals("u-1", auth.userId)
    }

    @Test fun expiredTokenIsRefreshedAndCallRetriedOnce() {
        val t = FakeTransport(
            session("a1"),
            HttpResult(401, """{"code":"PGRST303","message":"JWT expired"}"""),
            session("a2"),
            HttpResult(204, ""),
        )
        val auth = SupabaseAuth(ctx, base, "pk", t)
        SupabaseApi(base, "pk", auth, t).forgetMe()
        assertEquals("$base/auth/v1/token?grant_type=refresh_token", t.urls[2])
        assertEquals("r-a1", JSONObject(t.bodies[2]).getString("refresh_token"))
        assertEquals("Bearer a2", t.headers[3]["Authorization"])
    }

    @Test fun deadRefreshTokenSignsInAsNewDevice() {
        var clock = 0L
        val t = FakeTransport(session("a1", expiresIn = 60), HttpResult(400, """{"error_code":"refresh_token_not_found"}"""), session("b1"))
        val auth = SupabaseAuth(ctx, base, "pk", t) { clock }
        assertEquals("a1", auth.accessToken())
        clock = 3_600_000   // token long expired
        assertEquals("b1", auth.accessToken())
        assertEquals("$base/auth/v1/signup", t.urls[2])
    }

    @Test fun serverRejectionsSurfaceAsOutcomes() {
        val t = FakeTransport(session("a1"), HttpResult(400, """{"code":"22023","message":"invalid"}"""))
        val api = SupabaseApi(base, "pk", SupabaseAuth(ctx, base, "pk", t), t)
        try {
            api.submitObservations(JSONArray())
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(Outcome.DROP, e.outcome)
        }
        t.offline = true
        try {
            api.forgetMe()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(Outcome.RETRY, e.outcome)
        }
    }

    @Test fun spotsNearParsesRowsAndNulls() {
        val rows = SupabaseApi.parseSpots(
            """[{"id":7,"latitude":30.1,"longitude":31.2,"heading":90,"kind":"pothole","side":"left","severity":6.5,"n_devices":3,"last_hit":"2026-10-01T10:00:00+00:00"},
                {"id":8,"latitude":30.2,"longitude":31.3,"heading":null,"kind":null,"side":null,"severity":null,"n_devices":2,"last_hit":null}]"""
        )
        assertEquals(2, rows.size)
        assertEquals(7L, rows[0].id)
        assertEquals(90.0, rows[0].heading!!, 0.0)
        assertEquals("pothole", rows[0].kind)
        assertEquals(6.5, rows[0].severity!!, 1e-9)
        assertEquals(null, rows[1].heading)
        assertEquals(null, rows[1].kind)
        assertEquals(null, rows[1].severity)
    }
}
