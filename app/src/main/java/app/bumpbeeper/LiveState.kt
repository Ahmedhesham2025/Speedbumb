package app.bumpbeeper

/** Numbers the recording service shares with the screen. The screen reads them 4 times a second. */
object LiveState {
    @Volatile var recording = false
    /** Auto-detect driving is waiting for the next drive (the quiet "ready" notification is up). */
    @Volatile var watching = false
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
    /** Driving score so far this trip (-1 = too short yet), time moving, harsh events, % of time speeding. */
    @Volatile var liveScore = -1
    @Volatile var tripMovingS = 0.0
    @Volatile var tripEvents = 0
    @Volatile var speedingPct = 0.0
    @Volatile var lastDriveEvent = ""
    /** "Help improve detection": elements waiting for upload. */
    @Volatile var trainingQueued = 0
    /** Score of the trip that just ended (shown after Stop). */
    @Volatile var lastTripScore = -1
    @Volatile var lastEvent = ""
    @Volatile var lastIgnored = ""
    /** The phone has a gyroscope (needed to tell potholes from speed bumps reliably). */
    @Volatile var hasGyro = false
    /** The engine has learned which way the car's nose points (after a few speed-ups / brakings). */
    @Volatile var forwardKnown = false
    /** The current recording accepts labels ([BumpService.label] will be written to a recording file). */
    @Volatile var labelMode = false
    /** Last label the driver tapped this trip ("" = none yet) and how many labels count (an undo takes one back). */
    @Volatile var lastLabel = ""
    @Volatile var labelCount = 0
    /** Research recording is writing this trip's files (Diagnostics: "Mark" writes into them). */
    @Volatile var researchRunning = false
    /** The accelerometer rate the engine got this trip, Hz (0 = not measured yet). For Diagnostics. */
    @Volatile var sensorHz = 0.0

    /** Online bump map: last finished sync (wall ms, 0 = never), cached shared spots, observations waiting
     *  for upload, and the last problem in a few words ("" = none). Filled by app.bumpbeeper.sync.Sync. */
    @Volatile var syncLastAt = 0L
    @Volatile var syncRemoteSpots = 0
    @Volatile var syncPending = 0
    @Volatile var syncLastError = ""
    /** Shared spots: the last successful download (wall ms, 0 = never), and whether the last try failed. By Sync. */
    @Volatile var spotsOkAt = 0L
    @Volatile var spotsFailed = false

    /**
     * Live road speed limit (opt-in, [app.bumpbeeper.sync.LiveSpeedLimit]): km/h, null = unknown. In memory only,
     * at most 5 min old (TomTom terms). Show "© TomTom" next to it. [liveLimitsOn]: looked up on this trip.
     */
    @Volatile var speedLimitKmh: Int? = null
        private set
    /** SystemClock.elapsedRealtime when [speedLimitKmh] arrived, 0 = none. */
    @Volatile var speedLimitAtMs = 0L
        private set
    /** How old [speedLimitKmh] is, ms (-1 = none). */
    val speedLimitAgeMs: Long
        get() = speedLimitAtMs.let { if (it <= 0L) -1L else android.os.SystemClock.elapsedRealtime() - it }
    /** 0 = within the limit (or unknown), 1 = over it, 2 = over it by more than the chosen margin. */
    @Volatile var overLimit = 0
    @Volatile var liveLimitsOn = false

    fun setSpeedLimit(kmh: Int?, atMs: Long) {
        speedLimitAtMs = if (kmh == null) 0L else atMs
        speedLimitKmh = kmh
    }

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
        liveScore = -1; tripMovingS = 0.0; tripEvents = 0; speedingPct = 0.0; lastDriveEvent = ""
        speedKmh = Double.NaN; accuracyM = Double.NaN; lastFixAtMs = 0L
        lastIgnored = ""
        forwardKnown = false
        lastLabel = ""; labelCount = 0
        sensorHz = 0.0
        setSpeedLimit(null, 0L); overLimit = 0; liveLimitsOn = false
    }

    const val GRAPH_POINTS = 300
}

/** What the driver can tap while driving in label mode. Written as the `note` of `event=label` trace rows. */
object Labels {
    const val BUMP = "bump"
    const val POTHOLE_LEFT = "pothole_l"
    const val POTHOLE_RIGHT = "pothole_r"
    const val ROUGH = "rough"
    /** Takes back the previous label (the replay tool drops the label before an undo). */
    const val UNDO = "undo"
    val ALL = listOf(BUMP, POTHOLE_LEFT, POTHOLE_RIGHT, ROUGH, UNDO)
    /** Not a label kind (not in [ALL]): Diagnostics' "Mark", a research `lbl` line for test drives. */
    const val MARK = "mark"
}
