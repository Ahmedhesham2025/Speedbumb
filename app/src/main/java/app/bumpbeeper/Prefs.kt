package app.bumpbeeper

import android.content.Context
import android.content.SharedPreferences

/** User settings, stored on the phone. */
object Prefs {
    const val SENSITIVITY = "sensitivity"   // 0 = low, 1 = normal, 2 = high
    const val LOUD = "loud"
    const val CLICK_ON_NEW = "click_on_new"
    const val LEAD_SECONDS = "lead_seconds"
    const val QUIET_BELOW_KMH = "quiet_below_kmh"
    const val MAX_BUMP_KMH = "max_bump_kmh"
    const val WARN_POTHOLES = "warn_potholes"
    const val HARSH_MS2 = "harsh_ms2"
    const val VOICE_LANG = "voice_lang"     // "en" or "ar"
    const val SPEED_LIMIT = "speed_limit"
    const val DEBUG_RECORDING = "debug_recording"
    const val AUTO_START = "auto_start"
    const val CAR_ADDRESS = "car_address"
    const val CAR_NAME = "car_name"
    /** Label mode: the driver taps what they just drove over; forces a recording even with debug recording off. */
    const val LABEL_MODE = "label_mode"
    /** Where the phone sits in the car (written into recordings): mounted | cupholder | pocket | unknown. */
    const val PLACEMENT = "placement"
    val PLACEMENTS = listOf("mounted", "cupholder", "pocket", "unknown")
    /** Update check: when it last asked GitHub, and the newer version it found (if any). */
    const val UPDATE_CHECKED_AT = "update_checked_at"
    const val UPDATE_VERSION = "update_version"
    const val UPDATE_URL = "update_url"
    const val UPDATE_HTML_URL = "update_html_url"
    /**
     * The online bump map, as answered on the first-run screen: [SYNC_UNSET] (not answered: no network at all),
     * [SYNC_RECEIVE] (download confirmed spots only) or [SYNC_SHARE] (also upload hazard points and crash reports).
     */
    const val SYNC_CHOICE = "sync_choice"
    const val SYNC_UNSET = "unset"
    const val SYNC_RECEIVE = "receive"
    const val SYNC_SHARE = "share"
    val SYNC_CHOICES = listOf(SYNC_UNSET, SYNC_RECEIVE, SYNC_SHARE)

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
    /** Soft tick when a new spot is recorded on the first pass (on unless switched off). */
    fun clickOnNew(ctx: Context): Boolean = sp(ctx).getBoolean(CLICK_ON_NEW, true)

    /** Seconds of warning before a bump (4–12). */
    fun leadSeconds(ctx: Context): Int = sp(ctx).getInt(LEAD_SECONDS, 7)
    /** Don't beep when already slower than this, km/h (0 = always beep). */
    fun quietBelowKmh(ctx: Context): Int = sp(ctx).getInt(QUIET_BELOW_KMH, 20)
    /** Jolts above this speed aren't speed bumps (unless clearly a pothole), km/h. */
    fun maxBumpKmh(ctx: Context): Int = sp(ctx).getInt(MAX_BUMP_KMH, 50)
    fun warnPotholes(ctx: Context): Boolean = sp(ctx).getBoolean(WARN_POTHOLES, true)
    /** Potholes with an average jolt of at least this many m/s² get a voice warning (4–10). Default = [EngineConfig.harshPotholeMs2]. */
    fun harshMs2(ctx: Context): Int = sp(ctx).getInt(HARSH_MS2, 5)
    fun voiceLang(ctx: Context): String = sp(ctx).getString(VOICE_LANG, "en") ?: "en"
    /** Speed above which time counts as speeding, for the driving score (km/h). */
    fun speedLimit(ctx: Context): Int = sp(ctx).getInt(SPEED_LIMIT, 90)

    fun applyTo(cfg: DrivingConfig, ctx: Context) {
        cfg.speedLimitKmh = speedLimit(ctx).toDouble()
    }
    fun debugRecording(ctx: Context): Boolean = sp(ctx).getBoolean(DEBUG_RECORDING, false)

    fun labelMode(ctx: Context): Boolean = sp(ctx).getBoolean(LABEL_MODE, false)
    fun setLabelMode(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(LABEL_MODE, on).apply()
    /** A recording file is written when debug recording or label mode is on. */
    fun recordTrace(ctx: Context): Boolean = debugRecording(ctx) || labelMode(ctx)

    fun placement(ctx: Context): String = sp(ctx).getString(PLACEMENT, "unknown")?.takeIf { it in PLACEMENTS } ?: "unknown"
    fun setPlacement(ctx: Context, value: String) =
        sp(ctx).edit().putString(PLACEMENT, if (value in PLACEMENTS) value else "unknown").apply()

    fun syncChoice(ctx: Context): String = sp(ctx).getString(SYNC_CHOICE, SYNC_UNSET)?.takeIf { it in SYNC_CHOICES } ?: SYNC_UNSET
    /** Screens use [app.bumpbeeper.sync.Sync.setChoice]: it also starts (or stops) the sync. */
    fun setSyncChoice(ctx: Context, choice: String) =
        sp(ctx).edit().putString(SYNC_CHOICE, if (choice in SYNC_CHOICES) choice else SYNC_UNSET).apply()
    /** Upload hazard points (and crash reports): only when the user chose "share". */
    fun shareBumps(ctx: Context): Boolean = syncChoice(ctx) == SYNC_SHARE

    /**
     * Road speed limits for the driving score: after each trip a trimmed route goes through our server to TomTom.
     * Opt-in (off by default), and only used while the network is allowed ([syncChoice] not "unset"); see
     * [app.bumpbeeper.sync.SpeedLimitSync.allowed]. Screens switch it with [app.bumpbeeper.sync.SpeedLimitSync.setEnabled].
     */
    const val SPEED_LIMITS = "speed_limits"
    fun speedLimits(ctx: Context): Boolean = sp(ctx).getBoolean(SPEED_LIMITS, false)

    /**
     * Stop recording after the car has been parked this many minutes (0 = never). Only after real driving, so a red
     * light or a manual start in a car park never ends a trip; see [app.bumpbeeper.auto.AutoStop].
     */
    const val AUTO_STOP_MIN = "auto_stop_minutes"
    const val AUTO_STOP_DEFAULT_MIN = 3
    fun autoStopMinutes(ctx: Context): Int = sp(ctx).getInt(AUTO_STOP_MIN, AUTO_STOP_DEFAULT_MIN).coerceIn(0, 30)

    fun autoStart(ctx: Context): Boolean = sp(ctx).getBoolean(AUTO_START, false)
    fun carAddress(ctx: Context): String? = sp(ctx).getString(CAR_ADDRESS, null)
    fun carName(ctx: Context): String? = sp(ctx).getString(CAR_NAME, null)

    /** Engine settings as chosen on screen (for counting harsh potholes etc. outside a trip). */
    fun engineConfig(ctx: Context): EngineConfig = EngineConfig().also { applyTo(it, ctx) }

    /** Copy the on-screen settings into the engine. */
    fun applyTo(cfg: EngineConfig, ctx: Context) {
        cfg.joltThreshold = threshold(ctx)
        cfg.leadSeconds = leadSeconds(ctx).toDouble()
        cfg.quietBelowKmh = quietBelowKmh(ctx).toDouble()
        cfg.maxSpeedKmh = maxBumpKmh(ctx).toDouble()
        cfg.warnPotholes = warnPotholes(ctx)
        cfg.harshPotholeMs2 = harshMs2(ctx).toDouble()
    }
}
