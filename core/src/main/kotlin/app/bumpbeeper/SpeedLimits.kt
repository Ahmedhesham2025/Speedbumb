package app.bumpbeeper

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The privacy zone shared by everything that leaves the phone: nothing within [RADIUS_M] of where a trip
 * started or ended (home, work). The shared-map upload uses the same rule (the app's `sync.PrivacyZone`).
 * A route also loses its first and last [RADIUS_M] driven ([driven]), so a GPS jump at the end can't expose
 * the approach to home.
 */
object TripPrivacy {
    const val RADIUS_M = 300.0
    /** A first fix is often far off; the first (and last) fix this good is a second anchor for the zone. */
    const val GOOD_ACCURACY_M = 30.0

    /** Where the trip started and ended, by time: first and last fix, plus first and last within [GOOD_ACCURACY_M]. */
    fun anchors(fixes: List<Fix>): List<DoubleArray> {
        val all = RouteSampler.inTimeOrder(fixes)
        val good = all.filter { it.accuracyM <= GOOD_ACCURACY_M }
        return listOfNotNull(all.firstOrNull(), good.firstOrNull(), all.lastOrNull(), good.lastOrNull())
            .map { doubleArrayOf(it.lat, it.lon) }
    }

    /** True if the point is farther than [radiusM] from every anchor. No anchors → false (keep nothing). */
    fun outside(lat: Double, lon: Double, anchors: List<DoubleArray>, radiusM: Double = RADIUS_M): Boolean =
        anchors.isNotEmpty() && anchors.all { Geo.distance(lat, lon, it[0], it[1]) > radiusM }

    /**
     * Metres driven up to each fix of [ordered] (time order), from speed × time where a speed is known, so a
     * position jump doesn't count as distance. Straight-line distance only when neither fix has a speed.
     */
    fun driven(ordered: List<Fix>): DoubleArray {
        val out = DoubleArray(ordered.size)
        for (i in 1 until ordered.size) {
            val a = ordered[i - 1]; val b = ordered[i]
            val v = listOf(a.speedMps, b.speedMps).filter { !it.isNaN() }
            out[i] = out[i - 1] +
                if (v.isEmpty()) Geo.distance(a.lat, a.lon, b.lat, b.lon) else v.average() * (b.timeMs - a.timeMs) / 1000.0
        }
        return out
    }
}

/**
 * One point sent for a speed-limit lookup. [timeMs] is the fix time (same clock as [Fix.timeMs], used to match
 * limits back to fixes); [epochMs] is the same moment in epoch milliseconds, the `t` sent to the server.
 */
data class RoutePoint(val lat: Double, val lon: Double, val timeMs: Long, val epochMs: Long)

/**
 * Picks the points of a finished trip to send for a speed-limit lookup (one limit comes back per point).
 *
 * Puts the fixes in time order ([inTimeOrder]), drops fixes worse than [MAX_ACCURACY_M] and everything in the
 * [TripPrivacy] zone, then keeps about one point per [SPACING_M] of travel or per [SPACING_MS], whichever comes
 * first. [chunks] splits them into requests of 2..[MAX_POINTS] points and at most [MAX_CHUNK_M] of route, and
 * also wherever two points are more than [MAX_GAP_M] apart (the server refuses gaps over 6 km, e.g. a tunnel).
 * Send each chunk as one request, then pass the chunks and replies to [SpeedLimitScoring.evaluate].
 */
object RouteSampler {
    const val SPACING_M = 50.0
    const val SPACING_MS = 5_000L
    const val MAX_POINTS = 5_000
    /** TomTom allows 100 km of road per request; the straight-line sum of our points can be shorter than the road. */
    const val MAX_CHUNK_M = 80_000.0
    const val MAX_ACCURACY_M = 30.0
    const val MAX_GAP_M = 5_000.0

    /**
     * The fixes in time order. A fix older than the one kept before it is dropped, and so is one newer than both
     * fixes after it (a bad clock), so one wrong time costs that fix and never the rest of the trip.
     */
    fun inTimeOrder(fixes: List<Fix>): List<Fix> {
        val out = ArrayList<Fix>(fixes.size)
        for (i in fixes.indices) {
            val f = fixes[i]
            if (out.isNotEmpty() && f.timeMs < out.last().timeMs) continue
            if (i + 2 < fixes.size && f.timeMs > max(fixes[i + 1].timeMs, fixes[i + 2].timeMs)) continue
            out.add(f)
        }
        return out
    }

    /**
     * [Fix.timeMs] is the since-boot clock (`SystemClock.elapsedRealtime()`), but the server wants epoch time.
     * The app reads `epochOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()` once (at trip end)
     * and passes it here; each point gets `epochMs = timeMs + epochOffsetMs`. Required, so it can't be forgotten.
     */
    fun sample(fixes: List<Fix>, epochOffsetMs: Long): List<RoutePoint> {
        val ordered = inTimeOrder(fixes)
        val anchors = TripPrivacy.anchors(ordered)
        val driven = TripPrivacy.driven(ordered)
        val total = driven.lastOrNull() ?: 0.0
        val out = ArrayList<RoutePoint>()
        var prev: Fix? = null
        var travelled = 0.0     // metres along good fixes since the last kept point
        var lastCandidate: Fix? = null
        for ((i, f) in ordered.withIndex()) {
            if (f.accuracyM > MAX_ACCURACY_M || !TripPrivacy.outside(f.lat, f.lon, anchors)) continue
            if (driven[i] <= TripPrivacy.RADIUS_M || total - driven[i] <= TripPrivacy.RADIUS_M) continue
            prev?.let { travelled += Geo.distance(it.lat, it.lon, f.lat, f.lon) }
            prev = f
            val last = out.lastOrNull()
            if (last == null || travelled >= SPACING_M || f.timeMs - last.timeMs >= SPACING_MS) {
                out.add(RoutePoint(f.lat, f.lon, f.timeMs, f.timeMs + epochOffsetMs))
                travelled = 0.0
                lastCandidate = null
            } else lastCandidate = f
        }
        // Keep the end of the route too, so the last stretch gets a limit.
        lastCandidate?.let { out.add(RoutePoint(it.lat, it.lon, it.timeMs, it.timeMs + epochOffsetMs)) }
        return out
    }

    /**
     * Consecutive pieces of [points], each with 2..[maxPoints] points, ≤ [maxM] metres between its own points and
     * no step over [maxGapM] (a longer gap starts a new piece and is in neither). A piece left with one point joins
     * the piece before it if that stays within the limits; else it takes that piece's last point (if it has 3 or
     * more and the step fits); else it is dropped, because the server needs at least 2 points.
     */
    fun chunks(
        points: List<RoutePoint>, maxPoints: Int = MAX_POINTS, maxM: Double = MAX_CHUNK_M, maxGapM: Double = MAX_GAP_M,
    ): List<List<RoutePoint>> {
        val out = ArrayList<List<RoutePoint>>()
        var cur = ArrayList<RoutePoint>()
        var len = 0.0
        for (p in points) {
            val step = cur.lastOrNull()?.let { dist(it, p) } ?: 0.0
            if (cur.isNotEmpty() && (cur.size >= maxPoints || len + step > maxM || step > maxGapM)) {
                out.add(cur); cur = ArrayList(); len = 0.0
            } else len += step
            cur.add(p)
        }
        if (cur.isNotEmpty()) out.add(cur)
        val fixed = ArrayList<List<RoutePoint>>()
        for (c in out) {
            if (c.size >= 2) { fixed.add(c); continue }
            val before = fixed.lastOrNull() ?: continue
            val step = dist(before.last(), c[0])
            if (step > maxGapM || step > maxM) continue
            if (before.size < maxPoints && length(before) + step <= maxM) fixed[fixed.size - 1] = before + c[0]
            else if (before.size >= 3) { fixed[fixed.size - 1] = before.dropLast(1); fixed.add(listOf(before.last(), c[0])) }
        }
        return fixed
    }

    /** Straight-line metres between consecutive points. */
    fun length(points: List<RoutePoint>): Double = (1 until points.size).sumOf { dist(points[it - 1], points[it]) }
    private fun dist(a: RoutePoint, b: RoutePoint) = Geo.distance(a.lat, a.lon, b.lat, b.lon)
    /** [sample] then [chunks]: the requests to send for one trip. */
    fun plan(fixes: List<Fix>, epochOffsetMs: Long): List<List<RoutePoint>> = chunks(sample(fixes, epochOffsetMs))
}

/** Sampled points and their limits, one each (null = unknown). */
class RouteLimits(val points: List<RoutePoint>, val limitsKmh: List<Double?>)

/** Speeding measured against the road's real limits, for one trip. Bands are nested (over +20 is also over +10). */
class SpeedLimitResult(
    /** Distance driven at ≥ [SpeedLimitScoring.MIN_KMH], and the part of it with a known limit. */
    val distanceM: Double, val knownDistanceM: Double,
    /** Seconds driven at ≥ [SpeedLimitScoring.MIN_KMH] with a known limit. */
    val knownS: Double,
    val over10S: Double, val over20S: Double, val over30S: Double,
    val over10M: Double, val over20M: Double, val over30M: Double,
    /** Highest excess over the limit held for at least [SpeedLimitScoring.MIN_RUN_S], km/h (0 if none). */
    val maxExcessKmh: Double,
) {
    /** 0..1: share of the trip's distance with a known limit. */
    val knownShare: Double get() = if (distanceM > 0) min(1.0, knownDistanceM / distanceM) else 0.0
}

/**
 * Scores speeding against per-point road limits (from the speed-limit lookup of [RouteSampler] points).
 *
 * Each fix takes the limit of the sampled point nearest to it in time, if that point is within [MATCH_MS] or
 * [MATCH_M]; otherwise its limit is unknown. Fixes under [MIN_KMH] or without a speed are ignored. Time over a band
 * counts once the stretch has lasted [MIN_RUN_S], measured from the fix before its first fix to its last fix, with
 * [RUN_EPS_S] of slack so three fixes 999 ms apart count; shorter GPS spikes don't. A stretch is broken by a fix
 * under the band, an unknown limit, or a gap over [MAX_DT_S] (as in [DrivingMonitor]; a phone that sends fixes
 * less often falls back to the old score). A fix with the same or an older time than the one before is skipped.
 */
object SpeedLimitScoring {
    const val ATTRIBUTION = "© TomTom"
    const val MIN_KMH = 10.0
    const val MIN_RUN_S = 3.0
    const val RUN_EPS_S = 0.05
    const val MAX_DT_S = 3.0
    const val MATCH_MS = 10_000L
    const val MATCH_M = 100.0
    /** A limit replaces the fixed threshold only when it is known for at least this share of the distance. */
    const val MIN_KNOWN_SHARE = 0.5
    val BANDS_KMH = doubleArrayOf(10.0, 20.0, 30.0)
    /** Penalty points per minute per hour over each band; nested, so over +30 costs 1 + 1 + 2 = 4. */
    private val BAND_WEIGHTS = doubleArrayOf(1.0, 1.0, 2.0)
    /** Same cap as the fixed-threshold speed penalty in [DrivingStats]. */
    const val MAX_PENALTY = 40.0

    /**
     * Lines the replies up with the [RouteSampler.plan] chunks: [replies] has one entry per chunk, null for a
     * request that failed or was skipped (400, 429, 503 …). A missing reply, or one whose length doesn't match its
     * chunk, leaves that chunk's limits unknown; a limit that isn't a positive number is unknown too.
     */
    fun align(chunks: List<List<RoutePoint>>, replies: List<List<Double?>?>): RouteLimits {
        val limits = chunks.flatMapIndexed { i, c ->
            val reply = replies.getOrNull(i)?.takeIf { it.size == c.size }
            c.indices.map { j -> reply?.get(j)?.takeIf { it.isFinite() && it > 0 } }
        }
        return RouteLimits(chunks.flatten(), limits)
    }

    /** [evaluate] from the planned chunks and one reply per chunk (see [align]). */
    fun evaluate(fixes: List<Fix>, chunks: List<List<RoutePoint>>, replies: List<List<Double?>?>): SpeedLimitResult =
        align(chunks, replies).let { evaluate(fixes, it.points, it.limitsKmh) }

    /**
     * [limitsKmh] holds one entry per [points] entry (null = unknown). If the lists differ in length, no limit is
     * trusted (all unknown) rather than shifting limits onto the wrong points.
     */
    fun evaluate(fixes: List<Fix>, points: List<RoutePoint>, limitsKmh: List<Double?>): SpeedLimitResult {
        val aligned = points.size == limitsKmh.size
        val order = points.indices.sortedBy { points[it].timeMs }
        val pts = order.map { points[it] }
        val lims = order.map { i -> if (aligned) limitsKmh[i]?.takeIf { it.isFinite() && it > 0 } else null }
        var dist = 0.0; var knownDist = 0.0; var knownS = 0.0
        val overS = DoubleArray(3); val overM = DoubleArray(3)
        val runS = DoubleArray(3); val runM = DoubleArray(3)
        var maxExcess = 0.0
        val recent = ArrayDeque<Pair<Long, Double>>()   // (time, excess) of the current stretch above the limit
        var aboveSinceMs = -1L
        val runMs = ((MIN_RUN_S - RUN_EPS_S) * 1000).toLong()
        fun endRun(b: Int) {
            if (runS[b] >= MIN_RUN_S - RUN_EPS_S) { overS[b] += runS[b]; overM[b] += runM[b] }
            runS[b] = 0.0; runM[b] = 0.0
        }
        var prev: Fix? = null
        for (f in RouteSampler.inTimeOrder(fixes)) {
            val p = prev
            if (p != null && f.timeMs <= p.timeMs) continue
            prev = f
            if (p == null) continue
            val dt = (f.timeMs - p.timeMs) / 1000.0
            val kmh = (f.speedMps.takeIf { !it.isNaN() } ?: 0.0) * 3.6
            val moving = dt <= MAX_DT_S && kmh >= MIN_KMH
            if (moving) dist += kmh / 3.6 * dt
            val limit = if (moving) limitAt(f, pts, lims) else null
            if (limit == null) {
                for (b in 0..2) endRun(b)
                recent.clear(); aboveSinceMs = -1
                continue
            }
            val m = kmh / 3.6 * dt
            knownDist += m; knownS += dt
            val excess = kmh - limit
            for (b in 0..2) if (excess > BANDS_KMH[b]) { runS[b] += dt; runM[b] += m } else endRun(b)
            // Max excess: the lowest excess within the last MIN_RUN_S of a stretch that has lasted that long.
            if (excess > 0) {
                if (aboveSinceMs < 0) aboveSinceMs = p.timeMs
                recent.addLast(f.timeMs to excess)
                // A fix's excess holds from the fix before it, so the window is the fixes after (now − run).
                while (recent.first().first <= f.timeMs - runMs) recent.removeFirst()
                if (f.timeMs - aboveSinceMs >= runMs) maxExcess = max(maxExcess, recent.minOf { it.second })
            } else { recent.clear(); aboveSinceMs = -1 }
        }
        for (b in 0..2) endRun(b)
        return SpeedLimitResult(dist, knownDist, knownS, overS[0], overS[1], overS[2], overM[0], overM[1], overM[2], maxExcess)
    }

    /** The limit for one fix: the point of [points] (time order) nearest in time, if close enough in time or space. */
    fun limitAt(f: Fix, points: List<RoutePoint>, limitsKmh: List<Double?>): Double? {
        if (points.isEmpty() || points.size != limitsKmh.size) return null
        var lo = 0; var hi = points.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (points[mid].timeMs < f.timeMs) lo = mid + 1 else hi = mid }
        val i = if (lo > 0 && abs(points[lo - 1].timeMs - f.timeMs) <= abs(points[lo].timeMs - f.timeMs)) lo - 1 else lo
        val pt = points[i]
        val near = abs(pt.timeMs - f.timeMs) <= MATCH_MS || Geo.distance(f.lat, f.lon, pt.lat, pt.lon) <= MATCH_M
        return if (near) limitsKmh[i] else null
    }

    /**
     * Speed penalty, 0..[MAX_PENALTY] (the same scale as the fixed-threshold one):
     *
     *     penalty = min(40, 1·m10 + 1·m20 + 2·m30)
     *
     * where mN = minutes over limit +N km/h per hour of driving with a known limit (= 60 × share of that time).
     * So 10 % of the time at +15 costs 6 points, at +25 costs 12, at +35 costs 24; the old method gives 7.5 for
     * 10 % at an average excess of 15 km/h. Sustained speeding over +10 for 2/3 of the trip reaches the cap.
     */
    fun penalty(r: SpeedLimitResult): Double = penalty(r.knownS, r.over10S, r.over20S, r.over30S)

    /** [penalty] from the stored numbers: seconds with a known limit, and seconds over +10 / +20 / +30. */
    fun penalty(knownS: Double, over10S: Double, over20S: Double, over30S: Double): Double {
        if (knownS <= 0) return 0.0
        val perHour = 3600.0 / knownS
        val m = doubleArrayOf(over10S, over20S, over30S).map { it / 60.0 * perHour }
        return min(MAX_PENALTY, m.indices.sumOf { BAND_WEIGHTS[it] * m[it] }).coerceAtLeast(0.0)
    }

    /** The speed sub-score on its own, 0–100 (100 = never more than 10 km/h over the limit). */
    fun subScore(r: SpeedLimitResult): Int = Math.round(100.0 * (1.0 - penalty(r) / MAX_PENALTY)).toInt().coerceIn(0, 100)
}
