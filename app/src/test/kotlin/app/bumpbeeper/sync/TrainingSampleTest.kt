package app.bumpbeeper.sync

import app.bumpbeeper.DrivingStats
import app.bumpbeeper.Fix
import app.bumpbeeper.Geo
import app.bumpbeeper.JoltSample
import app.bumpbeeper.JoltWindow
import app.bumpbeeper.TripStats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** "Help improve detection": the upload JSON matches the server contract, and spot ids respect the privacy zone. */
class TrainingSampleTest {
    private val lat0 = 30.0444
    private val lon0 = 31.2357

    private fun window(gyro: Boolean = true) = JoltWindow(
        50, 2000, ShortArray(161) { (it * 10).toShort() },
        if (gyro) ShortArray(161) { (-it).toShort() } else null, if (gyro) ShortArray(161) { 7 } else null,
    )

    private fun sample(
        decision: String = "hit", spot: Long? = 77, lat: Double = lat0, lon: Double = lon0, speed: Double = 30.0,
        peak: Double = 5.5, shape: Double = -0.4, ratio: Double = 0.8, reason: String? = null, gyro: Boolean = true,
    ) = JoltSample(
        decision, reason, "bump", speed, 12.0, 4.0, peak, shape, false, ratio, 1.0, spot, lat, lon,
        1_759_000_000_000L, window(gyro),
    )

