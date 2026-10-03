package app.bumpbeeper.ui

/**
 * The order of the questions behind "Start recording when I drive" (after the prominent disclosure):
 * precise location → "Allow all the time" → notifications (Android 13+) → battery exemption → physical activity
 * (play edition only). Pure, so the order is unit-tested; MainActivity asks and calls AutoDetect.apply after each answer.
 */
object AutoSetup {
    enum class Step { FINE, BACKGROUND, NOTIFICATIONS, BATTERY, ACTIVITY }

    class State(
        val fine: Boolean,
        val background: Boolean,
        val sdk: Int,
        val notifications: Boolean,
        val batteryOk: Boolean,
        /** Play edition and AutoDetect.optionalPermission() != null. */
        val activityNeeded: Boolean,
    )

    /** Without these nothing runs, so a "no" ends the walk. The others only make it better. */
    fun required(step: Step): Boolean = step == Step.FINE || step == Step.BACKGROUND

    fun needed(step: Step, s: State): Boolean = when (step) {
        Step.FINE -> !s.fine
        Step.BACKGROUND -> !s.background
        Step.NOTIFICATIONS -> s.sdk >= 33 && !s.notifications
        Step.BATTERY -> !s.batteryOk
        Step.ACTIVITY -> s.activityNeeded
    }

    /** The next question, skipping those already asked in this walk ([asked]); null = done. */
    fun next(s: State, asked: Set<Step>): Step? = Step.values().firstOrNull { it !in asked && needed(it, s) }

    /** The disclosure comes before any location question (Google Play policy). */
    fun needsDisclosure(s: State): Boolean = !s.fine || !s.background
}
