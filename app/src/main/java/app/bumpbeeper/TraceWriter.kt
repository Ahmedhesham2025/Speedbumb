package app.bumpbeeper

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug recording: every accelerometer sample (with the gyroscope next to it), every GPS fix and every
 * engine event of one drive, in one CSV file. About 10–15 MB per hour of driving.
 *
 * Columns: t_s (seconds since start), type (accel | gps | event), the sensor values for that type,
 * and for events: event, bump_id, peak, note. Load it in pandas and filter on `type`.
 */
class TraceWriter(dir: File) {
    val file: File
    private val out: BufferedWriter
    private var startMs = -1L
    private var lastT = 0L

    init {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        file = File(dir, "trace_$stamp.csv")
        out = BufferedWriter(FileWriter(file), 1 shl 16)
        out.write("t_s,type,ax,ay,az,gx,gy,gz,vertical,lat,lon,speed_kmh,bearing,accuracy_m,event,bump_id,peak,note\n")
    }

    private fun ts(tMs: Long): String {
        if (startMs < 0) startMs = tMs
        lastT = tMs
        return f((tMs - startMs) / 1000.0, 3)
    }

    @Synchronized fun accel(tMs: Long, ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double, vertical: Double) {
        out.write(ts(tMs)); out.write(",accel,")
        out.write(f(ax, 3)); out.write(","); out.write(f(ay, 3)); out.write(","); out.write(f(az, 3)); out.write(",")
        out.write(f(gx, 4)); out.write(","); out.write(f(gy, 4)); out.write(","); out.write(f(gz, 4)); out.write(",")
        out.write(f(vertical, 3)); out.write(",,,,,,,,,\n")
    }

    @Synchronized fun gps(tMs: Long, lat: Double, lon: Double, speedKmh: Double, bearing: Double, accuracyM: Double) {
        out.write(ts(tMs)); out.write(",gps,,,,,,,,")
        out.write(f(lat, 7)); out.write(","); out.write(f(lon, 7)); out.write(",")
        out.write(f(speedKmh, 1)); out.write(","); out.write(f(bearing, 0)); out.write(",")
        out.write(f(accuracyM, 1)); out.write(",,,,\n")
    }

    /** Engine events have no sensor clock of their own; they are stamped with the latest sample's time. */
    @Synchronized fun event(e: BumpEvent) {
        out.write(f(if (startMs < 0) 0.0 else (lastT - startMs) / 1000.0, 3)); out.write(",event,,,,,,,,")
        out.write(f(e.lat, 7)); out.write(","); out.write(f(e.lon, 7)); out.write(",")
        out.write(f(e.speedKmh, 1)); out.write(","); out.write(f(e.heading, 0)); out.write(",,")
        out.write(e.type); out.write(","); out.write(if (e.bumpId >= 0) e.bumpId.toString() else ""); out.write(",")
        out.write(f(e.peak, 2)); out.write(","); out.write(e.note.replace(',', ';')); out.write("\n")
    }

    @Synchronized fun close() {
        try { out.close() } catch (_: Exception) {}
    }

    private fun f(x: Double, digits: Int): String = if (x.isNaN()) "" else String.format(Locale.US, "%.${digits}f", x)

    companion object {
        /** Keep at most this many recordings; older ones are deleted when a new drive starts. */
        const val KEEP = 20

        fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "traces")

        fun list(ctx: Context): List<File> =
            (dir(ctx).listFiles { f -> f.name.endsWith(".csv") } ?: emptyArray()).sortedBy { it.name }

        fun prune(ctx: Context) {
            val files = list(ctx)
            if (files.size > KEEP) files.take(files.size - KEEP).forEach { it.delete() }
        }
    }
}

/** Passes everything to the real store, and copies each event into the debug recording. */
class TracingStore(private val inner: BumpStore, private val trace: TraceWriter) : BumpStore by inner {
    override fun logEvent(e: BumpEvent) {
        inner.logEvent(e)
        trace.event(e)
    }
}
