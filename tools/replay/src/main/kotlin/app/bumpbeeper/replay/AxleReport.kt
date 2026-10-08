package app.bumpbeeper.replay

import app.bumpbeeper.*
import java.util.Locale
import kotlin.math.abs

/**
 * E2's two-axle verdict ([AxleSignature]) for every jolt the engine judged on a drive: reported only, nothing decides
 * on it yet. The drive is replayed through its own engine (the same feed as [Replayer]) to record what the engine saw:
 * its vertical signal, the gyroscope, and its up and forward directions when it decided. Each jolt is then analysed the
 * way part 2 will: with the GPS fixes the engine holds when it decides (1.2 s after the trigger), the GPS speed at the
 * jolt, the wait from the slowest plausible speed ([AxleSignature.lowSpeedMps], at most 1.6 s) and the starting
 * wheelbase (nothing learned yet).
 */
object AxleReport {
    /** One judged jolt: when it triggered, what the engine decided (learned, hit, same_pass, rejected), the verdict. */
    class Jolt(val tMs: Long, val decision: String, val speedKmh: Double, val result: AxleResult)

    /** History kept before a trigger: the backward window at slow speed plus the filters' settling. */
    private const val PRE_MS = 3000L
    /** A decision is logged at the first sample after decideAfterMs: within this much of it. */
    private const val DECISION_SLACK_MS = 200L
    /** The engine keeps this much GPS history. */
    private const val FIX_HISTORY_MS = 12_000L

    fun of(samples: List<TraceSample>, cfg: EngineConfig = EngineConfig(), axle: AxleConfig = AxleConfig()): List<Jolt> {
        val wheelbase = VehicleConfig().wheelbaseStartM
        val t = ArrayList<Long>()
        val v = ArrayList<Double>()
        val gyro = ArrayList<DoubleArray?>()
        val decided = ArrayList<Triple<Long, String, VehicleFrame?>>()   // decision time, decision, the engine's axes then
        var now = 0L
        var engine: BumpEngine? = null
        val store = object : BumpStore {
            private var id = 1L
            override fun loadBumps(): List<Bump> = emptyList()
            override fun insertBump(b: Bump): Long = id++
            override fun updateBump(b: Bump) {}
            override fun logEvent(e: BumpEvent) {
                val d = when (e.type) {
                    "new_bump" -> "learned"
                    "hit" -> "hit"
                    "hit_repeat" -> "same_pass"
                    "rejected" -> "rejected"
                    else -> return
                }
                val eng = engine ?: return
                val up = eng.upVector()
                val gravity = up?.let { doubleArrayOf(it[0] * 9.81, it[1] * 9.81, it[2] * 9.81) }
                decided.add(Triple(now, d, gravity?.let { VehicleFrame.of(it, eng.forwardVector()) }))
            }
        }
        val eng = BumpEngine(cfg, store, object : EngineListener {}, { Replayer.WALL_BASE_MS + now })
        engine = eng
        // The same feed as core's Replayer: time order, the gyroscope only once it has given a non-zero reading.
        var gyroSeen = false
        for (s in samples.sortedBy { it.tMs }) {
            now = s.tMs
            when (s) {
                is TraceSample.Accel -> {
                    val hasGyro = !s.gx.isNaN() && !s.gy.isNaN() && !s.gz.isNaN()
                    if (hasGyro && (s.gx != 0.0 || s.gy != 0.0 || s.gz != 0.0)) gyroSeen = true
                    val g = if (hasGyro && gyroSeen) doubleArrayOf(s.gx, s.gy, s.gz) else null
                    if (g != null) eng.onGyro(s.tMs, g[0], g[1], g[2])
                    eng.onAccel(s.tMs, s.ax, s.ay, s.az)
                    t.add(s.tMs)
                    v.add(eng.lastVertical)
                    gyro.add(g)
                }
                is TraceSample.Gps -> eng.onFix(
                    Fix(s.tMs, s.lat, s.lon, s.speedKmh / 3.6, s.bearing, if (s.accuracyM.isNaN()) 99.0 else s.accuracyM),
                )
                is TraceSample.Event -> {}
            }
        }
        val fixes = samples.filterIsInstance<TraceSample.Gps>().sortedBy { it.tMs }
            .map { Fix(it.tMs, it.lat, it.lon, it.speedKmh / 3.6, it.bearing, it.accuracyM) }
        val out = ArrayList<Jolt>()
        var until = Long.MIN_VALUE
        for (i in t.indices) {
            // The engine's trigger: |vertical| at the threshold, outside the refractory time after the last one.
            if (abs(v[i]) < cfg.joltThreshold || t[i] < until) continue
            val trig = t[i]
            until = trig + cfg.refractoryMs
            val due = trig + cfg.decideAfterMs
            val d = decided.firstOrNull { it.first >= due && it.first <= due + DECISION_SLACK_MS }
            val speed = speedAt(fixes, trig)
            // The fixes the engine holds when it decides; later ones haven't arrived yet.
            val known = fixes.filter { it.timeMs in trig - FIX_HISTORY_MS..due }
            val low = AxleSignature.lowSpeedMps(known, trig, speed, axle)
            val end = trig + AxleSignature.decideWindowMs(low, wheelbase, axle)
            val idx = t.indices.filter { t[it] >= trig - PRE_MS && t[it] <= end }
            val frame = d?.third
            val pitch = if (frame != null && frame.hasForward) {
                DoubleArray(idx.size) { k -> gyro[idx[k]]?.let { frame.pitchRate(it[0], it[1], it[2]) } ?: Double.NaN }
            } else null
            val r = AxleSignature.analyze(
                LongArray(idx.size) { t[idx[it]] }, DoubleArray(idx.size) { v[idx[it]] }, pitch, trig, speed, wheelbase,
                known, axle,
            )
            out.add(Jolt(trig, d?.second ?: "undecided", speed * 3.6, r))
        }
        return out
    }