    private fun shorts(b64: String): List<Short> {
        val b = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)
        return List(b.remaining() / 2) { b.short }
    }

    @Test fun sampleJsonFollowsTheContract() {
        val o = TrainingJson.sample("id-1", sample(), "mounted", 77)
        assertEquals(
            setOf("client_sample_id", "day", "decision", "reason", "classification", "placement", "speed_kmh",
                "heading_change_deg", "gps_accuracy_m", "peak", "shape_score", "first_down", "roll_pitch_ratio",
                "side_score", "spot_id", "rate_hz", "pre_ms", "accel_v", "gyro_roll", "gyro_pitch"),
            o.keySet(),
        )
        assertFalse("never coordinates", o.has("lat") || o.has("lon"))
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(o.getString("day")))
        assertEquals(77L, o.getLong("spot_id"))
        assertEquals(50, o.getInt("rate_hz"))
        assertEquals(2000, o.getInt("pre_ms"))
        val acc = shorts(o.getString("accel_v"))
        assertEquals(161, acc.size)
        assertEquals(1500.toShort(), acc[150])   // int16 little-endian, as stored
        assertEquals(shorts(o.getString("gyro_roll")).size, acc.size)
        assertEquals((-160).toShort(), shorts(o.getString("gyro_roll"))[160])
        assertEquals(7.toShort(), shorts(o.getString("gyro_pitch"))[0])
        assertTrue(o.isNull("reason"))
    }

    @Test fun valuesAreClampedIntoTheServerRanges() {
        val o = TrainingJson.sample("x", sample(speed = 300.0, peak = 150.0, shape = 2.0, ratio = Double.NaN, reason = "Bad Reason!", gyro = false), "pocket", null)
        assertEquals(250.0, o.getDouble("speed_kmh"), 0.0)
        assertEquals(100.0, o.getDouble("peak"), 0.0)
        assertEquals(1.0, o.getDouble("shape_score"), 0.0)
        assertTrue(o.isNull("roll_pitch_ratio"))
        assertTrue(o.isNull("reason"))
        assertTrue(o.isNull("spot_id"))
        assertTrue("both or neither", o.isNull("gyro_roll") && o.isNull("gyro_pitch"))
        val s = JoltSample("rejected", "too_slow", null, Double.NaN, -200.0, 900.0, Double.NaN, Double.NaN, null,
            5000.0, Double.NaN, null, Double.NaN, Double.NaN, 0L, window())
        val r = TrainingJson.sample("y", s, "unknown", null)
        assertEquals(0.0, r.getDouble("speed_kmh"), 0.0)
        assertEquals(-180.0, r.getDouble("heading_change_deg"), 0.0)
        assertEquals(500.0, r.getDouble("gps_accuracy_m"), 0.0)
        assertEquals(1000.0, r.getDouble("roll_pitch_ratio"), 0.0)
        assertEquals("too_slow", r.getString("reason"))
        assertTrue(r.isNull("peak") && r.isNull("first_down") && r.isNull("classification"))
    }

    @Test fun brandIsSanitised() {
        assertEquals("samsung", TrainingJson.brand("Samsung"))
        assertEquals("hmd global", TrainingJson.brand("HMD Global™"))
        assertEquals("abcdefghijklmnopqrst", TrainingJson.brand("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
        assertEquals(null, TrainingJson.brand("中兴"))
        assertEquals(null, TrainingJson.brand(null))
    }

    @Test fun tripSummaryAndBatch() {
        val d = DrivingStats().apply { harshBrakes = 2; swerves = 1; speedingS = 33.4; distanceM = 12_345.0 }
        val t = TripStats().apply { beeps = 4; distanceM = 12_000.0 }
        val trip = TrainingJson.trip("t1", 1_759_000_000_000L, 1_759_000_000_000L + 1_800_000L, "cupholder",
            mapOf("learned" to 3, "miss" to 1), t, d, 80, 200)
        assertEquals(1800, trip.getInt("duration_s"))
        assertEquals(12_345, trip.getInt("distance_m"))
        assertEquals(3, trip.getInt("n_learned"))
        assertEquals(0, trip.getInt("n_hit"))
        assertEquals(4, trip.getInt("n_beeps"))
        assertEquals(2, trip.getInt("harsh_brakes"))
        assertEquals(33, trip.getInt("speeding_s"))
        assertEquals(80, trip.getInt("battery_start"))
        assertTrue("out of range battery → null", trip.isNull("battery_end"))
        assertTrue(trip.getInt("score") in 0..100)
        assertFalse(trip.has("lat") || trip.has("route"))

        val items = listOf(
            TrainingStore.Item("s1", TrainingStore.SAMPLE, TrainingJson.sample("s1", sample(), "mounted", null).toString()),
            TrainingStore.Item("t1", TrainingStore.TRIP, trip.toString()),
        )
        val b = TrainingJson.batch("1.4.0", 34, "samsung", items)
        assertEquals(setOf("app_version", "sdk", "brand", "samples", "trips"), b.keySet())
        assertEquals(1, b.getJSONArray("samples").length())
        assertEquals("t1", b.getJSONArray("trips").getJSONObject(0).getString("client_trip_id"))
        assertTrue(JSONObject(b.toString()).isNull("brand").not())
    }

    @Test fun tripCountsComeFromTheEngine() {
        val sink = TrainingSink("mounted", 0L, 50)
        sink.onSample(sample(decision = "hit"))
        sink.onSample(sample(decision = "pass_clear"))
        val t = TripStats().apply { hits = 5; newBumps = 2; rejected = 3; misses = 1 }
        val trip = JSONObject(sink.build(true, 60_000L, t, null, 50).last().json)
        assertEquals(listOf(2, 3, 3, 1, 1, 0), listOf("learned", "hit", "rejected", "miss", "pass_clear", "user_mute").map { trip.getInt("n_$it") })
    }

    /** A 2 km eastbound trip with a sample at each given distance (m). */
    private fun tripWithSamplesAt(vararg at: Double): List<JSONObject> {
        val sink = TrainingSink("mounted", 1_759_000_000_000L, 90)
        var x = 0.0
        var t = 0L
        val todo = at.sorted().toMutableList()
        while (x <= 2000.0) {
            val p = Geo.move(lat0, lon0, 90.0, x)
            sink.onFix(Fix(t, p[0], p[1], Double.NaN, Double.NaN, 5.0))
            while (todo.isNotEmpty() && todo.first() <= x) {
                val q = Geo.move(lat0, lon0, 90.0, todo.removeAt(0))
                sink.onSample(sample(lat = q[0], lon = q[1]))
            }
            x += 50.0
            t += 3000
        }
        val items = sink.build(true, 1_759_000_600_000L, null, null, 85)
        assertEquals(TrainingStore.TRIP, items.last().kind)
        return items.filter { it.kind == TrainingStore.SAMPLE }.map { JSONObject(it.json) }
    }

    @Test fun spotIdDroppedWithin300mOfStartOrEnd() {
        // 310 m: outside 300 m, but the spot itself may be up to 20 m back towards home.
        val near = tripWithSamplesAt(100.0, 250.0, 310.0, 1850.0)
        assertEquals(4, near.size)
        assertTrue("no spot id near home or work", near.all { it.isNull("spot_id") })
        val middle = tripWithSamplesAt(1000.0)
        assertEquals(77L, middle.single().getLong("spot_id"))
    }

    @Test fun unknownPlaceOrNoConsentKeepsNothingIdentifying() {
        val sink = TrainingSink("unknown", 0L, -1)
        for (x in 0..40) sink.onFix(Fix(x * 1000L, lat0, lon0 + x * 0.001, Double.NaN, Double.NaN, 5.0))
        sink.onSample(sample(lat = Double.NaN, lon = Double.NaN))
        val items = sink.build(true, 60_000L, null, null, -1)
        assertTrue(JSONObject(items.first { it.kind == TrainingStore.SAMPLE }.json).isNull("spot_id"))
        val trip = JSONObject(items.last().json)
        assertEquals(1, trip.getInt("n_hit"))
        assertTrue(trip.isNull("battery_start") && trip.isNull("score"))

        val off = TrainingSink("unknown", 0L, -1)
        off.onSample(sample())
        assertTrue("consent withdrawn during the trip", off.build(false, 60_000L, null, null, -1).isEmpty())
    }
}
