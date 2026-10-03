package app.bumpbeeper

/**
 * The simulator's random numbers: the `java.util.Random` sequence for a seed. The scenarios were tuned on those
 * exact drives, so the JVM uses `java.util.Random` itself; other platforms use a port of the same generator.
 */
expect class SimRandom(seed: Long) {
    fun nextDouble(): Double
    fun nextBoolean(): Boolean
    fun nextGaussian(): Double
}
