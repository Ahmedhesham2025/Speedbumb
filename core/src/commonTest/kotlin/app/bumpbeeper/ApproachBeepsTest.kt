@file:Suppress("DEPRECATION")   // RemoteSpot still carries the shared map's old kind and side

package app.bumpbeeper

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Approach beeps after a bump's warning while the driver is still too fast, and the beeps of each sound. */
/** A point [p] m east along the test road. */
private fun at(p: Double) = Geo.move(30.0444, 31.2357, 90.0, p)

class ApproachBeepsTest {
    private fun spot(id: Long, p: Double, severity: Double = 7.0, devices: Int = 3) =
        at(p).let { RemoteSpot(id, it[0], it[1], 90.0, BumpKind.BUMP, Side.RIGHT, severity, devices) }

    /** A beep: sensor time, distance to the spot as the engine carried it, true distance. */
    private class Beep(val tMs: Long, val distM: Double, val trueM: Double)

    private class Run(spots: List<RemoteSpot>, val cfg: EngineConfig = EngineConfig()) {
        val warnings = ArrayList<Pair<Long, Warning>>()
        val beeps = ArrayList<Beep>()
        var t = 0L
        var p = 0.0
        val e = BumpEngine(cfg, MemoryStore(), object : EngineListener {
            override fun onWarning(w: Warning) { warnings.add(t to w) }
            override fun onApproachBeep(b: Bump, distanceM: Double, speedKmh: Double) {
                beeps.add(Beep(t, distanceM, Geo.distance(at(p)[0], at(p)[1], b.lat, b.lon)))
            }
        }, { 0L }, spotSource = ListSpotSource(spots))
        var mon: DrivingMonitor? = null

        /** Drive east to [to] m, at [mps] m/s from the speed [speedAt] gives for the position; 50 Hz sensors, 1 Hz GPS. */
        fun drive(to: Double, speedAt: (Double) -> Double) {
            while (p <= to) {
                val v = speedAt(p)
                if (t % 1000 == 0L) at(p).let {
                    e.onFix(Fix(t, it[0], it[1], v, 90.0, 5.0))
                    mon?.onFix(e.lastFix!!)
                }
                e.onAccel(t, 0.0, 0.0, 9.81)
                mon?.onAccel(t, 0.0, 0.0, 9.81)
                t += 20
                p += v * 0.02
            }
        }
    }

    /** Still at 50 km/h: beeps from 2.5 s after the warning, faster as the bump nears, none in the last 5 m or after. */
    @Test fun beepsFasterAsTheBumpNears() {
        val r = Run(listOf(spot(1, 1000.0)))
        r.drive(1100.0) { 14.0 }
        assertEquals(1, r.warnings.size)
        val (warnMs, w) = r.warnings[0]
        assertEquals(WarnSound.STRONG, w.sound)
        assertTrue(r.beeps.size >= 5, "beeps: ${r.beeps.size}")
        assertTrue(r.beeps.first().tMs - warnMs in 2500L..2520L, "first beep ${r.beeps.first().tMs - warnMs} ms after the warning")
        for (i in 1 until r.beeps.size) {
            val gap = r.beeps[i].tMs - r.beeps[i - 1].tMs
            val want = r.cfg.approachIntervalMs(r.beeps[i - 1].distM)
            assertTrue(abs(gap - want) <= 20, "beep $i: gap $gap ms, want $want ms at ${r.beeps[i - 1].distM} m")
            assertTrue(gap <= r.beeps[i - 1].tMs - (if (i > 1) r.beeps[i - 2].tMs else warnMs), "gaps never grow")
        }
        assertTrue(r.beeps.all { it.distM > 5.0 && it.trueM > 3.0 && abs(it.distM - it.trueM) < 15.0 }, "distances")
        assertTrue(r.beeps.last().tMs - r.beeps[r.beeps.size - 2].tMs <= 400, "fast at the end")
    }

