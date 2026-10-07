package app.bumpbeeper.research

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Research recording format "rr2": gzip text, one line per sensor sample or phone event:
 *
 *     t_dms,code,value,value,…
 *
 *  - t_dms: time since the trip started in tenths of a millisecond (0.1 ms), on Android's elapsedRealtime clock (the
 *    clock of the sensors and of GPS). Every line carries the full value, never a difference. 0.1 ms keeps the real
 *    spacing of 200 Hz samples (5 ms, and its jitter). A reading taken before the start (an on-change sensor's last
 *    value) is negative. (rr1 had whole milliseconds and was never released.)
 *  - Lines come in the order the phone delivered them, not sorted by time: a GPS fix carries its own, older time; an
 *    on-change sensor's first reading can be minutes old; batched sensors arrive up to a second late. Sort by t (per
 *    code) when order matters.
 *  - code: what the line holds, see [ALL]. Values are integers in fixed units: stored = round(physical × scale), so the
 *    files stay small and exact. An empty value means "not available". Readers skip codes they don't know.
 *  - `#` lines: `# key=value` metadata. Every file starts with the format, this code table, the app, the phone and its
 *    sensors, and the trip's keys: [TRIP_ID], [RESEARCH_ID], [SEGMENT] (0, 1, … one file per 10 minutes, t runs on
 *    across them), and the time anchors [START_UTC_MS] (wall clock at the start) and [START_ELAPSED_NS]: a line happened
 *    at elapsedRealtimeNanos = start_elapsed_ns + t_dms × 100,000. The trip's last file ends with [END_LINES] (lines of
 *    the whole trip) and [END_DROPPED] (lines lost because storage was too slow); a file without them was cut short.
 *
 * Plain Kotlin without Android, so tools/replay can copy this file the way it copies core's Simulator.
 */
object ResearchFormat {
    const val VERSION = "rr2"
    /** Nanoseconds per t unit (0.1 ms). */
    const val NS_PER_T = 100_000L
    /** t units per millisecond. */
    const val T_PER_MS = 10L

    const val TRIP_ID = "trip_id"
    const val RESEARCH_ID = "research_id"
    const val SEGMENT = "segment"
    const val START_UTC_MS = "start_utc_ms"
    const val START_ELAPSED_NS = "start_elapsed_ns"
    const val END_LINES = "end_lines"
    const val END_DROPPED = "end_dropped"

    /** A time on the elapsedRealtime clock in t units (0.1 ms), rounded down. */
    fun t(ns: Long): Long = Math.floorDiv(ns, NS_PER_T)

    /** One kind of line: [scales] turn each field into an integer (0 = a text field). */
    class Code(val code: String, val doc: String, vararg val scales: Double) {
        /** Only numbers: [LineEncoder.sample] can write it. */
        val numeric: Boolean = scales.none { it == 0.0 }
    }

    val ACCEL = Code("a", "accelerometer: x,y,z [mm/s^2]", 1e3, 1e3, 1e3)
    val ACCEL_UNCAL = Code("au", "accelerometer uncalibrated: x,y,z,bias_x,bias_y,bias_z [mm/s^2]", 1e3, 1e3, 1e3, 1e3, 1e3, 1e3)
    val GYRO = Code("g", "gyroscope: x,y,z [mrad/s]", 1e3, 1e3, 1e3)
    val GYRO_UNCAL = Code("gu", "gyroscope uncalibrated: x,y,z,drift_x,drift_y,drift_z [mrad/s]", 1e3, 1e3, 1e3, 1e3, 1e3, 1e3)
    val GRAVITY = Code("gr", "gravity: x,y,z [mm/s^2]", 1e3, 1e3, 1e3)
    val LINEAR = Code("la", "linear acceleration: x,y,z [mm/s^2]", 1e3, 1e3, 1e3)
    val ROTATION = Code("rv", "rotation vector: x,y,z,w [1/10000], heading_accuracy [mrad, negative = unknown]", 1e4, 1e4, 1e4, 1e4, 1e3)
    val GAME_ROTATION = Code("gv", "game rotation vector: x,y,z,w [1/10000]", 1e4, 1e4, 1e4, 1e4)
    val MAGNETIC = Code("m", "magnetic field: x,y,z [0.01 uT]", 100.0, 100.0, 100.0)
    val PROXIMITY = Code("px", "proximity: distance [cm]", 1.0)
    val LIGHT = Code("lx", "light: illuminance [lux]", 1.0)
    /** The sensor reports hPa; stored in 0.1 Pa (hPa × 1000). */
    val PRESSURE = Code("pa", "pressure [0.1 Pa]", 1e3)
    val STEP = Code("sd", "step detected")
    val ACCURACY = Code("ac", "sensor accuracy changed: code, SENSOR_STATUS_*", 0.0, 1.0)
    val GPS = Code(
        "G", "GPS fix, t = the fix's own time: lat,lon [1e-7 deg], alt [cm], speed [cm/s], bearing [0.01 deg], accuracy [cm], " +
            "speed_accuracy [cm/s], bearing_accuracy [0.01 deg], vertical_accuracy [cm], lag [ms until it reached the app]",
        1e7, 1e7, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 1.0,
    )
    val GNSS = Code("S", "GNSS status: used_in_fix, visible, mean_cn0_of_used [0.1 dB-Hz]", 1.0, 1.0, 10.0)
    val SCREEN = Code("scr", "screen: 1 on, 0 off", 1.0)
    val UNLOCK = Code("unl", "unlocked (USER_PRESENT)")
    val LOCK = Code("lk", "keyguard: 1 locked, 0 not locked (checked every 2 s, written when it changes)", 1.0)
    val AUDIO = Code("aud", "audio: mode [AudioManager.MODE_*], outputs [1 Bluetooth, 2 wired], call_device [AudioDeviceInfo.TYPE_*]", 1.0, 1.0, 1.0)
    val BATTERY = Code(
        "bat", "battery: percent, plugged [BatteryManager.BATTERY_PLUGGED_*, 0 = on battery], status [BatteryManager.BATTERY_STATUS_*], temperature [0.1 C]",
        1.0, 1.0, 1.0, 10.0,
    )
    val CAR_BT = Code("bt", "car Bluetooth: 1 connected, 0 disconnected", 1.0)
    val ACTIVITY = Code("act", "activity transition: 1 in vehicle, 2 on foot", 1.0)
    val LABEL = Code("lbl", "label tapped by the driver: kind", 0.0)
    val DROP = Code("drop", "lines dropped so far (the disk was too slow)", 1.0)

