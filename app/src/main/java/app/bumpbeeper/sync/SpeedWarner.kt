package app.bumpbeeper.sync

/**
 * When to warn about speeding against the live road limit (pure logic, engine thread).
 *
 * Warns once the speed has stayed above limit + margin for [HOLD_MS], then at most once per [REPEAT_MS] while it
 * stays the same limit; a different limit may warn again at once (still after [HOLD_MS] over it). A warning that
 * can't play now (muted, or a bump warning is playing: those come first) waits, and is not counted as given.
 */
class SpeedWarner {
    companion object {
        const val HOLD_MS = 3_000L
        const val REPEAT_MS = 60_000L
    }

    /** 0 = within the limit or unknown, 1 = over the limit, 2 = over it by more than the margin. */
    var overLimit = 0
        private set
    private var overSince = -1L
    private var lastWarnAt = -1L
    private var lastWarnLimit: Int? = null

    /** One speed reading. Returns the limit to announce now, or null. [mayPlay] false: muted or busy, wait. */
    fun onSpeed(nowMs: Long, kmh: Double, limitKmh: Int?, marginKmh: Int, mayPlay: Boolean): Int? {
        if (limitKmh == null || kmh.isNaN()) { overLimit = 0; overSince = -1; return null }
        overLimit = when {
            kmh > limitKmh + marginKmh -> 2
            kmh > limitKmh -> 1
            else -> 0
        }
        if (overLimit < 2) { overSince = -1; return null }
        if (overSince < 0) overSince = nowMs
        if (nowMs - overSince < HOLD_MS || !mayPlay) return null
        if (lastWarnAt >= 0 && nowMs - lastWarnAt < REPEAT_MS && limitKmh == lastWarnLimit) return null
        lastWarnAt = nowMs
        lastWarnLimit = limitKmh
        return limitKmh
    }
}
