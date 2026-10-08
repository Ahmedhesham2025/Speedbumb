package app.bumpbeeper.replay

import app.bumpbeeper.*
import java.util.Locale

/**
 * What the engine and the driving monitor did on one replayed drive, without labels: an unlabelled drive has no
 * precision or recall, but these counts still show when a change makes the engine learn, warn or reject differently.
 */
class DriveSummary(
    val distanceKm: Double,
    /** new_bump events: spots learned on this drive. */
    val learned: Int,
    /** beep events (not beep_quiet): warnings given. */
    val warnings: Int,
    val hits: Int,
    val misses: Int,
    /** rejected events by reason (first word of the note: phone_moving, too_fast, too_slow, weak_gps, no_gps, …). */
    val rejects: Map<String, Int>,
    val harshBrakes: Int, val harshAccels: Int, val harshCorners: Int, val swerves: Int,
    val bumpsFast: Int, val phoneUse: Int,
    /** Spots felt (new_bump + hit) by their band after the hit (mild, moderate, strong) and by confidence (soft, full). */
    val bands: Map<String, Int> = emptyMap(),
    val confidence: Map<String, Int> = emptyMap(),
    /** The placement the drive was replayed with (mounted, cupholder, pocket, unknown). */
    val placement: String = "unknown",
) {
    fun toMarkdown(title: String): String {
        val r = if (rejects.isEmpty()) "none" else rejects.entries.joinToString(", ") { "${it.key} ${it.value}" }
        return "### Unlabelled replay: $title\n\n| Count | Value |\n|---|---|\n" +
            "| Distance km | ${String.format(Locale.US, "%.2f", distanceKm)} |\n" +
            "| Spots learned (new_bump) | $learned |\n| Warnings (beep) | $warnings |\n| Hits | $hits |\n| Misses | $misses |\n" +
            "| Rejected | ${rejects.values.sum()} ($r) |\n" +
            "| Harsh brake / accel / corner / swerve | $harshBrakes / $harshAccels / $harshCorners / $swerves |\n" +
            "| Bumps taken fast | $bumpsFast |\n| Phone use | $phoneUse |\n" +
            "| Severity mild / moderate / strong | ${BANDS.joinToString(" / ") { (bands[it] ?: 0).toString() }} |\n" +
            "| Confidence soft / full | ${confidence["soft"] ?: 0} / ${confidence["full"] ?: 0} |\n| Placement | $placement |\n"
    }

    companion object {
        val BANDS = listOf("mild", "moderate", "strong")

        fun of(result: ReplayResult, placement: String = "unknown"): DriveSummary {
            val ev = result.events
            fun n(type: String) = ev.count { it.type == type }
            val rejects = ev.filter { it.type == "rejected" }
                .groupingBy { it.note.trim().substringBefore(' ').ifEmpty { "unknown" } }.eachCount().toSortedMap()
            val d = result.driving
            val felt = ev.filter { it.type == "new_bump" || it.type == "hit" }
            return DriveSummary(
                result.trip.distanceM / 1000.0, n("new_bump"), n("beep"), n("hit"), n("miss"), rejects,
                d.harshBrakes, d.harshAccels, d.harshCorners, d.swerves, d.bumpsFast, d.phoneUse,
                felt.groupingBy { Metrics.band(it.note) }.eachCount(), felt.groupingBy { Metrics.confidence(it.note) }.eachCount(),
                placement,
            )
        }
    }
}
