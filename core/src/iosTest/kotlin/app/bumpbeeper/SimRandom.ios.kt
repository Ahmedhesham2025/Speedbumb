package app.bumpbeeper

actual class SimRandom actual constructor(seed: Long) {
    private val r = JavaRandomPort(seed)
    actual fun nextDouble(): Double = r.nextDouble()
    actual fun nextBoolean(): Boolean = r.nextBoolean()
    actual fun nextGaussian(): Double = r.nextGaussian()
}
