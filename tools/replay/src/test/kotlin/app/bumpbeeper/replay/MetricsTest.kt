package app.bumpbeeper.replay

import app.bumpbeeper.*
import org.junit.Assert.*
import org.junit.Test

/** Hand-made labels and engine events on an eastbound road, to pin down each metric definition. */
class MetricsTest {
    private fun at(m: Double) = Geo.move(30.0, 31.0, 90.0, m)
    private fun label(tMs: Long, kind: String, m: Double) = at(m).let { Label(tMs, kind, it[0], it[1]) }
    private fun gps(tMs: Long, m: Double, kmh: Double) = at(m).let { TraceSample.Gps(tMs, it[0], it[1], kmh, 90.0, 5.0) }
    private fun event(tMs: Long, type: String, m: Double, note: String, distM: Double = Double.NaN) = at(m).let {
        BumpEvent(Replayer.WALL_BASE_MS + tMs, 1, type, 1, it[0], it[1], 40.0, 90.0, 5.0, 0.0, distM, note)
    }

    private val labels = listOf(
        label(10_000, "bump", 100.0),        // pass 1, found
        label(30_000, "pothole_r", 300.0),   // found, but on the wrong side
        label(50_000, "bump", 500.0),        // slow (10 km/h), missed
        label(60_000, "rough", 650.0),       // rough road: not a hazard, but a detection here isn't false
        label(200_000, "bump", 100.0),       // pass 2 over the first bump, found
    )
    private val samples = listOf(gps(10_000, 100.0, 40.0), gps(30_000, 300.0, 40.0), gps(50_000, 500.0, 10.0), gps(200_000, 100.0, 40.0))
    private val events = listOf(
        event(11_200, "new_bump", 101.0, "bump looks=bump score=-1.00 first=up no-gyro"),
        event(31_200, "new_bump", 303.0, "pothole looks=pothole score=0.90 first=down roll/pitch=2.00 side=left"),
        event(61_000, "new_bump", 652.0, "unsure looks=unsure score=0.00 first=up no-gyro"),
        event(70_000, "new_bump", 900.0, "bump looks=bump score=-1.00 first=up no-gyro"),       // nothing there
        event(100_000, "hit", 300.0, "hits 2/2 now=pothole looks=pothole score=0.9 first=down"), // right place, 70 s late
        event(185_000, "beep", 0.0, "bump", distM = 100.0),     // warns about the bump at 100 m: fine
        event(186_000, "beep", 600.0, "bump", distM = 100.0),   // warns about 700 m: nothing labelled there
        event(201_100, "hit", 98.0, "hits 2/2 now=bump looks=bump score=-1.00 first=up no-gyro"),
        event(201_500, "hit_repeat", 99.0, "same pass"),
    )
    private val trip = TripStats().apply { distanceM = 50_000.0 }
    private val r = Metrics.compute(labels, ReplayResult(events, trip, DrivingStats()), samples)

    @Test fun precisionAndRecall() {
        assertEquals(6, r.detections)          // new_bump + hit only; hit_repeat is the same pass
        assertEquals(4, r.matchedDetections)   // 100 m twice, 300 m, and 652 m matches the rough label
        assertEquals(4.0 / 6, r.precision, 1e-9)
        assertEquals(4, r.labels)
        assertEquals(0.75, r.recall, 1e-9)
        assertEquals(3, r.labelsFast)
        assertEquals(1.0, r.recallFast, 1e-9)
    }

    @Test fun kindAndSide() {
        assertEquals(1.0, r.kindAccuracy, 1e-9)
        assertEquals(1, r.sideChecked)
        assertEquals(0.0, r.sideAccuracy, 1e-9)
    }

    @Test fun falseWarnings() {
        assertEquals(2, r.beeps)
        assertEquals(1, r.falseWarnings)
        assertEquals(2.0, r.falseWarningsPer100Km, 1e-9)
    }

    @Test fun learnedWithinTwoPasses() {
        assertEquals(1, r.spots)
        assertEquals(1.0, r.learnedRate, 1e-9)
    }

    @Test fun outputs() {
        val json = r.toJson()
        assertTrue(json, json.contains("\"precision\": 0.6667"))
        assertTrue(json, json.contains("\"spots\": 1"))
        val md = r.toMarkdown("t")
        assertTrue(md, md.contains("| Precision | 0.67 | ≥ 0.90 | **FAIL** |"))
        assertTrue(md, md.contains("| Recall ≥ 25 km/h | 1.00 | ≥ 0.95 | pass |"))
        val empty = Metrics.compute(emptyList(), ReplayResult(emptyList(), TripStats(), DrivingStats()), emptyList())
        assertTrue(empty.toJson().contains("\"precision\": null"))
    }
}
