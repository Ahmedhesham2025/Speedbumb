package app.bumpbeeper

import kotlin.math.sqrt
import kotlin.test.Test

class ShapeDiagTest {
    @Test fun diag() {
        val rec = ArrayList<TraceSample>()
        val store = MemoryStore()
        Simulator(71).drive(store, DriveSpec(profileHumps = listOf(ProfileHump(900.0, "trapez", 0.10, 4.0, plateauM = 2.5)), bumpKmh = 15.0, cruiseKmh = 40.0, slowZonesAt = listOf(300.0)), recorder = rec)
        val ev = store.events.filter { it.type == "new_bump" || it.type == "rejected" }
        println("SDIAG events " + ev.map { it.type + " " + it.note })
        val acc = rec.filterIsInstance<TraceSample.Accel>()
        val big = acc.first { kotlin.math.abs(sqrt(it.ax * it.ax + it.ay * it.ay + it.az * it.az) - 9.81) > 3 }
        val sb = StringBuilder()
        for (a in acc) if (a.tMs in big.tMs - 400..big.tMs + 1600) sb.append(formatFixed(sqrt(a.ax * a.ax + a.ay * a.ay + a.az * a.az) - 9.81, 1)).append(' ')
        println("SDIAG trig=${big.tMs} " + sb)
        val gps = rec.filterIsInstance<TraceSample.Gps>().filter { it.tMs in big.tMs - 3000..big.tMs + 3000 }.map { formatFixed(it.speedKmh, 1) }
        println("SDIAG gps $gps")
    }
}
