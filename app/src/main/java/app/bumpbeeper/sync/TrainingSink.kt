package app.bumpbeeper.sync

import android.content.Context
import android.util.Log
import app.bumpbeeper.DrivingStats
import app.bumpbeeper.Fix
import app.bumpbeeper.JoltSample
import app.bumpbeeper.JoltSampleSink
import app.bumpbeeper.Prefs
import app.bumpbeeper.TripPrivacy
import app.bumpbeeper.TripStats
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * "Help improve detection": collects one trip's [JoltSample]s in memory and, at trip end, queues them and a
 * route-free trip summary in the training outbox. Only created while [Prefs.trainingActive]. Engine thread only.
 *
 * No coordinates leave the phone: a sample keeps at most the id of a confirmed shared spot, and not even that within
 * [TripPrivacy.RADIUS_M] of where the trip started or ended (or in its first / last [TripPrivacy.RADIUS_M] driven).
 */
class TrainingSink(
    private val placement: String,
    private val startWallMs: Long,
    private val batteryStart: Int,
) : JoltSampleSink {
    private val pending = ArrayList<JoltSample>()
    private val drivenAt = ArrayList<Double>()
    private val counts = HashMap<String, Int>()
    private var driven = 0.0
    private var first: Fix? = null
    private var firstGood: Fix? = null
    private var last: Fix? = null
    private var lastGood: Fix? = null

    /** Every GPS fix of the trip (same bookkeeping as [OutboxSink.onFix]). */
    fun onFix(f: Fix) {
        val prev = last
        if (prev != null) {
            if (f.timeMs < prev.timeMs) return
            driven += TripPrivacy.driven(listOf(prev, f))[1]
        }
        if (first == null) first = f
        last = f
        if (f.accuracyM <= TripPrivacy.GOOD_ACCURACY_M) {
            if (firstGood == null) firstGood = f
            lastGood = f
        }
    }

    override fun onSample(s: JoltSample) {
        counts[s.decision] = (counts[s.decision] ?: 0) + 1
        if (pending.size < MAX_PER_TRIP) { pending.add(s); drivenAt.add(driven) }
    }

    /**
     * Trip end: the outbox elements for this trip (samples, then the summary), shuffled so the upload order says
     * nothing about the driving order. [allowed] false (consent withdrawn during the trip) gives nothing.
     */
    fun build(
        allowed: Boolean, endWallMs: Long, trip: TripStats?, drive: DrivingStats?, batteryEnd: Int,
    ): List<TrainingStore.Item> {
        val anchors = listOfNotNull(first, firstGood, last, lastGood).map { doubleArrayOf(it.lat, it.lon) }
        val out = ArrayList<TrainingStore.Item>()
        if (allowed) {
            for ((i, s) in pending.withIndex()) {
                val d = drivenAt[i]
                // The event can be up to the match radius from the spot it reveals: check with that margin.
                val spotOk = s.sharedSpotId != null && !s.lat.isNaN() && !s.lon.isNaN() &&
                    d > SPOT_ZONE_M && driven - d > SPOT_ZONE_M && TripPrivacy.outside(s.lat, s.lon, anchors, SPOT_ZONE_M)
                val id = UUID.randomUUID().toString()
                out.add(TrainingStore.Item(id, TrainingStore.SAMPLE, TrainingJson.sample(id, s, placement, if (spotOk) s.sharedSpotId else null).toString()))
            }
            out.shuffle()   // relied on: the server's row order must say nothing about the driving order
            // Counts of what the engine decided (not only the samples it could cut a window for); the engine keeps no
            // pass_clear or mute counts, so those come from the samples.
            val n = HashMap(counts)
            if (trip != null) {
                n["learned"] = trip.newBumps; n["hit"] = maxOf(0, trip.hits - trip.newBumps)
                n["rejected"] = trip.rejected; n["miss"] = trip.misses
            }
            val id = UUID.randomUUID().toString()
            val summary = TrainingJson.trip(id, startWallMs, endWallMs, placement, n, trip, drive, batteryStart, batteryEnd)
            out.add(TrainingStore.Item(id, TrainingStore.TRIP, summary.toString()))
        }
        pending.clear()
        drivenAt.clear()
        return out
    }

    /**
     * Trip end, on the engine thread: builds the elements, then writes them on a background thread, so the engine
     * thread never waits for [Sync.lock] while a sync is on the network. See [queue].
     */
    fun flush(ctx: Context, tripId: Long, endWallMs: Long, trip: TripStats?, drive: DrivingStats?, batteryEnd: Int) {
        val items = build(Prefs.trainingActive(ctx), endWallMs, trip, drive, batteryEnd)
        if (items.isEmpty()) return
        // Set now, on this thread, so the trip-end sync (scheduled right after this) can never upload this trip.
        TrainingConsent.uploadLater(ctx, endWallMs)
        val app = ctx.applicationContext ?: ctx
        Thread({
            try {
                queue(app, items, tripId, endWallMs)
            } catch (e: Exception) {
                Log.w("BumpBeeper", "training samples not queued: ${e.javaClass.simpleName}")
            }
        }, "training-queue").start()
    }

    companion object {
        /** [TripPrivacy.RADIUS_M] plus the engine's match radius (an event is at most that far from its spot). */
        const val SPOT_ZONE_M = TripPrivacy.RADIUS_M + 20.0

        /**
         * Trips not confirmed yet (auto-started): their rows are held, see [release] / [discard]. Default: none.
         * Set by [app.bumpbeeper.auto.TripHold.installTraining] at app start.
         */
        @Volatile var isHeld: (Long) -> Boolean = { false }

        /**
         * Writes one trip's elements if consent still holds (checked under [Sync.lock], so a switch-off that is
         * emptying the outbox can't be undone by this trip), held when [isHeld]. Blocking; returns how many.
         */
        fun queue(ctx: Context, items: List<TrainingStore.Item>, tripId: Long, now: Long): Int = synchronized(Sync.lock) {
            if (!Prefs.trainingActive(ctx)) return 0
            Sync.withDb(ctx) { TrainingStore(it).add(items, now, tripId.toString(), isHeld(tripId)) }
            items.size
        }

        /** The trip was confirmed: its held rows may be uploaded if consent still holds, else they are deleted. Background thread. */
        fun release(ctx: Context, tripKey: String) {
            synchronized(Sync.lock) {
                Sync.withDb(ctx) {
                    if (!Prefs.trainingActive(ctx)) TrainingStore(it).discard(tripKey)
                    else { TrainingStore(it).release(tripKey); TrainingConsent.uploadLater(ctx, System.currentTimeMillis()) }
                }
            }
        }

        /** Not a drive: the trip's rows are deleted. Background thread. */
        fun discard(ctx: Context, tripKey: String) {
            synchronized(Sync.lock) { Sync.withDb(ctx) { TrainingStore(it).discard(tripKey) } }
        }

        /** The server takes 300 samples per device per day; more from one trip would only be refused. */
        const val MAX_PER_TRIP = 300
    }
}

