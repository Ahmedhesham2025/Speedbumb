package app.bumpbeeper

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Every number of [SeverityIndex]. Units: Hz, milliseconds, km/h. */
class SeverityConfig {
    /** The band the shaking is measured in: no slow sway of the car or the phone, nothing above what a road does. */
    var bandLowHz = 0.5
    /** At 50 Hz this is 0.4 × the rate; [Biquad] never lets it reach Nyquist on slower phones. */
    var bandHighHz = 20.0
    /** The dose is summed over this long, starting [preMs] before the trigger (both axles fit from about 10 km/h)... */
    var windowMs = 1000L
    var preMs = 200L
    /** ...after this much signal to settle the 0.5 Hz high-pass. Shorter history is used as it is. */
    var warmupMs = 1000L
    /** Speed normalisation: dose ÷ (speed / [refSpeedKmh])^[speedExponent]; below [minSpeedKmh] counts as that. */
    var refSpeedKmh = 30.0
    var speedExponent = 0.5
    var minSpeedKmh = 5.0
    /** The m/s² peak scale: index = normalised dose ÷ this, s^¼ (derivation in [SeverityIndex]). */
    var vdvPerPeak = 0.62
    /** The server keeps sev_index within 0..100. */
    var maxIndex = 100.0
}

/** How hard one jolt shook the car. NaN where it can't be told (no samples, unknown speed). */
class SeverityResult(
    /** On the m/s² peak scale, so the band edges ([EngineConfig.sevMildMax], [EngineConfig.sevStrongMin]) keep their meaning. */
    val index: Double,
    /** Raw vibration dose value over the window, m/s^1.75 (diagnostic). */
    val vdv: Double,
    /** [vdv] normalised to [SeverityConfig.refSpeedKmh] (diagnostic). */
    val vdvNorm: Double,
    /** Largest |acceleration| in the band within the window, m/s² (diagnostic). */
    val bandPeak: Double,
)

/**
 * A jolt's severity from its vibration dose rather than one sample's peak. The vertical acceleration is band-passed
 * (0.5–20 Hz), and its vibration dose value over 1 s, VDV = (∫a⁴dt)^¼, is taken from 0.2 s before the trigger. A bump
 * taken faster shakes harder, so the dose is divided by (v / 30 km/h)^0.5: a car-sized bump (quarter-car model, 8 cm ×
 * 1 m) gives VDV ∝ v^0.47, so the same bump then reads the same within ±12 % from 20 to 60 km/h. Very short, sharp
 * bumps (≤ 0.5 m) don't follow this: the tyre swallows them at speed.
 *
 * The peak scale: a half-sine of peak P lasting w has VDV = P·(3w/8)^¼. Real hits ring (seat, pocket) and both axles
 * fall in the window, so their dose is that of a longer pulse: on drive01's 53 learned or hit jolts (pocket, 7–58 km/h,
 * 100 Hz and the same at 50 Hz) the median of normalised VDV ÷ the engine's peak is 0.60–0.66 (IQR 0.50–0.81),
 * i.e. a half-sine of about 0.4 s. With [SeverityConfig.vdvPerPeak] = 0.62, index = normalised VDV ÷ 0.62 reads like
 * the peak of such a hit at 30 km/h: drive01's median index is 4.6 against a median peak of 4.55. The bands are not
 * refitted here; the index sorts jolts by dose, so about half of drive01's jolts change band against their peak.
 */
object SeverityIndex {
    fun of(
        t: LongArray, vertical: DoubleArray, triggerMs: Long, speedMps: Double, cfg: SeverityConfig = SeverityConfig(),
    ): SeverityResult {
        val n = min(t.size, vertical.size)
        val rate = Biquad.rateHz(t.copyOf(n))
        if (n < 3 || rate <= 0.0) return SeverityResult(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        val from = triggerMs - cfg.preMs
        val to = from + cfg.windowMs
        var i0 = 0
        while (i0 < n - 1 && t[i0] < from - cfg.warmupMs) i0++
        val x = Biquad.bandPass(vertical.copyOfRange(i0, n), cfg.bandLowHz, cfg.bandHighHz, rate)
        var sum4 = 0.0
        var peak = 0.0
        var any = false
        for (k in x.indices) {
            val i = i0 + k
            if (t[i] < from || t[i] > to) continue
            peak = max(peak, abs(x[k]))
            // Trapezoid on the real sample times: the same dose at any sample rate, jitter included.
            if (k > 0 && t[i - 1] >= from) {
                val a = x[k - 1] * x[k - 1]
                val b = x[k] * x[k]
                sum4 += 0.5 * (a * a + b * b) * (t[i] - t[i - 1]) / 1000.0
                any = true
            }
        }
        if (!any) return SeverityResult(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        val vdv = sum4.pow(0.25)
        val kmh = max(speedMps * 3.6, cfg.minSpeedKmh)
        val norm = vdv / (kmh / cfg.refSpeedKmh).pow(cfg.speedExponent)   // NaN speed → NaN
        return SeverityResult(min(norm / cfg.vdvPerPeak, cfg.maxIndex), vdv, norm, peak)
    }
}
