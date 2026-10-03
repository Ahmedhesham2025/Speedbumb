package app.bumpbeeper.sync

import app.bumpbeeper.Fix
import app.bumpbeeper.Geo
import app.bumpbeeper.TripPrivacy

/**
 * When to look up the live road speed limit, which fixes to send, and the cached answer (pure logic, engine thread;
 * one per trip). Times are the fixes' clock (`elapsedRealtime`); [wallMs] arguments are epoch ms, for the UTC day.
 *
 * A lookup is due about every [EVERY_M] driven, sooner on a road change (heading off by more than [TURN_DEG] for
 * [TURN_MS]) or when the answer is older than [REFRESH_MS]. Never within the trip's first [TripPrivacy.RADIUS_M]
 * driven or that close to where it started, never under [MIN_KMH], at most one per [MIN_GAP_MS], and only while
 * [takeCall] grants one (the shared daily count, [SpeedLimitQuota]). A 429 stops lookups until the next UTC day;
 * anything else that fails waits [BACKOFF_MS]. The answer is kept in memory for the current road and at most [MAX_CACHE_MS] (TomTom T&C 11.4).
 */
class LiveLimitPlanner(
    /** Counts one call for the UTC day of the epoch ms given; false = none left for live lookups today. */
    private val takeCall: (wallMs: Long) -> Boolean,
) {
    companion object {
        const val EVERY_M = 1_000.0
        const val MIN_KMH = 10.0
        const val MIN_GAP_MS = 30_000L
        const val REFRESH_MS = 2 * 60_000L
        const val MAX_CACHE_MS = 5 * 60_000L
        const val TURN_DEG = 45.0
        const val TURN_MS = 5_000L
        const val BACKOFF_MS = 5 * 60_000L
        /** Points sent: [MIN_POINTS]..[MAX_POINTS] good fixes, [STEP_M] apart, over the last [SPAN_M]. */
        const val MIN_POINTS = 5
        const val MAX_POINTS = 15
        const val STEP_M = 20.0
        const val SPAN_M = 300.0
        const val DAY_MS = 24 * 60 * 60 * 1000L
        /** A failed call (no network, timeout, sign-in failed). */
        const val OFFLINE = -1
    }

    /** UTC day of the last 429. */
    var blockedDay = -1L
        private set
    var inFlight = false
        private set

    private class Good(val fix: Fix, val drivenM: Double)
    private val good = ArrayDeque<Good>()
    private val start = ArrayList<Fix>()   // first fix and first good fix: the start of the privacy zone
    private var last: Fix? = null
    private var drivenM = 0.0
    private var lastCallAt = -1L
    private var drivenAtCall = 0.0
    private var backoffUntil = -1L
    private var heading = Double.NaN
    private var refHeading = Double.NaN
    private var turnSince = -1L
    private var roadChanged = false
    private var road = 0          // counts road changes, so an answer for the road before is dropped
    private var callRoad = 0
    private var limitKmh: Int? = null
    private var limitAt = -1L

    fun onFix(f: Fix) {
        val p = last
        if (p != null && f.timeMs <= p.timeMs) return
        last = f
        if (p != null) {
            // Distance driven from speed × time where known, so a GPS jump doesn't count (as TripPrivacy.driven).
            val v = listOf(p.speedMps, f.speedMps).filter { !it.isNaN() }
            drivenM += if (v.isEmpty()) Geo.distance(p.lat, p.lon, f.lat, f.lon) else v.average() * (f.timeMs - p.timeMs) / 1000.0
        }
        if (start.isEmpty() || (start.size == 1 && start[0].accuracyM > TripPrivacy.GOOD_ACCURACY_M && f.accuracyM <= TripPrivacy.GOOD_ACCURACY_M)) start.add(f)
        if (f.accuracyM > TripPrivacy.GOOD_ACCURACY_M) return
        val g = good.lastOrNull()
        good.addLast(Good(f, drivenM))
        while (good.size > 2 && drivenM - good[1].drivenM > SPAN_M + 100) good.removeFirst()
        // Heading: GPS bearing, else the direction from the previous good fix; only when moving.
        if (f.speedMps * 3.6 < MIN_KMH) return
        heading = when {
            !f.bearingDeg.isNaN() -> f.bearingDeg
            g != null && Geo.distance(g.fix.lat, g.fix.lon, f.lat, f.lon) >= 10 -> Geo.bearing(g.fix.lat, g.fix.lon, f.lat, f.lon)
            else -> heading
        }
        if (heading.isNaN()) return
        if (refHeading.isNaN()) refHeading = heading
        if (Geo.angleDiff(heading, refHeading) <= TURN_DEG) { turnSince = -1; return }
        if (turnSince < 0) turnSince = f.timeMs
        if (f.timeMs - turnSince >= TURN_MS) {
            // A new road: the old answer no longer applies.
            roadChanged = true; road++
            refHeading = heading; turnSince = -1
            limitKmh = null; limitAt = -1
        }
    }

    /** The cached limit, or null when unknown or older than [MAX_CACHE_MS] (then it is dropped). */
    fun limit(nowMs: Long): Int? {
        if (limitAt >= 0 && nowMs - limitAt > MAX_CACHE_MS) { limitKmh = null; limitAt = -1 }
        return limitKmh
    }

    val limitAtMs: Long get() = limitAt

    /** The live limit was switched off: nothing kept. */
    fun forget() { limitKmh = null; limitAt = -1 }

    /** The fixes to send now (time order), or null when no lookup is due or allowed. Counts the call. */
    fun next(nowMs: Long, wallMs: Long): List<Fix>? {
        val f = last ?: return null
        val today = wallMs / DAY_MS
        if (inFlight || blockedDay == today) return null
        if (nowMs < backoffUntil) return null
        if (lastCallAt >= 0 && nowMs - lastCallAt < MIN_GAP_MS) return null
        if (drivenM < TripPrivacy.RADIUS_M || f.speedMps.isNaN() || f.speedMps * 3.6 < MIN_KMH) return null
        val due = lastCallAt < 0 || drivenM - drivenAtCall >= EVERY_M || roadChanged || nowMs - lastCallAt >= REFRESH_MS
        if (!due) return null
        val pts = pick()
        if (pts.size < MIN_POINTS || !takeCall(wallMs)) return null
        inFlight = true
        lastCallAt = nowMs; drivenAtCall = drivenM
        roadChanged = false; callRoad = road
        if (!heading.isNaN()) refHeading = heading
        return pts
    }

    /** Good fixes outside the start zone, newest first [STEP_M] apart, up to [MAX_POINTS] or [SPAN_M]; time order. */
    private fun pick(): List<Fix> {
        val out = ArrayList<Fix>()
        var keptAt = Double.NaN
        for (i in good.indices.reversed()) {
            val g = good[i]
            if (g.drivenM <= TripPrivacy.RADIUS_M) break
            if (!TripPrivacy.outside(g.fix.lat, g.fix.lon, start.map { doubleArrayOf(it.lat, it.lon) })) continue
            if (out.isNotEmpty() && keptAt - g.drivenM < STEP_M) continue
            out.add(g.fix); keptAt = g.drivenM
            if (out.size >= MAX_POINTS || drivenM - g.drivenM >= SPAN_M) break
        }
        return out.reversed()
    }

    /** The answer to [next]'s call: [code] is the HTTP status or [OFFLINE]; [kmh] the last point's limit on 200. */
    fun onResult(nowMs: Long, wallMs: Long, code: Int, kmh: Int?) {
        inFlight = false
        when (code) {
            200 -> if (callRoad == road) { limitKmh = kmh; limitAt = nowMs }
            429 -> blockedDay = wallMs / DAY_MS
            else -> backoffUntil = nowMs + BACKOFF_MS
        }
    }
}