    val ALL: List<Code> = listOf(
        ACCEL, ACCEL_UNCAL, GYRO, GYRO_UNCAL, GRAVITY, LINEAR, ROTATION, GAME_ROTATION, MAGNETIC, PROXIMITY, LIGHT, PRESSURE,
        STEP, ACCURACY, GPS, GNSS, SCREEN, UNLOCK, LOCK, AUDIO, BATTERY, CAR_BT, ACTIVITY, LABEL, DROP,
    )
    private val byCode = ALL.associateBy { it.code }

    fun code(c: String): Code? = byCode[c]

    /** The `#` lines every file starts with: format, line layout, the code table, then [meta] (`key=value`). */
    fun header(meta: List<String>): List<String> =
        (listOf("format=$VERSION", "line=t_dms,code,values (t_dms: 0.1 ms since the trip started, elapsedRealtime clock, " +
            "in arrival order; empty = not available)") + ALL.map { "code.${it.code}=${it.doc}" } + meta)
            .map { "# " + it.replace('\n', ' ').replace('\r', ' ') }
}

/**
 * Builds lines in a fixed byte buffer without allocating (about 1,200 lines a second at full rate). Digits are ASCII
 * whatever the phone's language. Not thread safe: one producer.
 */
class LineEncoder(capacity: Int) {
    val bytes = ByteArray(capacity)
    var size = 0
        private set
    /** Lines ended since the last [clear]. */
    var lines = 0
        private set
    val room: Int get() = bytes.size - size

    /** Starts a line at [t] (0.1 ms units, see [ResearchFormat]). */
    fun start(t: Long, code: String): LineEncoder { long(t); put(','); ascii(code); return this }
    fun int(v: Long): LineEncoder { put(','); long(v); return this }
    fun int(v: Int): LineEncoder = int(v.toLong())
    /** [v] × [scale], rounded; NaN or infinite leaves the field empty. */
    fun scaled(v: Double, scale: Double): LineEncoder {
        put(',')
        if (!v.isNaN() && !v.isInfinite()) long(Math.round(v * scale))
        return this
    }
    fun empty(): LineEncoder { put(','); return this }
    /** Short free text (a label). Commas, line breaks and non-ASCII become `_`, so they can't break the line. */
    fun text(s: String): LineEncoder {
        put(',')
        for (i in 0 until minOf(s.length, MAX_TEXT)) put(s[i].let { if (it in ' '..'~' && it != ',') it else '_' })
        return this
    }
    fun end() { put('\n'); lines++ }
    /** A `# ` metadata line, such as the footer. */
    fun comment(s: String) {
        put('#'); put(' ')
        for (i in 0 until minOf(s.length, MAX_COMMENT)) put(s[i].let { if (it in ' '..'~') it else '?' })
        end()
    }
    /**
     * One line of [c] at [t], every field scaled by the code table; values the sensor didn't give stay empty. Only for
     * numeric codes: a text field (`ac`, `lbl`) needs [start], [text] and [end].
     */
    fun sample(t: Long, c: ResearchFormat.Code, v: FloatArray) {
        require(c.numeric) { "${c.code} has a text field" }
        start(t, c.code)
        for (i in c.scales.indices) if (i < v.size) scaled(v[i].toDouble(), c.scales[i]) else put(',')
        end()
    }
    fun sample(t: Long, c: ResearchFormat.Code, v: DoubleArray) {
        require(c.numeric) { "${c.code} has a text field" }
        start(t, c.code)
        for (i in c.scales.indices) if (i < v.size) scaled(v[i], c.scales[i]) else put(',')
        end()
    }
    fun clear() { size = 0; lines = 0 }

