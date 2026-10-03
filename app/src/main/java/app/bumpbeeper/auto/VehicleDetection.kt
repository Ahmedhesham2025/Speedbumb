package app.bumpbeeper.auto

import android.content.Context

/**
 * An edition's in-vehicle detection, implemented by `VehicleActivity` in src/foss (none) and src/play (Google
 * activity recognition, IN_VEHICLE enter/exit). [AutoDetect] falls back to [DriveWatcher] when it isn't available.
 */
interface VehicleDetection {
    /** A permission that would make it usable, or null. */
    fun missingPermission(ctx: Context): String?
    /** Ask for enter/exit events. [onResult] (main thread) gets true only once the request has succeeded. */
    fun start(ctx: Context, onResult: (Boolean) -> Unit)
    /** The last request succeeded and nothing stopped it since. */
    fun active(ctx: Context): Boolean
    fun stop(ctx: Context)
}
