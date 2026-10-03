package app.bumpbeeper

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bumpbeeper.sync.CachedSpotSource
import app.bumpbeeper.sync.SyncStore
import app.bumpbeeper.ui.MapSpot
import app.bumpbeeper.ui.SpotMarks
import app.bumpbeeper.ui.StreetMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Your bumps and potholes on a street map (plus the shared map's confirmed spots), filters, nearest-first list, share and import. */
class MapPage(private val a: MainActivity) : Page {
    private fun dp(v: Int) = Ui.dp(a, v)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tiles: List<TextView>
    private lateinit var map: StreetMap
    private lateinit var list: LinearLayout
    private lateinit var chips: List<TextView>
    private var cfg = EngineConfig()
    private var all: List<Bump> = emptyList()
    private var spots: List<MapSpot> = emptyList()
    private var filter = SpotMarks.ALL
    private var firstLoad = true
    private var sheet: Dialog? = null

    override val view: View = build()

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        fun add(v: View, top: Int = 0, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
            col.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply { topMargin = dp(top) })

        add(Ui.text(a, 22f, Ui.TEXT, bold = true, value = a.getString(R.string.map_title)))
        val t = listOf(R.string.map_tile_bumps, R.string.map_tile_potholes, R.string.map_tile_harsh, R.string.map_tile_muted).map { Ui.tile(a, a.getString(it)) }
        tiles = t.map { it.second }
        add(Ui.grid(a, t.map { it.first }, 4), 12)

        // Filter chips: all, bumps, potholes, harsh, muted (SpotMarks order).
        val names = listOf(R.string.map_chip_all, R.string.map_chip_bumps, R.string.map_chip_potholes, R.string.map_chip_harsh, R.string.map_chip_muted).map { a.getString(it) }
        chips = names.mapIndexed { i, n ->
            Ui.text(a, 13f, Ui.TEXT, bold = true, value = n).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                maxLines = 1
                isClickable = true
                setOnClickListener { filter = i; show() }
            }
        }
        add(Ui.row(a, *chips.toTypedArray(), gapDp = 4), 14)

        // A big map: most of the screen height; the rest of the page scrolls under it.
        map = StreetMap(a, interactive = true).apply { onSpot = { showSheet(it) } }
        add(map.view, 10, (a.resources.displayMetrics.heightPixels * 0.55).toInt())
        add(Ui.row(a,
            Ui.button(a, a.getString(R.string.map_zoom_out)) { map.zoomBy(-1.0) },
            Ui.button(a, a.getString(R.string.map_zoom_in)) { map.zoomBy(1.0) },
            Ui.button(a, a.getString(R.string.map_me)) { centerOnMe() },
            Ui.button(a, a.getString(R.string.map_north)) { map.northUp() },
        ), 8)
        add(Ui.text(a, 12f, Ui.DIM, value = a.getString(R.string.map_legend)), 6)

        add(Ui.section(a, a.getString(R.string.map_section_share)))
        val share = Ui.card(a)
        share.addView(Ui.row(a,
            Ui.button(a, a.getString(R.string.settings_share), Ui.Style.PRIMARY) { Sharing.chooseAndShare(a) },
            Ui.button(a, a.getString(R.string.settings_import)) { a.pickImportFile() },
        ))
        share.addView(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.map_share_hint)
        ).apply { setPadding(0, dp(10), 0, 0) })
        add(share)

        add(Ui.section(a, a.getString(R.string.map_section_nearest)))
        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        add(list)

        return ScrollView(a).apply { addView(col) }
    }

    override fun onShow() {
        map.resume()
        cfg = Prefs.engineConfig(a)
        val c = cfg
        val center = map.camera() ?: lastLocation()?.let { doubleArrayOf(it.latitude, it.longitude) } ?: savedCamera()
        Thread {
            val db = BumpDb(a.applicationContext)
            val (loaded, shared) = try {
                val bumps = db.loadBumps()
                // Confirmed shared spots from the local cache (never the network), around where you look or are.
                val lat = center?.get(0) ?: bumps.firstOrNull()?.lat
                val lon = center?.get(1) ?: bumps.firstOrNull()?.lon
                val remote = if (lat == null || lon == null) emptyList()
                    else runCatching { SyncStore(db).remoteSpotsInBox(lat, lon, SHARED_RADIUS_M).mapNotNull { CachedSpotSource.toRemote(it) } }
                        .getOrDefault(emptyList())
                bumps to remote
            } finally { db.close() }
            val merged = SpotMarks.merge(loaded.map { SpotMarks.fromLocal(it, c) }, shared.map { SpotMarks.fromShared(it, c) })
            ui.post { all = loaded; spots = merged; show() }
        }.start()
    }

    override fun onHide() {
        map.camera()?.let { saveCamera(it) }
        map.pause()
    }

    override fun release() {
        sheet?.dismiss()
        map.destroy()
    }

    private fun describe(s: MapSpot): String {
        val kind = a.getString(when {
            s.kind == BumpKind.POTHOLE && s.harsh -> R.string.map_describe_harsh_pothole
            s.kind == BumpKind.POTHOLE -> R.string.map_describe_pothole
            s.kind == BumpKind.BUMP -> R.string.map_kind_bump
            else -> R.string.map_kind_unsure
        })
        return if (s.kind == BumpKind.POTHOLE && s.side != Side.UNKNOWN) "$kind, ${sideName(s.side)}" else kind
    }

    private fun sideName(side: Side) = a.getString(when (side) {
        Side.LEFT -> R.string.map_side_left
        Side.RIGHT -> R.string.map_side_right
        Side.UNKNOWN -> R.string.map_side_unknown
    })

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

        val shown = spots.filter { SpotMarks.matches(it, filter) }
        val me = lastLocation()
        map.setMe(me?.latitude ?: Double.NaN, me?.longitude ?: Double.NaN)
        map.setSpots(shown)
        if (firstLoad) {
            firstLoad = false
            // Where you are, else where you last looked, else around your spots.
            val saved = savedCamera()
            when {
                me != null -> map.moveTo(me.latitude, me.longitude, StreetMap.DEFAULT_ZOOM, 0.0)
                saved != null -> map.moveTo(saved[0], saved[1], saved[2], 0.0)
                spots.isNotEmpty() -> map.moveTo(spots.map { it.lat }.average(), spots.map { it.lon }.average(), 13.0, 0.0)
                else -> firstLoad = true
            }
        }

        list.removeAllViews()
        if (shown.isEmpty()) {
            list.addView(Ui.text(a, 14f, Ui.DIM, value = a.getString(if (spots.isEmpty()) R.string.map_nothing_yet else R.string.map_nothing_filter)))
            return
        }
        val ref = me?.let { doubleArrayOf(it.latitude, it.longitude) } ?: map.camera()
            ?: doubleArrayOf(shown[0].lat, shown[0].lon)
        val sorted = SpotMarks.nearest(shown, ref[0], ref[1])
        for (s in sorted.take(200)) {
            val d = Geo.distance(ref[0], ref[1], s.lat, s.lon)
            val dir = compass(Geo.bearing(ref[0], ref[1], s.lat, s.lon))
            val dist = if (d >= 1000) a.getString(R.string.map_dist_km, String.format(Locale.US, "%.1f", d / 1000))
                else a.getString(R.string.map_dist_m, String.format(Locale.US, "%.0f", d))
            val detail = if (s.shared) a.getString(R.string.map_row_shared, dist, dir, s.devices)
                else a.getString(R.string.map_row_felt, dist, dir, s.hits, s.hits + s.clears) + if (s.muted) a.getString(R.string.map_row_muted) else ""
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.rounded(a, Ui.SURFACE, 14)
                isClickable = true
                setOnClickListener {
                    map.moveTo(s.lat, s.lon, maxOf(map.camera()?.get(2) ?: 0.0, 16.0), animateMs = 600)
                    showSheet(s)
                }
                addView(View(a).apply { background = Ui.rounded(a, s.icon.color, 6) }, LinearLayout.LayoutParams(dp(12), dp(12)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPaddingRelative(dp(12), 0, 0, 0)
                    addView(Ui.text(a, 15f, Ui.TEXT, bold = true, value = describe(s).replaceFirstChar { it.uppercase() }))
                    addView(Ui.text(a, 13f, Ui.DIM, value = detail))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.text(a, 18f, Ui.DIM, value = "›"))
            }
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        if (sorted.size > 200) list.addView(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.map_more, sorted.size - 200)))
    }

    private fun compass(deg: Double): String =
        a.getString(COMPASS[(((deg + 22.5) % 360) / 45).toInt()])

    private fun centerOnMe() {
        val me = lastLocation() ?: return a.toast(a.getString(R.string.map_location_unknown))
        map.setMe(me.latitude, me.longitude)
        map.moveTo(me.latitude, me.longitude, StreetMap.DEFAULT_ZOOM, animateMs = 600)
    }

    private fun saveCamera(c: DoubleArray) {
        Prefs.sp(a).edit().putString(KEY_CAMERA, String.format(Locale.US, "%.6f,%.6f,%.2f", c[0], c[1], c[2])).apply()
    }

    private fun savedCamera(): DoubleArray? =
        Prefs.sp(a).getString(KEY_CAMERA, null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }?.toDoubleArray()

    // ---------------------------------------------------------------- one spot

    /** A sheet from the bottom: what the spot is, and what you can do with it (yours only: mute, correct, delete). */
    private fun showSheet(s: MapSpot) {
        sheet?.dismiss()
        val b = all.firstOrNull { it.id == s.localId }
        val d = Dialog(a)
        val card = Ui.card(a, 20).apply { background = Ui.rounded(a, Ui.SURFACE, 22) }
        val full = { top: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) } }
        fun line(text: String) = card.addView(Ui.text(a, 14f, Ui.DIM, value = text), full(4))
        fun act(label: Int, style: Ui.Style = Ui.Style.SECONDARY, run: () -> Unit) =
            card.addView(Ui.button(a, a.getString(label), style) { d.dismiss(); run() }, full(8))
        fun pair(l: Int, r: Int, change: (Int) -> Unit) = card.addView(Ui.row(a,
            Ui.button(a, a.getString(l)) { d.dismiss(); change(-1) },
            Ui.button(a, a.getString(r)) { d.dismiss(); change(1) },
        ), full(8))

        card.addView(Ui.text(a, 19f, Ui.TEXT, bold = true, value = describe(s).replaceFirstChar { it.uppercase() }))
        line(a.getString(R.string.map_sheet_side, sideName(s.side)))
        line(a.getString(if (s.harsh) R.string.map_sheet_jolt_harsh else R.string.map_sheet_jolt, String.format(Locale.US, "%.1f", s.jolt)))
        if (s.shared) {
            line(a.getString(R.string.map_sheet_source_shared, s.devices))
            line(a.getString(R.string.map_sheet_shared_note))
        } else {
            line(a.getString(R.string.map_sheet_hits, s.hits, s.clears))
            b?.let { line(a.getString(R.string.map_sheet_first_seen, SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(it.firstSeen)))) }
            line(a.getString(R.string.map_sheet_source_mine) + if (s.muted) a.getString(R.string.map_row_muted) else "")
        }
        act(R.string.map_action_open_maps) { openInMaps(s) }
        if (b != null) {
            act(if (b.userMuted) R.string.map_action_unmute else R.string.map_action_mute, Ui.Style.PRIMARY) { edit(b) { it.userMuted = !it.userMuted } }
            pair(R.string.map_action_is_bump, R.string.map_action_is_pothole) { v -> edit(b) { it.kindScore = v.toDouble(); it.kindVotes = maxOf(it.kindVotes, 10) } }
            if (s.kind == BumpKind.POTHOLE) {
                pair(R.string.map_action_pothole_left, R.string.map_action_pothole_right) { v -> edit(b) { it.sideScore = v.toDouble(); it.sideVotes = maxOf(it.sideVotes, 10) } }
            }
            act(R.string.common_delete, Ui.Style.DANGER) { confirmDelete(b) }
        }
        act(R.string.common_close, Ui.Style.QUIET) {}
        d.setContentView(ScrollView(a).apply { addView(card) })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.setOnDismissListener { if (sheet === d) sheet = null }
        sheet = d
        d.show()
    }

    private fun openInMaps(s: MapSpot) {
        val label = Uri.encode(if (s.localId >= 0) a.getString(R.string.map_maps_label, s.localId, describe(s)) else describe(s))
        val uri = Uri.parse(String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(%s)", s.lat, s.lon, s.lat, s.lon, label))
        try { a.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { a.toast(a.getString(R.string.map_no_maps_app)) }
    }

    /** Changes can't be made while recording: the running trip has its own copy of the map. */
    private fun editable(): Boolean {
        if (LiveState.recording) a.toast(a.getString(R.string.map_stop_first))
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
            .setTitle(a.getString(R.string.map_delete_title, b.id))
            .setMessage(a.getString(R.string.map_delete_msg))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                Thread {
                    val db = BumpDb(a.applicationContext)
                    try { db.deleteBump(b.id) } finally { db.close() }
                    ui.post { onShow() }
                }.start()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private companion object {
        const val KEY_CAMERA = "ui_map_camera"
        /** Shared spots shown around the map centre (the cache only holds spots near where you drove anyway). */
        const val SHARED_RADIUS_M = 50_000.0
        val COMPASS = intArrayOf(
            R.string.map_compass_n, R.string.map_compass_ne, R.string.map_compass_e, R.string.map_compass_se,
            R.string.map_compass_s, R.string.map_compass_sw, R.string.map_compass_w, R.string.map_compass_nw,
        )
    }
}
