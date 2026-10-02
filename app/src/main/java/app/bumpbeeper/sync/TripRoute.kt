package app.bumpbeeper.sync

import app.bumpbeeper.Fix
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * One trip's GPS fixes (the ones the engine gets), kept for the speed-limit lookup after the trip (opt-in only).
 * Primitive arrays, 32 bytes a fix, at most [cap] fixes (5 h at 1 Hz, under 600 KB); later fixes are dropped.
 * A trip longer than that keeps only its first 5 h: its real end (often home) is then not a privacy-zone anchor, but
 * those later fixes are neither stored nor sent, so nothing near it leaves the phone.
 * Engine thread only.
 */
class TripRoute(private val cap: Int = MAX_FIXES) {
    private var t = LongArray(0)
    private var lat = DoubleArray(0)
    private var lon = DoubleArray(0)
    private var speed = FloatArray(0)
    private var acc = FloatArray(0)
    var size = 0
        private set

    fun add(f: Fix) {
        if (size >= cap) return
        if (size == t.size) {
            val n = minOf(cap, maxOf(256, t.size * 2))
            t = t.copyOf(n); lat = lat.copyOf(n); lon = lon.copyOf(n); speed = speed.copyOf(n); acc = acc.copyOf(n)
        }
        t[size] = f.timeMs; lat[size] = f.lat; lon[size] = f.lon
        speed[size] = f.speedMps.toFloat(); acc[size] = f.accuracyM.toFloat()
        size++
    }

    /** The fixes again (no bearing: the lookup doesn't use it). */
    fun fixes(): List<Fix> = List(size) { Fix(t[it], lat[it], lon[it], speed[it].toDouble(), Double.NaN, acc[it].toDouble()) }

    fun write(out: DataOutputStream) {
        out.writeInt(size)
        for (i in 0 until size) {
            out.writeLong(t[i]); out.writeDouble(lat[i]); out.writeDouble(lon[i]); out.writeFloat(speed[i]); out.writeFloat(acc[i])
        }
    }

    companion object {
        const val MAX_FIXES = 5 * 3600

        fun read(inp: DataInputStream): TripRoute {
            val n = inp.readInt()
            if (n !in 0..MAX_FIXES) throw IOException("bad route size")
            val r = TripRoute()
            repeat(n) {
                r.add(Fix(inp.readLong(), inp.readDouble(), inp.readDouble(), inp.readFloat().toDouble(), Double.NaN, inp.readFloat().toDouble()))
            }
            return r
        }
    }
}
