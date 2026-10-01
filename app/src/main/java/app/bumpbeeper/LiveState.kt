package app.bumpbeeper

/** Numbers the recording service shares with the screen. The screen reads them 4 times a second. */
object LiveState {
    @Volatile var recording = false
    @Volatile var speedKmh = Double.NaN
    @Volatile var accuracyM = Double.NaN
    @Volatile var lastFixAtMs = 0L          // SystemClock.elapsedRealtime of the last GPS fix
    @Volatile var bumpsOnMap = 0
    @Volatile var mutedBumps = 0
    @Volatile var tripHits = 0
    @Volatile var tripNew = 0
    @Volatile var tripBeeps = 0
    @Volatile var tripMisses = 0
    @Volatile var tripKm = 0.0
    @Volatile var tripPotholes = 0
    @Volatile var tripHarshPotholes = 0
    @Volatile var potholesOnMap = 0
    @Volatile var harshOnMap = 0
    @Volatile var lastEvent = ""
    @Volatile var lastIgnored = ""
    /** The phone has a gyroscope (needed to tell potholes from speed bumps reliably). */
    @Volatile var hasGyro = false
    /** The engine has learned which way the car's nose points (after a few speed-ups / brakings). */
    @Volatile var forwardKnown = false

    // Last 30 s of jolt readings (one value per 100 ms) for the jolt meter.
    private val graph = FloatArray(300)
    private var head = 0
    private var count = 0

    @Synchronized fun pushGraph(v: Float) {
        graph[head] = v
        head = (head + 1) % graph.size
        if (count < graph.size) count++
    }

    @Synchronized fun graphSnapshot(): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = graph[(head - count + i + graph.size) % graph.size]
        return out
    }

    @Synchronized fun resetTrip() {
        head = 0; count = 0
        tripHits = 0; tripNew = 0; tripBeeps = 0; tripMisses = 0; tripKm = 0.0
        tripPotholes = 0; tripHarshPotholes = 0
        speedKmh = Double.NaN; accuracyM = Double.NaN; lastFixAtMs = 0L
        lastIgnored = ""
        forwardKnown = false
    }

    const val GRAPH_POINTS = 300
}
