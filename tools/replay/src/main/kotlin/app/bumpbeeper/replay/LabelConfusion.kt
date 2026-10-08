package app.bumpbeeper.replay

import app.bumpbeeper.*
import kotlin.math.abs

/**
 * The driver's labels against what the engine decided about the jolts around them. Each label (undo applied) is paired
 * with the closest judged jolt within [WINDOW_MS], one to one, closest first. Rows: label kinds, plus [NO_LABEL] for
 * jolts nobody labelled. Columns: the decision (learned, hit, same_pass, rejected and its reason), plus [NO_JOLT] for
 * labels with no jolt near them. A `nothing` label on a learned jolt is a false bump; a `bump` with no jolt, a miss.
 */
class LabelConfusion(val counts: Map<String, Map<String, Int>>) {
    fun count(label: String, decision: String): Int = counts[label]?.get(decision) ?: 0

    fun toMarkdown(title: String): String {
        val rows = counts.keys.sortedWith(compareBy({ it == NO_LABEL }, { it }))
        val cols = counts.values.flatMap { it.keys }.distinct().sortedWith(compareBy({ it == NO_JOLT }, { it }))
        val sb = StringBuilder("### Labels against decisions (±${WINDOW_MS / 1000} s): $title\n\n")
        sb.append("| Label \\ decision | ").append(cols.joinToString(" | ")).append(" |\n")
        sb.append("|---|").append(cols.joinToString("") { "---|" }).append('\n')
        for (r in rows) sb.append("| $r | ").append(cols.joinToString(" | ") { count(r, it).toString() }).append(" |\n")
        return sb.toString()
    }

    /** A jolt the engine judged: when it triggered and what it decided. */
    class Jolt(val tMs: Long, val decision: String)

    companion object {
        const val WINDOW_MS = 3000L
        const val NO_LABEL = "(no label)"
        const val NO_JOLT = "(no jolt)"

        /** Judged jolts from the event log. A decision is logged [decideAfterMs] after its jolt triggered. */
        fun jolts(events: List<BumpEvent>, decideAfterMs: Long = EngineConfig().decideAfterMs): List<Jolt> =
            events.mapNotNull { e ->
                val decision = when (e.type) {
                    "new_bump" -> "learned"
                    "hit" -> "hit"
                    "hit_repeat" -> "same_pass"
                    "rejected" -> "rejected " + e.note.trim().substringBefore(' ').ifEmpty { "unknown" }
                    else -> return@mapNotNull null
                }
                Jolt(e.wallTime - Replayer.WALL_BASE_MS - decideAfterMs, decision)
            }

        fun of(runs: List<Run>): LabelConfusion {
            val counts = HashMap<String, MutableMap<String, Int>>()
            fun add(label: String, decision: String) = counts.getOrPut(label) { HashMap() }.merge(decision, 1, Int::plus)
            for (run in runs) {
                val labels = run.labels
                val jolts = jolts(run.result.events)
                val pairs = ArrayList<Triple<Long, Int, Int>>()
                for ((i, l) in labels.withIndex()) for ((j, jo) in jolts.withIndex()) {
                    val dt = abs(jo.tMs - l.tMs)
                    if (dt <= WINDOW_MS) pairs.add(Triple(dt, i, j))
                }
                val paired = HashMap<Int, Int>()
                val used = HashSet<Int>()
                for ((_, i, j) in pairs.sortedBy { it.first }) if (i !in paired && j !in used) { paired[i] = j; used.add(j) }
                for ((i, l) in labels.withIndex()) add(l.kind, paired[i]?.let { jolts[it].decision } ?: NO_JOLT)
                for ((j, jo) in jolts.withIndex()) if (j !in used) add(NO_LABEL, jo.decision)
            }
            return LabelConfusion(counts)
        }
    }
}
