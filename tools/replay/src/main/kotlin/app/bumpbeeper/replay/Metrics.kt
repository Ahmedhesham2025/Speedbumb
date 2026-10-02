package app.bumpbeeper.replay

import app.bumpbeeper.*
import java.util.Locale
import kotlin.math.*

/** Accuracy of one replayed drive. Definitions: docs/validation/metrics.md. Ratios are NaN when there is nothing to count. */
class MetricsReport(
    /** Hazard labels (bump, pothole_l, pothole_r; not rough), and those at ≥ 25 km/h; each with how many matched. */
    val labels: Int, val matchedLabels: Int, val labelsFast: Int, val matchedFast: Int,
    /** Engine detections (new_bump + hit) and how many matched any label. */
    val detections: Int, val matchedDetections: Int,
    val beeps: Int, val falseWarnings: Int, val distanceKm: Double,
    val kindChecked: Int, val kindCorrect: Int, val sideChecked: Int, val sideCorrect: Int,
    /** Labelled spots driven over at least twice, and how many the engine found on pass 1 or 2. */
    val spots: Int, val spotsLearned: Int,
) {
    val precision get() = ratio(matchedDetections, detections)
    val recall get() = ratio(matchedLabels, labels)
    val recallFast get() = ratio(matchedFast, labelsFast)
    val falseWarningsPer100Km get() = if (distanceKm > 0) falseWarnings / distanceKm * 100.0 else Double.NaN
    val kindAccuracy get() = ratio(kindCorrect, kindChecked)
    val sideAccuracy get() = ratio(sideCorrect, sideChecked)
    val learnedRate get() = ratio(spotsLearned, spots)

    private class Row(val name: String, val value: Double, val target: String, val pass: Boolean?)

    private fun rows() = listOf(
        Row("Precision", precision, "≥ 0.90", ok(precision) { it >= 0.90 }),
        Row("Recall ≥ 25 km/h", recallFast, "≥ 0.95", ok(recallFast) { it >= 0.95 }),
        Row("Recall overall", recall, "≥ 0.85", ok(recall) { it >= 0.85 }),
        Row("False warnings / 100 km", falseWarningsPer100Km, "≤ 1", ok(falseWarningsPer100Km) { it <= 1.0 }),
        Row("Bump vs pothole", kindAccuracy, "≥ 0.85", ok(kindAccuracy) { it >= 0.85 }),
        Row("Pothole side", sideAccuracy, "≥ 0.80", ok(sideAccuracy) { it >= 0.80 }),
        Row("Spot learned within 2 passes", learnedRate, "(tracked)", null),
    )

    fun toMarkdown(title: String): String {
        val sb = StringBuilder("### Replay: $title\n\n| Metric | Value | Target | |\n|---|---|---|---|\n")
        for (r in rows()) {
            val mark = when (r.pass) { true -> "pass"; false -> "**FAIL**"; null -> "" }
            sb.append("| ${r.name} | ${fmt(r.value)} | ${r.target} | $mark |\n")
        }
        sb.append(
            "\n$labels hazard labels ($matchedLabels matched), $detections detections ($matchedDetections matched), " +
                "$beeps beeps ($falseWarnings false), ${fmt(distanceKm)} km.\n",
        )
        return sb.toString()
    }

    fun toJson(): String {
        val fields = listOf(
            "precision" to precision, "recall" to recall, "recall_fast" to recallFast,
            "false_warnings_per_100km" to falseWarningsPer100Km, "kind_accuracy" to kindAccuracy,
            "side_accuracy" to sideAccuracy, "learned_within_2_passes" to learnedRate,
            "labels" to labels, "matched_labels" to matchedLabels, "labels_fast" to labelsFast, "matched_fast" to matchedFast,
            "detections" to detections, "matched_detections" to matchedDetections, "beeps" to beeps,
            "false_warnings" to falseWarnings, "distance_km" to distanceKm, "kind_checked" to kindChecked,
            "kind_correct" to kindCorrect, "side_checked" to sideChecked, "side_correct" to sideCorrect,
            "spots" to spots, "spots_learned" to spotsLearned,
        )
        return fields.joinToString(",\n", "{\n", "\n}\n") { (k, v) ->
            "  \"$k\": " + if (v is Double) (if (v.isNaN()) "null" else String.format(Locale.US, "%.4f", v)) else v.toString()
        }
    }

    private companion object {
        fun ratio(a: Int, b: Int) = if (b == 0) Double.NaN else a.toDouble() / b
        fun ok(x: Double, test: (Double) -> Boolean): Boolean? = if (x.isNaN()) null else test(x)
        fun fmt(x: Double) = if (x.isNaN()) "–" else String.format(Locale.US, "%.2f", x)
    }
}

