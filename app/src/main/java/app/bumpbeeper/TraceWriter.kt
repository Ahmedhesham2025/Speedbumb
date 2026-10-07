package app.bumpbeeper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Debug recording: every accelerometer sample (with the gyroscope next to it), every GPS fix and every
 * engine event of one drive, in one CSV file. About 10–15 MB per hour of driving.
 *
 * The file starts with a few `# key=value` comment lines (app version, device, placement), then the header.
 * Readers skip `#` lines and map columns by the header, so the header line itself never changes.
 *
 * Columns: t_s (seconds since start), type (accel | gps | event), the sensor values for that type,
 * and for events: event, bump_id, peak, note. gx/gy/gz stay empty until the first gyroscope reading. Load it in pandas and filter on `type`.
 * Extra event rows: `event=label` (note = what the driver tapped: bump, pothole_l, pothole_r, rough, undo)
 * and `event=battery` (peak = battery percent, every 5 minutes).
 */
class TraceWriter(dir: File, meta: List<String> = emptyList()) {
    val file: File
    private val out: BufferedWriter
    private var startMs = -1L
    private var lastT = 0L

    init {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        file = File(dir, "trace_$stamp.csv")
        out = BufferedWriter(FileWriter(file), 1 shl 16)
        for (line in meta) out.write("# ${line.replace('\n', ' ')}\n")
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
        eventRow(e.type, e.lat, e.lon, e.speedKmh, e.heading, if (e.bumpId >= 0) e.bumpId.toString() else "", e.peak, e.note)
    }

    /** What the driver tapped while driving (ground truth for replay tests). */
    @Synchronized fun label(kind: String, lat: Double, lon: Double, speedKmh: Double) {
        eventRow("label", lat, lon, speedKmh, Double.NaN, "", Double.NaN, kind)
        out.flush()   // a label is rare and precious: don't lose it if the app dies
    }

    /** Battery level in percent, so we can see what recording costs. */
    @Synchronized fun battery(percent: Int) {
        eventRow("battery", Double.NaN, Double.NaN, Double.NaN, Double.NaN, "", percent.toDouble(), "")
    }

    /** Battery saving switched on (car stopped) or off (moving again), to read next to the battery rows. */
    @Synchronized fun power(stopped: Boolean) {
        eventRow("power", Double.NaN, Double.NaN, Double.NaN, Double.NaN, "", Double.NaN, if (stopped) "stopped" else "moving")
    }

    private fun eventRow(type: String, lat: Double, lon: Double, kmh: Double, heading: Double, bumpId: String, peak: Double, note: String) {
        out.write(f(if (startMs < 0) 0.0 else (lastT - startMs) / 1000.0, 3)); out.write(",event,,,,,,,,")
        out.write(f(lat, 7)); out.write(","); out.write(f(lon, 7)); out.write(",")
        out.write(f(kmh, 1)); out.write(","); out.write(f(heading, 0)); out.write(",,")
        out.write(type); out.write(","); out.write(bumpId); out.write(",")
        out.write(f(peak, 2)); out.write(","); out.write(note.replace(',', ';').replace('\n', ' ')); out.write("\n")
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

        /** One file name for "all my recordings", e.g. recordings_2026-10-02_1430.zip. */
        fun zipName(now: Date): String = "recordings_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(now) + ".zip"

        /**
         * Packs [files] into one zip written to [out] (flat, one entry per file), so many recordings go to
         * Google Drive / WhatsApp as a single attachment. CSV text shrinks to about a fifth. Files that are gzip
         * already (research recordings) pass [level] = NO_COMPRESSION: squeezing them again only costs time.
         */
        fun zipTo(files: List<File>, out: OutputStream, level: Int = Deflater.DEFAULT_COMPRESSION) {
            val zip = ZipOutputStream(out)
            zip.setLevel(level)
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
            zip.finish()   // the caller closes [out]
        }

        /** The `# key=value` lines at the top of a recording. Nothing that identifies the user. */
        fun meta(ctx: Context): List<String> = listOf(
            "app_version=${appVersion(ctx)}",
            "device=${Build.MANUFACTURER}/${Build.MODEL}",
            "android=${Build.VERSION.SDK_INT}",
            "placement=${Prefs.placement(ctx)}",
            "gyro=${if (hasGyro(ctx)) "yes" else "none"}",
        )

        private fun hasGyro(ctx: Context): Boolean =
            (ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

        fun appVersion(ctx: Context): String = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}

/**
 * Passes everything to the real store, and copies each event into the debug recording (when there is one).
 * [trace] may be set later in the trip (label mode switched on while driving); only the engine thread touches it.
 */
class TracingStore(private val inner: BumpStore, @Volatile var trace: TraceWriter?) : BumpStore by inner {
    override fun logEvent(e: BumpEvent) {
        inner.logEvent(e)
        trace?.event(e)
    }
}
