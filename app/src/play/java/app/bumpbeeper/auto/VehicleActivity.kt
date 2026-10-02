package app.bumpbeeper.auto

import android.content.Context

/** play edition: Google's IN_VEHICLE detection comes in the next PR; until then the built-in [DriveWatcher] runs. */
object VehicleActivity : VehicleDetection {
    override fun missingPermission(ctx: Context): String? = null
    override fun start(ctx: Context, onResult: (Boolean) -> Unit) = onResult(false)
    override fun active(ctx: Context): Boolean = false
    override fun stop(ctx: Context) {}
}
