package app.bumpbeeper.ui

import android.content.Context
import app.bumpbeeper.Prefs

/**
 * The small street map on the Drive screen (v1.8): the rules, kept apart from the view so they are tested.
 * The map is only a picture: warnings come from the recording service and never depend on it.
 */
object DriveMap {
    /** Settings → "Show map while driving" (on by default). */
    const val KEY = "ui_drive_map"

    fun enabled(ctx: Context): Boolean = Prefs.sp(ctx).getBoolean(KEY, true)
    fun setEnabled(ctx: Context, on: Boolean) { Prefs.sp(ctx).edit().putBoolean(KEY, on).apply() }

    /** Shown while recording with the setting on (the Drive tab open and the screen on is up to the page). */
    fun shown(recording: Boolean, enabled: Boolean): Boolean = recording && enabled

    /** Closer in town, further out on fast roads: 17 when crawling, 16 at 40 km/h, 14.5 from 100 km/h. */
    fun zoomFor(kmh: Double): Double = if (kmh.isNaN()) 16.0 else (17.0 - kmh / 40.0).coerceIn(14.5, 17.0)

    /** Stopped (or no speed yet): few frames, nothing moves. Driving: smooth enough to follow the road. */
    fun fpsFor(kmh: Double): Int = if (kmh.isNaN() || kmh < STOPPED_KMH) 5 else 30

    /** The heading turns the map only when moving; standing still, GPS bearings are noise. */
    fun headingUsable(kmh: Double, hasBearing: Boolean): Boolean = hasBearing && !kmh.isNaN() && kmh >= STOPPED_KMH

    /** Spots are loaded around the car in this radius, and again after it moved [RELOAD_M]. */
    const val SPOTS_RADIUS_M = 3_000.0
    const val RELOAD_M = 1_000.0
    const val STOPPED_KMH = 5.0
}