/** The payload of `submit_training_samples` (supabase/README.md, *Training samples*). Out-of-range values are clamped. */
object TrainingJson {
    private val REASON = Regex("^[a-z_]{1,24}$")
    private val CLASSES = setOf("bump", "pothole", "unsure")

    /** Local drive date, no time of day. */
    fun day(wallMs: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(wallMs))

    /** `Build.BRAND` lower case, only `[a-z0-9 _-]`, at most 20 characters; null when nothing is left. */
    fun brand(raw: String?): String? =
        raw?.lowercase(Locale.US)?.filter { it in 'a'..'z' || it in '0'..'9' || it == ' ' || it == '_' || it == '-' }
            ?.trim()?.take(20)?.trim()?.takeIf { it.isNotEmpty() }

    /** int16 little-endian, base64. */
    fun base64(v: ShortArray): String {
        val b = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putShort(x)
        return Base64.getEncoder().encodeToString(b.array())
    }

    private fun num(x: Double, lo: Double, hi: Double, digits: Int = 2): Any {
        if (x.isNaN()) return JSONObject.NULL
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(x.coerceIn(lo, hi) * f) / f
    }

    fun sample(id: String, s: JoltSample, placement: String, spotId: Long?): JSONObject {
        val w = s.window
        val gyro = w.gyroRoll != null && w.gyroPitch != null
        return JSONObject()
            .put("client_sample_id", id)
            .put("day", day(s.wallTimeMs))
            .put("decision", s.decision)
            .put("reason", s.reason?.takeIf { REASON.matches(it) } ?: JSONObject.NULL)
            .put("classification", s.classification?.takeIf { it in CLASSES } ?: JSONObject.NULL)
            .put("placement", placement)
            .put("speed_kmh", num(if (s.speedKmh.isNaN()) 0.0 else s.speedKmh, 0.0, 250.0, 1))
            .put("heading_change_deg", num(s.headingChangeDeg, -180.0, 180.0, 1))
            .put("gps_accuracy_m", num(s.gpsAccuracyM, 0.0, 500.0, 1))
            .put("peak", num(s.peak, 0.0, 100.0))
            .put("shape_score", num(s.shapeScore, -1.0, 1.0))
            .put("first_down", s.firstDown ?: JSONObject.NULL)
            .put("roll_pitch_ratio", num(s.rollPitchRatio, 0.0, 1000.0))
            .put("side_score", num(s.sideScore, -1.0, 1.0))
            .put("spot_id", spotId ?: JSONObject.NULL)
            .put("rate_hz", w.rateHz)
            .put("pre_ms", w.preMs)
            .put("accel_v", base64(w.accel))
            .put("gyro_roll", if (gyro) base64(w.gyroRoll!!) else JSONObject.NULL)
            .put("gyro_pitch", if (gyro) base64(w.gyroPitch!!) else JSONObject.NULL)
    }