    private fun put(c: Char) { bytes[size++] = c.code.toByte() }
    private fun ascii(s: String) { for (c in s) put(c) }
    private fun long(v: Long) {
        if (v == Long.MIN_VALUE) { ascii(v.toString()); return }
        var n = v
        if (n < 0) { put('-'); n = -n }
        val first = size
        do { bytes[size++] = ('0'.code + (n % 10).toInt()).toByte(); n /= 10 } while (n > 0)
        var i = first
        var j = size - 1
        while (i < j) { val b = bytes[i]; bytes[i] = bytes[j]; bytes[j] = b; i++; j-- }
    }

    companion object {
        /** The longest line written (10 fields of 20 characters: 233 bytes); callers keep this much [room] free. */
        const val MAX_LINE = 256
        private const val MAX_TEXT = 64
        private const val MAX_COMMENT = 200
    }
}

/** One data line: [fields] are the values after the code as written ("" = not available). */
class ResearchRecord(val tDms: Long, val code: String, val fields: List<String>) {
    /** [tDms] in milliseconds. */
    val tMs: Double get() = tDms / ResearchFormat.T_PER_MS.toDouble()
    /** Field [i] in physical units (m/s², rad/s, µT, hPa, degrees, m…); NaN when empty, missing or text. */
    fun value(i: Int): Double {
        val scale = ResearchFormat.code(code)?.scales?.getOrNull(i) ?: 1.0
        val n = fields.getOrNull(i)?.toLongOrNull() ?: return Double.NaN
        return if (scale == 0.0) Double.NaN else n / scale
    }
    fun text(i: Int): String = fields.getOrNull(i) ?: ""
}

/** A whole file: its `# key=value` metadata, its lines, and whether it was cut off (the app died while writing it). */
class ResearchFile(val meta: Map<String, String>, val records: List<ResearchRecord>, val truncated: Boolean)

/** Reads research files, also for validation (tools/replay). */
object ResearchReader {
    private const val NL: Byte = 10

    fun read(f: File): ResearchFile = read(f.inputStream())

    fun read(input: InputStream): ResearchFile {
        val meta = LinkedHashMap<String, String>()
        val records = ArrayList<ResearchRecord>()
        val truncated = scan(input, { k, v -> meta[k] = v }, { records.add(it) })
        return ResearchFile(meta, records, truncated)
    }

    /**
     * Streams a file (gzip or plain text) line by line, for files too big to hold in memory, and closes [input]. A gzip
     * file cut off by a killed app reads up to its last flush (every 2 s); a half-written last line is dropped. Returns
     * true if cut off.
     */
    fun scan(input: InputStream, onMeta: (String, String) -> Unit, onRecord: (ResearchRecord) -> Unit): Boolean {
        val buffered = BufferedInputStream(input, 64 * 1024)
        var src: InputStream = buffered
        var truncated = false
        val line = ByteArrayOutputStream(256)
        val buf = ByteArray(64 * 1024)
        try {
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            if (gzip) src = GZIPInputStream(buffered, 64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    if (buf[i] == NL) {
                        handle(line.toString("UTF-8"), onMeta, onRecord)
                        line.reset()
                    } else {
                        line.write(buf[i].toInt())
                    }
                }
            }
        } catch (e: IOException) {
            truncated = true   // "Unexpected end of ZLIB input stream": no gzip trailer, the writer never closed it
        } finally {
            try { src.close() } catch (_: IOException) {}   // also frees the gzip Inflater's native memory
        }
        if (line.size() > 0) truncated = true   // a line without its newline was cut in the middle
        return truncated
    }

    private fun handle(raw: String, onMeta: (String, String) -> Unit, onRecord: (ResearchRecord) -> Unit) {
        val s = raw.trimEnd('\r')
        if (s.isEmpty()) return
        if (s[0] == '#') {
            val kv = s.substring(1).trim()
            val eq = kv.indexOf('=')
            if (eq > 0) onMeta(kv.substring(0, eq), kv.substring(eq + 1))
            return
        }
        val parts = s.split(',')
        if (parts.size < 2) return
        val t = parts[0].toLongOrNull() ?: return
        onRecord(ResearchRecord(t, parts[1], parts.subList(2, parts.size)))
    }
}
