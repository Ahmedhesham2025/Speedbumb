package app.bumpbeeper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Random

/**
 * A more realistic test generator than [Simulator]: a half-car (body heave and pitch on two suspensions, two unsprung
 * axles on tyres) driving one straight approach over a road profile, felt by a phone at a given sample rate and noise,
 * in a holder (on the dashboard, flat, top forward) or in a pocket (near the seat, swaying). Units: m, s, kg, N.
 */
class HalfCar(
    val bodyKg: Double = 1300.0,
    val pitchKgM2: Double = 2000.0,
    /** CG to the front axle and to the rear axle, m (the wheelbase is their sum). */
    val frontM: Double = 1.2,
    val rearM: Double = 1.4,
    val springNpm: Double = 40_000.0,
    val damperNspm: Double = 3_000.0,
    val axleKg: Double = 80.0,
    val tyreNpm: Double = 400_000.0,
)

/** How the phone feels the car. */
class PhoneVariant(
    val rateHz: Double = 100.0,
    /** Accelerometer noise, m/s² (per sample); the gyroscope gets a tenth of it, rad/s. */
    val noise: Double = 0.3,
    val pocket: Boolean = false,
) {
    /** Where the phone sits ahead of the CG, m: on the dashboard, or in a pocket by the seat. */
    val atM: Double get() = if (pocket) -0.3 else 1.0
    override fun toString() = "${rateHz.toInt()} Hz, noise $noise, ${if (pocket) "pocket" else "holder"}"
}

/** One drive: the road profile (height at a distance, m), where the driver slows down, and an optional handling. */
class Crossing(
    val road: (Double) -> Double,
    /** The road features, m from the start, and the speed the driver crosses them at, km/h. */
    val featuresAt: List<Double>,
    val crossKmh: Double,
    val cruiseKmh: Double = 35.0,
    val lengthM: Double = 400.0,
    /** A passenger picks the phone up at this distance (tilts it 70° for 4 s, knocking it once): null = never. */
    val handleAtM: Double? = null,
)

object HalfCarDrive {
    private const val LAT0 = 30.0444
    private const val LON0 = 31.2357
    private const val DT = 0.0005
    private const val GPS_LAG_S = 0.8

