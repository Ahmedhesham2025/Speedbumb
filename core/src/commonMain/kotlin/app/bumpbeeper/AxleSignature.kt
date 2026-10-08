package app.bumpbeeper

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The car's two-axle signature (E2). A speed bump is hit twice: by the front wheels, then by the rear ones a
 * wheelbase later, Δt = wheelbase / speed. Handling the phone, a knock or a pocket shake is one impulse, or many.
 *
 * The vertical acceleration is band-passed ([AxleConfig.bandLowHz]..[AxleConfig.bandHighHz]) and turned into a short
 * RMS envelope (window in time, so 50, 100 and 200 Hz give the same). Its humps are impulses; humps without a real dip
 * between them are one impulse (the lobes and ringing of one hit: repeated impulses are grouped). The front impulse
 * holds the trigger; the rear is looked for Δt ± 35 % after it, or the trigger was the rear and the front is
 * looked for before it. A pair scores by how balanced, how far above the road's noise, how separated (the envelope
 * dips between), how close to Δt and how alone it is (trains of impulses, rumble strips, score 0), and the pitch
 * order where the gyroscope shows it. Braking in the seconds before is reported too, for the caller to weigh.
 *
 * "No axle" ([AxleVerdict.ONE]) is only said when the rear would clearly have shown and nothing did; whenever the data
 * can't show it (Δt beyond the data at very slow crossings, noise, ringing, other impulses) the answer is UNKNOWN.
 * A phone near the front axle (dash mount) barely feels the rear wheels, so ONE means little there.
 */
object AxleSignature {
    /** How long to wait after a trigger so a rear impulse can show: Δt + margin, within the configured bounds. */
    fun decideWindowMs(speedMps: Double, wheelbaseM: Double, cfg: AxleConfig = AxleConfig()): Long {
        val ms = wheelbaseM / speedMps * 1000.0 + cfg.decideMarginMs
        if (!(speedMps > 0.0) || ms.isNaN()) return cfg.decideMinMs
        return ms.coerceIn(cfg.decideMinMs.toDouble(), cfg.decideMaxMs.toDouble()).toLong()
    }

