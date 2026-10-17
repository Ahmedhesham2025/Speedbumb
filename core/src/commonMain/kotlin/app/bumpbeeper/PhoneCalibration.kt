package app.bumpbeeper

import kotlin.math.sqrt

/** Thresholds of [PhoneCalibration]. Units: km/h, m/s², seconds. */
class CalibrationConfig {
    /** Learn only on steady straight road at these speeds. */
    var minKmh = 30.0
    var maxKmh = 60.0
    /** Vertical RMS over windows of this length... */
    var windowS = 1.0
    /**
     * ...kept as a histogram ([binMs2] wide bins up to [maxRms]) that forgets slowly: each new window weighs
     * 1 / [memoryWindows] against all before it (about 3 hours of steady driving, across trips)...
     */
    var binMs2 = 0.01
    var maxRms = 3.0
    var memoryWindows = 10_800.0
    /** ...of which the [percentile] is the phone's floor, once the windows weigh [minWindows]. */
    var percentile = 0.20
    var minWindows = 120.0
    /** A window more than this × the floor learned so far is a rough road, not the phone: left out. */
    var roughFactor = 2.0
    /** The floor the engine's thresholds were tuned on (m/s² RMS): scale 1. */
    var refRms = 0.35
    /** The scale stays in this range... */
    var minScale = 0.5
    var maxScale = 1.5
    /** ...and a lowered jolt trigger never goes below this, m/s² (noise). */
    var minTriggerMs2 = 1.8
}

/**
 * The phone's own vibration floor on smooth road, learned per way it sits: the 20th percentile of the vertical RMS over
 * 1 s windows on steady straight road at 30–60 km/h, with no jolt in them, over hours of driving. A low percentile and
 * a long memory make it the phone's floor rather than the road's: smooth stretches are on every commute, and a rough
 * day doesn't move it. Its ratio to [CalibrationConfig.refRms] ([scaleFor], 0.5–1.5) multiplies the jolt trigger
 * ([trigger], never below 1.8 m/s² when lowered) and divides the severity index, so a phone that rattles in a pocket
 * doesn't over-trigger and a stiff holder isn't under-sensitive.
 *
 * The key ([keyFor]) is the placement setting, or, when that is "unknown", what the phone is doing: "pocket" in pocket
 * mode, "mounted" when it rests in a holder; a phone just lying loose learns nothing and uses 1.
 *
 * The scale a trip uses is the one learned before it ([freeze] when the engine starts): learning during the trip counts
 * from the next one. The app keeps it between trips ([encode] / [decode], in Prefs).
 */
class PhoneCalibration(val cfg: CalibrationConfig = CalibrationConfig()) {
    private class Hist(n: Int) {
        val bins = DoubleArray(n)
        var weight = 0.0
    }

    private val hists = HashMap<String, Hist>()
    private val frozen = HashMap<String, Double>()
    private val nBins = (cfg.maxRms / cfg.binMs2).toInt() + 1

    // The window going on.
    private var sumSq = 0.0
    private var n = 0
    private var startMs = -1L
    private var clean = true
    private var windowKey: String? = null

    /** The floor learned for [key], m/s² RMS; NaN until the windows weigh [CalibrationConfig.minWindows]. */
    fun floor(key: String): Double {
        val h = hists[key] ?: return Double.NaN
        if (h.weight < cfg.minWindows) return Double.NaN
        val target = h.weight * cfg.percentile
        var acc = 0.0
        for (i in h.bins.indices) {
            acc += h.bins[i]
            if (acc >= target) return (i + 0.5) * cfg.binMs2
        }
        return cfg.maxRms
    }

    /** The scale learned so far for [key] (1.0 until there are enough windows). */
    fun learnedScale(key: String): Double {
        val f = floor(key)
        return if (f.isNaN()) 1.0 else (f / cfg.refRms).coerceIn(cfg.minScale, cfg.maxScale)
    }

    /** Fix the scales this trip uses ([scaleFor]) to what was learned before it. The engine calls it when it starts. */
    fun freeze() {
        frozen.clear()
        for (k in hists.keys) frozen[k] = learnedScale(k)
    }

    /** The scale this trip uses for [key] ([keyFor]): the one learned before the trip ([freeze]); 1.0 if none. */
    fun scaleFor(key: String?): Double = key?.let { frozen[it] } ?: 1.0

