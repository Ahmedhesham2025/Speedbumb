package app.bumpbeeper.replay

import app.bumpbeeper.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The phone pick-ups a passenger marked on a test drive (Diagnostics → Mark): an `event=mark` row in the debug trace,
 * an `lbl` line `mark` in research files (contracts/research-rr2.md). A mark is a handling marker, never a label: it is
 * kept out of label matching and no undo takes it back ([Metrics.labels], [Rr2]). Two release gates look ±[WINDOW_MS]
 * around each mark (docs/validation/metrics.md):
 *  - harsh events ([HARSH]) near a mark: the hand moved the phone, not the car. Gate: none.
 *  - phone use: at least [PHONE_USE_GATE] of the pick-ups of [MIN_PICKUP_MS] or more made while driving have a
 *    `phone_use` event near their mark. A pick-up's length is measured here, apart from the engine ([pickupMs]).
 */
class MarkReport(
    val marks: Int,
    /** Harsh events within the window of a mark, by type; each event counted once. */
    val harshNear: Map<String, Int>,
    /** Marks with a `phone_use` event within the window. */
    val withPhoneUse: Int,
    /** The marks the phone-use gate counts, and those of them with phone use. */
    val gated: Int, val gatedWithPhoneUse: Int,
    /** Marks left out of the gate: a pick-up shorter than [MIN_PICKUP_MS], or the car stopped (GPS < [MOVING_KMH]). */
    val short: Int, val stopped: Int,
    /** Gated marks of unknown length (no gyroscope): counted, to be safe. */
    val unknownLength: Int,
) {
    val harsh: Int get() = harshNear.values.sum()

    /** The share of gated marks with phone use; NaN without any. */
    val phoneUseShare: Double get() = if (gated == 0) Double.NaN else gatedWithPhoneUse.toDouble() / gated

    fun toMarkdown(title: String): String {
        val byType = HARSH.joinToString(" / ") { (harshNear[it] ?: 0).toString() }
        val share = if (gated == 0) "n/a" else String.format(Locale.ROOT, "%.0f %%", phoneUseShare * 100)
        val useGate = when {
            gated == 0 -> "n/a"
            phoneUseShare >= PHONE_USE_GATE -> "pass"
            else -> "**FAIL**"
        }
        val sb = StringBuilder("### Phone pick-ups (marks, ±${WINDOW_MS / 1000} s): $title\n\n")
        sb.append("| Metric | Value | Gate |\n|---|---|---|\n")
        sb.append("| Marks | $marks | |\n")
        sb.append("| Harsh events near a mark (brake / accel / corner / swerve) | $harsh ($byType) | 0: ")
            .append(if (harsh == 0) "pass" else "**FAIL**").append(" |\n")
        sb.append("| Marks with phone use near | $withPhoneUse / $marks | |\n")
        sb.append("| Pick-ups ≥ ${MIN_PICKUP_MS / 1000.0} s while driving, with phone use | $gatedWithPhoneUse / $gated ($share) | ")
            .append("≥ ${(PHONE_USE_GATE * 100).toInt()} %: $useGate |\n")
        sb.append("| Left out: shorter / car stopped | $short / $stopped | |\n")
        sb.append("| Gated, length unknown (no gyroscope) | $unknownLength | |\n")
        return sb.toString()
    }

    companion object {
        const val MARK = "mark"
        const val WINDOW_MS = 5000L
        /** A pick-up this long or longer must be phone use (DrivingConfig.phoneUseS, E3). */
        const val MIN_PICKUP_MS = 1500L
        const val PHONE_USE_GATE = 0.9
        val HARSH = listOf("harsh_brake", "harsh_accel", "harsh_corner", "swerve")
        /** Phone use is judged while driving only (the monitor's 10 km/h). */
        const val MOVING_KMH = 10.0
        const val SEARCH_MS = 10_000L
        const val SMOOTH_MS = 200L
        /** Rotation faster than a car turns (a tight turn is about 0.5 rad/s): a hand lifting or putting the phone down. */
        const val BURST_RAD_S = 0.6

        /** Mark times (ms), in order: `mark` events, and `label` rows reading `mark` (read the same way). */
        fun marks(samples: List<TraceSample>): List<Long> = samples
            .filter { it is TraceSample.Event && (it.type == MARK || (it.type == "label" && it.note.trim() == MARK)) }
            .map { it.tMs }.sorted()

        /**
         * How long the phone was handled around [markMs], ms: from the first rotation burst within [SEARCH_MS] before
         * it (lifting the phone) to the last within [SEARCH_MS] after it (putting it down), the mark included; 0 when
         * there is none (a tap on a phone left in its holder). A burst: the gyroscope's magnitude averaged over
         * [SMOOTH_MS] above [BURST_RAD_S]. Null without gyroscope readings. [accel] in time order.
         */
        fun pickupMs(accel: List<TraceSample.Accel>, markMs: Long): Long? {
            val near = accel.filter { it.tMs in (markMs - SEARCH_MS - SMOOTH_MS)..(markMs + SEARCH_MS) && !rate(it).isNaN() }
            if (near.isEmpty()) return null
            var from = Long.MAX_VALUE
            var to = Long.MIN_VALUE
            var lo = 0
            var sum = 0.0
            for ((i, s) in near.withIndex()) {
                sum += rate(s)
                while (near[lo].tMs <= s.tMs - SMOOTH_MS) sum -= rate(near[lo++])
                if (s.tMs < markMs - SEARCH_MS || sum / (i - lo + 1) <= BURST_RAD_S) continue
                from = min(from, s.tMs)
                to = max(to, s.tMs)
            }
            return if (from == Long.MAX_VALUE) 0L else max(to, markMs) - min(from, markMs)
        }

        private fun rate(s: TraceSample.Accel) = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)

        fun of(runs: List<Run>): MarkReport {
            val harsh = HashMap<String, Int>()
            var count = 0
            var withUse = 0
            var gated = 0
            var gatedUse = 0
            var short = 0
            var stopped = 0
            var unknown = 0
            for (run in runs) {
                val times = marks(run.samples)
                if (times.isEmpty()) continue
                fun tOf(e: BumpEvent) = e.wallTime - Replayer.WALL_BASE_MS
                fun nearMark(t: Long) = times.any { abs(it - t) <= WINDOW_MS }
                for (e in run.result.events) if (e.type in HARSH && nearMark(tOf(e))) harsh.merge(e.type, 1, Int::plus)
                val uses = run.result.events.filter { it.type == "phone_use" }.map { tOf(it) }
                val accel = run.samples.filterIsInstance<TraceSample.Accel>().sortedBy { it.tMs }
                val fixes = run.samples.filterIsInstance<TraceSample.Gps>().sortedBy { it.tMs }
                for (m in times) {
                    count++
                    val used = uses.any { abs(it - m) <= WINDOW_MS }
                    if (used) withUse++
                    val len = pickupMs(accel, m)
                    when {
                        // No fix within 3 s: NaN, counted as driving.
                        Metrics.speedAt(fixes, m) < MOVING_KMH -> stopped++
                        len != null && len < MIN_PICKUP_MS -> short++
                        else -> {
                            gated++
                            if (used) gatedUse++
                            if (len == null) unknown++
                        }
                    }
                }
            }
            return MarkReport(count, harsh, withUse, gated, gatedUse, short, stopped, unknown)
        }
    }
}
