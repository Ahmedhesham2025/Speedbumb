package app.bumpbeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** "Help improve detection": the engine hands judged jolt candidates, with their signal window, to a [JoltSampleSink]. */
class JoltSampleTest {
    private val lat0 = 30.0444
    private val lon0 = 31.2357

    /** A straight eastbound drive: 50 Hz accel (flat phone, gravity on z), 1 Hz GPS, jolts at the given seconds. */
    private fun drive(
        store: MemoryStore, samples: MutableList<JoltSample>?, seconds: Int, speedMps: Double,
        joltsAtS: List<Double> = emptyList(), spots: SpotSource? = null, before: (BumpEngine) -> Unit = {},
    ): BumpEngine {
        var tMs = 0L
        val sink = if (samples == null) JoltSampleSink.NONE else JoltSampleSink { samples.add(it) }
        val eng = BumpEngine(EngineConfig(), store, object : EngineListener {}, { 1_700_000_000_000L + tMs }, 1, spots, null, sink)
        before(eng)
        while (tMs <= seconds * 1000L) {
            val ts = tMs / 1000.0
            var z = 9.81
            for (j in joltsAtS) {
                val tau = ts - j
                if (tau in 0.0..0.1) z += 6.0 else if (tau > 0.1 && tau <= 0.2) z -= 4.0
            }
            eng.onAccel(tMs, 0.0, 0.0, z)
            if (tMs % 1000 == 0L) {
                val p = Geo.move(lat0, lon0, 90.0, speedMps * ts)
                eng.onFix(Fix(tMs, p[0], p[1], speedMps, 90.0, 5.0))
            }
            tMs += 20
        }
        return eng
    }

    private fun checkWindow(s: JoltSample) {
        val w = s.window
        assertEquals(50, w.rateHz)
        assertTrue("2..4 s: ${w.accel.size}", w.accel.size in 2 * w.rateHz..4 * w.rateHz + 1)
        assertTrue(w.preMs in 0..2000)
        assertTrue("trigger inside the window", w.preMs.toLong() * w.rateHz < 1000L * w.accel.size)
        assertTrue("no gyroscope → no gyro channels", w.gyroRoll == null && w.gyroPitch == null)
    }

    @Test fun learnedThenHitThenMiss() {
        val store = MemoryStore()
        val s1 = ArrayList<JoltSample>()
        drive(store, s1, 60, 8.33, listOf(20.0))
        val learned = s1.single()
        assertEquals("learned", learned.decision)
        assertNull(learned.sharedSpotId)
        assertEquals(30.0, learned.speedKmh, 1.0)
        assertTrue(learned.peak >= 3.0)
        assertNotNull(learned.classification)
        assertEquals(0.0, learned.headingChangeDeg, 1e-6)
        assertEquals(5.0, learned.gpsAccuracyM, 1e-6)
        checkWindow(learned)
        // The jolt (+6 m/s²) sits at the trigger: pre_ms into the window.
        val at = learned.window.preMs * learned.window.rateHz / 1000
        val near = (maxOf(0, at - 3)..minOf(learned.window.accel.size - 1, at + 6)).maxOf { abs(learned.window.accel[it].toInt()) }
        assertTrue("jolt at the trigger, got $near", near >= (5.0 / JoltWindow.ACCEL_SCALE).toInt())

        val s2 = ArrayList<JoltSample>()
        val eng2 = drive(store, s2, 60, 8.33, listOf(20.0))
        assertEquals(listOf("hit"), s2.map { it.decision })
        // Muting a spot felt on this trip gives a user_mute sample at once, around that hit.
        eng2.muteBump(store.saved.first().id)
        assertEquals(listOf("hit", "user_mute"), s2.map { it.decision })
        // Same moment (the hit), and by now the full 2 s after it is in the buffer.
        assertEquals(s2[0].window.preMs, s2[1].window.preMs)
        assertEquals(s2[0].window.accel.toList(), s2[1].window.accel.take(s2[0].window.accel.size))
        assertEquals(4 * 50 + 1, s2[1].window.accel.size)

        val s3 = ArrayList<JoltSample>()
        drive(store, s3, 60, 8.33)
        assertEquals(listOf("miss"), s3.map { it.decision })
        checkWindow(s3[0])
        assertEquals(4 * 50 + 1, s3[0].window.accel.size)   // a pass has the full ±2 s
    }

