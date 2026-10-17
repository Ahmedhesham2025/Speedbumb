package app.bumpbeeper.replay

import app.bumpbeeper.*
import java.util.Locale
import kotlin.math.*

/** Accuracy of one or more replayed drives. Definitions: docs/validation/metrics.md. Ratios are NaN when there is nothing to count. */
class MetricsReport(
    /** Hazard labels (bump with or without its band; not rough or nothing), and those at ≥ 25 km/h; each with how many matched. */
    val labels: Int, val matchedLabels: Int, val labelsFast: Int, val matchedFast: Int,
    /** Engine detections (new_bump + hit) and how many matched any label. */
    val detections: Int, val matchedDetections: Int,
    val beeps: Int, val falseWarnings: Int, val distanceKm: Double,
    /** Labelled spots driven over at least twice, and how many the engine found on pass 1 or 2. */
    val spots: Int, val spotsLearned: Int,
    /** Counts per run, in replay order (empty for a single recording). */
    val runs: List<RunCounts> = emptyList(),
) {
    val precision get() = ratio(matchedDetections, detections)
    val recall get() = ratio(matchedLabels, labels)
    val recallFast get() = ratio(matchedFast, labelsFast)
    val falseWarningsPer100Km get() = if (distanceKm > 0) falseWarnings / distanceKm * 100.0 else Double.NaN
    val learnedRate get() = ratio(spotsLearned, spots)

    private class Row(val name: String, val value: Double, val target: String, val pass: Boolean?)

    private fun rows() = listOf(
        Row("Precision", precision, "≥ 0.90", ok(precision) { it >= 0.90 }),
        Row("Recall ≥ 25 km/h", recallFast, "≥ 0.95", ok(recallFast) { it >= 0.95 }),
        Row("Recall overall", recall, "≥ 0.85", ok(recall) { it >= 0.85 }),
        Row("False warnings / 100 km", falseWarningsPer100Km, "≤ 1", ok(falseWarningsPer100Km) { it <= 1.0 }),
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
        if (runs.isNotEmpty()) {
            sb.append("\n| Run | Labels (matched) | Detections (matched) | Beeps (false) | km |\n|---|---|---|---|---|\n")
            for (r in runs) {
                sb.append("| ${r.name} | ${r.labels} (${r.matchedLabels}) | ${r.detections} (${r.matchedDetections}) | ")
                sb.append("${r.beeps} (${r.falseWarnings}) | ${fmt(r.distanceKm)} |\n")
            }
        }
        return sb.toString()
    }

    fun toJson(): String {
        val fields = listOf(
            "precision" to precision, "recall" to recall, "recall_fast" to recallFast,
            "false_warnings_per_100km" to falseWarningsPer100Km, "learned_within_2_passes" to learnedRate,
            "labels" to labels, "matched_labels" to matchedLabels, "labels_fast" to labelsFast, "matched_fast" to matchedFast,
            "detections" to detections, "matched_detections" to matchedDetections, "beeps" to beeps,
            "false_warnings" to falseWarnings, "distance_km" to distanceKm, "spots" to spots, "spots_learned" to spotsLearned,
        )
        val body = fields.map { (k, v) -> "  \"$k\": " + num(v) }.toMutableList()
        if (runs.isNotEmpty()) {
            body.add(
                runs.joinToString(",\n", "  \"runs\": [\n", "\n  ]") { r ->
                    "    {\"name\": \"${jsonText(r.name)}\", \"labels\": ${r.labels}, \"matched_labels\": ${r.matchedLabels}, " +
                        "\"detections\": ${r.detections}, \"matched_detections\": ${r.matchedDetections}, \"beeps\": ${r.beeps}, " +
                        "\"false_warnings\": ${r.falseWarnings}, \"distance_km\": ${num(r.distanceKm)}}"
                },
            )
        }
        return body.joinToString(",\n", "{\n", "\n}\n")
    }

    private companion object {
        fun ratio(a: Int, b: Int) = if (b == 0) Double.NaN else a.toDouble() / b
        fun ok(x: Double, test: (Double) -> Boolean): Boolean? = if (x.isNaN()) null else test(x)
        fun fmt(x: Double) = if (x.isNaN()) "–" else String.format(Locale.US, "%.2f", x)
        fun num(v: Any): String =
            if (v is Double) (if (v.isNaN()) "null" else String.format(Locale.US, "%.4f", v)) else v.toString()
        /** File names only: escape what JSON requires, drop other control characters. */
        fun jsonText(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").filter { it >= ' ' }
    }
}

/** One replayed recording: its labels (see [Metrics.labels]), what the engine did, the raw samples (for GPS), the placement. */
class Run(val name: String, val labels: List<Label>, val result: ReplayResult, val samples: List<TraceSample>, val placement: String = "unknown")

/** Per-run counts, shown when several runs of one route are scored together. */
class RunCounts(
    val name: String, val labels: Int, val matchedLabels: Int, val detections: Int, val matchedDetections: Int,
    val beeps: Int, val falseWarnings: Int, val distanceKm: Double,
)

object Metrics {
    const val MATCH_MS = 2000L
    const val MATCH_M = 15.0
    /** The distance gate grows by speed × time gap, with the time gap capped here. */
    const val MAX_GAP_S = 2.0
    /** The engine logs a detection this long after the jolt it placed (EngineConfig.decideAfterMs). */
    val DECIDE_MS = EngineConfig().decideAfterMs
    const val WARN_M = 30.0
    const val FAST_KMH = 25.0
    /** A label after the last GPS fix is moved on from that fix for at most this long. */
    const val MAX_EXTRAPOLATE_MS = 2000L
    /** A spot to be found: a bump, with or without its band (old pothole labels read as bumps, TraceReader.labels). */
    fun isHazard(kind: String) = kind == "bump" || kind.startsWith("bump_") || kind.startsWith("pothole")

    /** One felt hit, as the engine logged it. [band] = the spot's severity after this hit (mild/moderate/strong, "" unknown). */
    class Detection(val tMs: Long, val lat: Double, val lon: Double, val band: String = "")

    fun detections(events: List<BumpEvent>): List<Detection> =
        events.filter { it.type == "new_bump" || it.type == "hit" }.map {
            Detection(it.wallTime - Replayer.WALL_BASE_MS, it.lat, it.lon, band(it.note))
        }

    /** The band in an engine note ("bump sev=moderate conf=soft …"), "" when there is none. */
    fun band(note: String): String = Regex("sev=(\\w+)").find(note)?.groupValues?.get(1) ?: ""

    /** The confidence in an engine note ("conf=soft"), "" when there is none. */
    fun confidence(note: String): String = Regex("conf=(\\w+)").find(note)?.groupValues?.get(1) ?: ""

    /**
     * The passenger's labels (undo applied, [TraceReader.labels]), each placed where the car was at the moment of
     * the tap: between the two GPS fixes around it, or moved on from the last fix by its speed and bearing.
     * (The nearest 1 Hz fix alone can be half a second, 7 m at 50 km/h, away from the tap.)
     */
    fun labels(samples: List<TraceSample>): List<Label> {
        val fixes = samples.filterIsInstance<TraceSample.Gps>().sortedBy { it.tMs }
        // A label row reading `mark` is a handling marker ([MarkReport]), never a label, and no undo takes it back.
        val taps = samples.filterNot { it is TraceSample.Event && it.type == "label" && it.note.trim() == MarkReport.MARK }
        return TraceReader.labels(taps).map { l ->
            val p = positionAt(fixes, l.tMs) ?: return@map l
            Label(l.tMs, l.kind, p[0], p[1])
        }
    }

    /** Car position at [tMs] from GPS fixes sorted by time, or null if there are none. */
    fun positionAt(fixes: List<TraceSample.Gps>, tMs: Long): DoubleArray? {
        if (fixes.isEmpty()) return null
        val i = fixes.indexOfFirst { it.tMs > tMs }   // first fix after the tap
        if (i == 0) return doubleArrayOf(fixes[0].lat, fixes[0].lon)   // before the first fix: nothing better
        if (i > 0) {
            val a = fixes[i - 1]
            val b = fixes[i]
            val k = (tMs - a.tMs).toDouble() / (b.tMs - a.tMs)
            return doubleArrayOf(a.lat + (b.lat - a.lat) * k, a.lon + (b.lon - a.lon) * k)
        }
        // After the last fix: move on along its bearing at its speed, for a short while only.
        val a = fixes.last()
        if (a.bearing.isNaN() || a.speedKmh.isNaN()) return doubleArrayOf(a.lat, a.lon)
        val dtS = min(tMs - a.tMs, MAX_EXTRAPOLATE_MS) / 1000.0
        return Geo.move(a.lat, a.lon, a.bearing, a.speedKmh / 3.6 * dtS)
    }

    /** GPS speed nearest in time (within 3 s), km/h; NaN if unknown. */
    fun speedAt(fixes: List<TraceSample.Gps>, tMs: Long): Double {
        val f = fixes.minByOrNull { abs(it.tMs - tMs) } ?: return Double.NaN
        return if (abs(f.tMs - tMs) <= 3000) f.speedKmh else Double.NaN
    }

    /** Distance gate for a label at [speedKmh] tapped [dtMs] from the detection's jolt: 15 m + speed × |dt| (dt ≤ 2 s). */
    fun gateM(speedKmh: Double, dtMs: Long): Double =
        MATCH_M + (if (speedKmh.isNaN()) 0.0 else speedKmh / 3.6) * min(abs(dtMs) / 1000.0, MAX_GAP_S)

    /**
     * One-to-one matching, closest in time first; hazard labels are paired before `rough` ones, so a rough tap
     * can't take a detection away from a hazard. [speedsKmh] = each label's speed (NaN or missing: 15 m only).
     * Returns label index → detection index.
     */
    fun match(labels: List<Label>, dets: List<Detection>, speedsKmh: List<Double> = emptyList()): Map<Int, Int> {
        val pairs = ArrayList<Triple<Long, Int, Int>>()
        for ((i, l) in labels.withIndex()) for ((j, d) in dets.withIndex()) {
            val dt = abs(d.tMs - l.tMs)
            if (dt > MATCH_MS) continue
            // The detection is placed at the jolt but logged when the engine decides, DECIDE_MS later; the car moved
            // on from the jolt for as long as the tap came after it. A label without GPS is matched on time alone.
            val gate = gateM(speedsKmh.getOrElse(i) { Double.NaN }, l.tMs - (d.tMs - DECIDE_MS))
            if (l.lat.isNaN() || Geo.distance(l.lat, l.lon, d.lat, d.lon) <= gate) pairs.add(Triple(dt, i, j))
        }
        val out = HashMap<Int, Int>()
        val used = HashSet<Int>()
        val order = pairs.sortedWith(compareBy<Triple<Long, Int, Int>>({ !isHazard(labels[it.second].kind) }, { it.first }))
        for ((_, i, j) in order) {
            if (i !in out && j !in used) { out[i] = j; used.add(j) }
        }
        return out
    }

    /** One recording on its own, with the labels as given. */
    fun compute(labels: List<Label>, result: ReplayResult, samples: List<TraceSample>): MetricsReport =
        compute(listOf(Run("run", labels, result, samples)))

    /**
     * Several runs of one route, replayed in order on one shared map. Labels and detections are matched within each
     * run; a beep is checked against the labels of every run (a spot labelled on any run is real); spots group the
     * hazard labels of all runs in replay order, so pass 1 and pass 2 are normally runs 1 and 2.
     */
    fun compute(runs: List<Run>): MetricsReport {
        var labelsN = 0; var matchedLabels = 0; var labelsFast = 0; var matchedFast = 0
        var detN = 0; var matchedDet = 0; var beepsN = 0; var falseN = 0; var distM = 0.0
        val placed = runs.flatMap { r -> r.labels.filter { !it.lat.isNaN() } }
        val passes = ArrayList<Pair<Label, Boolean>>()   // every hazard label, in replay order: (label, matched)
        val perRun = ArrayList<RunCounts>()

        for (run in runs) {
            val labels = run.labels
            val fixes = run.samples.filterIsInstance<TraceSample.Gps>()
            val speeds = labels.map { speedAt(fixes, it.tMs) }
            val dets = detections(run.result.events)
            val matches = match(labels, dets, speeds)
            val hazards = labels.indices.filter { isHazard(labels[it].kind) }
            val fast = hazards.filter { speeds[it] >= FAST_KMH }

            for (i in hazards.sortedBy { labels[it].tMs }) passes.add(Pair(labels[i], i in matches))

            // A beep is false when no label lies within WARN_M of the spot it warned about: distanceM ahead of the
            // car along its heading, or the car's own position when either is missing.
            val beeps = run.result.events.filter { it.type == "beep" }
            val falseHere = beeps.count { e ->
                val spot = if (e.heading.isNaN() || e.distanceM.isNaN()) doubleArrayOf(e.lat, e.lon)
                    else Geo.move(e.lat, e.lon, e.heading, e.distanceM)
                placed.none { Geo.distance(it.lat, it.lon, spot[0], spot[1]) <= WARN_M }
            }

            val hm = hazards.count { it in matches }
            labelsN += hazards.size; matchedLabels += hm
            labelsFast += fast.size; matchedFast += fast.count { it in matches }
            detN += dets.size; matchedDet += matches.size
            beepsN += beeps.size; falseN += falseHere
            distM += run.result.trip.distanceM
            perRun.add(RunCounts(run.name, hazards.size, hm, dets.size, matches.size, beeps.size, falseHere, run.result.trip.distanceM / 1000.0))
        }

        // Group hazard labels into spots (within MATCH_M of the spot's first label); each label is one pass.
        val spots = ArrayList<MutableList<Pair<Label, Boolean>>>()
        for (p in passes) {
            val l = p.first
            if (l.lat.isNaN()) continue
            val s = spots.firstOrNull { val f = it[0].first; Geo.distance(f.lat, f.lon, l.lat, l.lon) <= MATCH_M }
            if (s != null) s.add(p) else spots.add(mutableListOf(p))
        }
        val repeated = spots.filter { it.size >= 2 }

        return MetricsReport(
            labelsN, matchedLabels, labelsFast, matchedFast, detN, matchedDet, beepsN, falseN, distM / 1000.0,
            repeated.size, repeated.count { s -> s.take(2).any { it.second } },
            if (runs.size > 1) perRun else emptyList(),
        )
    }
}
