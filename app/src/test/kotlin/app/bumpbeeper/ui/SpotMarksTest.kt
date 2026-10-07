package app.bumpbeeper.ui

import app.bumpbeeper.Bump
import app.bumpbeeper.BumpKind
import app.bumpbeeper.EngineConfig
import app.bumpbeeper.RemoteSpot
import app.bumpbeeper.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The street map's pure logic (v1.8): spot → icon and feature properties, filters, merging, cluster sizes. */
class SpotMarksTest {
    private val cfg = EngineConfig()

    /** [legacy]: an old pothole spot not felt again; [soft]: felt once so far; jolt m/s² (also its severity index). */
    private fun bump(id: Long, legacy: Boolean = false, soft: Boolean = false, jolt: Double = 3.0, muted: Boolean = false,
                     hits: Int = if (soft) 1 else 4, passes: Int = if (soft) 1 else 5,
                     lat: Double = 30.0444, lon: Double = 31.2357) = Bump(
        id, lat, lon, 90.0, hits, passes, passes - hits, hits, 0L, 0L, muted, peakAvg = jolt, sevIndex = jolt, legacy = legacy,
    )

    private fun remote(id: Long, kind: BumpKind, severity: Double = 3.0, lat: Double = 30.05, lon: Double = 31.24) =
        RemoteSpot(id, lat, lon, 0.0, kind, Side.UNKNOWN, severity, 3)

    @Test fun iconForEachKind() {
        assertEquals(SpotIcon.BUMP, SpotMarks.fromLocal(bump(1), cfg).icon)
        assertEquals(SpotIcon.POTHOLE, SpotMarks.fromLocal(bump(2, legacy = true, jolt = 3.0), cfg).icon)
        assertEquals(SpotIcon.HARSH, SpotMarks.fromLocal(bump(3, legacy = true, jolt = 9.0), cfg).icon)
        assertEquals(SpotIcon.UNSURE, SpotMarks.fromLocal(bump(4, soft = true), cfg).icon)
        assertEquals(SpotIcon.MUTED, SpotMarks.fromLocal(bump(5, legacy = true, jolt = 9.0, muted = true), cfg).icon)
    }

    @Test fun autoMutedSpotIsGrey() {
        // Driven over 8 times, felt once: probably a false detection, muted by the engine.
        val s = SpotMarks.fromLocal(bump(6, hits = 1, passes = 8), cfg)
        assertTrue(s.muted)
        assertEquals(SpotIcon.MUTED, s.icon)
    }

    @Test fun iconColoursMatchTheLegend() {
        assertEquals(0xFFFFA726.toInt(), SpotIcon.BUMP.color)       // orange
        assertEquals(0xFFEF5350.toInt(), SpotIcon.POTHOLE.color)    // red
        assertEquals(0xFFB71C1C.toInt(), SpotIcon.HARSH.color)      // dark red
        assertEquals("!", SpotIcon.HARSH.mark)
        assertEquals(0xFFFFEB3B.toInt(), SpotIcon.UNSURE.color)     // yellow
        assertEquals(SpotIcon.entries.size, SpotIcon.entries.map { it.id }.toSet().size)
    }

    @Test fun sharedSpotHarshByTheSameRule() {
        assertEquals(SpotIcon.HARSH, SpotMarks.fromShared(remote(7, BumpKind.POTHOLE, severity = cfg.sevStrongMin), cfg).icon)
        assertEquals(SpotIcon.POTHOLE, SpotMarks.fromShared(remote(8, BumpKind.POTHOLE, severity = cfg.sevStrongMin - 0.1), cfg).icon)
        val s = SpotMarks.fromShared(remote(9, BumpKind.BUMP, severity = 99.0), cfg)
        assertEquals(SpotIcon.BUMP, s.icon)
        assertTrue(s.shared)
        assertFalse(s.muted)
        assertEquals(-1L, s.localId)
    }