    /**
     * Judge the jolt that triggered at [triggerMs]. [t] (ms, rising) and [vertical] (m/s², gravity removed) hold the
     * signal up to the decision; [pitchRate] (rad/s, nose up, [VehicleFrame.pitchRate], NaN where unknown) is optional.
     * [speedMps] is the speed at the jolt, [fixes] the recent GPS fixes (for braking).
     */
    fun analyze(
        t: LongArray, vertical: DoubleArray, pitchRate: DoubleArray?, triggerMs: Long, speedMps: Double,
        wheelbaseM: Double, fixes: List<Fix> = emptyList(), cfg: AxleConfig = AxleConfig(),
    ): AxleResult {
        val brake = brakeDrop(fixes, triggerMs, speedMps, cfg)
        val braked = if (brake.isNaN()) null else brake >= cfg.brakeMinDropKmh
        fun unknown(reason: String, expected: Double = Double.NaN) = AxleResult(
            AxleVerdict.UNKNOWN, Double.NaN, Double.NaN, expected, Double.NaN, Double.NaN, false, 0, brake, braked, reason,
        )
        if (!(speedMps * 3.6 >= cfg.minSpeedKmh)) return unknown("slow")
        val exp = wheelbaseM / speedMps * 1000.0
        if (!(exp >= cfg.minDtMs)) return unknown("fast", exp)
        val n = min(t.size, vertical.size)
        val rate = Biquad.rateHz(t.copyOf(n))
        if (n < 10 || rate < cfg.minRateHz) return unknown("no_data", exp)
        val ts = DoubleArray(n) { t[it].toDouble() }
        val x = Biquad.bandPass(vertical.copyOf(n), cfg.bandLowHz, cfg.bandHighHz, rate)
        val w = (cfg.envFraction * exp).coerceIn(cfg.envMinMs, cfg.envMaxMs)
        val e = envelope(ts, x, w)
        val tol = cfg.dtTolerance
        val trig = triggerMs.toDouble()
        val spanFrom = max(ts[0] + cfg.warmupMs, trig - (1 + tol) * exp - cfg.spanMarginMs)
        val spanTo = trig + (1 + tol) * exp + cfg.spanMarginMs
        var i0 = 0
        while (i0 < n && ts[i0] < spanFrom) i0++
        var i1 = i0 - 1
        while (i1 + 1 < n && ts[i1 + 1] <= spanTo) i1++
        if (i1 - i0 + 1 < 10) return unknown("no_data", exp)

        val hs = humps(ts, e, i0, n - 1, cfg.sepFraction * w, cfg)
        val g0 = hs.firstOrNull { it.start <= trig && trig <= it.end }
            ?: hs.firstOrNull { it.start > trig && it.start <= trig + cfg.frontSearchMs }
            ?: return unknown("no_front", exp)
        // The road's own shaking: the median envelope before the jolt (at least 0.3 s of it), else over the span.
        val cut = min(g0.start, trig) - w
        val pre = (i0..i1).filter { ts[it] < cut }
        val noise = median(if (pre.size >= 0.3 * rate) pre.map { e[it] } else (i0..i1).map { e[it] })
        for (h in hs) h.peak = maxAbs(ts, x, i0, n - 1, h.start, h.end)
        val rawPeak = maxAbs(ts, vertical, i0, i1, trig - 20.0, trig + 300.0)
        if (rawPeak > 0.0 && g0.peak < cfg.minBandShare * rawPeak) return unknown("above_band", exp)

        var best = -1.0
        var bestF: Hump? = null
        var bestR: Hump? = null
        var bestRearFirst = false
        var bestPitch = 0
        var evidence = 0.0
        var tooStrong = false
        for (m in hs) {
            if (m === g0) continue
            val d = m.t - g0.t
            val rearFirst = when {
                d >= (1 - tol) * exp && d <= (1 + tol) * exp -> false
                -d >= (1 - tol) * exp && -d <= (1 + tol) * exp -> true
                else -> continue
            }
            val f = if (rearFirst) m else g0
            val r = if (rearFirst) g0 else m
            val weak = min(f.a, r.a)
            val balance = ramp(weak / max(f.a, r.a), cfg.minBalance, cfg.goodBalance)
            val snr = ramp(weak / max(noise, 1e-9), cfg.snrLow, cfg.snrHigh)
            val rise = ramp(weak / max(lowest(ts, e, f.t, r.t), noise), cfg.riseRatio, cfg.riseGood)
            evidence = max(evidence, balance * snr * rise)
            if (r.peak > cfg.maxRearRatio * f.peak) {
                if (!rearFirst) tooStrong = true
                continue
            }
            val dev = abs((r.t - f.t) / exp - 1.0) / tol
            var s = balance * snr * rise * (1.0 - 0.5 * dev * dev) * uniqueness(f, r, hs, exp, cfg) * quiet(ts, e, f, r, cfg)
            val pitch = if (pitchRate != null && pitchRate.size >= n) pitchOrder(ts, pitchRate, f.t, r.t, cfg) else 0
            if (pitch < 0) s *= cfg.pitchWrongFactor
            if (s > best) {
                best = s; bestF = f; bestR = r; bestRearFirst = rearFirst; bestPitch = pitch
            }
        }
        val score = max(best, 0.0)
        fun result(v: AxleVerdict, reason: String): AxleResult {
            val f = bestF
            val r = bestR
            return if (f == null || r == null) {
                AxleResult(v, score, Double.NaN, exp, g0.peak, Double.NaN, false, 0, brake, braked, reason)
            } else {
                AxleResult(v, score, r.t - f.t, exp, f.peak, r.peak, bestRearFirst, bestPitch, brake, braked, reason)
            }
        }
        if (best >= cfg.bothMinScore) return result(AxleVerdict.BOTH, "pair")
        // From here on it is not a clean pair; "no axle" needs the rear to have been plainly missing.
        if (tooStrong) return result(AxleVerdict.UNKNOWN, "second_stronger")
        if (evidence > cfg.oneMaxEvidence) return result(AxleVerdict.UNKNOWN, "unclear")
        val fwdOk = g0.t + (1 + tol) * exp + w / 2 <= ts[n - 1]
        val backOk = g0.t - (1 + tol) * exp - w / 2 >= spanFrom
        if (!fwdOk || !backOk) return result(AxleVerdict.UNKNOWN, "window")
        if (g0.a * cfg.visibleRatio < cfg.visibleSnr * noise) return result(AxleVerdict.UNKNOWN, "noisy")
        if (lowest(ts, e, g0.t, g0.t + (1 - tol) * exp) > cfg.decayFraction * g0.a) return result(AxleVerdict.UNKNOWN, "ringing")
        if (hs.any { it !== g0 && it.a >= cfg.loneFraction * g0.a }) return result(AxleVerdict.UNKNOWN, "other_impulse")
        val fwdLevel = level(ts, e, g0.t + (1 - tol) * exp, g0.t + (1 + tol) * exp)
        val backLevel = level(ts, e, g0.t - (1 + tol) * exp, g0.t - (1 - tol) * exp)
        if (max(fwdLevel, backLevel) > cfg.quietFraction * g0.a) return result(AxleVerdict.UNKNOWN, "busy")
        return result(AxleVerdict.ONE, "single")
    }

