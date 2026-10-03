package app.bumpbeeper

import java.util.Random

// tools/replay compiles this file too, with the `actual` keywords removed (see its build file).
actual class SimRandom actual constructor(seed: Long) {
    private val r = Random(seed)
    actual fun nextDouble(): Double = r.nextDouble()
    actual fun nextBoolean(): Boolean = r.nextBoolean()
    actual fun nextGaussian(): Double = r.nextGaussian()
}
