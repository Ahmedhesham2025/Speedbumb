package app.bumpbeeper.auto

import android.content.Context
import app.bumpbeeper.R

/**
 * Why each automatic start happened, or didn't (Diagnostics): the last [MAX] entries, kept across restarts.
 * [Entries] is pure Kotlin; [add] / [lines] keep it in SharedPreferences.
 */
object StartLog {
    const val MAX = 12

    enum class Reason(val res: Int) {
        MOTION_CHECK(R.string.start_motion_check),
        MOTION_WALKING(R.string.start_motion_walking),
        WAITING_FOR_SPEED(R.string.start_waiting_speed),
        SKIP_NO_FIX(R.string.start_skip_no_fix),
        SKIP_SLOW(R.string.start_skip_slow),
        SKIP_VEHICLE_SLOW(R.string.start_skip_vehicle_slow),
        SKIP_SNOOZED(R.string.start_skip_snoozed),
        STARTED_MOTION(R.string.start_by_motion),
        STARTED_GOOGLE(R.string.start_by_google),
        STARTED_CAR(R.string.start_by_car),
        CONFIRMED(R.string.start_confirmed),
        DISCARDED(R.string.start_discarded),
    }

    /** Newest last. The same reason twice in a row is kept once (motion fires again and again while walking). */
    class Entries(val list: List<Pair<Long, Reason>> = emptyList()) {
        fun plus(atMs: Long, r: Reason): Entries =
            if (list.lastOrNull()?.second == r) this else Entries((list + (atMs to r)).takeLast(MAX))

        fun encode(): String = list.joinToString(";") { "${it.first}:${it.second.name}" }

        companion object {
            fun decode(s: String?): Entries = Entries(s.orEmpty().split(';').mapNotNull { e ->
                val i = e.indexOf(':')
                val at = e.take(maxOf(i, 0)).toLongOrNull() ?: return@mapNotNull null
                val r = Reason.values().firstOrNull { it.name == e.substring(i + 1) } ?: return@mapNotNull null
                at to r
            })
        }
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("start_log", Context.MODE_PRIVATE)

    fun read(ctx: Context): Entries = Entries.decode(sp(ctx).getString("e", null))

    fun add(ctx: Context, r: Reason, atMs: Long = System.currentTimeMillis()) = synchronized(this) {
        sp(ctx).edit().putString("e", read(ctx).plus(atMs, r).encode()).apply()
    }

    /** Newest first, "14:05 Started by motion". */
    fun lines(ctx: Context): List<String> {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        return read(ctx).list.asReversed().map { (at, r) -> "${f.format(java.util.Date(at))} ${ctx.getString(r.res)}" }
    }
}