    /** One impulse: an envelope hump. [t] is its onset, [a] its strongest envelope value, [peak] its band peak. */
    private class Hump(var t: Double, val a: Double, var start: Double, var end: Double) {
        val topT = ArrayList<Double>()
        val topA = ArrayList<Double>()
        var peak = 0.0
    }

    /** RMS of [x] over [w] ms centred on every sample, from a running integral: the same at any sample rate. */
    private fun envelope(ts: DoubleArray, x: DoubleArray, w: Double): DoubleArray {
        val n = ts.size
        val s = DoubleArray(n)
        for (i in 1 until n) s[i] = s[i - 1] + 0.5 * (x[i] * x[i] + x[i - 1] * x[i - 1]) * (ts[i] - ts[i - 1])
        fun at(tq: Double, k: Int): Double =
            if (k >= n - 1 || ts[k + 1] <= ts[k]) s[k] else s[k] + (tq - ts[k]) / (ts[k + 1] - ts[k]) * (s[k + 1] - s[k])
        val e = DoubleArray(n)
        var lo = 0
        var hi = 0
        for (i in 0 until n) {
            val a = max(ts[0], ts[i] - w / 2)
            val b = min(ts[n - 1], ts[i] + w / 2)
            while (lo + 1 < n && ts[lo + 1] <= a) lo++
            while (hi + 1 < n && ts[hi + 1] <= b) hi++
            e[i] = if (b > a) sqrt(max(0.0, (at(b, hi) - at(a, lo)) / (b - a))) else abs(x[i])
        }
        return e
    }

