package app.bumpbeeper

import android.content.Context
import android.content.SharedPreferences

/** User settings, stored on the phone. */
object Prefs {
    const val SENSITIVITY = "sensitivity"   // 0 = low, 1 = normal, 2 = high
    const val LOUD = "loud"
    const val CLICK_ON_NEW = "click_on_new"

    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun sensitivity(ctx: Context): Int = sp(ctx).getInt(SENSITIVITY, 1)

    /** Jolt threshold in m/s² for the chosen sensitivity. Higher sensitivity = lower threshold. */
    fun threshold(ctx: Context): Double = thresholdFor(sensitivity(ctx))

    fun thresholdFor(level: Int): Double = when (level) {
        0 -> 4.0
        2 -> 2.2
        else -> 3.0
    }

    fun loud(ctx: Context): Boolean = sp(ctx).getBoolean(LOUD, false)
    fun clickOnNew(ctx: Context): Boolean = sp(ctx).getBoolean(CLICK_ON_NEW, false)
}