    /** GPS speed at [tMs], m/s, between the fixes around it (the nearest one at either end); NaN without fixes. */
    private fun speedAt(fixes: List<Fix>, tMs: Long): Double {
        val after = fixes.indexOfFirst { it.timeMs > tMs }
        return when {
            fixes.isEmpty() -> Double.NaN
            after == 0 -> fixes[0].speedMps
            after < 0 -> fixes.last().speedMps
            else -> {
                val a = fixes[after - 1]
                val b = fixes[after]
                a.speedMps + (b.speedMps - a.speedMps) * (tMs - a.timeMs) / (b.timeMs - a.timeMs)
            }
        }
    }

    /** Verdicts by decision, score bins, the pairs' Δt, and why the rest is unknown. */
    fun toMarkdown(jolts: List<Jolt>, title: String): String {
        val sb = StringBuilder("### Axle signature (E2, reported only): $title\n\n")
        sb.append("| Decision | Jolts | Both axles | No axle | Unknown | Score ≥ 0.8 / 0.6–0.8 / 0.3–0.6 / < 0.3 / none |\n")
        sb.append("|---|---|---|---|---|---|\n")
        for (d in listOf("learned", "hit", "same_pass", "rejected", "undecided")) {
            val js = jolts.filter { it.decision == d }
            if (js.isEmpty()) continue
            fun n(verdict: AxleVerdict) = js.count { it.result.verdict == verdict }
            val s = js.map { it.result.score }
            val bins = listOf(
                s.count { it >= 0.8 }, s.count { it >= 0.6 && it < 0.8 }, s.count { it >= 0.3 && it < 0.6 },
                s.count { it < 0.3 }, s.count { it.isNaN() },
            )
            sb.append("| $d | ${js.size} | ${n(AxleVerdict.BOTH)} | ${n(AxleVerdict.ONE)} | ${n(AxleVerdict.UNKNOWN)} | ")
            sb.append(bins.joinToString(" / ")).append(" |\n")
        }
        val pairs = jolts.filter { it.result.verdict == AxleVerdict.BOTH }
        if (pairs.isNotEmpty()) {
            val ratio = pairs.map { it.result.dtMs / it.result.expectedDtMs }.sorted()
            val wb = pairs.map { it.result.dtMs / 1000.0 * it.speedKmh / 3.6 }.sorted()
            sb.append(
                String.format(
                    Locale.US, "\nPairs: measured Δt / expected median %.2f (%.2f–%.2f); Δt × speed median %.2f m (%.2f–%.2f).\n",
                    ratio[ratio.size / 2], ratio.first(), ratio.last(), wb[wb.size / 2], wb.first(), wb.last(),
                ),
            )
        }
        val why = jolts.filter { it.result.verdict == AxleVerdict.UNKNOWN }.groupingBy { it.result.reason }.eachCount()
        val reasons = why.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
        if (why.isNotEmpty()) sb.append("Unknown because: $reasons.\n")
        return sb.toString()
    }
}
