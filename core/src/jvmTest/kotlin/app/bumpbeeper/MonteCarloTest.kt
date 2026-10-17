package app.bumpbeeper

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Robustness on the half-car ([HalfCar]): many crossings per case, each with its own hump size, crossing speed,
 * phone (50/100/200 Hz, noise, holder or pocket) and noise. Rates are pinned in tolerant bands: they catch a
 * regression, not a percent. MONTE_CARLO_N (a system property) sets the crossings per case; CI runs the default.
 */
class MonteCarloTest {
    private val n = System.getProperty("MONTE_CARLO_N")?.toIntOrNull() ?: 120
    private val car = HalfCar()

    /** The share of trials whose road features were each learned, and false spots (away from all of them) per trial. */
    private class Rate(val case: String, val trials: Int, val found: Double, val falsePerTrial: Double, val learned: List<BumpEvent>) {
        override fun toString() = "$case: found ${"%.2f".format(found)} false/trial ${"%.2f".format(falsePerTrial)} ($trials trials)"
    }

    private fun run(case: String, seed: Long, make: (Random) -> Pair<Crossing, PhoneVariant>): Rate {
        val rnd = Random(seed)
        var found = 0
        var falses = 0
        val all = ArrayList<BumpEvent>()
        repeat(n) {
            val (c, phone) = make(rnd)
            val samples = HalfCarDrive.samples(c, car, phone, rnd)
            val placement = DrivingConfig().apply { placement = if (phone.pocket) "pocket" else "mounted" }
            val learned = Replayer.replay(samples, MemoryStore(), drivingCfg = placement).events.filter { it.type == "new_bump" }
            all.addAll(learned)
            val near = c.featuresAt.map { f -> learned.any { Geo.distance(it.lat, it.lon, HalfCarDrive.LAT, HalfCarDrive.lonAt(f)) <= 20.0 } }
            if (near.all { it }) found++
            falses += learned.count { e -> c.featuresAt.none { f -> Geo.distance(e.lat, e.lon, HalfCarDrive.LAT, HalfCarDrive.lonAt(f)) <= 20.0 } }
        }
        return Rate(case, n, found.toDouble() / n, falses.toDouble() / n, all).also { println("MONTECARLO $it") }
    }

    private fun phone(rnd: Random, pocket: Boolean = rnd.nextInt(3) == 0) =
        PhoneVariant(listOf(50.0, 100.0, 200.0)[rnd.nextInt(3)], 0.15 + 0.35 * rnd.nextDouble(), pocket)

    private fun between(rnd: Random, lo: Double, hi: Double) = lo + (hi - lo) * rnd.nextDouble()

    @Test fun circularHumps() {
        val r = run("circular", 1) { rnd ->
            Crossing(HalfCarDrive.circular(250.0, between(rnd, 0.07, 0.15), between(rnd, 0.6, 1.2)), listOf(250.0), between(rnd, 8.0, 25.0)) to phone(rnd)
        }
        assertTrue(r.found >= 0.9 && r.falsePerTrial <= 0.1, "$r")
    }

    @Test fun sinusoidalHumps() {
        val r = run("sinusoidal", 2) { rnd ->
            Crossing(HalfCarDrive.sinus(250.0, between(rnd, 0.07, 0.15), between(rnd, 1.0, 4.0)), listOf(250.0), between(rnd, 8.0, 25.0)) to phone(rnd)
        }
        assertTrue(r.found >= 0.8 && r.falsePerTrial <= 0.1, "$r")
    }

    @Test fun trapezoidalHumps() {
        val r = run("trapezoidal", 3) { rnd ->
            val top = between(rnd, 1.5, 4.0)
            Crossing(HalfCarDrive.trapez(250.0, between(rnd, 0.07, 0.12), top + between(rnd, 0.6, 1.4), top), listOf(250.0), between(rnd, 8.0, 20.0)) to phone(rnd)
        }
        assertTrue(r.found >= 0.8 && r.falsePerTrial <= 0.1, "$r")
    }

    @Test fun rowsOfHumps() {
        val r = run("rows", 4) { rnd ->
            val count = 2 + rnd.nextInt(2)
            val gap = between(rnd, 6.0, 10.0)
            val at = List(count) { 250.0 + gap * it }
            Crossing(HalfCarDrive.sum(at.map { HalfCarDrive.circular(it, between(rnd, 0.08, 0.12), 0.8) }), at, between(rnd, 8.0, 18.0)) to phone(rnd)
        }
        assertTrue(r.found >= 0.8 && r.falsePerTrial <= 0.1, "$r")
    }

    @Test fun potholesInAHolder() {
        // Down first with the phone in a holder: rejected ("down_first"), or with too little pitch to tell (DOWN_WEAK)
        // at most a maybe. "found" is the false rate here; measured 0.26 (all DOWN_WEAK), 0.98 before the warm-up.
        val r = run("potholes (holder)", 5) { rnd ->
            Crossing(HalfCarDrive.pothole(250.0, between(rnd, 0.05, 0.08), between(rnd, 0.5, 1.0)), listOf(250.0), between(rnd, 20.0, 40.0), cruiseKmh = 40.0) to
                phone(rnd, pocket = false)
        }
        assertTrue(r.found <= 0.4, "$r")
        assertTrue(r.learned.all { "polarity=down_weak" in it.note }, "a pothole learned other than as a down-first maybe: $r")
    }

    @Test fun handlingIsNoBump() {
        // No road feature at all: the phone is picked up and knocked at a steady 30 km/h.
        val r = run("handling", 6) { rnd ->
            Crossing({ 0.0 }, emptyList(), 30.0, cruiseKmh = 30.0, handleAtM = between(rnd, 200.0, 300.0)) to phone(rnd)
        }
        assertTrue(r.falsePerTrial <= 0.1, "$r")
    }
}