    /** The envelope's humps from index [i0] to [i1]: tops merge while too close or without a real dip between them. */
    private fun humps(ts: DoubleArray, e: DoubleArray, i0: Int, i1: Int, minSep: Double, cfg: AxleConfig): List<Hump> {
        val stack = ArrayList<Hump>()
        for (i in max(i0, 1)..min(i1, e.size - 2)) {
            if (!(e[i] >= e[i - 1] && e[i] > e[i + 1])) continue
            // Parabola through the three samples: the top between them.
            val y0 = e[i - 1]
            val y1 = e[i]
            val y2 = e[i + 1]
            val den = y0 - 2 * y1 + y2
            val d = if (den == 0.0) 0.0 else (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5)
            val step = if (d < 0) ts[i] - ts[i - 1] else ts[i + 1] - ts[i]
            var cur = Hump(ts[i] + d * step, y1 - 0.25 * (y0 - y2) * d, ts[i], ts[i])
            cur.topT.add(cur.t)
            cur.topA.add(cur.a)
            while (stack.isNotEmpty()) {
                val p = stack[stack.size - 1]
                if (cur.t - p.t >= minSep && lowest(ts, e, p.t, cur.t) * cfg.riseRatio <= min(p.a, cur.a)) break
                val big = if (cur.a > p.a) cur else p
                val merged = Hump(big.t, big.a, p.start, cur.end)
                merged.topT.addAll(p.topT); merged.topT.addAll(cur.topT)
                merged.topA.addAll(p.topA); merged.topA.addAll(cur.topA)
                cur = merged
                stack.removeAt(stack.size - 1)
            }
            stack.add(cur)
        }
        if (stack.isEmpty()) return stack
        // Boundaries at the dips; then each hump's time is its onset.
        stack[0].start = ts[i0]
        stack[stack.size - 1].end = ts[i1]
        for (k in 0 until stack.size - 1) {
            val j = lowestIndex(ts, e, stack[k].t, stack[k + 1].t)
            val tv = if (j < 0) stack[k].t else ts[j]
            stack[k].end = tv
            stack[k + 1].start = tv
        }
        for (h in stack) {
            val k = h.topA.indexOfFirst { it >= cfg.onsetFraction * h.a }
            if (k >= 0) h.t = h.topT[k]
        }
        return stack
    }

    /** 1 alone; halved per other strong impulse not a wheelbase from another; 0 for a train. */
    private fun uniqueness(f: Hump, r: Hump, hs: List<Hump>, exp: Double, cfg: AxleConfig): Double {
        val weak = min(f.a, r.a)
        val meas = r.t - f.t
        val strong = hs.filter { it.a >= cfg.otherFraction * weak }
        // A train (rumble strip, rough road) keeps going: count every strong impulse in the span, not only near the pair.
        if (strong.size > cfg.maxImpulses) return 0.0
        val other = strong.filter { it !== f && it !== r && it.t >= f.t - exp && it.t <= r.t + exp }
        if (isTrain(f, exp, cfg) || isTrain(r, exp, cfg) || other.any { isTrain(it, exp, cfg) }) return 0.0
        var u = 1.0
        for (m in other) {
            if (strong.none { it !== m && abs(abs(it.t - m.t) - meas) <= cfg.explainTolerance * meas }) u *= 0.5
        }
        return u
    }

    /** A hump whose strong tops spread over most of a wheelbase is a train of impulses, not one axle. */
    private fun isTrain(h: Hump, exp: Double, cfg: AxleConfig): Boolean {
        var first = Double.NaN
        var last = Double.NaN
        for (k in h.topA.indices) {
            if (h.topA[k] < cfg.onsetFraction * h.a) continue
            if (first.isNaN()) first = h.topT[k]
            last = h.topT[k]
        }
        return last - first > cfg.trainFraction * exp
    }

    /** Two axle hits die down between them; a long shake doesn't (only judged when they are far enough apart). */
    private fun quiet(ts: DoubleArray, e: DoubleArray, f: Hump, r: Hump, cfg: AxleConfig): Double {
        val gap = r.t - f.t
        if (gap < cfg.quietMinMs) return 1.0
        val mid = level(ts, e, f.t + 0.25 * gap, r.t - 0.25 * gap)
        return if (mid.isNaN()) 1.0 else ramp(mid / min(f.a, r.a), cfg.quietBad, cfg.quietGood)
    }

