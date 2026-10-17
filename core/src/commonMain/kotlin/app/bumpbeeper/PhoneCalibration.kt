package app.bumpbeeper

import kotlin.math.sqrt

/** Thresholds of [PhoneCalibration]. Units: km/h, m/s², seconds. */
class CalibrationConfig {
    /** Learn only on steady straight road at these speeds. */
    var minKmh = 30.0
    var maxKmh = 60.0
    /** Vertical RMS over windows of this length... */
    var windowS = 1.0
    /** ...the newest [keep] of them per placement, and their median once there are [minWindows]. */
    var keep = 300
    var minWindows = 120
    /** The noise floor the engine's thresholds were tuned on (m/s² RMS): scale 1. */
    var refRms = 0.35
    /** The scale stays in this range. */
    var minScale = 0.7
    var maxScale = 1.5
}

/**
 * The phone's own vibration level on smooth road, learned per placement (mounted, pocket, cup holder, unknown): the
 * rolling median of the vertical RMS over 1 s windows on steady straight road at 30–60 km/h, with no jolt in them.
 * Its ratio to [CalibrationConfig.refRms] ([scale], 0.7–1.5) multiplies the jolt trigger and divides the severity
 * index, so a phone that rattles in a pocket doesn't over-trigger and a stiff holder isn't under-sensitive.
 *
 * The scale a trip uses is the one learned before it ([scaleFor] is frozen when the engine starts): learning during the
 * trip counts from the next one. The app keeps it between trips ([encode] / [decode], in Prefs).
 */
class PhoneCalibration(val cfg: CalibrationConfig = CalibrationConfig()) {
    private val windows = HashMap<String, ArrayDeque<Double>>()
    private val frozen = HashMap<String, Double>()

    // The window going on.
    private var sumSq = 0.0
    private var n = 0
    private var startMs = -1L
    private var clean = true

    /** The scale learned so far for [placement] (1.0 until there are enough windows). */
    fun learnedScale(placement: String): Double {
        val w = windows[placement] ?: return 1.0
        if (w.size < cfg.minWindows) return 1.0
        val s = w.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        return (median / cfg.refRms).coerceIn(cfg.minScale, cfg.maxScale)
    }

    /** Fix the scales this trip uses ([scaleFor]) to what was learned before it. The engine calls it when it starts. */
    fun freeze() {
        frozen.clear()
        for (p in windows.keys) frozen[p] = learnedScale(p)
    }

    /** The scale this trip uses for [placement]: the one learned before the trip ([freeze]); 1.0 if none. */
    fun scaleFor(placement: String): Double = frozen[placement] ?: 1.0

    /** Windows learned for [placement]. */
    fun windowCount(placement: String): Int = windows[placement]?.size ?: 0

    /**
     * One accelerometer sample: [v] the vertical with gravity removed (m/s²). [steady] = steady straight driving at
     * [speedKmh] with the phone at rest; [jolt] = a jolt over the trigger is on (that window is dropped).
     */
    fun onSample(tMs: Long, v: Double, speedKmh: Double, steady: Boolean, jolt: Boolean, placement: String) {
        val ok = steady && speedKmh in cfg.minKmh..cfg.maxKmh
        if (!ok) { startMs = -1; return }
        if (startMs < 0) { startMs = tMs; sumSq = 0.0; n = 0; clean = true }
        sumSq += v * v; n++
        if (jolt) clean = false
        if (tMs - startMs >= (cfg.windowS * 1000).toLong()) {
            if (clean && n >= 10) {
                val w = windows.getOrPut(placement) { ArrayDeque() }
                w.addLast(sqrt(sumSq / n))
                while (w.size > cfg.keep) w.removeFirst()
            }
            startMs = -1
        }
    }

    /** For the Diagnostics screen: "pocket ×1.32 (300), mounted ×0.85 (140)". */
    fun describe(): String =
        if (windows.isEmpty()) "not learned yet"
        else windows.keys.sorted().joinToString(", ") { "$it ×${formatFixed(learnedScale(it), 2)} (${windowCount(it)})" }

    /** Placement:rms,rms,…;… (the windows, 3 decimals). */
    fun encode(): String = windows.entries.joinToString(";") { (p, w) -> p + ":" + w.joinToString(",") { formatFixed(it, 3) } }

    companion object {
        /** A calibration from [encode]'s text; null or unreadable parts are skipped. */
        fun decode(s: String?, cfg: CalibrationConfig = CalibrationConfig()): PhoneCalibration {
            val c = PhoneCalibration(cfg)
            for (part in s?.split(';') ?: emptyList()) {
                val i = part.indexOf(':')
                if (i <= 0) continue
                val values = part.substring(i + 1).split(',').mapNotNull { it.trim().toDoubleOrNull() }.filter { it > 0 }
                if (values.isNotEmpty()) c.windows[part.substring(0, i)] = ArrayDeque(values.takeLast(cfg.keep))
            }
            return c
        }
    }
}
