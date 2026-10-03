package app.bumpbeeper.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import app.bumpbeeper.BumpService
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs

/**
 * Auto start by detecting driving, no Bluetooth needed (#49). The public API for screens:
 * [setEnabled] (the settings switch), [status] (what to show), [missingPermissions] / [optionalPermission]
 * (what to ask for), and [apply] (call it in MainActivity.onResume; also runs after a reboot or an app update).
 *
 * How it detects:
 *  - play edition with Google Play services and the "physical activity" permission: Google's IN_VEHICLE enter/exit
 *    ([VehicleActivity]). Nothing of ours runs in between; Android lets an activity transition start recording.
 *  - otherwise (foss edition, or no Google): BumpService stays in the foreground with a quiet "ready to detect
 *    driving" notification and [DriveWatcher] waits for the significant-motion sensor, then checks speed by GPS.
 * Both need background location ("Allow all the time"): recording then starts while the app is closed.
 */
object AutoDetect {
    enum class Status {
        /** Switched off in settings. */
        OFF,
        /** Switched on, but [missingPermissions] are not granted yet: nothing runs. */
        NEEDS_PERMISSION,
        /** Google's in-vehicle detection (play edition). */
        GOOGLE_ACTIVITY,
        /** Built-in: motion sensor + short GPS checks (the quiet notification is up). */
        MOTION_SENSOR,
        /** Built-in, but this phone has no significant-motion sensor: only other apps' GPS fixes and Bluetooth start it. */
        PASSIVE_ONLY,
        /** Switched on with permissions, but nothing runs: Android refused to start it (or killed it). [apply] retries. */
        NOT_RUNNING,
    }

    fun enabled(ctx: Context): Boolean = Prefs.autoDetect(ctx)

    /** The settings switch. Starts or stops detection right away; call it from a screen (foreground). */
    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs.sp(ctx).edit().putBoolean(Prefs.AUTO_DETECT, on).apply()
        apply(ctx)
    }

    /** Required runtime permissions not granted yet: precise location, then "Allow all the time". Ask in this order. */
    fun missingPermissions(ctx: Context): List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ).filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /**
     * Play edition with Google Play services only: the "physical activity" permission that switches to Google's
     * in-vehicle detection (better, and no notification). Null when not needed or already granted. Without it the
     * built-in detection runs. Call [apply] after the user answers.
     */
    fun optionalPermission(ctx: Context): String? = VehicleActivity.missingPermission(ctx)

    /** What actually runs now (not just what was asked for). */
    fun status(ctx: Context): Status = when {
        !enabled(ctx) -> Status.OFF
        missingPermissions(ctx).isNotEmpty() -> Status.NEEDS_PERMISSION
        VehicleActivity.active(ctx) -> Status.GOOGLE_ACTIVITY
        !LiveState.watching -> Status.NOT_RUNNING
        hasMotionSensor(ctx) -> Status.MOTION_SENSOR
        else -> Status.PASSIVE_ONLY
    }

    /** Make the running detection match the setting and permissions. Idempotent and cheap. */
    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app) || missingPermissions(app).isNotEmpty()) {
            VehicleActivity.stop(app)
            BumpService.unwatch(app)
            return
        }
        // Requested again on every call (app resume, boot, update): Google drops it when its data is cleared.
        VehicleActivity.start(app) { ok ->
            if (ok) BumpService.unwatch(app)   // Google watches; no need to keep our service up
            else BumpService.watch(app)
        }
    }

    private fun hasMotionSensor(ctx: Context): Boolean =
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null
}
