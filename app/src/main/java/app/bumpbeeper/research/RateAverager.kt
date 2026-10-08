package app.bumpbeeper.research

/**
 * Research recording asks Android for 200 Hz, and Android hands that rate to every listener of the sensor: BumpService's
 * too. The engine was built for 50–100 Hz. Its jolt trigger and peak use raw samples (a 200 Hz stream carries more of the
 * sensor's bandwidth), its training ring holds a fixed number of samples, and its work grows with the rate. So while
 * research runs, BumpService averages the engine's accelerometer and gyroscope into bins of at least [binMs] (≤ 100 Hz),
 * about what the engine sees without research. Samples [binMs] or more apart pass unchanged. Plain Kotlin.
 */
class RateAverager(private val binMs: Long = 10) {
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0
    private var n = 0
    private var lastOutMs = Long.MIN_VALUE / 2

    /** The average of the last finished bin. */
    var x = 0.0
        private set
    var y = 0.0
        private set
    var z = 0.0
        private set

    /** Adds a sample at [tMs]. True when a bin is finished: [x], [y] and [z] then hold its average. */
    fun add(tMs: Long, ax: Double, ay: Double, az: Double): Boolean {
        sx += ax; sy += ay; sz += az; n++
        if (tMs - lastOutMs < binMs) return false
        x = sx / n; y = sy / n; z = sz / n
        sx = 0.0; sy = 0.0; sz = 0.0; n = 0
        lastOutMs = tMs
        return true
    }

    companion object {
        /** The rate research recording asks for: 200 Hz ([ResearchRecorder]). */
        const val RESEARCH_PERIOD_US = 5_000

        /**
         * For an engine that asked for [periodUs] ([app.bumpbeeper.Prefs.sensorPeriodUs]): bins of that period, at most
         * 10 ms as before (50 Hz asked → ≤ 100 Hz, unchanged). Null when it asked for research's rate or faster: research
         * then adds nothing it didn't ask for, so its samples pass as they come.
         */
        fun forPeriod(periodUs: Int): RateAverager? =
            if (periodUs <= RESEARCH_PERIOD_US) null else RateAverager((periodUs / 1000L).coerceIn(1L, 10L))
    }
}