    @Test fun featurePropertiesCarryKeyAndIcon() {
        val mine = SpotMarks.properties(SpotMarks.fromLocal(bump(42, legacy = true, jolt = 9.0), cfg))
        assertEquals("m42", mine["key"])
        assertEquals("bb-harsh", mine["icon"])
        assertEquals(false, mine["shared"])
        assertEquals(1.0, mine["size"])
        val shared = SpotMarks.properties(SpotMarks.fromShared(remote(42, BumpKind.BUMP), cfg))
        assertEquals("s42", shared["key"])       // never clashes with your spot #42
        assertEquals("bb-bump", shared["icon"])
        assertEquals(true, shared["shared"])
        assertTrue((shared["order"] as Int) < (mine["order"] as Int))   // yours are drawn on top
    }

    @Test fun filtersKeepTheOldMeaningAndAddMuted() {
        val spots = listOf(
            SpotMarks.fromLocal(bump(1), cfg),
            SpotMarks.fromLocal(bump(2, soft = true), cfg),
            SpotMarks.fromLocal(bump(3, legacy = true, jolt = 3.0), cfg),
            SpotMarks.fromLocal(bump(4, legacy = true, jolt = 9.0), cfg),
            SpotMarks.fromLocal(bump(5, muted = true), cfg),
        )
        fun ids(f: Int) = spots.filter { SpotMarks.matches(it, f) }.map { it.localId }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(SpotMarks.ALL))
        assertEquals(listOf(1L, 2L, 5L), ids(SpotMarks.BUMPS))        // bumps include unsure ones, as before
        assertEquals(listOf(3L, 4L), ids(SpotMarks.POTHOLES))
        assertEquals(listOf(4L), ids(SpotMarks.HARSH))
        assertEquals(listOf(5L), ids(SpotMarks.MUTED))
    }

    @Test fun sharedSpotOnTopOfYoursIsDrawnOnce() {
        val mine = listOf(SpotMarks.fromLocal(bump(1, lat = 30.0, lon = 31.0), cfg))
        val shared = listOf(
            SpotMarks.fromShared(remote(10, BumpKind.BUMP, lat = 30.0001, lon = 31.0), cfg),   // ~11 m away: the same bump
            SpotMarks.fromShared(remote(11, BumpKind.BUMP, lat = 30.001, lon = 31.0), cfg),    // ~110 m away: another one
        )
        assertEquals(listOf("m1", "s11"), SpotMarks.merge(mine, shared).map { it.key })
        assertEquals(listOf("s10", "s11"), SpotMarks.merge(emptyList(), shared).map { it.key })
    }

    @Test fun nearestFirstWithinRadius() {
        val spots = listOf(
            SpotMarks.fromLocal(bump(1, lat = 30.010, lon = 31.0), cfg),
            SpotMarks.fromLocal(bump(2, lat = 30.001, lon = 31.0), cfg),
            SpotMarks.fromLocal(bump(3, lat = 30.100, lon = 31.0), cfg),
        )
        assertEquals(listOf(2L, 1L, 3L), SpotMarks.nearest(spots, 30.0, 31.0).map { it.localId })
        assertEquals(listOf(2L, 1L), SpotMarks.nearest(spots, 30.0, 31.0, radiusM = 2_000.0).map { it.localId })
    }

    @Test fun clusterThresholds() {
        // Grouped up to a district-sized view, single icons once a few streets fill the screen.
        assertEquals(13, SpotMarks.CLUSTER_MAX_ZOOM)
        assertTrue(SpotMarks.CLUSTER_RADIUS_DP in 30..60)
        assertEquals(SpotMarks.CLUSTER_SIZES[0], SpotMarks.clusterSizeDp(2))
        assertEquals(SpotMarks.CLUSTER_SIZES[0], SpotMarks.clusterSizeDp(9))
        assertEquals(SpotMarks.CLUSTER_SIZES[1], SpotMarks.clusterSizeDp(10))
        assertEquals(SpotMarks.CLUSTER_SIZES[1], SpotMarks.clusterSizeDp(99))
        assertEquals(SpotMarks.CLUSTER_SIZES[2], SpotMarks.clusterSizeDp(5000))
        assertTrue(SpotMarks.CLUSTER_SIZES.toList() == SpotMarks.CLUSTER_SIZES.sorted())
    }
}
