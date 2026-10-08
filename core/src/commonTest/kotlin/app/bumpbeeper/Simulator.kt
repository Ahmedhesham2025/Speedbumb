package app.bumpbeeper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** In-memory stand-in for the SQLite database. Keeps copies, like a real database would. */
class MemoryStore : BumpStore {
    val saved = ArrayList<Bump>()
    val events = ArrayList<BumpEvent>()
    private var nextId = 1L

    override fun loadBumps(): List<Bump> = saved.map { it.copy() }
    override fun insertBump(b: Bump): Long {
        val id = nextId++
        val c = b.copy()
        c.id = id
        saved.add(c)
        return id
    }
    override fun updateBump(b: Bump) {
        val i = saved.indexOfFirst { it.id == b.id }
        if (i >= 0) saved[i] = b.copy()
    }
    override fun logEvent(e: BumpEvent) { events.add(e) }
}

/** What happens on one simulated drive. Positions are metres from the west end of a 2 km east–west road. */
class DriveSpec(
    val westbound: Boolean = false,
    /** Real speed bumps: the driver sees them and slows to 15 km/h. */
    val bumpsAt: List<Double> = emptyList(),
    /** One-off jolts that are NOT real bumps (something on the road, something dropped). Felt only on this drive. */
    val oneOffJoltsAt: List<Double> = emptyList(),
    /** Places the driver slows to 25 km/h (junction, traffic). */
    val slowZonesAt: List<Double> = emptyList(),
    /** Where a passenger picks the phone up out of the holder and shakes it around. */
    val handlingAt: Double? = null,
    /** More places where the phone is picked up the same way (held 5 s, then put back). */
    val moreHandlingsAt: List<Double> = emptyList(),
    /** Spots where the driver slows to bump speed but feels nothing (the bump was removed). */
    val silentBumpsAt: List<Double> = emptyList(),
    /** Bumps the driver crawls over at 8 km/h, so gently that nothing is felt. */
    val crawlAt: List<Double> = emptyList(),
    /** Speed the driver slows to for bumps. */
    val bumpKmh: Double = 15.0,
    /**
     * Dips in the road under the right wheels (7 m/s²): the driver doesn't slow down; the wheel drops first, so the
     * jolt is down first and the car rolls. The engine records them as bumps like any other jolt.
     */
    val dipsAt: List<Double> = emptyList(),
    /** Small dips (right wheels, 4.2 m/s²). */
    val smallDipsAt: List<Double> = emptyList(),
    /** Old name of [dipsAt] (potholes are bumps since v2): the same down-first jolts, still recorded as bumps. */
    @Deprecated("No potholes since v2: use dipsAt") val potholesAt: List<Double> = emptyList(),
    /** Dips under the left wheels (old name; the side no longer matters to the engine). */
    @Deprecated("No potholes since v2: use dipsAt") val potholesLeftAt: List<Double> = emptyList(),
    /** Cruising speed between bumps. */
    val cruiseKmh: Double = 50.0,
    /** Phone has a gyroscope. */
    val gyro: Boolean = true,
    /** Emergency stops: the driver brakes at 6.5 m/s² (≈ 0.66 g) for 1.5 s, then speeds up again normally. */
    val hardBrakesAt: List<Double> = emptyList(),
    /** Swerves: a sudden turn left, then right (0.7 s each), like dodging something. */
    val swervesAt: List<Double> = emptyList(),
    /** Sharp turns: the car turns left at 0.45 rad/s for 2 s (≈ 52°, ≈ 6 m/s² at 50 km/h), and back right 6 s later. */
    val sharpTurnsAt: List<Double> = emptyList(),
    /** The phone twists 50° about the vertical in 0.8 s (shifting in a pocket): the gyroscope turns, the car doesn't. */
    val pocketTwistsAt: List<Double> = emptyList(),
    /** The phone is jostled (tipped 60° for [jostleHeldS] and shaken), like a loose phone in a pocket. */
    val jostlesAt: List<Double> = emptyList(),
    /** How long a jostle keeps the phone tipped, s (plus 0.25 s each way). */
    val jostleHeldS: Double = 0.5,
    /** Road joints (expansion seams): a short sharp up-first jolt on both axles that barely rocks the car. */
    val seamsAt: List<Double> = emptyList(),
    /** Length of the road, m. */
    val roadM: Double = 2000.0,
    /** The phone is picked up (tipped 70° in 0.3 s, shaken a little), held still for the given seconds and put back: (position, s). */
    val holdsAt: List<Pair<Double, Double>> = emptyList(),
    /** The phone feels 5 m/s² of braking for 1.2 s (it slides, or tips in a pocket) while the car keeps its speed. */
    val brakeSpikesAt: List<Double> = emptyList(),
    /** Sets the phone's own signals on every sample: (seconds driven, metres driven, the signals, sensor time ms). */
    val phoneSignals: ((Double, Double, PhoneSignals, Long) -> Unit)? = null,
)