    /**
     * +1 when the car pitched nose up around the front impulse and nose down around the rear one (rad, pitch rate
     * integrated over ± a window, less its average before), -1 the other way round, 0 when unknown or not car-sized.
     */
    private fun pitchOrder(ts: DoubleArray, p: DoubleArray, tF: Double, tR: Double, cfg: AxleConfig): Int {
        val wp = (0.4 * (tR - tF)).coerceIn(60.0, 200.0)
        var sum = 0.0
        var k = 0
        for (i in ts.indices) {
            if (ts[i] >= tF - wp - 300.0 && ts[i] < tF - wp && !p[i].isNaN()) { sum += p[i]; k++ }
        }
        if (k < 3) return 0
        val base = sum / k
        fun turn(tc: Double): Double {
            var s = 0.0
            var m = 0
            for (i in 1 until ts.size) {
                if (ts[i] < tc - wp || ts[i] > tc + wp || p[i].isNaN() || p[i - 1].isNaN()) continue
                s += 0.5 * (p[i] + p[i - 1] - 2 * base) * (ts[i] - ts[i - 1]) / 1000.0
                m++
            }
            return if (m >= 3) s else Double.NaN
        }
        val dF = turn(tF)
        val dR = turn(tR)
        if (dF.isNaN() || dR.isNaN() || max(abs(dF), abs(dR)) > cfg.pitchMaxRad) return 0
        return when {
            dF > cfg.pitchMinRad && dR < -cfg.pitchMinRad -> 1
            dF < -cfg.pitchMinRad && dR > cfg.pitchMinRad -> -1
            else -> 0
        }
    }

    /** Most speed lost from the fixes in the window before [tMs] down to [speedMps], km/h; NaN without such fixes. */
    private fun brakeDrop(fixes: List<Fix>, tMs: Long, speedMps: Double, cfg: AxleConfig): Double {
        var top = Double.NaN
        for (f in fixes) {
            if (f.timeMs < tMs - cfg.brakeWindowMs || f.timeMs > tMs || f.speedMps.isNaN()) continue
            if (top.isNaN() || f.speedMps > top) top = f.speedMps
        }
        return if (top.isNaN() || speedMps.isNaN()) Double.NaN else max(0.0, (top - speedMps) * 3.6)
    }

    /** 0 at [lo], 1 at [hi], straight between; [lo] > [hi] makes it fall. NaN gives 0. */
    private fun ramp(x: Double, lo: Double, hi: Double): Double = when {
        x.isNaN() -> 0.0
        lo < hi -> ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        else -> ((lo - x) / (lo - hi)).coerceIn(0.0, 1.0)
    }

    private fun lowestIndex(ts: DoubleArray, e: DoubleArray, ta: Double, tb: Double): Int {
        var j = -1
        for (i in ts.indices) if (ts[i] > ta && ts[i] < tb && (j < 0 || e[i] < e[j])) j = i
        return j
    }

    /** The envelope's lowest value strictly between [ta] and [tb]; +∞ when no sample lies between. */
    private fun lowest(ts: DoubleArray, e: DoubleArray, ta: Double, tb: Double): Double {
        val j = lowestIndex(ts, e, ta, tb)
        return if (j < 0) Double.POSITIVE_INFINITY else e[j]
    }

    /** Median envelope from [ta] to [tb]; NaN when no sample lies there. */
    private fun level(ts: DoubleArray, e: DoubleArray, ta: Double, tb: Double): Double =
        median(ts.indices.filter { ts[it] >= ta && ts[it] <= tb }.map { e[it] })

    private fun maxAbs(ts: DoubleArray, v: DoubleArray, from: Int, to: Int, ta: Double, tb: Double): Double {
        var m = 0.0
        for (i in from..to) if (ts[i] >= ta && ts[i] <= tb) m = max(m, abs(v[i]))
        return m
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sorted()
        val k = s.size / 2
        return if (s.size % 2 == 1) s[k] else 0.5 * (s[k - 1] + s[k])
    }
}
