package app.bumpbeeper.sync

import app.bumpbeeper.Confidence
import app.bumpbeeper.Observation
import app.bumpbeeper.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The v2 data fields between the app and the engine: the cache → [app.bumpbeeper.RemoteSpot], [Observation] → upload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpotFieldsV2Test {

    @Test fun theCacheHandsTheEngineWhatV2Said() {
        val rows = SupabaseApi.parseSpotsV2(
            """[{"id":7,"lat":30.1,"lon":31.2,"heading":90,"severity":6.5,"severity_band":"strong","confidence":"full","n_devices":3,"n_hits":5,"legacy":true},
                {"id":8,"lat":30.2,"lon":31.3,"heading":0,"severity":5.6,"severity_band":"strong","confidence":"full","n_devices":1,"n_hits":1,"legacy":false},
                {"id":9,"lat":30.3,"lon":31.4,"heading":180,"severity":4.0,"severity_band":"extreme","confidence":"sure","n_devices":2,"legacy":false}]"""
        )
        val (legacy, axle, odd) = rows.map { CachedSpotSource.toRemote(it)!! }
        assertTrue(legacy.legacy)
        assertEquals("a legacy spot is soft, whatever else", Confidence.SOFT, legacy.confidence)
        assertEquals(5, legacy.nHits)
        assertEquals(Severity.STRONG, legacy.band)
        // One strong hit with both axles felt: full on the server with a single hit.
        assertEquals(listOf<Any?>(1, Severity.STRONG, Confidence.FULL, false), listOf(axle.nHits, axle.band, axle.confidence, axle.legacy))
        assertEquals("values this version doesn't know", listOf<Any?>(null, null, 2), listOf(odd.band, odd.confidence, odd.nHits))
    }

    @Test fun aSpotsNearRowKeepsTheOldMeaning() {
        val old = CachedSpotSource.toRemote(SpotRow(5, 30.0, 31.0, 90.0, "pothole", "left", 6.0, 3))!!
        assertTrue(old.legacy)
        assertEquals("hits = phones", 3, old.nHits)
        assertNull(old.band)
        assertNull(old.confidence)
        assertFalse(CachedSpotSource.toRemote(SpotRow(6, 30.0, 31.0, 90.0, "bump", null, 3.0, 2))!!.legacy)
    }

    private fun obs(sev: Double? = null, axle: Double? = null) =
        Observation("6f1c2a3e-1111-4222-8333-944455556666", "jolt", 30.04, 31.23, 90.0, 40.0, 4.0, 0.0, 0.0, 1_790_000_000_000L, sev, axle)

    @Test fun severityAndAxleGoAlongOnlyWhenKnown() {
        val none = ObservationJson.toJson(obs())
        assertFalse(none.has("sev_index"))
        assertFalse(none.has("axle"))
        val both = ObservationJson.toJson(obs(sev = 4.25, axle = 0.7))
        assertEquals(4.25, both.getDouble("sev_index"), 0.0)
        assertEquals(0.7, both.getDouble("axle"), 0.0)
        assertEquals(2, both.get("schema"))
        // The server rejects a whole batch for a value out of range: made to fit, and NaN is left out.
        val odd = ObservationJson.toJson(obs(sev = 250.0, axle = -0.1))
        assertEquals(100.0, odd.getDouble("sev_index"), 0.0)
        assertEquals(0.0, odd.getDouble("axle"), 0.0)
        assertFalse(ObservationJson.toJson(obs(sev = Double.NaN, axle = Double.NaN)).let { it.has("sev_index") || it.has("axle") })
    }
}