class TripResult(
    val beepBumpIds: List<Long>,
    /** Real distance from the car to the bump's stored position at the moment of each beep. */
    val beepTrueDistM: List<Double>,
    val newBumps: Int,
    val knownHits: Int,
    val rejected: List<String>,
    val stats: TripStats,
    /** Snapshots of the engine's forward-direction learning (for debugging the shape tests). */
    val forwardTrace: List<String> = emptyList(),
    /** The driving monitor was in pocket mode at the end of the trip. */
    val pocketMode: Boolean = false,
    /** How the simulated driver drove, as measured by [DrivingMonitor]. */
    val driving: DrivingStats = DrivingStats(),
    /** Every warning as the engine handed it out (sound + group), in order. */
    val warnings: List<Warning> = emptyList(),
    /** "New spot recorded" ticks (rate-limited, one per new spot at most). */
    val ticks: Int = 0,
    /** The engine's phone state at the end of the trip. */
    val phone: PhoneStateDetector? = null,
)

/**
 * Drives a simulated car along a straight road and produces what the phone would see:
 * 50 Hz accelerometer readings (phone tilted in a dashboard holder, road noise, braking,
 * front- and rear-axle hits on bumps) and 1 Hz GPS fixes (±3 m noise, 0.8 s late).
 */
class Simulator(seed: Long) {
    private val rnd = SimRandom(seed)
    private fun gauss(sd: Double) = rnd.nextGaussian() * sd

    val lat0 = 30.0444      // Cairo
    val lon0 = 31.2357
    val roadLen = 2000.0

    /** Road position → lat/lon. Westbound lane is 10 m south of the eastbound one (divided road). */
    fun point(p: Double, westbound: Boolean): DoubleArray {
        val base = Geo.move(lat0, lon0, 90.0, p)
        return if (westbound) Geo.move(base[0], base[1], 180.0, 10.0) else base
    }

    private fun pulse(tau: Double, amp: Double): Double {
        val period = 0.1
        return when {
            tau < 0 -> 0.0
            tau < period -> amp * sin(PI * tau / period)                       // pushed up
            tau < 2 * period -> -0.7 * amp * sin(PI * (tau - period) / period)  // drops back down
            else -> 0.0
        }
    }

    /** Dip: the wheel drops (down) for 60 ms, then slams into the far edge (up). */
    private fun dipPulse(tau: Double, amp: Double): Double = when {
        tau < 0 -> 0.0
        tau < 0.06 -> -0.6 * amp * sin(PI * tau / 0.06)
        tau < 0.11 -> amp * sin(PI * (tau - 0.06) / 0.05)
        else -> 0.0
    }

    /** One rocking swing (rad/s) lasting [len] seconds. */
    private fun swing(tau: Double, peak: Double, len: Double): Double =
        if (tau < 0 || tau >= len) 0.0 else peak * sin(2 * PI * tau / len)

    private fun bumpAmp(speedMps: Double) = 2.0 + 0.2 * speedMps * 3.6   // 5 m/s² at 15 km/h

    /** Rotation matrix for yaw/pitch/roll (radians). Columns = phone axes expressed in car axes (fwd, left, up). */
    private fun rot(yaw: Double, pitch: Double, roll: Double): Array<DoubleArray> {
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        return arrayOf(
            doubleArrayOf(cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr),
            doubleArrayOf(sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr),
            doubleArrayOf(-sp, cp * sr, cp * cr),
        )
    }

