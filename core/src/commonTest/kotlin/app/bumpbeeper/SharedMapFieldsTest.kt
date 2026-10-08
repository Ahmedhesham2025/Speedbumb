package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The v2 fields of [RemoteSpot] and [Observation] are data only, and their defaults keep the old meaning. */
@Suppress("DEPRECATION")
class SharedMapFieldsTest {
    @Test fun aRemoteSpotWithoutV2FieldsKeepsTheOldMeaning() {
        val old = RemoteSpot(1, 30.0, 31.0, 90.0, BumpKind.POTHOLE, Side.LEFT, 6.5, 3)
        assertTrue(old.legacy, "an old pothole spot is legacy")
        assertEquals(3, old.nHits, "hits = phones where the server didn't say")
        assertNull(old.band)
        assertNull(old.confidence)
        assertFalse(RemoteSpot(2, 30.0, 31.0, 90.0, BumpKind.BUMP, Side.UNKNOWN, 3.0, 1).legacy)
    }

    @Test fun v2FieldsAreKept() {
        val s = RemoteSpot(3, 30.0, 31.0, 90.0, BumpKind.BUMP, Side.UNKNOWN, 5.6, 1,
            nHits = 2, band = Severity.STRONG, confidence = Confidence.FULL)
        assertEquals(listOf<Any?>(2, Severity.STRONG, Confidence.FULL, false), listOf(s.nHits, s.band, s.confidence, s.legacy))
    }

    @Test fun anObservationHasNoSeverityOrAxleUnlessGiven() {
        val o = Observation("id", "jolt", 30.0, 31.0, 90.0, 40.0, 4.0, 0.0, 0.0, 1000L)
        assertNull(o.sevIndex)
        assertNull(o.axle)
        val v2 = o.copy(sevIndex = 4.2, axle = 0.7)
        assertEquals(4.2, v2.sevIndex)
        assertEquals(0.7, v2.axle)
    }
}
