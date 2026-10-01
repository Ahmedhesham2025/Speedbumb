package app.bumpbeeper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Your bumps on a simple map and in a list: open one in Google Maps, mute it, fix its type, delete it, share or import. */
class BumpsActivity : Activity() {

    private lateinit var summary: TextView
    private lateinit var map: BumpMapView
    private lateinit var list: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var bumps: List<Bump> = emptyList()
    private val cfg = EngineConfig()
    private var firstLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ---------------------------------------------------------------- layout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(sizeSp: Float, bold: Boolean = false, dim: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (dim) alpha = 0.72f
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(4)
                if (i < views.size - 1) marginEnd = dp(4)
            })
        }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        fun add(v: View, topDp: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
            col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(topDp) })
        }

        add(label(24f, bold = true).apply { text = "Your bumps" })
        summary = label(14f, dim = true)
        add(summary, 4)

        map = BumpMapView(this).apply { onSelect = { showActions(it) } }
        add(map, 12, dp(320))
        add(row(
            button("−") { map.radiusM *= 2 },
            button("+") { map.radiusM /= 2 },
            button("Me") { centerOnMe() },
        ), 6)
        add(label(12f, dim = true).apply {
            text = "● orange = speed bump · ● red = pothole · ● grey = not sure yet · ○ hollow = muted. " +
                "The tick shows the direction it warns for. Drag to move, tap a dot for options."
        }, 2)

        add(row(button("Share map") { shareMap() }, button("Import map") { pickImport() }), 14)
        add(label(12f, dim = true).apply {
            text = "Share sends your bumps file (WhatsApp, email…). On the other phone: Import map and pick that file. " +
                "Bumps already on that phone's map are kept as they are."
        }, 2)

        add(label(12f, bold = true, dim = true).apply { text = "NEAREST FIRST" }, 18)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(list, 4)

        return ScrollView(this).apply { addView(col) }
    }

    // ---------------------------------------------------------------- data

    private fun reload() {
        Thread {
            val db = BumpDb(applicationContext)
            val loaded = try { db.loadBumps() } finally { db.close() }
            ui.post { show(loaded) }
        }.start()
    }

    private fun lastLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun show(loaded: List<Bump>) {
        bumps = loaded
        val me = lastLocation()
        map.meLat = me?.latitude ?: Double.NaN
        map.meLon = me?.longitude ?: Double.NaN
        if (firstLoad) {
            firstLoad = false
            if (me != null) {
                map.centerLat = me.latitude; map.centerLon = me.longitude
            } else if (loaded.isNotEmpty()) {
                map.centerLat = loaded.map { it.lat }.average(); map.centerLon = loaded.map { it.lon }.average()
            }
            // Zoom so the nearest few bumps are visible.
            val near = loaded.map { Geo.distance(map.centerLat, map.centerLon, it.lat, it.lon) }.sorted()
            map.radiusM = if (near.isEmpty()) 1000.0 else (near[minOf(4, near.size - 1)] * 1.3).coerceAtLeast(200.0)
        }
        map.bumps = loaded

        val nBump = loaded.count { it.kind == BumpKind.BUMP }
        val nHole = loaded.count { it.kind == BumpKind.POTHOLE }
        val nUnsure = loaded.count { it.kind == BumpKind.UNSURE }
        val nMuted = loaded.count { it.isMuted(cfg) }
        summary.text = "${loaded.size} on the map: $nBump speed bumps, $nHole potholes, $nUnsure not sure yet · $nMuted muted"

        list.removeAllViews()
        val refLat = me?.latitude ?: map.centerLat
        val refLon = me?.longitude ?: map.centerLon
        val sorted = loaded.sortedBy { Geo.distance(refLat, refLon, it.lat, it.lon) }
        for (b in sorted.take(300)) {
            val d = Geo.distance(refLat, refLon, b.lat, b.lon)
            val dir = compass(Geo.bearing(refLat, refLon, b.lat, b.lon))
            val dist = if (d >= 1000) String.format(Locale.US, "%.1f km", d / 1000) else String.format(Locale.US, "%.0f m", d)
            val muted = if (b.isMuted(cfg)) " · muted" else ""
            list.addView(label(15f).apply {
                text = "#${b.id}  ${b.kind.label} · $dist $dir · felt ${b.hits}/${b.passes}$muted"
                setTextColor(map.colorOf(b))
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    map.centerLat = b.lat; map.centerLon = b.lon; map.selectedId = b.id
                    map.radiusM = minOf(map.radiusM, 400.0)
                    showActions(b)
                }
            })
        }
        if (sorted.size > 300) list.addView(label(13f, dim = true).apply { text = "…and ${sorted.size - 300} more (export to see all)" })
    }

    private fun compass(deg: Double): String =
        arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

    private fun centerOnMe() {
        val me = lastLocation()
        if (me == null) { toast("Location not known yet"); return }
        map.meLat = me.latitude; map.meLon = me.longitude
        map.centerLat = me.latitude; map.centerLon = me.longitude
        map.invalidate()
    }

    // ---------------------------------------------------------------- one bump

    private fun showActions(b: Bump) {
        val date = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(b.firstSeen))
        val info = String.format(
            Locale.US, "%s · felt %d of %d passes · first seen %s\nPothole score %.2f from %d hits (−1 bump … +1 pothole)",
            b.kind.label, b.hits, b.passes, date, b.kindScore, b.kindVotes,
        )
        val actions = arrayOf(
            "Open in Google Maps",
            if (b.userMuted) "Unmute" else "Mute (never warn here)",
            "It's a speed bump",
            "It's a pothole",
            "Delete",
        )
        AlertDialog.Builder(this)
            .setTitle("#${b.id}: $info")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openInMaps(b)
                    1 -> edit(b) { it.userMuted = !it.userMuted }
                    2 -> edit(b) { it.kindScore = -1.0; it.kindVotes = maxOf(it.kindVotes, 10) }
                    3 -> edit(b) { it.kindScore = 1.0; it.kindVotes = maxOf(it.kindVotes, 10) }
                    4 -> confirmDelete(b)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openInMaps(b: Bump) {
        val label = Uri.encode("Bump Beeper #${b.id} (${b.kind.label})")
        val uri = Uri.parse(String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(%s)", b.lat, b.lon, b.lat, b.lon, label))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            toast("No maps app found")
        }
    }

    /** Changes can't be made while recording: the running trip has its own copy of the map. */
    private fun editable(): Boolean {
        if (LiveState.recording) toast("Stop recording first, then edit the map")
        return !LiveState.recording
    }

    private fun edit(b: Bump, change: (Bump) -> Unit) {
        if (!editable()) return
        Thread {
            val db = BumpDb(applicationContext)
            try {
                val fresh = db.loadBumps().firstOrNull { it.id == b.id }
                if (fresh != null) { change(fresh); db.updateBump(fresh) }
            } finally { db.close() }
            ui.post { reload() }
        }.start()
    }

    private fun confirmDelete(b: Bump) {
        if (!editable()) return
        AlertDialog.Builder(this)
            .setTitle("Delete #${b.id}?")
            .setMessage("It disappears from the map. If it's really there, it is learned again the next time you drive over it.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    val db = BumpDb(applicationContext)
                    try { db.deleteBump(b.id) } finally { db.close() }
                    ui.post { reload() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- share / import

    private fun shareMap() {
        Thread {
            val db = BumpDb(applicationContext)
            val csv = try { db.bumpsCsv() } finally { db.close() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val uri = CsvExport.saveUri(this, "bumps_shared_$stamp.csv", csv)
            ui.post {
                if (uri == null) { toast("Could not save the file"); return@post }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Bump Beeper map")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share bump map"))
            }
        }.start()
    }

    private fun pickImport() {
        if (!editable()) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"   // CSV files arrive with many different types (WhatsApp, email, Drive…)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_IMPORT)
        } catch (e: Exception) {
            toast("No file picker found")
        }
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val msg = try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val db = BumpDb(applicationContext)
                val (added, dup, bad) = try { db.importBumpsCsv(text) } finally { db.close() }
                if (added == 0 && dup == 0) "That doesn't look like a Bump Beeper bumps file"
                else "Imported $added new · $dup already on your map" + if (bad > 0) " · $bad unreadable rows" else ""
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
            ui.post { toast(msg); reload() }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_IMPORT = 10
    }
}
