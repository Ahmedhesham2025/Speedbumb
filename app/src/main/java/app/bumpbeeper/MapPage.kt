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
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.bumpbeeper.ui.SpotMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Your bumps: the map full screen (today's offline map, restyled pins), a top bar with the count, import and share,
 * filter chips, map buttons, and a bottom sheet that lists the nearest spots or shows the one you tapped.
 */
class MapPage(private val a: MainActivity) : Page {
    private fun dp(v: Int) = Ui.dp(a, v)
    private val ui = Handler(Looper.getMainLooper())

    internal lateinit var map: SpotMap   // internal: the screenshot test checks it
    private lateinit var count: TextView
    private lateinit var list: LinearLayout
    private lateinit var listBox: View
    private lateinit var spotBox: LinearLayout
    private lateinit var chips: List<TextView>
    private var cfg = EngineConfig()
    private var all: List<Bump> = emptyList()
    private var filter = 0      // 0 all, 1 strong, 2 bumps (confirmed), 3 maybe (incl. old spots), 4 muted
    private var firstLoad = true

    override val view: View = build()

    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun full(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(a).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Ui.DIM)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun build(): View {
        val o = Ui.Space.OVERLAY
        map = SpotMap(a, interactive = true).apply { onSelect = { showSpot(it) }; offlineChipTopDp = 140 }

        // Top bar: "Your map · 128", import a file, share.
        count = Ui.text(a, 16f, Ui.DIM)
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(a, Ui.SURFACE, 26)
            elevation = Ui.dpf(a, 4f)
            setPadding(dp(18), 0, dp(4), 0)
            minimumHeight = dp(52)
            addView(count, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_import, a.getString(R.string.settings_import)) { a.pickImportFile() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(iconButton(R.drawable.ic_share, a.getString(R.string.settings_share)) { Sharing.chooseAndShare(a) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        // Filter chips: a severity dot or the "maybe" ring on each.
        val names = listOf(
            a.getString(R.string.map_chip_all) to null, a.getString(R.string.map_chip_harsh) to Ui.SEV_STRONG,
            a.getString(R.string.map_chip_bumps) to Ui.SEV_MOD, a.getString(R.string.map_chip_maybe) to Ui.SEV_MAYBE,
            a.getString(R.string.map_tile_muted) to Ui.SEV_MUTED,
        )
        chips = names.mapIndexed { i, (n, dot) -> Ui.tappable(Ui.chip(a, n, dot = dot)) { filter = i; show() } }
        val chipRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            chips.forEachIndexed { i, c -> addView(c, wrap().apply { if (i > 0) marginStart = dp(8) }) }
        }
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(o), dp(o), dp(o), 0)
            addView(bar, full())
            addView(HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(chipRow)
            }, full(4))
        }

        // Map buttons on the end side: zoom in, zoom out, my location.
        val fabs = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            for ((res, desc, act) in listOf(
                Triple(R.drawable.ic_add, R.string.map_zoom_in) { map.radiusM /= 2 },
                Triple(R.drawable.ic_remove, R.string.map_zoom_out) { map.radiusM *= 2 },
                Triple(R.drawable.ic_my_location, R.string.drive_me) { centerOnMe() },
            )) addView(Ui.fab(a, res, a.getString(desc).trim()) { act() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(10) })
        }

        // Sheet: the nearest spots, or the spot you tapped.
        list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        listBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, Ui.Type.LABEL, Ui.PRIMARY, bold = true, value = a.getString(R.string.map_section_nearest)))
            addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(6) })
        }
        spotBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val sheet = Ui.sheet(a).apply {
            addView(listBox, full())
            addView(spotBox, full())
        }
        val bottom = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(fabs, wrap().apply { gravity = Gravity.END; marginEnd = dp(o) })
            addView(map.creditView(), wrap().apply { marginStart = dp(o); bottomMargin = dp(6) })   // the map data credit
            addView(sheet, full())
        }
        return FrameLayout(a).apply {
            setBackgroundColor(Ui.BG)
            addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    override fun onHide() = map.pause()

    override fun onDestroy() = map.destroy()

    override fun onShow() {
        map.resume()
        cfg = Prefs.engineConfig(a)
        Thread {
            val db = BumpDb(a.applicationContext)
            val loaded = try { db.loadBumps() } finally { db.close() }
            ui.post { all = loaded; show() }
        }.start()
    }

    // Filters: strong bumps, confirmed bumps, "maybe" (felt once, or an old spot from before v2: Bump.legacy), muted.
    private fun matches(b: Bump) = when (filter) {
        1 -> b.severity(cfg) == Severity.STRONG
        2 -> b.confidence(cfg) == Confidence.FULL
        3 -> b.confidence(cfg) == Confidence.SOFT
        4 -> b.isMuted(cfg)
        else -> true
    }

    /** The localized name of a spot: "Strong bump ahead" is for driving; here "Mild / Moderate / Strong bump" or "Maybe a bump". */
    private fun describe(b: Bump): String = a.getString(when {
        b.confidence(cfg) == Confidence.SOFT -> R.string.drive_next_maybe
        b.severity(cfg) == Severity.STRONG -> R.string.map_spot_strong
        b.severity(cfg) == Severity.MODERATE -> R.string.drive_next_moderate
        else -> R.string.drive_next_mild
    })

    private fun lastLocation(): Location? = a.lastLocation()

    private fun show() {
        chips.forEachIndexed { i, c ->
            val on = i == filter
            val l = c.paddingStart; val t = c.paddingTop; val r = c.paddingEnd; val b = c.paddingBottom
            c.background = InsetDrawable(Ui.rounded(a, if (on) Ui.PRIMARY_CONT else Ui.SURFACE, Ui.Shape.CHIP), 0, dp(6), 0, dp(6))
            c.setPaddingRelative(l, t, r, b)   // the inset background would reset the sides to 0 (chips overlapped)
            c.setTextColor(if (on) Ui.ON_PRIMARY_CONT else Ui.TEXT)
        }
        count.text = a.getString(R.string.map_title) + " · " + all.size

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
            list.addView(Ui.text(a, 14f, Ui.DIM, value = a.getString(if (all.isEmpty()) R.string.map_nothing_yet else R.string.map_nothing_filter))
                .apply { setPadding(0, dp(8), 0, dp(8)) })
            return
        }
        val refLat = me?.latitude ?: map.centerLat
        val refLon = me?.longitude ?: map.centerLon
        val sorted = shown.sortedBy { Geo.distance(refLat, refLon, it.lat, it.lon) }
        for ((i, b) in sorted.take(200).withIndex()) {
            if (i > 0) list.addView(View(a).apply { setBackgroundColor(Ui.SURFACE3) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(30) })
            list.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(Ui.Space.ROW)
                isClickable = true
                setOnClickListener {
                    map.centerLat = b.lat; map.centerLon = b.lon; map.selectedId = b.id
                    map.radiusM = minOf(map.radiusM, 400.0)
                    showSpot(b)
                }
                addView(pin(b, 14), LinearLayout.LayoutParams(dp(14), dp(14)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, dp(8), 0)
                    addView(Ui.text(a, 15f, Ui.TEXT, value = describe(b)))
                    addView(Ui.text(a, 13f, Ui.DIM, value = where(b, refLat, refLon) + if (b.isMuted(cfg)) a.getString(R.string.map_row_muted) else ""))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(a).apply {
                    setImageResource(R.drawable.ic_chevron)
                    imageTintList = ColorStateList.valueOf(Ui.DIM)
                }, wrap())
            })
        }
        if (sorted.size > 200) list.addView(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.map_more, sorted.size - 200)))
    }

    /** "320 m NE · felt 4/5". */
    private fun where(b: Bump, refLat: Double, refLon: Double): String {
        val d = Geo.distance(refLat, refLon, b.lat, b.lon)
        val dir = compass(Geo.bearing(refLat, refLon, b.lat, b.lon))
        val dist = if (d >= 1000) a.getString(R.string.map_dist_km, String.format(Locale.US, "%.1f", d / 1000))
            else a.getString(R.string.map_dist_m, String.format(Locale.US, "%.0f", d))
        return a.getString(R.string.map_row_felt, dist, dir, b.hits, b.passes)
    }

    /** A small pin like the map's: severity fill, a dashed ring for "maybe", a hollow ring when muted. */
    private fun pin(b: Bump, sizeDp: Int): View = View(a).apply {
        val c = map.colorOf(b)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when {
                b.isMuted(cfg) -> { setColor(0); setStroke(dp(2), c) }
                b.confidence(cfg) == Confidence.SOFT -> { setColor(Ui.SURFACE); setStroke(dp(2), c, Ui.dpf(a, 3f), Ui.dpf(a, 2f)) }
                else -> setColor(c)
            }
            setSize(dp(sizeDp), dp(sizeDp))
        }
    }

    private fun compass(deg: Double): String =
        a.getString(COMPASS[(((deg + 22.5) % 360) / 45).toInt()])

    private fun centerOnMe() {
        val me = lastLocation() ?: return a.toast(a.getString(R.string.map_location_unknown))
        map.meLat = me.latitude; map.meLon = me.longitude
        map.centerLat = me.latitude; map.centerLon = me.longitude
        map.invalidate()
    }

    // ---------------------------------------------------------------- one spot

    /** The tapped spot in the sheet (it was a dialog): what it is, where, how sure, and Mute / Maps / Delete. */
    private fun showSpot(b: Bump) {
        val me = lastLocation()
        val date = SimpleDateFormat("d MMM yyyy", Ui.locale(a)).format(Date(b.firstSeen))
        val info = a.getString(R.string.map_spot_info, b.hits, b.passes, String.format(Locale.US, "%.1f", b.peakAvg), date)
        spotBox.removeAllViews()
        spotBox.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pin(b, 28), LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
                addView(Ui.text(a, 18f, Ui.TEXT, bold = true, value = describe(b)))
                addView(Ui.text(a, 13f, Ui.DIM, value = "#${b.id} · " + where(b, me?.latitude ?: map.centerLat, me?.longitude ?: map.centerLon)))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_close, a.getString(R.string.common_close)) { closeSpot() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        spotBox.addView(Ui.text(a, 13f, Ui.DIM, value = info).apply { setPadding(0, dp(8), 0, dp(12)) })
        fun action(label: String, res: Int, color: Int, act: () -> Unit) = Ui.button(a, label, Ui.Style.SECONDARY) { act() }.apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setCompoundDrawablesRelative(Ui.icon(a, res, color, 18), null, null, null)
            compoundDrawablePadding = dp(6)
            setPadding(dp(10), 0, dp(10), 0)
        }
        spotBox.addView(Ui.row(a,
            action(a.getString(if (b.userMuted) R.string.map_action_unmute else R.string.map_spot_mute), R.drawable.ic_mute, Ui.TEXT) {
                edit(b) { it.userMuted = !it.userMuted }
            },
            action(a.getString(R.string.map_spot_maps), R.drawable.ic_maps, Ui.TEXT) { openInMaps(b) },
            action(a.getString(R.string.common_delete), R.drawable.ic_delete, Ui.ERR) { confirmDelete(b) },
        ))
        listBox.visibility = View.GONE
        spotBox.visibility = View.VISIBLE
    }

    private fun closeSpot() {
        map.selectedId = -1
        spotBox.visibility = View.GONE
        listBox.visibility = View.VISIBLE
    }

    private fun openInMaps(b: Bump) {
        val label = Uri.encode(a.getString(R.string.map_maps_label, b.id, describe(b)))
        val uri = Uri.parse(String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(%s)", b.lat, b.lon, b.lat, b.lon, label))
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
            ui.post { closeSpot(); onShow() }
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
                    ui.post { closeSpot(); onShow() }
                }.start()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private companion object {
        val COMPASS = intArrayOf(
            R.string.map_compass_n, R.string.map_compass_ne, R.string.map_compass_e, R.string.map_compass_se,
            R.string.map_compass_s, R.string.map_compass_sw, R.string.map_compass_w, R.string.map_compass_nw,
        )
    }
}