object Metrics {
    const val MATCH_MS = 2000L
    const val MATCH_M = 15.0
    const val WARN_M = 30.0
    const val FAST_KMH = 25.0
    val HAZARDS = setOf("bump", "pothole_l", "pothole_r")

    /** One felt hit, as the engine logged it. [kind] = what the spot is after this hit; [side] = left/right/unknown. */
    class Detection(val tMs: Long, val lat: Double, val lon: Double, val kind: String, val side: String)

    fun detections(events: List<BumpEvent>): List<Detection> =
        events.filter { it.type == "new_bump" || it.type == "hit" }.map {
            // new_bump note: "<kind> looks=…"; hit note: "hits 2/3 now=<kind> looks=…"; both end with "side=…" when known.
            val kind = Regex("now=(\\w+)").find(it.note)?.groupValues?.get(1) ?: it.note.trim().substringBefore(' ')
            val side = Regex("side=(left|right)").find(it.note)?.groupValues?.get(1) ?: "unknown"
            Detection(it.wallTime - Replayer.WALL_BASE_MS, it.lat, it.lon, kind, side)
        }

    private fun near(l: Label, lat: Double, lon: Double, maxM: Double) =
        l.lat.isNaN() || Geo.distance(l.lat, l.lon, lat, lon) <= maxM   // a label without GPS is matched on time alone

    /** One-to-one matching, closest in time first. Returns label index → detection index. */
    fun match(labels: List<Label>, dets: List<Detection>): Map<Int, Int> {
        val pairs = ArrayList<Triple<Long, Int, Int>>()
        for ((i, l) in labels.withIndex()) for ((j, d) in dets.withIndex()) {
            val dt = abs(d.tMs - l.tMs)
            if (dt <= MATCH_MS && near(l, d.lat, d.lon, MATCH_M)) pairs.add(Triple(dt, i, j))
        }
        val out = HashMap<Int, Int>()
        val used = HashSet<Int>()
        for ((_, i, j) in pairs.sortedBy { it.first }) {
            if (i !in out && j !in used) { out[i] = j; used.add(j) }
        }
        return out
    }

    fun compute(labels: List<Label>, result: ReplayResult, samples: List<TraceSample>): MetricsReport {
        val dets = detections(result.events)
        val matches = match(labels, dets)
        val fixes = samples.filterIsInstance<TraceSample.Gps>()
        fun speedAt(tMs: Long): Double {
            val f = fixes.minByOrNull { abs(it.tMs - tMs) } ?: return Double.NaN
            return if (abs(f.tMs - tMs) <= 3000) f.speedKmh else Double.NaN
        }
        val hazards = labels.indices.filter { labels[it].kind in HAZARDS }
        val fast = hazards.filter { speedAt(labels[it].tMs) >= FAST_KMH }

        var kindChecked = 0; var kindCorrect = 0; var sideChecked = 0; var sideCorrect = 0
        for (i in hazards) {
            val d = dets[matches[i] ?: continue]
            val kind = labels[i].kind
            kindChecked++
            if (d.kind == (if (kind == "bump") "bump" else "pothole")) kindCorrect++
            if (kind != "bump") {
                sideChecked++
                if (d.side == (if (kind == "pothole_l") "left" else "right")) sideCorrect++
            }
        }

        // A beep is false when no label lies within WARN_M of the spot it warned about (distanceM ahead of the car).
        val beeps = result.events.filter { it.type == "beep" }
        val falseWarnings = beeps.count { e ->
            val spot = if (e.heading.isNaN() || e.distanceM.isNaN()) doubleArrayOf(e.lat, e.lon)
                else Geo.move(e.lat, e.lon, e.heading, e.distanceM)
            labels.none { !it.lat.isNaN() && Geo.distance(it.lat, it.lon, spot[0], spot[1]) <= WARN_M }
        }

        // Group hazard labels into spots (within MATCH_M of the spot's first label); each label is one pass.
        val spots = ArrayList<MutableList<Int>>()
        for (i in hazards.sortedBy { labels[it].tMs }) {
            val l = labels[i]
            if (l.lat.isNaN()) continue
            val s = spots.firstOrNull { val f = labels[it[0]]; Geo.distance(f.lat, f.lon, l.lat, l.lon) <= MATCH_M }
            if (s != null) s.add(i) else spots.add(mutableListOf(i))
        }
        val repeated = spots.filter { it.size >= 2 }

        return MetricsReport(
            hazards.size, hazards.count { it in matches }, fast.size, fast.count { it in matches },
            dets.size, matches.size, beeps.size, falseWarnings, result.trip.distanceM / 1000.0,
            kindChecked, kindCorrect, sideChecked, sideCorrect,
            repeated.size, repeated.count { s -> s.take(2).any { it in matches } },
        )
    }
}