    /** The phone's samples and the GPS fixes for [c] in [car] with [phone]; [rnd] makes noise and GPS errors. */
    fun samples(c: Crossing, car: HalfCar, phone: PhoneVariant, rnd: Random): List<TraceSample> {
        val out = ArrayList<TraceSample>()
        val wb = car.frontM + car.rearM
        // States: body heave z, pitch th (nose up), axle heights wf, wr; their rates.
        var z = 0.0; var vz = 0.0; var th = 0.0; var vth = 0.0
        var wf = 0.0; var vwf = 0.0; var wr = 0.0; var vwr = 0.0
        var s = 0.0; var v = 0.0; var t = 0.0
        var nextSample = 0.0; var nextFix = 1.0
        val history = ArrayList<DoubleArray>()   // (t, s, v) for the lagged GPS
        var handleStart = -1.0
        fun road(x: Double) = (-2..2).sumOf { c.road(x + it * 0.0375) } / 5   // the tyre's contact patch
        while (s < c.lengthM) {
            // The driver: up to cruise, down to the crossing speed 3 m before each feature, then back up.
            var target = c.cruiseKmh / 3.6
            for (f in c.featuresAt) {
                val ds = f - s
                if (ds > -wb - 1 && ds < 120) target = min(target, sqrt((c.crossKmh / 3.6).let { it * it } + 2 * 2.5 * max(0.0, ds - 3)))
            }
            val aLong = if (target > v) min(1.5, (target - v) / DT) else max(-3.0, (target - v) / DT)
            v = max(0.0, v + aLong * DT)
            s += v * DT
            t += DT
            if (c.handleAtM != null && handleStart < 0 && s >= c.handleAtM) handleStart = t
            // The half-car.
            val hf = road(s); val hr = road(s - wb)
            val zf = z + car.frontM * th; val zr = z - car.rearM * th
            val vzf = vz + car.frontM * vth; val vzr = vz - car.rearM * vth
            val ff = car.springNpm * (wf - zf) + car.damperNspm * (vwf - vzf)
            val fr = car.springNpm * (wr - zr) + car.damperNspm * (vwr - vzr)
            val az = (ff + fr) / car.bodyKg
            val ath = (car.frontM * ff - car.rearM * fr) / car.pitchKgM2
            val awf = (car.tyreNpm * (hf - wf) - ff) / car.axleKg
            val awr = (car.tyreNpm * (hr - wr) - fr) / car.axleKg
            vz += az * DT; z += vz * DT; vth += ath * DT; th += vth * DT
            vwf += awf * DT; wf += vwf * DT; vwr += awr * DT; wr += vwr * DT
            if (t >= nextSample) {
                nextSample += 1.0 / phone.rateHz
                history.add(doubleArrayOf(t, s, v))
                var up = 9.81 + az + phone.atM * ath + rnd.nextGaussian() * phone.noise
                var fwd = aLong + rnd.nextGaussian() * phone.noise
                var side = rnd.nextGaussian() * phone.noise
                var gx = vth + rnd.nextGaussian() * phone.noise * 0.1
                var gy = rnd.nextGaussian() * phone.noise * 0.1
                var gz = rnd.nextGaussian() * phone.noise * 0.1
                if (phone.pocket) {   // a leg sways it
                    side += 0.5 * sin(2 * PI * 0.7 * t); gy += 0.08 * cos(2 * PI * 0.7 * t)
                }
                if (handleStart >= 0) {
                    val h = t - handleStart
                    val tilt = degToRad(70.0) * when { h < 0.4 -> h / 0.4; h < 4.4 -> 1.0; h < 4.8 -> 1 - (h - 4.4) / 0.4; else -> 0.0 }
                    val rate = degToRad(70.0) / 0.4 * when { h < 0.4 -> 1.0; h in 4.4..4.8 -> -1.0; else -> 0.0 }
                    if (h in 1.0..1.1) up += 6.0 * sin(PI * (h - 1.0) / 0.1)   // a knock
                    val f2 = fwd * cos(tilt) - up * sin(tilt)
                    up = fwd * sin(tilt) + up * cos(tilt)
                    fwd = f2
                    gx += rate
                }
                // Phone axes: x right, y forward (top), z up.
                out.add(TraceSample.Accel((t * 1000).toLong(), side, fwd, up, gx, gy, gz))
            }
            if (t >= nextFix) {
                nextFix += 1.0
                val then = history.lastOrNull { it[0] <= t - GPS_LAG_S } ?: history.first()
                val x = then[1] + rnd.nextGaussian() * 2.0
                out.add(
                    TraceSample.Gps(
                        (t * 1000).toLong(), LAT0 + rnd.nextGaussian() * 1.5e-5, LON0 + x / (111_320.0 * cos(degToRad(LAT0))),
                        max(0.0, then[2] * 3.6 + rnd.nextGaussian() * 0.3), 90.0, 4.0,
                    )
                )
            }
        }
        return out
    }

    /** Where [m] metres along the road is (east of the start), for matching the engine's spots. */
    fun lonAt(m: Double) = LON0 + m / (111_320.0 * cos(degToRad(LAT0)))
    const val LAT = LAT0

    // Road profiles: [at] m from the start.
    fun circular(at: Double, h: Double, l: Double): (Double) -> Double = { x -> val u = (x - at) / l; if (u <= 0 || u >= 1) 0.0 else h * (1 - (2 * u - 1).let { it * it }) }
    fun sinus(at: Double, h: Double, l: Double): (Double) -> Double = { x -> val u = (x - at) / l; if (u <= 0 || u >= 1) 0.0 else h * (1 - cos(2 * PI * u)) / 2 }
    fun trapez(at: Double, h: Double, l: Double, top: Double): (Double) -> Double = { x ->
        val u = x - at; val ramp = (l - top) / 2
        if (u <= 0 || u >= l) 0.0 else h * minOf(1.0, u / ramp, (l - u) / ramp)
    }
    fun pothole(at: Double, depth: Double, width: Double): (Double) -> Double = { x -> -sinus(at, depth, width)(x) }
    fun sum(parts: List<(Double) -> Double>): (Double) -> Double = { x -> parts.sumOf { it(x) } }
}
