package app.bumpbeeper.auto

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.bumpbeeper.Prefs
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * play edition: Google's activity recognition transitions ([VehicleActivityReceiver] handles them):
 * IN_VEHICLE enter starts recording; WALKING / RUNNING enter means the user left the car (ON_FOOT isn't offered by the
 * Transition API). A bare IN_VEHICLE exit isn't requested: Google reports it for a car standing at a light, too.
 * Needs Google Play services and the "physical activity" permission (ACTIVITY_RECOGNITION, Android 10+); without
 * either, [AutoDetect] uses the built-in detection like the foss edition.
 */
object VehicleActivity : VehicleDetection {
    private const val TAG = "BumpBeeper"
    /** The last request succeeded (kept across restarts; Google keeps the request until a reboot or a data clear). */
    private const val REGISTERED = "auto_detect_google_registered"

    override fun missingPermission(ctx: Context): String? =
        if (googleServices(ctx) && !granted(ctx)) Manifest.permission.ACTIVITY_RECOGNITION else null

    override fun start(ctx: Context, onResult: (Boolean) -> Unit) {
        if (!googleServices(ctx) || !granted(ctx)) {
            setRegistered(ctx, false)
            return onResult(false)
        }
        val wanted = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.WALKING, DetectedActivity.RUNNING)
        val transitions = wanted.map {
            ActivityTransition.Builder().setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        }
        try {
            ActivityRecognition.getClient(ctx)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(ctx))
                .addOnSuccessListener {
                    setRegistered(ctx, true)
                    onResult(true)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "activity transitions refused", e)
                    setRegistered(ctx, false)
                    onResult(false)
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "activity transitions: no permission", e)
            setRegistered(ctx, false)
            onResult(false)
        }
    }

    override fun active(ctx: Context): Boolean =
        Prefs.sp(ctx).getBoolean(REGISTERED, false) && granted(ctx) && googleServices(ctx)

    override fun stop(ctx: Context) {
        setRegistered(ctx, false)
        if (!googleServices(ctx)) return
        try {
            ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(pending(ctx))
        } catch (e: Exception) {
            Log.w(TAG, "activity transitions not removed", e)
        }
    }

    private fun setRegistered(ctx: Context, on: Boolean) = Prefs.sp(ctx).edit().putBoolean(REGISTERED, on).apply()

    private fun granted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun googleServices(ctx: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    } catch (_: Throwable) {
        false
    }

    /** Mutable on Android 12+: Google fills in the transition result. */
    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, VehicleActivityReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )
}
