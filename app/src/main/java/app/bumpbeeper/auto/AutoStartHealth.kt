package app.bumpbeeper.auto

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.bumpbeeper.LiveState
import app.bumpbeeper.R

/**
 * Can auto start start a trip right now, and if not, why (Diagnostics, the "blocked" notification)? Each way it can
 * fail silently on Android 12–16, ranked so the first one that applies is the one to fix first. [decide] is pure.
 */
object AutoStartHealth {
    /** What blocks auto start ([blocks] true) or only weakens it (a warning), with the settings screen that fixes it. */
    enum class Problem(val blocks: Boolean, val text: Int) {
        /** No precise location: nothing can run. */
        NO_LOCATION(true, R.string.autostart_no_location),
        /** Not "Allow all the time" (never given, or revoked: Android removes it from apps unused for months). */
        NO_BACKGROUND_LOCATION(true, R.string.autostart_no_background),
        /** "Battery: Restricted", or the Restricted standby bucket: Android refuses a foreground start from the background. */
        BACKGROUND_RESTRICTED(true, R.string.autostart_restricted),
        /** Allowed, but the watching service isn't running (Android killed it and refused the restart). */
        NOT_RUNNING(true, R.string.autostart_not_running),
        /** Notifications off: "Was this a drive?" is never seen, so every automatic trip is deleted unsent after 24 h. */
        NO_NOTIFICATIONS(false, R.string.autostart_no_notifications),
        /** Battery optimisation on: Doze and the phone maker's battery saver may stop the watching service. */
        BATTERY_OPTIMISED(false, R.string.autostart_battery),
        /** A phone maker that blocks start after a restart unless its own "Autostart" is allowed (can't be read). */
        OEM_AUTOSTART(false, R.string.autostart_oem),
    }

    class Inputs(
        val enabled: Boolean,
        val fineLocation: Boolean,
        val backgroundLocation: Boolean,
        val backgroundRestricted: Boolean,
        val notifications: Boolean,
        val batteryExempt: Boolean,
        /** The watching service runs, a recording runs, or Google's in-vehicle detection is registered. */
        val detecting: Boolean,
        /** This phone maker has its own "Autostart" switch ([oemAutostart]). */
        val oemAutostart: Boolean = false,
    )

    /** Off (null), or the problems in the order to fix them: empty = ready. */
    fun decide(i: Inputs): List<Problem>? {
        if (!i.enabled) return null
        val out = ArrayList<Problem>()
        when {
            !i.fineLocation -> out += Problem.NO_LOCATION
            !i.backgroundLocation -> out += Problem.NO_BACKGROUND_LOCATION
            i.backgroundRestricted -> out += Problem.BACKGROUND_RESTRICTED
            !i.detecting -> out += Problem.NOT_RUNNING
        }
        if (!i.notifications) out += Problem.NO_NOTIFICATIONS
        if (!i.batteryExempt) out += Problem.BATTERY_OPTIMISED
        if (i.oemAutostart) out += Problem.OEM_AUTOSTART
        return out
    }

    fun read(ctx: Context): Inputs {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val am = ctx.getSystemService(ActivityManager::class.java)
        val bucket = try { ctx.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket } catch (_: Exception) { null }
        return Inputs(
            enabled = AutoDetect.enabled(ctx),
            fineLocation = has(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocation = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            backgroundRestricted = am?.isBackgroundRestricted == true ||
                (Build.VERSION.SDK_INT >= 30 && bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED),
            notifications = ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false,
            batteryExempt = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
            detecting = LiveState.watching || LiveState.recording || VehicleActivity.active(ctx),
            oemAutostart = oemAutostart(ctx) != null && !oemAllowed(ctx),
        )
    }

    fun check(ctx: Context): List<Problem>? = decide(read(ctx))

    /**
     * Phone makers whose own "Autostart" / startup manager blocks the boot broadcast (and so auto start after a
     * restart) unless the app is allowed there: their screen, when this phone has it.
     */
    private val OEM_AUTOSTART_SCREENS = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        // Infinix, Tecno and itel (Transsion): Phone Master stops apps in the background, not only after a restart.
        "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
    )

    /** Phone Master's screen names differ by version: when none of the screens above resolves, open Phone Master. */
    private const val TRANSSION_MANAGER = "com.transsion.phonemaster"

    /**
     * The maker's Autostart setting can't be read, so the hint would show forever: after the user opened that screen
     * ([oemOpened]) and came back, "Done, I allowed it" ([setOemAllowed]) hides it. Per install: this file is not
     * backed up or moved to a new phone (backup_rules, data_extraction_rules), where it must be allowed again.
     */
    private fun oem(ctx: Context) = ctx.getSharedPreferences("oem_autostart", Context.MODE_PRIVATE)

    fun oemAllowed(ctx: Context): Boolean = oem(ctx).getBoolean("allowed", false)
    fun setOemAllowed(ctx: Context) { oem(ctx).edit().putBoolean("allowed", true).apply() }
    fun oemOpened(ctx: Context): Boolean = oem(ctx).getBoolean("opened", false)
    fun setOemOpened(ctx: Context) { oem(ctx).edit().putBoolean("opened", true).apply() }

    fun oemAutostart(ctx: Context): Intent? = OEM_AUTOSTART_SCREENS.asSequence()
        .map { (p, c) -> Intent().setComponent(android.content.ComponentName(p, c)) }
        .firstOrNull { i -> try { i.resolveActivity(ctx.packageManager) != null } catch (_: Exception) { false } }
        ?: try { ctx.packageManager.getLaunchIntentForPackage(TRANSSION_MANAGER) } catch (_: Exception) { null }

    /** The one settings screen that fixes [p] (the app's own page where Android has nothing more direct). */
    fun fix(ctx: Context, p: Problem): Intent {
        val pkg = Uri.parse("package:" + ctx.packageName)
        return when (p) {
            Problem.OEM_AUTOSTART -> oemAutostart(ctx) ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            Problem.BATTERY_OPTIMISED -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Problem.NO_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            // Not running: opening the app restarts it (AutoDetect.apply in onResume).
            Problem.NOT_RUNNING -> ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
