package app.bumpbeeper

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Your bumps and potholes: offline map, filters, nearest-first list, share and import. */
class MapPage(private val a: MainActivity) : Page {
    private fun dp(v: Int) = Ui.dp(a, v)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tiles: List<TextView>
    private lateinit var map: BumpMapView
    private lateinit var list: LinearLayout
    private lateinit var chips: List<TextView>
    private var cfg = EngineConfig()
    private var all: List<Bump> = emptyList()
    private var filter = 0      // 0 all, 1 speed bumps, 2 potholes, 3 harsh potholes
    private var firstLoad = true

    override val view: View = build()

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        fun add(v: View, top: Int = 0, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
            col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply { topMargin = dp(top) })

        add(Ui.text(a, 22f, Ui.TEXT, bold = true, value = "Your map"))
        val t = listOf("Speed bumps", "Potholes", "Harsh", "Muted").map { Ui.tile(a, it) }
        tiles = t.map { it.second }
        add(Ui.grid(a, t.map { it.first }, 4), 12)

        // Filter chips.
        val names = listOf("All", "Bumps", "Potholes", "Harsh")
        chips = names.mapIndexed { i, n ->
            Ui.text(a, 14f, Ui.TEXT, bold = true, value = n).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                setOnClickListener { filter = i; show() }
            }
        }
        add(Ui.row(a, *chips.toTypedArray(), gapDp = 6), 14)

        map = BumpMapView(a).apply { onSelect = { showActions(it) } }
        add(map, 10, dp(340))
        add(Ui.row(a,
            Ui.button(a, "−  Zoom out") { map.radiusM *= 2 },
            Ui.button(a, "+  Zoom in") { map.radiusM /= 2 },
            Ui.button(a, "◎  Me") { centerOnMe() },
        ), 8)
        add(Ui.text(a, 12f, Ui.DIM, value = "● orange speed bump · ● red pothole · ● grey not sure yet · ○ hollow muted. " +
            "The tick shows the direction it warns for. Drag to move; tap a dot for options. " +
            "No streets because the app works offline: use Open in Google Maps."), 6)

        add(Ui.section(a, "Share & import"))
        val share = Ui.card(a)
        share.addView(Ui.row(a,
            Ui.button(a, "Share…", Ui.Style.PRIMARY) { Sharing.chooseAndShare(a) },
            Ui.button(a, "Import a file") { a.pickImportFile() },
        ))
        share.addView(Ui.text(a, 13f, Ui.DIM, value =
            "To give your bumps to someone: Share… → Bump file, and send it (WhatsApp, email, Drive…). " +
                "They tap the file and choose Bump Beeper, or use Import a file. Their own spots are kept; yours are added.\n" +
                "Map file (KML) opens in Google Earth and can be imported into Google My Maps."
        ).apply { setPadding(0, dp(10), 0, 0) })
        add(share)

        add(Ui.section(a, "Nearest first"))
        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        add(list)

        return ScrollView(a).apply { addView(col) }
    }

    override fun onShow() {
        cfg = Prefs.engineConfig(a)
        Thread {
            val db = BumpDb(a.applicationContext)
            val loaded = try { db.loadBumps() } finally { db.close() }
            ui.post { all = loaded; show() }
        }.start()
    }

    private fun matches(b: Bump) = when (filter) {
        1 -> b.kind == BumpKind.BUMP || b.kind == BumpKind.UNSURE
        2 -> b.kind == BumpKind.POTHOLE
        3 -> b.isHarsh(cfg)
        else -> true
    }

    private fun describe(b: Bump): String = when {
        b.kind != BumpKind.POTHOLE -> b.kind.label
        else -> (if (b.isHarsh(cfg)) "harsh pothole" else "pothole") + (if (b.side != Side.UNKNOWN) ", ${b.side.label}" else "")
    }

    private fun lastLocation(): Location? {
        if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = a.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
    }

    private fun show() {
        chips.forEachIndexed { i, c ->
            c.background = Ui.rounded(a, if (i == filter) Ui.ACCENT else Ui.SURFACE2, 20)
            c.setTextColor(if (i == filter) Ui.ON_ACCENT else Ui.TEXT)
        }
        tiles[0].text = all.count { it.kind == BumpKind.BUMP }.toString()
        tiles[1].text = all.count { it.kind == BumpKind.POTHOLE }.toString()
        tiles[2].text = all.count { it.isHarsh(cfg) }.toString()
        tiles[3].text = all.count { it.isMuted(cfg) }.toString()

        val shown = all.filter { matches(it) }
        val me = lastLocation()
        map.meLat = me?.latitude ?: Double.NaN
        map.meLon = me?.longitude ?: Double.NaN
        if (firstLoad && all.isNotEmpty() || firstLoad && me != null) {
            firstLoad = false
            if (me != null) { map.centerLat = me.latitude; map.centerLon = me.longitude }
            else { map.centerLat = all.map { it.lat }.average(); map.centerLon = all.map { it.lon }.average() }
            val near = all.map { Geo.distance(map.centerLat, map.centerLon, it.lat, it.lon) }.sorted()
            map.radiusM = if (near.isEmpty()) 1000.0 else (near[minOf(4, near.size - 1)] * 1.3).coerceAtLeast(200.0)
        }
        map.bumps = shown

        list.removeAllViews()
        if (shown.isEmpty()) {
            list.addView(Ui.text(a, 14f, Ui.DIM, value = if (all.isEmpty()) "Nothing yet. Bumps are recorded the first time you drive over them." else "Nothing in this filter."))
            return
        }
        val refLat = me?.latitude ?: map.centerLat
        val refLon = me?.longitude ?: map.centerLon
        val sorted = shown.sortedBy { Geo.distance(refLat, refLon, it.lat, it.lon) }
        for (b in sorted.take(200)) {
            val d = Geo.distance(refLat, refLon, b.lat, b.lon)
            val dir = compass(Geo.bearing(refLat, refLon, b.lat, b.lon))
            val dist = if (d >= 1000) String.format(Locale.US, "%.1f km", d / 1000) else String.format(Locale.US, "%.0f m", d)
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.rounded(a, Ui.SURFACE, 14)
                isClickable = true
                setOnClickListener {
                    map.centerLat = b.lat; map.centerLon = b.lon; map.selectedId = b.id
                    map.radiusM = minOf(map.radiusM, 400.0)
                    showActions(b)
                }
                addView(View(a).apply { background = Ui.rounded(a, map.colorOf(b), 6) }, LinearLayout.LayoutParams(dp(12), dp(12)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(Ui.text(a, 15f, Ui.TEXT, bold = true, value = describe(b).replaceFirstChar { it.uppercase() }))
                    addView(Ui.text(a, 13f, Ui.DIM, value = "$dist $dir · felt ${b.hits}/${b.passes}" + if (b.isMuted(cfg)) " · muted" else ""))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.text(a, 18f, Ui.DIM, value = "›"))
            }
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        if (sorted.size > 200) list.addView(Ui.text(a, 13f, Ui.DIM, value = "…and ${sorted.size - 200} more (Share → Bump file to see all)"))
    }

    private fun compass(deg: Double): String =
        arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

    private fun centerOnMe() {
        val me = lastLocation() ?: return a.toast("Location not known yet")
        map.meLat = me.latitude; map.meLon = me.longitude
        map.centerLat = me.latitude; map.centerLon = me.longitude
        map.invalidate()
    }

    // ---------------------------------------------------------------- one spot

    private fun showActions(b: Bump) {
        val date = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(b.firstSeen))
        val info = String.format(
            Locale.US, "Felt %d of %d passes · average jolt %.1f m/s² · first seen %s",
            b.hits, b.passes, b.peakAvg, date,
        )
        val actions = arrayOf(
            "Open in Google Maps",
            if (b.userMuted) "Unmute" else "Mute (never warn here)",
            "It's a speed bump",
            "It's a pothole",
            "Pothole is on the left",
            "Pothole is on the right",
            "Delete",
        )
        AlertDialog.Builder(a)
            .setTitle("#${b.id} · ${describe(b)}\n$info")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openInMaps(b)
                    1 -> edit(b) { it.userMuted = !it.userMuted }
                    2 -> edit(b) { it.kindScore = -1.0; it.kindVotes = maxOf(it.kindVotes, 10) }
                    3 -> edit(b) { it.kindScore = 1.0; it.kindVotes = maxOf(it.kindVotes, 10) }
                    4 -> edit(b) { it.sideScore = -1.0; it.sideVotes = maxOf(it.sideVotes, 10) }
                    5 -> edit(b) { it.sideScore = 1.0; it.sideVotes = maxOf(it.sideVotes, 10) }
                    6 -> confirmDelete(b)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openInMaps(b: Bump) {
        val label = Uri.encode("Bump Beeper #${b.id} (${describe(b)})")
        val uri = Uri.parse(String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(%s)", b.lat, b.lon, b.lat, b.lon, label))
        try { a.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { a.toast("No maps app found") }
    }

    /** Changes can't be made while recording: the running trip has its own copy of the map. */
    private fun editable(): Boolean {
        if (LiveState.recording) a.toast("Stop recording first, then edit the map")
        return !LiveState.recording
    }

    private fun edit(b: Bump, change: (Bump) -> Unit) {
        if (!editable()) return
        Thread {
            val db = BumpDb(a.applicationContext)
            try {
                val fresh = db.loadBumps().firstOrNull { it.id == b.id }
                if (fresh != null) { change(fresh); db.updateBump(fresh) }
            } finally { db.close() }
            ui.post { onShow() }
        }.start()
    }

    private fun confirmDelete(b: Bump) {
        if (!editable()) return
        AlertDialog.Builder(a)
            .setTitle("Delete #${b.id}?")
            .setMessage("It disappears from the map. If it's really there, it is learned again the next time you drive over it.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    val db = BumpDb(a.applicationContext)
                    try { db.deleteBump(b.id) } finally { db.close() }
                    ui.post { onShow() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
