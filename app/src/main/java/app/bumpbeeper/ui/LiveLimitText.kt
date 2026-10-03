package app.bumpbeeper.ui

import app.bumpbeeper.Prefs
import app.bumpbeeper.Ui

/**
 * Pure rules for the live speed-limit sign on the Drive screen and its switch in Settings (the lookups themselves are
 * `sync.LiveSpeedLimit`). Kept free of Android so they are unit-tested.
 */
object LiveLimitText {
    /** The consent text the user agreed to; `Prefs.LIVE_LIMITS_CONSENT_VERSION` must be at least this for calls. */
    const val CONSENT_VERSION = 1
    /** TomTom's terms: a limit older than 5 minutes is not shown. */
    const val STALE_MS = 5 * 60_000L
    /** What the sign shows when the limit is unknown or too old. */
    const val UNKNOWN = "– –"

    /** The number in the sign, or [UNKNOWN] (no answer, a nonsense value, no age, or older than [STALE_MS]). */
    fun signText(limitKmh: Int?, ageMs: Long): String =
        if (limitKmh == null || limitKmh <= 0 || ageMs < 0 || ageMs > STALE_MS) UNKNOWN else limitKmh.toString()

    /** Smaller text for three digits, so "120" still fits inside the red ring. */
    fun textScale(text: String): Float = if (text.length >= 3) 0.36f else 0.46f

    /** The big speed number: normal, orange when over the limit, red when over it by more than the margin. */
    fun speedColor(overLimit: Int): Int = when (overLimit) {
        1 -> Ui.ORANGE
        2 -> Ui.RED
        else -> Ui.TEXT
    }

    /** The sign is shown only while recording with the feature switched on and agreed to. */
    fun showSign(recording: Boolean, on: Boolean, consentVersion: Int): Boolean =
        recording && on && consentVersion >= CONSENT_VERSION

    enum class TurnOn { NEED_MAP, ASK_CONSENT }

    /** Turning the switch on: nothing goes to our server while the shared-map question is unanswered, so that comes first. */
    fun turnOnStep(syncChoice: String): TurnOn =
        if (syncChoice == Prefs.SYNC_UNSET) TurnOn.NEED_MAP else TurnOn.ASK_CONSENT

    /** The margin as stored, falling back to the default 10 km/h for anything not offered. */
    fun margin(stored: Int): Int = if (stored in Prefs.LIMIT_MARGINS) stored else 10
}
