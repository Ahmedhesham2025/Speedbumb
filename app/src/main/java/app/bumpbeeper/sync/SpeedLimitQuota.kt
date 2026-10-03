package app.bumpbeeper.sync

import android.content.Context
import app.bumpbeeper.Prefs

/**
 * One local count of speed-limit calls per UTC day, shared by the after-trip lookup ([SpeedLimitSync]) and the live
 * limit ([LiveSpeedLimit]), against the server's per-user quota of [MAX_PER_DAY]. Live calls leave the last
 * [KEEP_FOR_AFTER_TRIP] to the after-trip score. Only counts are stored (settings file), never routes or limits.
 * Any thread.
 */
object SpeedLimitQuota {
    const val MAX_PER_DAY = 60
    const val KEEP_FOR_AFTER_TRIP = 4
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val DAY = "speed_limit_quota_day"
    private const val CALLS = "speed_limit_quota_calls"

    /** Calls counted on the UTC day of [wallMs]. */
    @Synchronized fun used(ctx: Context, wallMs: Long): Int {
        val sp = Prefs.sp(ctx)
        return if (sp.getLong(DAY, -1L) == wallMs / DAY_MS) sp.getInt(CALLS, 0) else 0
    }

    /** Counts one call if fewer than [MAX_PER_DAY] − [reserve] were made today; false = none left. */
    @Synchronized fun take(ctx: Context, wallMs: Long, reserve: Int = 0): Boolean {
        val n = used(ctx, wallMs)
        if (n >= MAX_PER_DAY - reserve) return false
        Prefs.sp(ctx).edit().putLong(DAY, wallMs / DAY_MS).putInt(CALLS, n + 1).apply()
        return true
    }

    /** The server said 429: nothing more today, of either kind. */
    @Synchronized fun exhaust(ctx: Context, wallMs: Long) {
        Prefs.sp(ctx).edit().putLong(DAY, wallMs / DAY_MS).putInt(CALLS, MAX_PER_DAY).apply()
    }
}
