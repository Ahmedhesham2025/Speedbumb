package app.bumpbeeper

/** What a replayed drive produced. */
class ReplayResult(
    /** Every event the engine and the driving monitor logged, in order. */
    val events: List<BumpEvent>,
    val trip: TripStats,
    val driving: DrivingStats,
)

/**
 * Feeds a recorded (or simulated) drive into a fresh [BumpEngine] + [DrivingMonitor], in time order,
 * the same way BumpService does on the phone. Event rows in the trace are ignored (they are the old output).
 */
object Replayer {
    /** Wall-clock base for logged events, so replays are deterministic. */
    const val WALL_BASE_MS = 1_700_000_000_000L

    fun replay(
        samples: List<TraceSample>,
        store: BumpStore,
        cfg: EngineConfig = EngineConfig(),
        drivingCfg: DrivingConfig = DrivingConfig(),
        tripId: Long = 1L,
    ): ReplayResult {
        val events = ArrayList<BumpEvent>()
        val logging = object : BumpStore by store {
            override fun logEvent(e: BumpEvent) {
                events.add(e)
                store.logEvent(e)
            }
        }
        var nowMs = 0L
        var engine: BumpEngine? = null
        var monitor: DrivingMonitor? = null
        // Like the service: a hit's speed is the latest GPS speed.
        val listener = object : EngineListener {
            override fun onNewBump(b: Bump) { monitor?.onBumpHit(b, (engine?.lastFix?.speedMps ?: 0.0) * 3.6) }
            override fun onKnownBumpHit(b: Bump) { monitor?.onBumpHit(b, (engine?.lastFix?.speedMps ?: 0.0) * 3.6) }
        }
        val eng = BumpEngine(cfg, logging, listener, { WALL_BASE_MS + nowMs }, tripId)
        engine = eng
        val mon = DrivingMonitor(drivingCfg, eng) { type, lat, lon, kmh, value, note ->
            logging.logEvent(BumpEvent(WALL_BASE_MS + nowMs, tripId, type, -1, lat, lon, kmh, Double.NaN, value, Double.NaN, Double.NaN, note))
        }
        monitor = mon

        // The app writes 0,0,0 for the gyroscope before its first reading (and on phones without one),
        // so the gyroscope only counts once a real (non-zero) reading has been seen.
        var gyroSeen = false
        for (s in samples.sortedBy { it.tMs }) {   // stable: rows with the same time keep their order
            nowMs = s.tMs
            when (s) {
                is TraceSample.Accel -> {
                    val hasGyro = !s.gx.isNaN() && !s.gy.isNaN() && !s.gz.isNaN()
                    if (hasGyro && (s.gx != 0.0 || s.gy != 0.0 || s.gz != 0.0)) gyroSeen = true
                    if (hasGyro && gyroSeen) {
                        eng.onGyro(s.tMs, s.gx, s.gy, s.gz)
                        mon.onGyro(s.gx, s.gy, s.gz)
                    }
                    eng.onAccel(s.tMs, s.ax, s.ay, s.az)
                    mon.onAccel(s.tMs, s.ax, s.ay, s.az)
                }
                is TraceSample.Gps -> {
                    val fix = Fix(
                        s.tMs, s.lat, s.lon, s.speedKmh / 3.6, s.bearing,
                        if (s.accuracyM.isNaN()) 99.0 else s.accuracyM,   // the service's value for "no accuracy"
                    )
                    eng.onFix(fix)
                    mon.onFix(eng.lastFix ?: fix)
                }
                is TraceSample.Event -> {}
            }
        }
        mon.finish()
        return ReplayResult(events, eng.trip, mon.stats)
    }
}