    @Test fun intervalCurve() {
        val c = EngineConfig()
        assertEquals(1200L, c.approachIntervalMs(150.0))
        assertEquals(1200L, c.approachIntervalMs(100.0))
        assertEquals(725L, c.approachIntervalMs(55.0))
        assertEquals(250L, c.approachIntervalMs(10.0))
        assertEquals(250L, c.approachIntervalMs(2.0))
        val steep = EngineConfig().apply { approachCurve = 2.0 }
        assertTrue(steep.approachIntervalMs(55.0) < 725L, "a steeper curve speeds up sooner")
    }

    /** Slowing below 25 km/h ends the beeps for good, even if the driver speeds up again. */
    @Test fun stopsWhenTheDriverSlows() {
        val r = Run(listOf(spot(1, 1000.0)))
        var slowedMs = -1L
        r.drive(1100.0) { p -> if (p in 960.0..985.0) 5.0.also { if (slowedMs < 0) slowedMs = r.t } else 14.0 }
        assertTrue(r.beeps.isNotEmpty() && slowedMs > 0)
        assertTrue(r.beeps.all { it.tMs <= slowedMs + 1000 }, "a beep after slowing: ${r.beeps.map { it.tMs - slowedMs }}")
    }

    /** Warned below 25 km/h (but above the 20 km/h quiet speed): the warning, no approach beeps. */
    @Test fun noneWhenSlowEnough() {
        val r = Run(listOf(spot(1, 1000.0)))
        r.drive(1100.0) { 6.2 }
        assertEquals(1, r.warnings.size)
        assertEquals(0, r.beeps.size)
    }

    /** Every confirmed band gets them, a "maybe" spot never; switched off, none. */
    @Test fun bandsMaybesAndSwitch() {
        for ((sev, devices, want) in listOf(Triple(3.0, 3, true), Triple(4.2, 3, true), Triple(7.0, 1, false))) {
            val r = Run(listOf(spot(1, 1000.0, sev, devices)))
            r.drive(1100.0) { 14.0 }
            assertEquals(1, r.warnings.size)
            assertEquals(want, r.beeps.isNotEmpty(), "severity $sev, $devices phones: ${r.warnings[0].second.sound}")
        }
        val off = Run(listOf(spot(1, 1000.0)), EngineConfig().apply { approachBeeps = false })
        off.drive(1100.0) { 14.0 }
        assertEquals(0, off.beeps.size)
    }

    /** No spot ahead: speeding (over the monitor's limit) never makes an approach beep. */
    @Test fun noneForSpeeding() {
        val r = Run(emptyList())
        val mon = DrivingMonitor(DrivingConfig().apply { speedLimitKmh = 50.0 }, r.e) { _, _, _, _, _, _ -> }
        r.mon = mon
        r.drive(1500.0) { 25.0 }
        mon.finish()
        assertTrue(mon.stats.speedingExcess > 0, "90 km/h in a 50 zone is speeding")
        assertEquals(0, r.beeps.size)
        assertEquals(0, r.warnings.size)
    }

    /** At most one sequence per spot: beeped, then a second spot later gets its own. */
    @Test fun oneSequencePerSpot() {
        val r = Run(listOf(spot(1, 1000.0), spot(2, 1600.0)))
        r.drive(1700.0) { 14.0 }
        assertEquals(2, r.warnings.size)
        val split = r.warnings[1].first
        val first = r.beeps.filter { it.tMs < split }
        val second = r.beeps.filter { it.tMs >= split }
        assertTrue(first.isNotEmpty() && second.isNotEmpty())
        assertTrue(second.first().tMs - split >= 2500, "the second sequence waits for its own warning")
    }

    /** Mild one beep, moderate two, strong three and then the voice; a "maybe" one soft beep. */
    @Test fun strongIsThreeBeepsThenTheVoice() {
        assertEquals(listOf(1, 1, 2, 3), listOf(WarnSound.SOFT, WarnSound.MILD, WarnSound.MODERATE, WarnSound.STRONG).map { it.beeps })
        assertEquals(listOf(WarnSound.STRONG), WarnSound.entries.filter { it.voice })
    }
}
