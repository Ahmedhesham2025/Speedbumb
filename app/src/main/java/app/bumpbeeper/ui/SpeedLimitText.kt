package app.bumpbeeper.ui

import android.content.Context
import app.bumpbeeper.DrivingStats
import app.bumpbeeper.R
import app.bumpbeeper.SpeedLimitScoring
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Words and sums for real road speed limits (TomTom, looked up after a trip by `sync.SpeedLimitSync`):
 * the Settings status line, the per-trip coverage line, the over-limit bands, and the combined "Your driving" stats.
 */
object SpeedLimitText {

    /**
     * The trips added up into one set of numbers for the summary card. Known distance is summed in metres, so the
     * combined share is the share of *all* the trips' distance with a known limit (trips never looked up count as
     * unknown). [DrivingStats.usesSpeedLimits] then decides for the summary the same way it does for one trip.
     */
    fun combine(trips: List<DrivingStats>): DrivingStats {
        val sum = DrivingStats()
        var knownM = 0.0
        var anyLookedUp = false
        for (d in trips) {
            sum.movingS += d.movingS; sum.distanceM += d.distanceM; sum.speedingS += d.speedingS
            sum.speedingExcess += d.speedingExcess; sum.maxSpeedKmh = maxOf(sum.maxSpeedKmh, d.maxSpeedKmh)
            sum.harshBrakes += d.harshBrakes; sum.harshAccels += d.harshAccels; sum.harshCorners += d.harshCorners
            sum.swerves += d.swerves; sum.bumpsFast += d.bumpsFast; sum.phoneUse += d.phoneUse
            if (d.limitKnownShare >= 0) {
                anyLookedUp = true
                knownM += d.limitKnownShare * d.distanceM
                sum.limitKnownS += d.limitKnownS
                sum.overLimit10S += d.overLimit10S; sum.overLimit20S += d.overLimit20S; sum.overLimit30S += d.overLimit30S
                sum.maxOverLimitKmh = maxOf(sum.maxOverLimitKmh, d.maxOverLimitKmh)
            }
        }
        sum.limitKnownShare = when {
            !anyLookedUp -> -1.0
            sum.distanceM > 0 -> (knownM / sum.distanceM).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        return sum
    }

    /** Whole percent, rounded down so "under half" never shows as 50 % (the epsilon keeps 0.57 at 57, not 56). */
    fun percent(share: Double): String = String.format(Locale.US, "%d", floor(share.coerceIn(0.0, 1.0) * 100 + 1e-9).toInt())

    /** Seconds as m:ss ("0:00", "2:05", "75:00"). */
    fun minSec(seconds: Double): String {
        val s = if (seconds.isNaN() || seconds < 0) 0L else seconds.roundToLong()
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /**
     * The line under a trip: limits used (with the © TomTom notice), looked up but too little known, still waiting
     * ([waiting]: its route is queued on the phone), or null when limits play no part.
     */
    fun tripLine(ctx: Context, d: DrivingStats, waiting: Boolean): String? = when {
        d.usesSpeedLimits -> ctx.getString(R.string.limits_trip_known, percent(d.limitKnownShare), SpeedLimitScoring.ATTRIBUTION)
        d.limitKnownShare >= 0 -> ctx.getString(R.string.limits_trip_too_little, percent(d.limitKnownShare), SpeedLimitScoring.ATTRIBUTION)
        waiting -> ctx.getString(R.string.limits_trip_waiting)
        else -> null
    }

    /** The summary card's line (null when the summary uses the fixed speed rule). */
    fun summaryLine(ctx: Context, sum: DrivingStats): String? =
        if (sum.usesSpeedLimits) ctx.getString(R.string.limits_summary, percent(sum.limitKnownShare), SpeedLimitScoring.ATTRIBUTION)
        else null

    /** "+10 / +20 / +30" band times, e.g. "2:05 / 0:40 / 0:00". */
    fun bands(d: DrivingStats): String = "${minSec(d.overLimit10S)} / ${minSec(d.overLimit20S)} / ${minSec(d.overLimit30S)}"

    /** "+12 km/h" (rounded). */
    fun maxOver(ctx: Context, d: DrivingStats): String =
        ctx.getString(R.string.limits_max_over_value, String.format(Locale.US, "%.0f", d.maxOverLimitKmh))

    /**
     * Settings: why the switch is on but nothing is looked up ([allowed] is `SpeedLimitSync.allowed`: today the only
     * reason is the shared map being off, which keeps the app offline), or "" when it works or is off.
     */
    fun settingsStatus(ctx: Context, on: Boolean, allowed: Boolean): String =
        if (on && !allowed) ctx.getString(R.string.limits_status_offline) else ""
}
