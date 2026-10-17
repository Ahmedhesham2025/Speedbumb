package app.bumpbeeper.replay

import app.bumpbeeper.PhoneSignals

/**
 * A research trip's phone-state lines ([Rr2Trip.phone]) fed into the engine's [PhoneSignals] as the app's PhoneFeed sets
 * them, so a replay judges handling as the phone did. [apply] goes to Replayer as its `signals` hook: before each
 * sample, every line up to that time is applied, in order (a line from before the start sets the start's value).
 *  - `scr` → screenOn; `unl` → unlockedAtMs (the line's time, the sensor clock like the samples).
 *  - `px` → proximityNear: closer than the sensor's range (header `sensor.px` max) or 5 cm, whichever is less.
 *  - `lx` → lux.
 *  - `aud` → handheldCall: in call or VoIP mode (2, 3) at the earpiece (call_device 1); with no call device recorded,
 *    at the earpiece when nothing (Bluetooth, wired) is connected.
 *  - `lk` → keyguardLocked; keyguardPresent once the lock screen has been seen up (PhoneFeed's sawLocked; the
 *    recording has no isDeviceSecure), unknown (null) for a recording with no `lk` lines.
 *  - `bat` → charging: plugged in (not 0).
 */
class PhoneSignalFeed(lines: List<PhoneLine>, meta: Map<String, String> = emptyMap()) {
    private val lines = lines.sortedBy { it.tMs }
    private var next = 0
    private var sawLocked = false
    private val nearCm = minOf(
        meta["sensor.px"]?.split(';')?.firstOrNull { it.startsWith("max=") }?.substringAfter('=')?.toDoubleOrNull() ?: Double.MAX_VALUE,
        5.0,
    )

    fun apply(tMs: Long, s: PhoneSignals) {
        while (next < lines.size && lines[next].tMs <= tMs) set(lines[next++], s)
    }

    private fun set(l: PhoneLine, s: PhoneSignals) {
        val v = l.values
        fun at(i: Int) = v.getOrElse(i) { Double.NaN }
        when (l.code) {
            "scr" -> if (!at(0).isNaN()) s.screenOn = at(0) == 1.0
            "unl" -> s.unlockedAtMs = l.tMs
            "px" -> if (!at(0).isNaN()) s.proximityNear = at(0) < nearCm
            "lx" -> if (!at(0).isNaN()) s.lux = at(0)
            "aud" -> {
                val inCall = at(0) == 2.0 || at(0) == 3.0
                val earpiece = if (!at(2).isNaN()) at(2) == 1.0 else at(1).isNaN() || at(1) == 0.0
                s.handheldCall = inCall && earpiece
            }
            "lk" -> if (!at(0).isNaN()) {
                val locked = at(0) == 1.0
                sawLocked = sawLocked || locked
                s.keyguardLocked = locked
                s.keyguardPresent = sawLocked
            }
            "bat" -> if (!at(1).isNaN()) s.charging = at(1) != 0.0
        }
    }
}