    fun trip(
        id: String, startWallMs: Long, endWallMs: Long, placement: String, counts: Map<String, Int>,
        t: TripStats?, d: DrivingStats?, batteryStart: Int, batteryEnd: Int,
    ): JSONObject {
        fun n(x: Int) = x.coerceIn(0, 10_000)
        fun pct(x: Int): Any = if (x in 0..100) x else JSONObject.NULL
        val distance = maxOf(t?.distanceM ?: 0.0, d?.distanceM ?: 0.0)
        val o = JSONObject()
            .put("client_trip_id", id)
            .put("day", day(startWallMs))
            .put("placement", placement)
            .put("duration_s", ((endWallMs - startWallMs) / 1000).coerceIn(0, 86_400))
            .put("distance_m", distance.roundToInt().coerceIn(0, 2_000_000))
        for (k in listOf("learned", "hit", "rejected", "miss", "pass_clear", "user_mute")) o.put("n_$k", n(counts[k] ?: 0))
        o.put("n_beeps", if (t != null) n(t.beeps) else JSONObject.NULL)
        o.put("battery_start", pct(batteryStart)).put("battery_end", pct(batteryEnd))
        if (d == null) {
            for (k in listOf("harsh_brakes", "harsh_accels", "harsh_corners", "swerves", "bumps_fast", "phone_use", "speeding_s", "score")) {
                o.put(k, JSONObject.NULL)
            }
        } else {
            o.put("harsh_brakes", n(d.harshBrakes)).put("harsh_accels", n(d.harshAccels)).put("harsh_corners", n(d.harshCorners))
                .put("swerves", n(d.swerves)).put("bumps_fast", n(d.bumpsFast)).put("phone_use", n(d.phoneUse))
                .put("speeding_s", d.speedingS.roundToInt().coerceIn(0, 86_400)).put("score", d.score().coerceIn(0, 100))
        }
        return o
    }

    /** The `batch` argument: device fields once, then the elements of [items]. */
    fun batch(appVersion: String, sdk: Int, brand: String?, items: List<TrainingStore.Item>): JSONObject {
        val samples = JSONArray()
        val trips = JSONArray()
        for (it in items) (if (it.kind == TrainingStore.TRIP) trips else samples).put(JSONObject(it.json))
        return JSONObject().put("app_version", appVersion.take(40).ifEmpty { "unknown" }).put("sdk", sdk.coerceIn(29, 100))
            .put("brand", brand ?: JSONObject.NULL).put("samples", samples).put("trips", trips)
    }
}