    fun drive(
        store: BumpStore, spec: DriveSpec, cfg: EngineConfig = EngineConfig(), tripId: Long = 1,
        drivingCfg: DrivingConfig = DrivingConfig(),
        /** If given, every accelerometer sample, GPS fix and logged event is added here (for trace export). */
        recorder: MutableList<TraceSample>? = null,
        /** Shared-map spots and observation outbox (both off by default). */
        spotSource: SpotSource? = null,
        observationSink: ObservationSink? = null,
        /** Press "Mute last beep" right after every beep (at the next GPS fix, like the app's button would). */
        muteEveryBeep: Boolean = false,
    ): TripResult {
        val len = spec.roadM
        fun travel(p: Double) = if (spec.westbound) len - p else p   // road position ↔ distance travelled
        val bumps = spec.bumpsAt.map { travel(it) }
        // (position, speed the driver slows to)
        val slowFor = (spec.bumpsAt + spec.silentBumpsAt).map { Pair(travel(it), spec.bumpKmh / 3.6) } +
            spec.crawlAt.map { Pair(travel(it), 8 / 3.6) }
        val jolts = spec.oneOffJoltsAt.map { travel(it) }
        // (distance travelled, side: +1 right / -1 left, jolt size)
        @Suppress("DEPRECATION")
        val dips = (spec.dipsAt + spec.potholesAt).map { Triple(travel(it), 1.0, 7.0) } +
            spec.potholesLeftAt.map { Triple(travel(it), -1.0, 7.0) } +
            spec.smallDipsAt.map { Triple(travel(it), 1.0, 4.2) }
        val zones = spec.slowZonesAt.map { travel(it) }
        val handlings = (listOfNotNull(spec.handlingAt) + spec.moreHandlingsAt).map { travel(it) }

        var s = 0.0
        var v = 0.0
        var t = 0.0
        val beepIds = ArrayList<Long>()
        val beepTrue = ArrayList<Double>()
        val warnings = ArrayList<Warning>()
        var ticks = 0
        var newBumps = 0
        var knownHits = 0
        val rejected = ArrayList<String>()
        var mutePending = false

        var recTMs = 0L
        val sink: BumpStore = if (recorder == null) store else object : BumpStore by store {
            override fun logEvent(e: BumpEvent) {
                recorder.add(TraceSample.Event(recTMs, e.type, e.bumpId, e.peak, e.note))
                store.logEvent(e)
            }
        }
        var monitor: DrivingMonitor? = null
        val listener = object : EngineListener {
            override fun onNewBump(b: Bump) { newBumps++; monitor?.onBumpHit(b, v * 3.6) }
            override fun onNewSpotTick(b: Bump) { ticks++ }
            override fun onWarning(w: Warning) { warnings.add(w); super.onWarning(w) }
            override fun onKnownBumpHit(b: Bump) { knownHits++; monitor?.onBumpHit(b, v * 3.6) }
            override fun onBeep(b: Bump, distanceM: Double, speedKmh: Double) {
                val car = point(travel(s), spec.westbound)
                beepIds.add(b.id)
                if (muteEveryBeep) mutePending = true
                beepTrue.add(Geo.distance(car[0], car[1], b.lat, b.lon))
            }
            override fun onJoltRejected(peak: Double, reason: String) { rejected.add(reason) }
        }
        val engine = BumpEngine(cfg, sink, listener, { 1_700_000_000_000L + (t * 1000).toLong() }, tripId, spotSource, observationSink)
        monitor = DrivingMonitor(drivingCfg, engine) { type, lat, lon, kmh, value, note ->
            sink.logEvent(BumpEvent(1_700_000_000_000L + (t * 1000).toLong(), tripId, type, -1, lat, lon, kmh, Double.NaN, value, Double.NaN, Double.NaN, note))
        }
        val mon = monitor!!
        val brakes = spec.hardBrakesAt.map { travel(it) }
        val swerves = spec.swervesAt.map { travel(it) }
        val turns = spec.sharpTurnsAt.map { travel(it) }
        val twists = spec.pocketTwistsAt.map { travel(it) }
        val jostles = spec.jostlesAt.map { travel(it) }
        val seams = spec.seamsAt.map { travel(it) }
        val holds = spec.holdsAt.map { Pair(travel(it.first), it.second) }
        val spikes = spec.brakeSpikesAt.map { travel(it) }
        var holdStart = -1.0
        var holdS = 0.0
        var spikeStart = -1.0
        var prevTilt = 0.0
        var forceBrakeUntil = -1.0
        var swerveStart = -1.0
        var turnStart = -1.0
        var twistStart = -1.0
        var jostleStart = -1.0
        val seamHits = ArrayList<DoubleArray>()   // (time, amplitude)
        // The car's own heading change from swerves and turns (radians, left = positive): the GPS bearing follows it.
        var carYaw = 0.0

        val dt = 0.02
        val cruise = spec.cruiseKmh / 3.6
        val zoneSpeed = 25 / 3.6
        val maxDecel = 3.5
        val maxAccel = 1.5
        val yaw0 = degToRad(25.0)
        val pitch0 = degToRad(-65.0)
        val roll0 = degToRad(12.0)
        val gpsLag = 0.8

        val history = ArrayList<DoubleArray>()   // (t, s, v)
        val crossings = ArrayList<DoubleArray>() // (time, amplitude)
        val holeHits = ArrayList<DoubleArray>()  // (time, amplitude, side)
        var lastFixT = -1.0
        var handlingStart = -1.0
        val fwdTrace = ArrayList<String>()

        while (s < len) {
            // Driver: cruise at 50, brake (comfortably) for bumps and slow zones they can see.
            var target = cruise
            for ((sb, vb) in slowFor) {
                val ds = sb - s
                if (ds > -3 && ds < 150) target = min(target, sqrt(vb * vb + 2 * maxDecel * 0.8 * max(0.0, ds - 3)))
            }
            for (sz in zones) {
                val ds = sz - s
                if (ds > -60 && ds < 250) {
                    target = min(target, if (ds <= 60) zoneSpeed else sqrt(zoneSpeed * zoneSpeed + 2 * maxDecel * 0.8 * (ds - 60)))
                }
            }
            for (sb in brakes) if (s < sb && s + v * dt >= sb) forceBrakeUntil = t + 1.5
            for (sw in swerves) if (s < sw && s + v * dt >= sw) swerveStart = t
            for (st in turns) if (s < st && s + v * dt >= st) turnStart = t
            for (sp in twists) if (s < sp && s + v * dt >= sp) twistStart = t
            for (sj in jostles) if (s < sj && s + v * dt >= sj) jostleStart = t
            for ((sh, secs) in holds) if (s < sh && s + v * dt >= sh) { holdStart = t; holdS = secs }
            for (sk in spikes) if (s < sk && s + v * dt >= sk) spikeStart = t
            val vNew = when {
                t < forceBrakeUntil -> max(0.5, v - 6.5 * dt)
                target > v -> min(target, v + maxAccel * dt)
                else -> max(target, v - maxDecel * dt)
            }
            val aLong = (vNew - v) / dt
            val sNew = s + (v + vNew) / 2 * dt

            for (sb in bumps) {
                if (s < sb && sNew >= sb) crossings.add(doubleArrayOf(t, bumpAmp(vNew)))                  // front axle
                if (s < sb + 2.6 && sNew >= sb + 2.6) crossings.add(doubleArrayOf(t, 0.8 * bumpAmp(vNew))) // rear axle
            }
            for (sj in jolts) if (s < sj && sNew >= sj) crossings.add(doubleArrayOf(t, 6.0))
            for (ss in seams) {
                if (s < ss && sNew >= ss) seamHits.add(doubleArrayOf(t, 6.0))
                if (s < ss + 2.6 && sNew >= ss + 2.6) seamHits.add(doubleArrayOf(t, 5.0))
            }
            for ((sp, side, amp) in dips) {
                if (s < sp && sNew >= sp) holeHits.add(doubleArrayOf(t, amp, side))                    // front wheel
                if (s < sp + 2.6 && sNew >= sp + 2.6) holeHits.add(doubleArrayOf(t, 0.7 * amp, side))  // rear wheel, same side
            }
            for (sh in handlings) if (s < sh && sNew >= sh) handlingStart = t

            s = sNew
            v = vNew
            t += dt
            history.add(doubleArrayOf(t, s, v, carYaw))

            // Vertical: road noise + occasional rough patch + bump pulses.
            var av = gauss(0.35)
            if (rnd.nextDouble() < 0.002) av += if (rnd.nextBoolean()) 1.5 else -1.5
            for (c in crossings) av += pulse(t - c[0], c[1])
            for (c in holeHits) av += dipPulse(t - c[0], c[1])
            for (c in seamHits) av += pulse(t - c[0], c[1])

            // Rotation (car axes: roll about forward, pitch about left, yaw about up), rad/s.
            // Bumps tip the car nose-up/down (pitch); a dip under one wheel rocks it sideways (roll).
            // Positive roll (about the forward axis) = left side up, right side down: a right-wheel dip starts positive.
            var roll = gauss(0.02)
            var pitch = gauss(0.02)
            var yaw = gauss(0.01)
            var carTurn = 0.0
            if (swerveStart >= 0) {
                val k = t - swerveStart
                carTurn += when { k < 0.7 -> 0.28; k < 1.4 -> -0.28; else -> 0.0 }   // left, then right
            }
            if (turnStart >= 0) {
                val k = t - turnStart
                carTurn += when { k < 2.0 -> 0.45; k in 8.0..10.0 -> -0.45; else -> 0.0 }   // left, then back right
            }
            yaw += carTurn
            carYaw += carTurn * dt
            // The phone turning in a pocket: only the gyroscope (and the phone's axes) see it.
            var twist = 0.0
            var twistRate = 0.0
            if (twistStart >= 0) {
                val k = t - twistStart
                twistRate = if (k < 0.8) degToRad(50.0) / 0.8 else 0.0
                twist = degToRad(50.0) * min(1.0, k / 0.8)
            }
            val lateral = v * (yaw)   // sideways (to the left) force from turning
            for (c in crossings) pitch += swing(t - c[0], 0.05 * c[1], 0.2)
            for (c in holeHits) { roll += c[2] * swing(t - c[0], 0.08 * c[1], 0.15); pitch += swing(t - c[0], 0.015 * c[1], 0.15) }
            for (c in seamHits) pitch += swing(t - c[0], 0.005 * c[1], 0.1)   // a seam is too short to rock the car
            crossings.removeAll { t - it[0] > 0.5 }
            holeHits.removeAll { t - it[0] > 0.5 }
            seamHits.removeAll { t - it[0] > 0.5 }

            // Passenger picks the phone up: it rotates 70° and gets shaken, is held 5 s, then put back.
            var extraTilt = 0.0
            var shake = 0.0
            if (handlingStart >= 0) {
                val h = t - handlingStart
                extraTilt = degToRad(70.0) * when {
                    h < 0.6 -> h / 0.6
                    h < 5.0 -> 1.0
                    h < 5.6 -> 1.0 - (h - 5.0) / 0.6
                    else -> 0.0
                }
                if (h < 1.5 || (h > 5.0 && h < 6.0)) shake = 4.0
            }
            if (jostleStart >= 0) {
                val h = t - jostleStart
                val held = spec.jostleHeldS
                if (h < held + 0.5) extraTilt += degToRad(60.0) * when { h < 0.25 -> h / 0.25; h < 0.25 + held -> 1.0; else -> 1.0 - (h - 0.25 - held) / 0.25 }
                if (h < min(0.6, held + 0.5)) shake = 4.0
            }
            if (holdStart >= 0) {
                val h = t - holdStart
                if (h < holdS + 0.6) extraTilt += degToRad(70.0) * when { h < 0.3 -> h / 0.3; h < 0.3 + holdS -> 1.0; else -> 1.0 - (h - 0.3 - holdS) / 0.3 }
                if (h < 0.6 || (h > holdS + 0.2 && h < holdS + 0.8)) shake = max(shake, 2.0)
            }
            var spike = 0.0
            if (spikeStart >= 0 && t - spikeStart < 1.2) spike = -5.0 * minOf(1.0, (t - spikeStart) / 0.15, (1.2 - (t - spikeStart)) / 0.15)
            // The phone's own turning when it is tipped (the gyroscope feels it), about the axis the tilt turns around.
            val tiltRate = (extraTilt - prevTilt) / dt
            prevTilt = extraTilt

            // Specific force in car axes (fwd, left, up), then into the tilted phone's axes.
            val fv = doubleArrayOf(aLong + 0.3 * av + gauss(0.2) + spike, lateral + gauss(0.2), 9.81 + av)
            val r = rot(yaw0 + twist, pitch0 + extraTilt, roll0)   // pitch axis is horizontal → tilts the phone 70° relative to gravity
            val ax = r[0][0] * fv[0] + r[1][0] * fv[1] + r[2][0] * fv[2] + gauss(shake)
            val ay = r[0][1] * fv[0] + r[1][1] * fv[1] + r[2][1] * fv[2] + gauss(shake)
            val az = r[0][2] * fv[0] + r[1][2] * fv[1] + r[2][2] * fv[2] + gauss(shake)
            val tMs = (t * 1000).toLong()
            recTMs = tMs
            spec.phoneSignals?.invoke(t, s, engine.phone.signals, tMs)
            var rgx = Double.NaN; var rgy = Double.NaN; var rgz = Double.NaN
            if (spec.gyro) {
                val psi = yaw0 + twist
                val w = doubleArrayOf(roll - sin(psi) * tiltRate, pitch + cos(psi) * tiltRate, yaw + twistRate)
                val wx = r[0][0] * w[0] + r[1][0] * w[1] + r[2][0] * w[2] + gauss(shake * 0.3)
                val wy = r[0][1] * w[0] + r[1][1] * w[1] + r[2][1] * w[2] + gauss(shake * 0.3)
                val wz = r[0][2] * w[0] + r[1][2] * w[1] + r[2][2] * w[2] + gauss(shake * 0.3)
                engine.onGyro(tMs, wx, wy, wz)
                mon.onGyro(wx, wy, wz)
                rgx = wx; rgy = wy; rgz = wz
            }
            engine.onAccel(tMs, ax, ay, az)
            mon.onAccel(tMs, ax, ay, az)
            recorder?.add(TraceSample.Accel(tMs, ax, ay, az, rgx, rgy, rgz, engine.lastVertical))

            // GPS once a second, reporting where the car was 0.8 s ago, with ±3 m noise.
            if (t - lastFixT >= 1.0 - 1e-9) {
                lastFixT = t
                val past = history.lastOrNull { it[0] <= t - gpsLag } ?: history.first()
                val p = point(travel(past[1]), spec.westbound)
                val n1 = Geo.move(p[0], p[1], 0.0, gauss(3.0))
                val n2 = Geo.move(n1[0], n1[1], 90.0, gauss(3.0))
                val speed = max(0.0, past[2] + gauss(0.3))
                val bearing = if (past[2] > 1.0) ((if (spec.westbound) 270.0 else 90.0) - radToDeg(past[3]) + gauss(3.0) + 360) % 360 else Double.NaN
                recorder?.add(TraceSample.Gps(tMs, n2[0], n2[1], speed * 3.6, bearing, 5.0))
                engine.onFix(Fix(tMs, n2[0], n2[1], speed, bearing, 5.0))
                if (mutePending) { mutePending = false; engine.muteBump(engine.lastBeepedId) }
                mon.onFix(engine.lastFix!!)
                if (fwdTrace.size < 40 && (t < 12 || ((t + 0.5).toInt() % 5 == 0))) {
                    fwdTrace.add("t=${formatFixed(t, 0)} s=${formatFixed(s, 0)} v=${formatFixed(v, 1)} ${engine.forwardDebug}")
                }
            }
        }
        mon.finish()
        fwdTrace.add("monitor: ${mon.debug}")
        return TripResult(beepIds, beepTrue, newBumps, knownHits, rejected, engine.trip, fwdTrace, mon.pocketMode, mon.stats, warnings, ticks, engine.phone)
    }
}
