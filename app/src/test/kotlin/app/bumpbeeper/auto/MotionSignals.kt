package app.bumpbeeper.auto

/** 4 s of accelerometer magnitudes at 25 Hz (m/s²), as the phone would measure them. */
object MotionSignals {
    private fun signal(f: (Double) -> Double) = (0 until 100).map { f(it / 25.0) }
    private fun noise(i: Double, amp: Double) = amp * Math.sin(i * 977.0) * Math.cos(i * 331.0)

    /** On a table: sensor noise only. */
    val still = signal { t -> 9.81 + noise(t, 0.03) }
    /** In a moving car: road and engine vibration, about 0.3 m/s². */
    val car = signal { t -> 9.81 + 0.35 * Math.sin(2 * Math.PI * 11 * t) + noise(t, 0.2) }
    /** Walking, phone in a pocket: about 2 steps a second, several m/s² each. */
    val walking = signal { t -> 9.81 + 3.5 * Math.sin(2 * Math.PI * 2 * t) + noise(t, 0.5) }
    /** Carried gently around the flat: a slow sway, about 1 Hz. */
    val carried = signal { t -> 9.81 + 0.6 * Math.sin(2 * Math.PI * 1 * t) + noise(t, 0.03) }
    /** Picked up and put down: one burst, then still. */
    val putDown = signal { t -> 9.81 + (if (t < 1.0) 1.2 * Math.sin(2 * Math.PI * 3 * t) else 0.0) + noise(t, 0.03) }
}
