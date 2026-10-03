package app.bumpbeeper

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * "Help improve detection" (opt-in): one jolt candidate as the engine judged it, with a short window of the signal
 * around it. The app uploads it without coordinates (supabase/README.md, *Training samples*).
 */
class JoltSample(
    /** learned | hit | rejected | miss | pass_clear | user_mute ([DECISIONS]). */
    val decision: String,
    /** Why, in `[a-z_]{1,24}` (e.g. the reject reason, `same_pass`), or null. */
    val reason: String?,
    /** This jolt's shape (bump | pothole | unsure), or the spot's kind for a pass; null when not looked at. */
    val classification: String?,
    val speedKmh: Double,
    /** Signed change of the GPS heading over the ~4 s before, degrees (-180..180). NaN = unknown. */
    val headingChangeDeg: Double,
    /** Accuracy of the GPS fix nearest the moment, metres. NaN = no fix. */
    val gpsAccuracyM: Double,
    /** Vertical jolt, m/s² (for a pass: the strongest one felt near the spot). NaN = none. */
    val peak: Double,
    /** -1 (speed bump) .. +1 (pothole). NaN = not looked at. */
    val shapeScore: Double,
    val firstDown: Boolean?,
    /** Roll ÷ pitch rocking; NaN without gyroscope or forward direction. */
    val rollPitchRatio: Double,
    /** -1 left .. +1 right, NaN when not looked at or without gyroscope. */
    val sideScore: Double,
    /** Server id of the confirmed shared spot this happened at, or null. Never a local spot id. */
    val sharedSpotId: Long?,
    /** Where it happened, for the app's privacy zone only: never uploaded. NaN = unknown. */
    val lat: Double,
    val lon: Double,
    val wallTimeMs: Long,
    val window: JoltWindow,
) {
    companion object {
        val DECISIONS = listOf("learned", "hit", "rejected", "miss", "pass_clear", "user_mute")
    }
}

/**
 * The signal around a jolt on an even grid of [rateHz]: [preMs] before the trigger, the rest after it.
 * int16 at [ACCEL_SCALE] / [GYRO_SCALE] per unit (clamped), the server's format. Gyro (roll and pitch rate in the
 * car's frame) is both-or-neither, the same length as [accel].
 */
class JoltWindow(val rateHz: Int, val preMs: Int, val accel: ShortArray, val gyroRoll: ShortArray?, val gyroPitch: ShortArray?) {
    companion object {
        /** m/s² per unit (±131 m/s²). */
        const val ACCEL_SCALE = 0.004
        /** rad/s per unit (±32.7 rad/s). */
        const val GYRO_SCALE = 0.001

        fun encode(x: Double, scale: Double): Short =
            if (x.isNaN()) 0 else (x / scale).roundToLong().coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt().toShort()
    }
}

/** Where the engine hands [JoltSample]s. Called on the engine thread; must be quick (no network, no disk). */
fun interface JoltSampleSink {
    fun onSample(s: JoltSample)

    companion object {
        /** The default: nothing is kept, and the engine doesn't even keep the window buffer. */
        val NONE = JoltSampleSink { }
    }
}

/**
 * The last [capacity] samples (vertical accel, and roll / pitch rate when known), for cutting [JoltWindow]s.
 * 4096 samples = 82 s at 50 Hz, enough to reach back to a spot passed a few fixes ago.
 */
class WindowRing(private val capacity: Int = 4096) {
    private val t = LongArray(capacity)
    private val v = FloatArray(capacity)
    private val roll = FloatArray(capacity)
    private val pitch = FloatArray(capacity)
    private var head = 0
    private var count = 0

    /** [rollRate] / [pitchRate] NaN when there is no gyroscope or the car's forward direction is unknown. */
    fun add(tMs: Long, vertical: Double, rollRate: Double, pitchRate: Double) {
        if (count > 0 && tMs < t[(head - 1 + capacity) % capacity]) return   // out of order: skip
        t[head] = tMs; v[head] = vertical.toFloat(); roll[head] = rollRate.toFloat(); pitch[head] = pitchRate.toFloat()
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    private fun idx(i: Int) = (head - count + i + capacity) % capacity

    /**
     * The window around [centerMs]: up to [preMs] before and [postMs] after (each at most 2000), resampled to the
     * phone's average rate (nearest sample). Null when less than 2 s of signal is around it.
     */
    fun window(centerMs: Long, preMs: Long = MAX_SIDE_MS, postMs: Long = MAX_SIDE_MS): JoltWindow? {
        if (count < 2) return null
        val first = t[idx(0)]
        val last = t[idx(count - 1)]
        val pre = minOf(preMs, MAX_SIDE_MS, centerMs - first)
        val post = minOf(postMs, MAX_SIDE_MS, last - centerMs)
        if (pre < 0 || post < 0) return null
        // Average rate over the window's span of the buffer.
        var n = 0
        for (i in 0 until count) { val ti = t[idx(i)]; if (ti >= centerMs - pre && ti <= centerMs + post) n++ }
        if (n < 2 || pre + post <= 0) return null
        val rate = ((n - 1) * 1000.0 / (pre + post)).roundToInt().coerceIn(20, 200)
        val preSteps = floor(pre * rate / 1000.0).toInt()
        val postSteps = floor(post * rate / 1000.0).toInt()
        if (preSteps + postSteps < 2 * rate) return null
        val size = preSteps + postSteps + 1
        val acc = ShortArray(size)
        val r = ShortArray(size)
        val p = ShortArray(size)
        var gyro = true
        var j = 0
        for (k in 0 until size) {
            val tk = centerMs + (k - preSteps) * 1000.0 / rate
            while (j + 1 < count && abs(t[idx(j + 1)] - tk) <= abs(t[idx(j)] - tk)) j++
            val s = idx(j)
            acc[k] = JoltWindow.encode(v[s].toDouble(), JoltWindow.ACCEL_SCALE)
            if (roll[s].isNaN() || pitch[s].isNaN()) gyro = false
            r[k] = JoltWindow.encode(roll[s].toDouble(), JoltWindow.GYRO_SCALE)
            p[k] = JoltWindow.encode(pitch[s].toDouble(), JoltWindow.GYRO_SCALE)
        }
        val preOut = (preSteps * 1000 / rate)
        return JoltWindow(rate, preOut, acc, if (gyro) r else null, if (gyro) p else null)
    }

    companion object {
        const val MAX_SIDE_MS = 2000L
    }
}