    @Test fun muteBeforeThePassWaitsForIt() {
        val store = MemoryStore()
        drive(store, null, 60, 8.33, listOf(20.0))
        val id = store.saved.first().id
        val s = ArrayList<JoltSample>()
        drive(store, s, 60, 8.33) { it.muteBump(id) }
        assertEquals(listOf("miss", "user_mute"), s.map { it.decision })
    }

    @Test fun rejectedJoltHasItsReason() {
        val s = ArrayList<JoltSample>()
        drive(MemoryStore(), s, 20, 0.4, listOf(10.0))
        val r = s.single()
        assertEquals("rejected", r.decision)
        assertEquals("too_slow", r.reason)
        assertNull(r.classification)
        assertTrue(r.shapeScore.isNaN())
        checkWindow(r)
    }

    @Test fun sharedSpotCarriesItsServerIdOnly() {
        val p = Geo.move(lat0, lon0, 90.0, 8.33 * 20.0)
        val spots = object : SpotSource {
            override fun spotsNear(lat: Double, lon: Double, radiusM: Double) =
                listOf(RemoteSpot(4242, p[0], p[1], 90.0, BumpKind.BUMP, Side.UNKNOWN, 5.0, 3))
        }
        val s = ArrayList<JoltSample>()
        drive(MemoryStore(), s, 60, 8.33, listOf(20.0), spots)
        assertEquals(4242L, s.single().sharedSpotId)
        // A clean pass over the shared spot at speed: pass_clear with its id.
        val s2 = ArrayList<JoltSample>()
        drive(MemoryStore(), s2, 60, 8.33, spots = spots)
        assertEquals(listOf("pass_clear"), s2.map { it.decision })
        assertEquals(4242L, s2[0].sharedSpotId)
    }

    @Test fun noSinkChangesNothing() {
        val a = MemoryStore()
        val b = MemoryStore()
        drive(a, null, 60, 8.33, listOf(20.0, 40.0))
        drive(b, ArrayList(), 60, 8.33, listOf(20.0, 40.0))
        assertEquals(a.events.map { it.type + it.note }, b.events.map { it.type + it.note })
        assertEquals(a.saved.size, b.saved.size)
    }

    @Test fun ringEncodesAndClamps() {
        assertEquals(250.toShort(), JoltWindow.encode(1.0, JoltWindow.ACCEL_SCALE))
        assertEquals(Short.MAX_VALUE, JoltWindow.encode(500.0, JoltWindow.ACCEL_SCALE))
        assertEquals(Short.MIN_VALUE, JoltWindow.encode(-500.0, JoltWindow.ACCEL_SCALE))
        assertEquals(1500.toShort(), JoltWindow.encode(1.5, JoltWindow.GYRO_SCALE))

        val r = WindowRing()
        for (t in 0..6000 step 20) r.add(t.toLong(), t / 1000.0, 0.1, -0.2)
        val w = r.window(3000)!!
        assertEquals(50, w.rateHz)
        assertEquals(2000, w.preMs)
        assertEquals(201, w.accel.size)
        assertEquals((3.0 / JoltWindow.ACCEL_SCALE).toInt().toShort(), w.accel[100])
        assertEquals(100.toShort(), w.gyroRoll!![0])
        assertEquals((-200).toShort(), w.gyroPitch!![200])
        // Near the start of the buffer there is less before the trigger.
        val early = r.window(500)!!
        assertEquals(500, early.preMs)
        assertEquals(126, early.accel.size)
        // Less than 2 s of signal around it: no window.
        assertNull(WindowRing().apply { for (t in 0..1500 step 20) add(t.toLong(), 0.0, 0.0, 0.0) }.window(700))
        // Any sample without gyro → no gyro channels.
        val g = WindowRing().apply { for (t in 0..5000 step 20) add(t.toLong(), 0.0, if (t == 2500) Double.NaN else 0.0, 0.0) }
        assertNull(g.window(2500)!!.gyroRoll)
    }
}