    /** The jolt trigger for [key]: [base] × its scale; lowered, never below [CalibrationConfig.minTriggerMs2]. */
    fun trigger(base: Double, key: String?): Double {
        val s = scaleFor(key)
        return if (s >= 1.0) base * s else maxOf(base * s, minOf(base, cfg.minTriggerMs2))
    }

    /** How much the windows learned for [key] weigh. */
    fun windowWeight(key: String): Double = hists[key]?.weight ?: 0.0

    /**
     * One accelerometer sample: [v] the vertical with gravity removed (m/s²). [steady] = steady straight driving at
     * [speedKmh] with the phone at rest; [jolt] = a jolt over the trigger is on (that window is dropped); [key] = how
     * the phone sits ([keyFor]), null = don't learn.
     */
    fun onSample(tMs: Long, v: Double, speedKmh: Double, steady: Boolean, jolt: Boolean, key: String?) {
        val ok = key != null && steady && speedKmh in cfg.minKmh..cfg.maxKmh
        if (!ok || (windowKey != null && windowKey != key)) {
            startMs = -1; windowKey = null
            if (!ok) return
        }
        if (startMs < 0) { startMs = tMs; sumSq = 0.0; n = 0; clean = true; windowKey = key }
        sumSq += v * v; n++
        if (jolt) clean = false
        if (tMs - startMs >= (cfg.windowS * 1000).toLong()) {
            if (clean && n >= 10) add(key!!, sqrt(sumSq / n))
            startMs = -1; windowKey = null
        }
    }

    private fun add(key: String, rms: Double) {
        val h = hists.getOrPut(key) { Hist(nBins) }
        val f = floor(key)
        if (!f.isNaN() && rms > cfg.roughFactor * f) return   // rough road: not the phone
        val keep = 1.0 - 1.0 / cfg.memoryWindows
        for (i in h.bins.indices) h.bins[i] *= keep
        h.weight = h.weight * keep + 1.0
        h.bins[(rms / cfg.binMs2).toInt().coerceIn(0, nBins - 1)] += 1.0
    }

    /** For the Diagnostics screen: "mounted ×0.85 (140), pocket ×1.32 (300)". */
    fun describe(): String =
        if (hists.isEmpty()) "not learned yet"
        else hists.keys.sorted().joinToString(", ") { "$it ×${formatFixed(learnedScale(it), 2)} (${windowWeight(it).toInt()})" }

    /** key:bin=weight,bin=weight,…;… (bins of weight 0.005 or more, 2 decimals). */
    fun encode(): String = hists.entries.joinToString(";") { (k, h) ->
        k + ":" + h.bins.withIndex().filter { it.value >= 0.005 }.joinToString(",") { "${it.index}=${formatFixed(it.value, 2)}" }
    }

    companion object {
        const val MOUNTED = "mounted"
        const val POCKET = "pocket"

        /**
         * The key for a phone with placement setting [placement]: the setting itself when it is set; when "unknown",
         * "pocket" in [pocketMode], "mounted" when it rests in a holder ([state]), else null (no calibration).
         */
        fun keyFor(placement: String, pocketMode: Boolean, state: PhoneState): String? = when {
            placement != "unknown" -> placement
            pocketMode -> POCKET
            state == PhoneState.STABLE_MOUNTED -> MOUNTED
            else -> null
        }

        /** A calibration from [encode]'s text; null or unreadable parts are skipped. */
        fun decode(s: String?, cfg: CalibrationConfig = CalibrationConfig()): PhoneCalibration {
            val c = PhoneCalibration(cfg)
            for (part in s?.split(';') ?: emptyList()) {
                val i = part.indexOf(':')
                if (i <= 0) continue
                val h = Hist(c.nBins)
                for (e in part.substring(i + 1).split(',')) {
                    val j = e.indexOf('=')
                    if (j <= 0) continue
                    val bin = e.substring(0, j).trim().toIntOrNull() ?: continue
                    val w = e.substring(j + 1).trim().toDoubleOrNull() ?: continue
                    if (bin !in 0 until c.nBins || w <= 0) continue
                    h.bins[bin] += w
                    h.weight += w
                }
                if (h.weight > 0) c.hists[part.substring(0, i)] = h
            }
            return c
        }
    }
}
