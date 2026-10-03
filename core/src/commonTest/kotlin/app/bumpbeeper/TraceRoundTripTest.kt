package app.bumpbeeper

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/** Simulated drive → CSV → TraceReader → Replayer must give the same map as the original drive. */
class TraceRoundTripTest {

    private fun roundTrip(seed: Long, spec: DriveSpec) {
        val sim = Simulator(seed)
        val original = MemoryStore()
        val samples = ArrayList<TraceSample>()
        sim.drive(original, spec, tripId = 1, recorder = samples)

        val csv = TraceWriterCore.toCsv(samples, startMs = 0)
        val read = TraceReader.read(csv.lineSequence())
        assertEquals(samples.size, read.size, "every recorded row is read back")

        val replayed = MemoryStore()
        val result = Replayer.replay(read, replayed)

        val newOrig = original.events.count { it.type == "new_bump" }
        val newReplay = result.events.count { it.type == "new_bump" }
        println("  seed $seed: new_bump original=$newOrig replay=$newReplay")
        assertTrue(newOrig > 0, "the drive should record something")
        assertEquals(newOrig, newReplay, "same number of new_bump events")
        assertEquals(newReplay, replayed.events.count { it.type == "new_bump" })

        val a = original.saved.sortedBy { it.lon }
        val b = replayed.saved.sortedBy { it.lon }
        assertEquals(a.size, b.size, "same number of spots")
        for (i in a.indices) {
            val d = Geo.distance(a[i].lat, a[i].lon, b[i].lat, b[i].lon)
            println("    ${a[i].kind} vs ${b[i].kind}, ${formatFixed(d, 2)} m apart")
            assertEquals(a[i].kind, b[i].kind, "spot $i kind")
            assertTrue(d <= 1.0, "spot $i moved ${d} m")
        }
    }

    @Test fun bumpsAndPotholeSurviveTheCsv() = roundTrip(
        41, DriveSpec(bumpsAt = listOf(300.0, 1100.0), potholesAt = listOf(700.0), cruiseKmh = 40.0),
    )

    @Test fun noGyroDriveSurvivesTheCsv() = roundTrip(
        12, DriveSpec(bumpsAt = listOf(500.0, 1500.0), potholesLeftAt = listOf(1000.0), cruiseKmh = 40.0, gyro = false),
    )

    @Test fun writerMatchesAppFormat() {
        val csv = TraceWriterCore.toCsv(
            listOf(
                TraceSample.Accel(1000, 1.23456, -9.8, 0.5, 0.012345, Double.NaN, 0.0, 0.1234),
                TraceSample.Gps(2000, 30.04441234, 31.2357, 40.06, 89.6, 5.0),
                TraceSample.Gps(3000, 30.0444, 31.2357, Double.NaN, Double.NaN, 5.0),
                TraceSample.Event(3000, "new_bump", 3, 5.432, "bump, looks=bump"),
            ),
        ).lines()
        assertEquals(TraceReader.HEADER.joinToString(","), csv[0])
        assertEquals("0.000,accel,1.235,-9.800,0.500,0.0123,,0.0000,0.123,,,,,,,,,", csv[1])
        assertEquals("1.000,gps,,,,,,,,30.0444123,31.2357000,40.1,90,5.0,,,,", csv[2])
        assertEquals("2.000,gps,,,,,,,,30.0444000,31.2357000,,,5.0,,,,", csv[3])
        assertEquals("2.000,event,,,,,,,,,,,,,new_bump,3,5.43,bump; looks=bump", csv[4])
        for (line in csv.take(5)) assertEquals(18, line.split(',').size, line)
    }

    @Test fun commentsAndUnknownColumnsAreIgnored() {
        val csv = """
            # app=1.4.0 phone=Pixel
            # battery=87
            type,t_s,ax,ay,az,gx,gy,gz,extra,lat,lon,speed_kmh,bearing,accuracy_m,event,bump_id,peak,note
            accel,0.020,0.1,0.2,9.8,,,,whatever,,,,,,,,,
            # battery=86
            gps,1.000,,,,,,,,30.1,31.2,36.0,,4.0,,,,
            magnetometer,1.100,1,2,3
            battery,1.200,,,,,,,,,,,,,,,,85
            event,1.300,,,,,,,,,,,,,label,,,bump
        """.trimIndent()
        val s = TraceReader.read(csv.lineSequence())
        assertEquals(3, s.size)
        val acc = s[0] as TraceSample.Accel
        assertEquals(20L, acc.tMs)
        assertEquals(9.8, acc.az, 1e-9)
        assertTrue(acc.gx.isNaN())
        val gps = s[1] as TraceSample.Gps
        assertEquals(1000L, gps.tMs)
        assertEquals(10.0, gps.speedKmh / 3.6, 1e-9)
        assertTrue(gps.bearing.isNaN())
        val ev = s[2] as TraceSample.Event
        assertEquals("label", ev.type)
        assertEquals(-1L, ev.bumpId)
        assertEquals("bump", ev.note)
    }

    @Test fun labelsWithUndo() {
        val s = listOf(
            TraceSample.Gps(1000, 30.0, 31.0, 40.0, 90.0, 5.0),
            TraceSample.Gps(2000, 30.1, 31.1, 40.0, 90.0, 5.0),
            TraceSample.Gps(3000, 30.2, 31.2, 40.0, 90.0, 5.0),
            TraceSample.Event(1100, "label", -1, Double.NaN, "bump"),
            TraceSample.Event(1900, "label", -1, Double.NaN, "pothole_l"),
            TraceSample.Event(1950, "label", -1, Double.NaN, "undo"),
            TraceSample.Event(2900, "label", -1, Double.NaN, "pothole_r"),
            TraceSample.Event(2950, "new_bump", 1, 5.0, "bump"),
        )
        val labels = TraceReader.labels(s)
        assertEquals(listOf("bump", "pothole_r"), labels.map { it.kind })
        assertEquals(30.0, labels[0].lat, 1e-9)
        assertEquals(31.2, labels[1].lon, 1e-9)
        // A leading undo has nothing to remove.
        assertEquals(0, TraceReader.labels(listOf(TraceSample.Event(0, "label", -1, Double.NaN, "undo"))).size)
    }
}
