package app.bumpbeeper.auto

import android.content.Context

/** foss edition: no Google activity recognition, so detection is always the built-in [DriveWatcher]. */
object VehicleActivity : VehicleDetection {
    override fun missingPermission(ctx: Context): String? = null
    override fun start(ctx: Context, onResult: (Boolean) -> Unit) = onResult(false)
    override fun active(ctx: Context): Boolean = false
    override fun stop(ctx: Context) {}
}
