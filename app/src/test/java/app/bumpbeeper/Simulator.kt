package app.bumpbeeper

import java.util.Random
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
    /** One-off jolts that are NOT bumps (a pothole you hit once, something dropped). Felt only on this drive. */
    val oneOffJoltsAt: List<Double> = emptyList(),
    /** Places the driver slows to 25 km/h (junction, traffic). */
    val slowZonesAt: List<Double> = emptyList(),
    /** Where a passenger picks the phone up out of the holder and shakes it around. */
    val handlingAt: Double? = null,
    /** Spots where the driver slows to bump speed but feels nothing (the bump was removed). */
    val silentBumpsAt: List<Double> = emptyList(),
    /** Bumps the driver crawls over at 8 km/h, so gently that nothing is felt. */
    val crawlAt: List<Double> = emptyList(),
    /** Speed the driver slows to for bumps. */
    val bumpKmh: Double = 15.0,
)

class TripResult(
    val beepBumpIds: List<Long>,
    /** Real distance from the car to the bump's stored position at the moment of each beep. */
    val beepTrueDistM: List<Double>,
    val newBumps: Int,
    val knownHits: Int,
    val rejected: List<String>,
    val stats: TripStats,
)

/**
 * Drives a simulated car along a straight road and produces what the phone would see:
 * 50 Hz accelerometer readings (phone tilted in a dashboard holder, road noise, braking,
 * front- and rear-axle hits on bumps) and 1 Hz GPS fixes (±3 m noise, 0.8 s late).
 */
class Simulator(seed: Long) {
    private val rnd = Random(seed)
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

    fun drive(store: BumpStore, spec: DriveSpec, cfg: EngineConfig = EngineConfig(), tripId: Long = 1): TripResult {
        val len = roadLen
        fun travel(p: Double) = if (spec.westbound) len - p else p   // road position ↔ distance travelled
        val bumps = spec.bumpsAt.map { travel(it) }
        // (position, speed the driver slows to)
        val slowFor = (spec.bumpsAt + spec.silentBumpsAt).map { Pair(travel(it), spec.bumpKmh / 3.6) } +
            spec.crawlAt.map { Pair(travel(it), 8 / 3.6) }
        val jolts = spec.oneOffJoltsAt.map { travel(it) }
        val zones = spec.slowZonesAt.map { travel(it) }
        val handling = spec.handlingAt?.let { travel(it) }

        var s = 0.0
        var v = 0.0
        var t = 0.0
        val beepIds = ArrayList<Long>()
        val beepTrue = ArrayList<Double>()
        var newBumps = 0
        var knownHits = 0
        val rejected = ArrayList<String>()

        val listener = object : EngineListener {
            override fun onNewBump(b: Bump) { newBumps++ }
            override fun onKnownBumpHit(b: Bump) { knownHits++ }
            override fun onBeep(b: Bump, distanceM: Double, speedKmh: Double) {
                val car = point(travel(s), spec.westbound)
                beepIds.add(b.id)
                beepTrue.add(Geo.distance(car[0], car[1], b.lat, b.lon))
            }
            override fun onJoltRejected(peak: Double, reason: String) { rejected.add(reason) }
        }
        val engine = BumpEngine(cfg, store, listener, { 1_700_000_000_000L + (t * 1000).toLong() }, tripId)

        val dt = 0.02
        val cruise = 50 / 3.6
        val zoneSpeed = 25 / 3.6
        val maxDecel = 3.5
        val maxAccel = 1.5
        val yaw0 = Math.toRadians(25.0)
        val pitch0 = Math.toRadians(-65.0)
        val roll0 = Math.toRadians(12.0)
        val gpsLag = 0.8

        val history = ArrayList<DoubleArray>()   // (t, s, v)
        val crossings = ArrayList<DoubleArray>() // (time, amplitude)
        var lastFixT = -1.0
        var handlingStart = -1.0

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
            val vNew = if (target > v) min(target, v + maxAccel * dt) else max(target, v - maxDecel * dt)
            val aLong = (vNew - v) / dt
            val sNew = s + (v + vNew) / 2 * dt

            for (sb in bumps) {
                if (s < sb && sNew >= sb) crossings.add(doubleArrayOf(t, bumpAmp(vNew)))                  // front axle
                if (s < sb + 2.6 && sNew >= sb + 2.6) crossings.add(doubleArrayOf(t, 0.8 * bumpAmp(vNew))) // rear axle
            }
            for (sj in jolts) if (s < sj && sNew >= sj) crossings.add(doubleArrayOf(t, 6.0))
            if (handling != null && handlingStart < 0 && s < handling && sNew >= handling) handlingStart = t

            s = sNew
            v = vNew
            t += dt
            history.add(doubleArrayOf(t, s, v))

            // Vertical: road noise + occasional rough patch + bump pulses.
            var av = gauss(0.35)
            if (rnd.nextDouble() < 0.002) av += if (rnd.nextBoolean()) 1.5 else -1.5
            for (c in crossings) av += pulse(t - c[0], c[1])
            crossings.removeAll { t - it[0] > 0.5 }

            // Passenger picks the phone up: it rotates 70° and gets shaken, is held 5 s, then put back.
            var extraTilt = 0.0
            var shake = 0.0
            if (handlingStart >= 0) {
                val h = t - handlingStart
                extraTilt = Math.toRadians(70.0) * when {
                    h < 0.6 -> h / 0.6
                    h < 5.0 -> 1.0
                    h < 5.6 -> 1.0 - (h - 5.0) / 0.6
                    else -> 0.0
                }
                if (h < 1.5 || (h > 5.0 && h < 6.0)) shake = 4.0
            }

            // Specific force in car axes (fwd, left, up), then into the tilted phone's axes.
            val fv = doubleArrayOf(aLong + 0.3 * av + gauss(0.2), gauss(0.2), 9.81 + av)
            val r = rot(yaw0, pitch0 + extraTilt, roll0)   // pitch axis is horizontal → tilts the phone 70° relative to gravity
            val ax = r[0][0] * fv[0] + r[1][0] * fv[1] + r[2][0] * fv[2] + gauss(shake)
            val ay = r[0][1] * fv[0] + r[1][1] * fv[1] + r[2][1] * fv[2] + gauss(shake)
            val az = r[0][2] * fv[0] + r[1][2] * fv[1] + r[2][2] * fv[2] + gauss(shake)
            val tMs = (t * 1000).toLong()
            engine.onAccel(tMs, ax, ay, az)

            // GPS once a second, reporting where the car was 0.8 s ago, with ±3 m noise.
            if (t - lastFixT >= 1.0 - 1e-9) {
                lastFixT = t
                val past = history.lastOrNull { it[0] <= t - gpsLag } ?: history.first()
                val p = point(travel(past[1]), spec.westbound)
                val n1 = Geo.move(p[0], p[1], 0.0, gauss(3.0))
                val n2 = Geo.move(n1[0], n1[1], 90.0, gauss(3.0))
                val speed = max(0.0, past[2] + gauss(0.3))
                val bearing = if (past[2] > 1.0) ((if (spec.westbound) 270.0 else 90.0) + gauss(3.0) + 360) % 360 else Double.NaN
                engine.onFix(Fix(tMs, n2[0], n2[1], speed, bearing, 5.0))
            }
        }
        return TripResult(beepIds, beepTrue, newBumps, knownHits, rejected, engine.trip)
    }
}
