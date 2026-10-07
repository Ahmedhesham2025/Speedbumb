package app.bumpbeeper

import kotlin.test.Test
import kotlin.test.assertEquals

/** Severity bands with their hysteresis, the confidence rule and old pothole spots ([Bump.legacy]), without driving. */
class SeverityTest {
    private val cfg = EngineConfig()

    private fun spot(hits: Int = 1, sev: Double = 0.0, legacy: Boolean = false, axleHits: Int = 0) =
        Bump(1, 30.0444, 31.2357, 90.0, hits, hits, 0, hits, 0, 0, sevIndex = sev, axleHits = axleHits, legacy = legacy)

    @Test fun plainBandEdges() {
        assertEquals(3.5, cfg.sevMildMax, 0.0)
        assertEquals(5.0, cfg.sevStrongMin, 0.0)
        assertEquals(Severity.MILD, Severity.of(0.0, null, cfg))
        assertEquals(Severity.MILD, Severity.of(3.49, null, cfg))
        assertEquals(Severity.MODERATE, Severity.of(3.5, null, cfg))
        assertEquals(Severity.MODERATE, Severity.of(4.99, null, cfg))
        assertEquals(Severity.STRONG, Severity.of(5.0, null, cfg))
    }

    /** A spot leaves its band only 10 % past an edge: up from edge × 1.1, down below edge × 0.9. */
    @Test fun hysteresisAtBothEdgesBothWays() {
        // Up from mild: moderate from 3.85, strong from 5.5.
        assertEquals(Severity.MILD, Severity.of(3.84, Severity.MILD, cfg))
        assertEquals(Severity.MODERATE, Severity.of(3.86, Severity.MILD, cfg))
        assertEquals(Severity.MODERATE, Severity.of(5.49, Severity.MILD, cfg))
        assertEquals(Severity.STRONG, Severity.of(5.51, Severity.MILD, cfg))
        // From moderate: mild below 3.15, strong from 5.5.
        assertEquals(Severity.MODERATE, Severity.of(3.16, Severity.MODERATE, cfg))
        assertEquals(Severity.MILD, Severity.of(3.14, Severity.MODERATE, cfg))
        assertEquals(Severity.MODERATE, Severity.of(5.49, Severity.MODERATE, cfg))
        assertEquals(Severity.STRONG, Severity.of(5.51, Severity.MODERATE, cfg))
        // Down from strong: moderate below 4.5, mild below 3.15.
        assertEquals(Severity.STRONG, Severity.of(4.51, Severity.STRONG, cfg))
        assertEquals(Severity.MODERATE, Severity.of(4.49, Severity.STRONG, cfg))
        assertEquals(Severity.MODERATE, Severity.of(3.16, Severity.STRONG, cfg))
        assertEquals(Severity.MILD, Severity.of(3.14, Severity.STRONG, cfg))
    }

    @Test fun edgesAndHysteresisAreConfigurable() {
        val wide = EngineConfig().apply { sevMildMax = 2.0; sevStrongMin = 8.0 }
        assertEquals(Severity.MILD, Severity.of(1.9, null, wide))
        assertEquals(Severity.MODERATE, Severity.of(7.9, null, wide))
        assertEquals(Severity.STRONG, Severity.of(8.0, null, wide))
        val none = EngineConfig().apply { sevHysteresis = 0.0 }
        assertEquals(Severity.MODERATE, Severity.of(3.5, Severity.MILD, none))
        assertEquals(Severity.MODERATE, Severity.of(4.99, Severity.STRONG, none))
    }

    @Test fun addSeverityAveragesAndSettlesTheBand() {
        val b = spot(hits = 0)
        b.addSeverity(4.0, 0, cfg)                       // the first hit is the whole average
        assertEquals(4.0, b.sevIndex, 1e-9)
        assertEquals(Severity.MODERATE, b.lastBand)
        b.addSeverity(6.0, 1, cfg)                       // 5.0: plainly strong, but moderate holds until 5.5
        assertEquals(5.0, b.sevIndex, 1e-9)
        assertEquals(Severity.MODERATE, b.severity(cfg))
        b.addSeverity(8.0, 2, cfg)                       // 6.0
        assertEquals(6.0, b.sevIndex, 1e-9)
        assertEquals(Severity.STRONG, b.severity(cfg))
        // The newest hit keeps at least a tenth of the weight, like the position and the peak average.
        val old = spot(hits = 20, sev = 3.0).also { it.addSeverity(13.0, 20, cfg) }
        assertEquals(4.0, old.sevIndex, 1e-9)
    }

    /** Loaded without its last band (from the database), a spot showed the plain band of its index: that one holds. */
    @Test fun reloadedSpotKeepsItsBandInsideTheHysteresis() {
        val b = spot(hits = 3, sev = 5.1)                // plainly strong
        assertEquals(Severity.STRONG, b.severity(cfg))
        b.addSeverity(2.9, 3, cfg)                       // 4.55: still strong (it drops only below 4.5)
        assertEquals(4.55, b.sevIndex, 1e-9)
        assertEquals(Severity.STRONG, b.severity(cfg))
    }

    @Test fun confidenceRule() {
        assertEquals(Confidence.SOFT, spot(hits = 1, sev = 9.0).confidence(cfg))                 // felt once: maybe
        assertEquals(Confidence.FULL, spot(hits = 2, sev = 2.0).confidence(cfg))                 // felt twice
        // One hit with both axles felt is enough only for a strong one (E2 detects axles; 0 until then).
        assertEquals(Confidence.FULL, spot(hits = 1, sev = 6.0, axleHits = 1).confidence(cfg))
        assertEquals(Confidence.SOFT, spot(hits = 1, sev = 4.0, axleHits = 1).confidence(cfg))
        // An old pothole spot stays a maybe, however often the old version felt it.
        assertEquals(Confidence.SOFT, spot(hits = 7, sev = 6.0, legacy = true).confidence(cfg))
    }

    @Test fun copyKeepsTheNewFields() {
        val b = spot(hits = 3, sev = 4.2, legacy = true, axleHits = 2).apply { lastBand = Severity.MODERATE }
        val c = b.copy()
        assertEquals(listOf<Any?>(4.2, 2, true, Severity.MODERATE), listOf(c.sevIndex, c.axleHits, c.legacy, c.lastBand))
    }

    @Test fun describeForScreens() {
        assertEquals("moderate bump (maybe)", spot(hits = 1, sev = 4.0).describe(cfg))
        assertEquals("strong bump", spot(hits = 2, sev = 7.0).describe(cfg))
        assertEquals("mild bump (maybe)", spot(hits = 5, sev = 3.0, legacy = true).describe(cfg))
    }
}
