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
) {
    fun toMarkdown(title: String): String {
        val r = if (rejects.isEmpty()) "none" else rejects.entries.joinToString(", ") { "${it.key} ${it.value}" }
        return "### Unlabelled replay: $title\n\n| Count | Value |\n|---|---|\n" +
            "| Distance km | ${String.format(Locale.US, "%.2f", distanceKm)} |\n" +
            "| Spots learned (new_bump) | $learned |\n| Warnings (beep) | $warnings |\n| Hits | $hits |\n| Misses | $misses |\n" +
            "| Rejected | ${rejects.values.sum()} ($r) |\n" +
            "| Harsh brake / accel / corner / swerve | $harshBrakes / $harshAccels / $harshCorners / $swerves |\n" +
            "| Bumps taken fast | $bumpsFast |\n| Phone use | $phoneUse |\n"
    }

    companion object {
        fun of(result: ReplayResult): DriveSummary {
            val ev = result.events
            fun n(type: String) = ev.count { it.type == type }
            val rejects = ev.filter { it.type == "rejected" }
                .groupingBy { it.note.trim().substringBefore(' ').ifEmpty { "unknown" } }.eachCount().toSortedMap()
            val d = result.driving
            return DriveSummary(
                result.trip.distanceM / 1000.0, n("new_bump"), n("beep"), n("hit"), n("miss"), rejects,
                d.harshBrakes, d.harshAccels, d.harshCorners, d.swerves, d.bumpsFast, d.phoneUse,
            )
        }
    }
}
