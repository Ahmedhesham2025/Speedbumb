package app.bumpbeeper

import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.widget.HorizontalScrollView
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.bumpbeeper.ui.LiveLimitText
import app.bumpbeeper.ui.NextBump
import app.bumpbeeper.ui.SpotMap
import app.bumpbeeper.ui.SpeedSignView
import app.bumpbeeper.sync.OfflineBanner
import app.bumpbeeper.ui.SyncChoice
import java.util.Locale

/** The dashboard: one big Start/Stop button, speed, this trip's numbers and score, and what just happened. */
class DrivePage(private val a: MainActivity) : Page {
    private fun dp(v: Int) = Ui.dp(a, v)

    private lateinit var status: TextView
    private lateinit var syncLine: TextView
    private lateinit var offline: TextView
    private var ticks = 0
    private lateinit var setupCard: LinearLayout
    private lateinit var startBtn: TextView
    private lateinit var speed: TextView
    private lateinit var signBox: LinearLayout
    private lateinit var sign: SpeedSignView
    private lateinit var gps: TextView
    private lateinit var tDist: TextView
    private lateinit var tTime: TextView
    private lateinit var tScore: TextView
    private lateinit var tWarn: TextView
    private lateinit var tBumps: TextView
    private lateinit var tHoles: TextView
    private lateinit var muteBtn: View
    private lateinit var updateCard: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var labelCard: LinearLayout
    private lateinit var labelStatus: TextView
    private lateinit var labelLater: TextView
    private var wasRecording: Boolean? = null
    private var updateShown: String? = null

    // Ready (not recording): the map with your bumps, a few chips, one setup row, the last trip, Start drive.
    private lateinit var readyView: View
    private lateinit var liveView: View
    internal lateinit var map: SpotMap   // internal: the screenshot test checks it
    private lateinit var autoChip: TextView
    private lateinit var readyHint: TextView
    private lateinit var lastTripRow: LinearLayout
    private lateinit var lastTripBadge: TextView
    private lateinit var lastTripTitle: TextView
    private var mapCentred = false
    /** Score of the newest saved trip (-1 = none or too short), read with the map in [loadMap]. */
    private var savedLastScore = -1

    override val view: View = build()

    private fun build(): View {
        readyView = buildReady()
        liveView = buildLive()
        return FrameLayout(a).apply {
            setBackgroundColor(Ui.BG)
            // One map behind both states: ready shows your bumps around you, recording follows the car.
            addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(readyView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(liveView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun full(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun buildReady(): View {
        map = SpotMap(a, interactive = false).apply { radiusM = 600.0 }
        val o = Ui.Space.OVERLAY

        // Top: what starts the next drive, and the shared map (tap: Settings).
        autoChip = Ui.chip(a, "", R.drawable.ic_bluetooth)
        syncLine = Ui.tappable(Ui.chip(a, "", R.drawable.ic_people)) {
            if (!LiveState.recording) a.select(MainActivity.TAB_SETTINGS)
        }
        val chips = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(autoChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(syncLine, wrap())
        }

        // "New version available" banner; filled in by tick() once the update check in MainActivity answers.
        updateCard = Ui.card(a).apply {
            background = Ui.rounded(a, Ui.SURFACE, Ui.Shape.CARD, Ui.PRIMARY)
            elevation = Ui.dpf(a, 3f)
            visibility = View.GONE
        }
        updateText = Ui.text(a, 16f, Ui.TEXT, bold = true)
        updateCard.addView(updateText)
        updateCard.addView(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.drive_update_hint)))
        updateCard.addView(Ui.row(a,
            Ui.button(a, a.getString(R.string.drive_download), Ui.Style.PRIMARY) { a.openUpdate() },
            Ui.button(a, a.getString(R.string.common_not_now), Ui.Style.QUIET) { a.dismissUpdate(); updateCard.visibility = View.GONE },
        ).apply { setPadding(0, dp(10), 0, 0) })

        // One setup row at a time (onShow picks the first thing missing).
        setupCard = Ui.card(a, 12).apply { elevation = Ui.dpf(a, 3f) }

        val top = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(o), dp(o), dp(o), 0)
            addView(chips, full())
            addView(updateCard, full(10))
            addView(setupCard, full(10))
        }

        // Bottom: my-location button, then the sheet with the last trip and Start drive.
        val me = Ui.fab(a, R.drawable.ic_my_location, a.getString(R.string.drive_me)) { centreOnMe() }
        lastTripBadge = Ui.scoreBadge(a)
        lastTripTitle = Ui.text(a, 16f, Ui.TEXT, bold = true)
        lastTripRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Ui.Space.ROW)
            isClickable = true
            setOnClickListener { a.select(MainActivity.TAB_TRIPS) }
            addView(lastTripBadge, wrap())
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
                addView(lastTripTitle)
                addView(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.drive_last_trip_more)))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(Ui.DIM)
            }, wrap())
        }
        readyHint = Ui.text(a, 13f, Ui.DIM).apply { gravity = Gravity.CENTER }
        val sheet = Ui.sheet(a).apply {
            addView(lastTripRow, full())
            addView(Ui.bigPill(a, a.getString(R.string.drive_start_drive), R.drawable.ic_play, Ui.PRIMARY, Ui.ON_PRIMARY) {
                a.toggleRecording()
            }, full(10))
            addView(readyHint, full(10))
        }
        val bottom = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(me, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.END
                marginEnd = dp(o)
                bottomMargin = dp(o)
            })
            addView(map.creditView(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(o); bottomMargin = dp(6) })   // the map data credit, above the sheet
            addView(sheet, full())
        }

        return FrameLayout(a).apply {
            addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    /** Your bumps on the Drive map, loaded off the main thread like the Map tab does. */
    private fun loadMap() {
        Thread {
            val db = BumpDb(a.applicationContext)
            val ecfg = Prefs.engineConfig(a)
            val (loaded, newest) = try { db.loadBumps().filter { !it.isRetired(ecfg) } to db.trips(1).firstOrNull() } finally { db.close() }
            a.runOnUiThread {
                savedLastScore = newest?.score ?: -1
                map.bumps = loaded
                if (!mapCentred && loaded.isNotEmpty() && map.meLat.isNaN()) {
                    map.centerLat = loaded.map { it.lat }.average()
                    map.centerLon = loaded.map { it.lon }.average()
                    map.invalidate()
                }
            }
        }.start()
    }

    /** The blue dot from the newest position Android has; centres on it the first time (or when asked). */
    private fun updateMe(centre: Boolean) {
        val me = a.lastLocation() ?: return
        map.meLat = me.latitude
        map.meLon = me.longitude
        // While driving the street map turns with the car (heading-up); parked it stays north-up.
        map.heading = if (me.hasBearing() && me.speed > 1.5f) me.bearing.toDouble() else Double.NaN
        map.headingUp = LiveState.recording
        if (centre || !mapCentred) {
            map.centerLat = me.latitude
            map.centerLon = me.longitude
            mapCentred = true
        }
        map.invalidate()
    }

    private fun centreOnMe() {
        if (a.lastLocation() == null) a.toast(a.getString(R.string.map_location_unknown)) else updateMe(centre = true)
    }

    // Recording, the road view: the street map heading-up with your puck low, a dark banner on top with the next
    // bump, round buttons on the end side, the speedometer at the start corner, the road's name, and a short sheet.
    private lateinit var banner: LinearLayout
    private lateinit var nextGlyph: ImageView
    private lateinit var nextDist: TextView
    private lateinit var nextWhat: TextView
    private lateinit var nextBeeps: LinearLayout
    private lateinit var thenChip: TextView
    private lateinit var recenterBtn: View
    private lateinit var speedo: FrameLayout
    private lateinit var roadPill: TextView
    private lateinit var tomtom: TextView
    private lateinit var tripTime: TextView
    private lateinit var tripSub: TextView
    private lateinit var scoreRing: ScoreRingView
    private lateinit var tripChip: TextView
    private lateinit var placeChip: TextView
    private lateinit var breakdownRow: LinearLayout
    private lateinit var detailsToggle: View
    private lateinit var details: LinearLayout
    private var nearShown: Boolean? = null

    /** The banner is dark in both themes (it sits on a light map by day). */
    private val bannerBg = 0xFF1C2026.toInt()

    /** A round floating button (48 dp, white surface, shadow) for the end-side column. */
    private fun roundFab(res: Int, desc: String, onClick: () -> Unit): ImageView = Ui.fab(a, res, desc, onClick).apply {
        background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), Ui.rounded(a, Ui.SURFACE, 24), Ui.rounded(a, 0xFFFFFFFF.toInt(), 24))
    }

    private fun buildLive(): View {
        val o = Ui.Space.OVERLAY

        // Top: the banner, edge to edge, then the status chips and the bump after the next one.
        nextGlyph = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER }
        nextDist = Ui.text(a, Ui.Type.NEXT, 0xFFFFFFFF.toInt(), bold = true).apply { includeFontPadding = false }
        nextWhat = Ui.text(a, 15f, bold = true)
        nextBeeps = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            repeat(3) { i -> addView(View(a), LinearLayout.LayoutParams(dp(8), dp(8)).apply { if (i > 0) marginStart = dp(4) }) }
        }
        thenChip = Ui.text(a, 13f, 0xFFB8C0CC.toInt()).apply { visibility = View.GONE; setPadding(0, dp(2), 0, 0) }
        // Recording: a small dot at the banner's end (green recording, amber waiting for GPS); the words are for TalkBack.
        status = Ui.text(a, 14f, Ui.GREEN, bold = true, value = "●")
        banner = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bannerBg)
            elevation = Ui.dpf(a, 6f)
            addView(nextGlyph, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
                addView(nextDist)
                addView(nextWhat)
                addView(thenChip)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(status)
                addView(nextBeeps, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        offline = Ui.chip(a, a.getString(R.string.drive_offline_banner), R.drawable.ic_cloud_off).apply {
            background = Ui.rounded(a, Ui.WARN_CONT, Ui.Shape.CHIP)
            setTextColor(Ui.WARN)
            maxLines = 2
            ellipsize = null
            setPadding(paddingLeft, dp(6), paddingRight, dp(6))
            visibility = View.GONE
        }
        val chips = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(o), dp(8), dp(o), 0)
            addView(offline, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(banner, full())
            addView(chips, full())
        }

        // End side: sound, mark a bump here, recenter (only after you moved the map).
        recenterBtn = roundFab(R.drawable.ic_navigation, a.getString(R.string.drive_recenter)) {
            map.recenter()
            recenterBtn.visibility = View.GONE
        }.apply { visibility = View.GONE }
        map.onPanned = { recenterBtn.visibility = View.VISIBLE }
        val fabs = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            for (v in listOf(
                roundFab(R.drawable.ic_volume, a.getString(R.string.drive_sound)) { showVolume() },
                roundFab(R.drawable.ic_report, a.getString(R.string.drive_report)) {}.apply {
                    imageTintList = null   // the yellow triangle keeps its own colours
                    setOnClickListener { reportBump(it) }
                },
                recenterBtn,
            )) addView(v, LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(12) })
        }

        // Start corner: the speedometer, the road's limit sign on its top end.
        speed = Ui.text(a, 30f, 0xFFFFFFFF.toInt(), bold = true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        sign = SpeedSignView(a)
        signBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(sign, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        speedo = FrameLayout(a).apply {
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bannerBg) }
                elevation = Ui.dpf(a, 6f)
                addView(speed)
                addView(Ui.text(a, 11f, 0xFFB8C0CC.toInt(), value = a.getString(R.string.drive_kmh_unit)).apply { gravity = Gravity.CENTER })
            }, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.BOTTOM or Gravity.START))
            addView(signBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END).apply { })
            elevation = Ui.dpf(a, 8f)
        }
        // The TomTom notice stays with the sign (their terms), small, under the speedometer.
        tomtom = Ui.text(a, Ui.Type.CAPTION, Ui.DIM, value = SpeedLimitScoring.ATTRIBUTION).apply { visibility = View.GONE }
        roadPill = Ui.chip(a, "").apply { gravity = Gravity.CENTER; visibility = View.GONE; maxLines = 1 }

        // Sheet: Stop, the trip's time with distance and score, and the details button.
        tripTime = Ui.text(a, 28f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        tripSub = Ui.text(a, 13f, Ui.DIM).apply { gravity = Gravity.CENTER }
        startBtn = TextView(a).apply {
            background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), Ui.rounded(a, Ui.ERR_CONT, 16), Ui.rounded(a, 0xFFFFFFFF.toInt(), 16))
            setCompoundDrawablesRelative(Ui.icon(a, R.drawable.ic_stop, Ui.ERR, 26), null, null, null)
            gravity = Gravity.CENTER
            setPadding(dp(15), 0, 0, 0)
            contentDescription = a.getString(R.string.drive_stop_desc)
            isClickable = true
            setOnClickListener { a.toggleRecording() }
        }
        detailsToggle = ImageView(a).apply {
            setImageResource(R.drawable.ic_tune)
            imageTintList = ColorStateList.valueOf(Ui.TEXT)
            scaleType = ImageView.ScaleType.CENTER
            background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), Ui.rounded(a, Ui.SURFACE2, 16), Ui.rounded(a, 0xFFFFFFFF.toInt(), 16))
            contentDescription = a.getString(R.string.drive_details)
            isClickable = true
            setOnClickListener { toggleDetails() }
        }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(startBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(tripTime)
                addView(tripSub)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(detailsToggle, LinearLayout.LayoutParams(dp(56), dp(56)))
        }
        details = buildDetails()
        val sheet = Ui.sheet(a).apply {
            addView(buildLabelPanel(), full())
            addView(row, full(4))
            addView(details, full(12))
        }
        showDetails(Prefs.sp(a).getBoolean(KEY_DETAILS, false))

        // Bottom: speedometer (start) and the buttons (end) over the road's name, then the sheet.
        val above = FrameLayout(a).apply {
            setPadding(dp(o), 0, dp(o), dp(10))
            addView(fabs, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM).apply { bottomMargin = dp(36) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(speedo, LinearLayout.LayoutParams(dp(104), dp(104)))
                addView(tomtom, wrap().apply { topMargin = dp(2) })
                addView(map.creditView(), wrap().apply { topMargin = dp(2) })   // the map data credit, with TomTom's
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM))
            // Above the credit lines (TomTom's and the map data's run along the bottom), so it never covers them.
            addView(roadPill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(48) })
        }
        val bottom = ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(above, full())
                addView(sheet, full())
            })
        }
        return FrameLayout(a).apply {
            addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    /** Opened with the details button: where points went so far, False alarm, the trip's tiles. */
    private fun buildDetails(): LinearLayout {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        breakdownRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(breakdownRow) }, full())
        muteBtn = Ui.button(a, a.getString(R.string.drive_false_alarm), Ui.Style.SECONDARY) { a.muteLast() }.apply {
            minHeight = dp(Ui.Space.DRIVE_TARGET)
            contentDescription = a.getString(R.string.drive_mute_last)
            setCompoundDrawablesRelative(Ui.icon(a, R.drawable.ic_mute, Ui.TEXT), null, null, null)
        }
        col.addView(muteBtn, full(10))
        scoreRing = ScoreRingView(a)   // kept for the score colour bands of the chips
        tripChip = Ui.chip(a, "", floating = false)
        placeChip = Ui.chip(a, "", R.drawable.ic_phone, floating = false)
        gps = Ui.chip(a, "", floating = false)
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(placeChip, wrap())
            addView(gps, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        }, full(10))
        val tiles = listOf(
            R.string.drive_tile_distance, R.string.drive_tile_time, R.string.drive_tile_score,
            R.string.drive_tile_warnings, R.string.drive_tile_bumps, R.string.drive_tile_strong,
        ).map { Ui.tile(a, a.getString(it)) }
        tDist = tiles[0].second; tTime = tiles[1].second; tScore = tiles[2].second
        tWarn = tiles[3].second; tBumps = tiles[4].second; tHoles = tiles[5].second
        col.addView(Ui.grid(a, tiles.map { it.first }, 3), full(10))
        return col
    }

    private fun toggleDetails() {
        val on = !Prefs.sp(a).getBoolean(KEY_DETAILS, false)
        Prefs.sp(a).edit().putBoolean(KEY_DETAILS, on).apply()
        showDetails(on)
    }

    private fun showDetails(on: Boolean) {
        details.visibility = if (on) View.VISIBLE else View.GONE
        detailsToggle.isSelected = on
    }

    /** The banner from the engine's spots ahead (copied numbers only), the strong state, and the red road. */
    private fun showNext(rec: Boolean) {
        val ahead = if (rec) NextBump.from(LiveState.spotsAhead) else emptyList()
        val n = ahead.firstOrNull()
        val then = ahead.getOrNull(1)
        thenChip.visibility = if (then == null) View.GONE else View.VISIBLE
        if (then != null) thenChip.text = a.getString(R.string.drive_next_then, sevName(then), distText(then.distanceM))
        thenChip.setTextColor(if (n != null && NextBump.near(n)) 0xE6FFFFFF.toInt() else 0xFFB8C0CC.toInt())
        val near = n != null && NextBump.near(n)
        val strongAhead = n?.takeIf { it.severity == Severity.STRONG && !it.maybe }
        map.aheadStrong(strongAhead?.distanceM?.takeIf { it <= 400.0 })
        if (n == null) {
            // Slim: nothing ahead.
            banner.setBackgroundColor(bannerBg)
            banner.setPadding(dp(16), dp(10), dp(16), dp(10))
            nextGlyph.visibility = View.GONE
            nextDist.visibility = View.GONE
            nextBeeps.visibility = View.GONE
            nextWhat.text = a.getString(R.string.drive_no_bumps_ahead)
            nextWhat.setTextColor(0xFFB8C0CC.toInt())
            banner.contentDescription = nextWhat.text
            nearShown = null
            return
        }
        nextGlyph.visibility = View.VISIBLE
        nextDist.visibility = View.VISIBLE
        nextBeeps.visibility = View.VISIBLE
        banner.setBackgroundColor(if (near) STRONG_BANNER else bannerBg)
        val band = if (n.maybe) Ui.SEV_MAYBE else Ui.sevColor(n.severity)
        val accent = if (near) 0xFFFFFFFF.toInt() else band
        nextGlyph.setImageResource(if (near) R.drawable.ic_warning else R.drawable.ic_bump)
        nextGlyph.imageTintList = ColorStateList.valueOf(if (near) Ui.SEV_STRONG else if (n.maybe) Ui.SEV_MAYBE else Ui.sevInk(n.severity))
        nextGlyph.background = GradientDrawable().apply {
            cornerRadius = Ui.dpf(a, 14f)
            setColor(if (near) 0xFFFFFFFF.toInt() else if (n.maybe) bannerBg else band)
            if (n.maybe) setStroke(dp(2), Ui.SEV_MAYBE, Ui.dpf(a, 6f), Ui.dpf(a, 4f))
        }
        nextDist.text = distText(n.distanceM)
        nextDist.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (near) Ui.Type.NEXT_NEAR else Ui.Type.NEXT)
        val road = map.roadName(Ui.locale(a).language == "ar")
        nextWhat.text = if (near) a.getString(R.string.drive_slow_to, EngineConfig().approachFastKmh.toInt()) + " · " + sevName(n)
            else if (road != null) sevName(n) + " · " + road else sevName(n)
        nextWhat.setTextColor(accent)
        for (i in 0 until nextBeeps.childCount) {
            nextBeeps.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i < n.beeps) accent else 0x4DFFFFFF)
            }
        }
        nextBeeps.contentDescription = a.resources.getQuantityString(R.plurals.drive_next_beeps, n.beeps, n.beeps)
        banner.contentDescription = nextWhat.text.toString() + ", " + nextDist.text
        if (nearShown != near) {
            nearShown = near
            banner.setPadding(dp(16), dp(if (near) 16 else 12), dp(16), dp(if (near) 16 else 12))
        }
    }

    /** Red when over the road's limit, or too fast for a strong bump close ahead. */
    private fun speedoAlarm(): Boolean {
        val n = NextBump.from(LiveState.spotsAhead).firstOrNull()
        val tooFast = n != null && NextBump.near(n) && LiveState.speedKmh > EngineConfig().approachFastKmh
        return LiveState.overLimit >= 1 || tooFast
    }

    /** The phone's volume panel for the warnings' sound (alarm volume with Loud beeps, else media). */
    private fun showVolume() {
        val am = a.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(if (Prefs.loud(a)) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    /** "Bump here": a manual label at this spot (label mode recordings keep it; otherwise it says where to turn it on). */
    private fun reportBump(v: View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        val ok = BumpService.label(a, Labels.BUMP)
        Toast.makeText(a, a.getString(if (ok) R.string.drive_report_saved else R.string.drive_report_label_off), Toast.LENGTH_SHORT).show()
        if (ok) updateLabelStatus()
    }

    /** Points lost so far, as chips ("Speeding −6"); none while the trip is too short. */
    private fun showBreakdown() {
        breakdownRow.removeAllViews()
        val bd = LiveState.scoreBreakdown ?: return
        for (item in bd.items.filter { it.points > 0 }) {
            val name = a.getString(when (item.category) {
                ScoreCategory.SPEEDING -> R.string.event_speeding
                ScoreCategory.HARSH_BRAKING -> R.string.trips_fact_braking
                ScoreCategory.HARSH_ACCEL -> R.string.trips_fact_accel
                ScoreCategory.CORNERING -> R.string.trips_fact_cornering
                ScoreCategory.SWERVES -> R.string.trips_fact_swerves
                ScoreCategory.PHONE_USE -> R.string.trips_fact_phone
                ScoreCategory.BUMPS_FAST -> R.string.trips_fact_bumps_fast
            })
            breakdownRow.addView(Ui.chip(a, name + " " + Ui.ltr("−${item.points}"), dot = Ui.ERR, floating = false),
                wrap().apply { marginEnd = dp(6) })
        }
    }

    private fun sevName(n: NextBump.Ahead): String = a.getString(when {
        n.maybe -> R.string.drive_next_maybe
        n.severity == Severity.STRONG -> R.string.drive_next_strong
        n.severity == Severity.MODERATE -> R.string.drive_next_moderate
        else -> R.string.drive_next_mild
    })

    private fun distText(m: Double): String {
        val (v, km) = NextBump.distanceText(m)
        return a.getString(if (km) R.string.map_dist_km else R.string.map_dist_m, v)
    }

    private fun placeName(): String = when (Prefs.placement(a)) {
        "mounted" -> a.getString(R.string.settings_place_mounted)
        "cupholder" -> a.getString(R.string.settings_place_cupholder)
        "pocket" -> a.getString(R.string.settings_place_pocket)
        "seat" -> a.getString(R.string.settings_place_seat)
        else -> ""
    }

    // ---------------------------------------------------------------- label mode

    /** Big buttons for the passenger who marks what the car just drove over (Settings → Road testing). */
    private fun buildLabelPanel(): View {
        // Label mode: the passenger's panel inside the sheet. Child order matters to tick(): the header first, the
        // "starts with the next recording" line last, everything between shown only while labels are accepted.
        labelCard = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        labelCard.addView(Ui.text(a, 13f, Ui.WARN, bold = true, value = a.getString(R.string.drive_label_header)).apply {
            background = Ui.rounded(a, Ui.WARN_CONT, Ui.Shape.CHIP)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setCompoundDrawablesRelative(Ui.icon(a, R.drawable.ic_tag, Ui.WARN, 18), null, null, null)
            compoundDrawablePadding = dp(10)
        })
        labelCard.addView(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.drive_label_driver_note)).apply {
            setPadding(dp(4), dp(8), 0, dp(8))
        })
        fun btn(kind: String, bg: Int, fg: Int, heightDp: Int) = Ui.bigButton(a, labelName(kind), bg, fg) { v -> tapLabel(v, kind) }.apply {
            minHeight = dp(heightDp)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        // No pothole buttons since v2: every spot is a bump (replay reads old pothole labels as bumps).
        labelCard.addView(Ui.row(a,
            btn(Labels.BUMP, Ui.SEV_MOD, Ui.SEV_MOD_INK, 88),
            btn(Labels.ROUGH, Ui.SURFACE3, Ui.TEXT, 88),
            gapDp = 10,
        ))
        labelCard.addView(btn(Labels.UNDO, Ui.ERR_CONT, Ui.ERR, Ui.Space.DRIVE_TARGET), full(10))
        labelStatus = Ui.text(a, 15f, Ui.TEXT, bold = true).apply { setPadding(dp(4), dp(10), 0, 0) }
        labelCard.addView(labelStatus)
        // Turned on mid-trip: the service only accepts labels from the next recording on.
        labelLater = Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.drive_label_later)).apply {
            setPadding(dp(4), dp(4), 0, 0)
        }
        labelCard.addView(labelLater)
        return labelCard
    }

    private fun tapLabel(v: View, kind: String) {
        // A firm buzz, so the passenger knows the tap counted without looking.
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        if (!BumpService.label(a, kind)) Toast.makeText(a, a.getString(R.string.drive_label_not_saved), Toast.LENGTH_SHORT).show()
        updateLabelStatus()
    }

    private fun updateLabelStatus() {
        val n = LiveState.labelCount
        val last = LiveState.lastLabel
        val t = if (last.isEmpty()) a.getString(R.string.drive_label_none)
            else a.getString(if (n == 1) R.string.drive_label_last_one else R.string.drive_label_last_many, labelName(last), n)
        if (labelStatus.text.toString() != t) labelStatus.text = t
    }

    private fun labelName(kind: String): String = when (kind) {
        Labels.BUMP -> a.getString(R.string.drive_label_bump)
        Labels.ROUGH -> a.getString(R.string.drive_label_rough)
        Labels.UNDO -> a.getString(R.string.drive_label_undo)
        else -> kind
    }

    // ---------------------------------------------------------------- setup checklist

    override fun onHide() = map.pause()

    override fun onDestroy() = map.destroy()

    override fun onShow() {
        map.resume()
        setupCard.removeAllViews()
        var rows = 0
        // One row at a time over the map: the first thing missing; the next shows once it is done.
        fun item(title: String, why: String, action: String, onClick: () -> Unit) {
            if (rows > 0) return
            setupCard.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(a, 15f, Ui.TEXT, bold = true, value = title))
                    addView(Ui.text(a, 13f, Ui.DIM, value = why))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.button(a, action, Ui.Style.PRIMARY, onClick), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            })
            rows++
        }
        setupCard.addView(Ui.text(a, 12f, Ui.ACCENT, bold = true, value = a.getString(R.string.drive_setup_header)).apply { setPadding(0, 0, 0, dp(8)) })
        if (a.missingBasics().isNotEmpty()) item(a.getString(R.string.drive_setup_location_title), a.getString(R.string.drive_setup_location_why), a.getString(R.string.common_allow)) { a.requestBasics() }
        if (!a.batteryOk()) item(a.getString(R.string.drive_setup_background_title), a.getString(R.string.drive_setup_background_why), a.getString(R.string.common_allow)) { a.batterySettings() }
        if (rows == 0 && !a.autoStartOn() && !Prefs.sp(a).getBoolean(KEY_HIDE_AUTO, false)) {
            item(a.getString(R.string.drive_setup_auto_title), a.getString(R.string.drive_setup_auto_why), a.getString(R.string.drive_setup_auto_action)) { a.setupAuto() }
            setupCard.addView(Ui.button(a, a.getString(R.string.common_not_now), Ui.Style.QUIET) {
                Prefs.sp(a).edit().putBoolean(KEY_HIDE_AUTO, true).apply()
                onShow()
            })
        }
        setupCard.visibility = if (rows == 0) View.GONE else View.VISIBLE
        loadMap()
        updateMe(centre = false)
        tick()
    }

    // ---------------------------------------------------------------- live

    override fun tick() {
        val rec = LiveState.recording
        // OSM bumps can warn here ("Maybe a bump" ahead): their credit shows with the map's.
        map.osmCredit = LiveState.osmBumps > 0 && Prefs.osmWarnings(a)
        if (wasRecording != rec) {
            wasRecording = rec
            map.radiusM = if (rec) 400.0 else 600.0
            map.puckLow = rec   // driving: more road ahead
            map.offlineChipTopDp = if (rec) 140 else 64
            if (rec) { map.recenter(); recenterBtn.visibility = View.GONE }
            readyView.visibility = if (rec) View.GONE else View.VISIBLE
            liveView.visibility = if (rec) View.VISIBLE else View.GONE
            if (!rec) loadMap()   // a trip just ended: its new bumps
        }
        val gpsOk = rec && LiveState.lastFixAtMs > 0 && SystemClock.elapsedRealtime() - LiveState.lastFixAtMs < 5000
        status.contentDescription = when {
            !rec -> a.getString(R.string.drive_status_stopped)
            gpsOk -> a.getString(R.string.drive_status_recording)
            else -> a.getString(R.string.drive_status_waiting_gps)
        }
        status.setTextColor(when { !rec -> Ui.DIM; gpsOk -> Ui.GREEN; else -> Ui.ORANGE })
        val line = SyncChoice.driveLine(a)
        if (syncLine.text.toString() != line) syncLine.text = line
        syncLine.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        val auto = if (a.autoStartOn()) a.getString(R.string.drive_gps_auto, Prefs.carName(a)) else ""
        if (autoChip.text.toString() != auto) autoChip.text = auto
        autoChip.visibility = if (auto.isEmpty()) View.INVISIBLE else View.VISIBLE
        readyHint.text = a.getString(if (a.autoStartOn()) R.string.drive_ready_auto_hint else R.string.drive_ready_hint)
        // The map: the blue dot every 2 s when parked; while recording it follows the car every second.
        if (if (rec) ticks % 4 == 0 else ticks % 8 == 0) updateMe(centre = rec)
        // Once a second is enough for the network check; off at once when recording stops.
        ticks++
        if (!rec || ticks % 4 == 0) offline.visibility = if (rec && OfflineBanner.show(a)) View.VISIBLE else View.GONE

        speed.text = if (gpsOk) String.format(Locale.US, "%.0f", LiveState.speedKmh) else if (rec) "–" else ""
        showNext(rec)
        (speed.parent as? View)?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (rec && gpsOk && speedoAlarm()) Ui.SEV_STRONG else bannerBg)
        }
        val sc = LiveState.liveScore
        if (scoreRing.score != sc) scoreRing.score = sc
        val km = a.getString(R.string.drive_km, String.format(Locale.US, "%.1f", LiveState.tripKm))
        tripTime.text = Ui.duration(LiveState.tripMovingS.toLong())
        tripSub.text = a.getString(R.string.drive_km_score, km, if (sc >= 0) sc.toString() else "–")
        if (ticks % 4 == 0) {
            val road = if (rec) map.roadName(Ui.locale(a).language == "ar") else null
            roadPill.text = road ?: ""
            roadPill.visibility = if (road == null) View.GONE else View.VISIBLE
            if (details.visibility == View.VISIBLE) showBreakdown()
        }
        val place = placeName()
        if (placeChip.text.toString() != place) placeChip.text = place
        placeChip.visibility = if (place.isEmpty()) View.GONE else View.VISIBLE
        showSign(rec, gpsOk)
        gps.text = when {
            !rec -> if (a.autoStartOn()) a.getString(R.string.drive_gps_auto, Prefs.carName(a)) else a.getString(R.string.drive_gps_tap_start)
            gpsOk -> a.getString(R.string.drive_gps_accuracy, String.format(Locale.US, "%.0f", LiveState.accuracyM), detectionText())
            else -> a.getString(R.string.drive_gps_looking)
        }

        // The trip that just ended (in memory), else the newest saved one: after a restart the row still shows.
        val last = if (LiveState.lastTripScore >= 0) LiveState.lastTripScore else savedLastScore
        lastTripRow.visibility = if (!rec && last >= 0) View.VISIBLE else View.GONE
        if (!rec && last >= 0) {
            Ui.setScore(lastTripBadge, last)
            lastTripTitle.text = a.getString(R.string.drive_last_trip_title, Ui.grade(a, last))
        }

        tDist.text = if (rec) a.getString(R.string.drive_km, String.format(Locale.US, "%.1f", LiveState.tripKm)) else "–"
        tTime.text = if (rec) Ui.duration(LiveState.tripMovingS.toLong()) else "–"
        val s = LiveState.liveScore
        tScore.text = if (rec && s >= 0) s.toString() else "–"
        tScore.setTextColor(if (rec) Ui.scoreColor(s) else Ui.TEXT)
        tWarn.text = if (rec) LiveState.tripBeeps.toString() else "–"
        tBumps.text = if (rec) LiveState.tripHits.toString() else "–"
        tHoles.text = if (rec) LiveState.tripPotholes.toString() else "–"   // strong bumps felt (the old field name)

        muteBtn.visibility = if (rec) View.VISIBLE else View.GONE

        val labelsLive = rec && LiveState.labelMode
        val labelsPending = rec && !LiveState.labelMode && Prefs.labelMode(a)
        labelCard.visibility = if (labelsLive || labelsPending) View.VISIBLE else View.GONE
        for (i in 1 until labelCard.childCount - 1) labelCard.getChildAt(i).visibility = if (labelsLive) View.VISIBLE else View.GONE
        labelLater.visibility = if (labelsPending) View.VISIBLE else View.GONE
        if (labelsLive) updateLabelStatus()
        // While a passenger labels, the trip details stay folded away: the buttons sit right above Stop.
        if (labelsLive && details.visibility == View.VISIBLE) details.visibility = View.GONE

        // No distractions while driving: the banner waits until recording stops.
        val up = if (rec) null else a.pendingUpdate()
        if (up == null) {
            updateCard.visibility = View.GONE
        } else {
            if (updateShown != up.version) {
                updateText.text = a.getString(R.string.drive_update_available, up.version)
                updateShown = up.version
            }
            updateCard.visibility = View.VISIBLE
        }

    }

    /** The live speed-limit sign and the speed's colour (orange over the limit, red over it by the chosen margin). */
    private fun showSign(rec: Boolean, gpsOk: Boolean) {
        // With the shared map unanswered nothing is looked up, so a sign stuck at "– –" would only mislead.
        val on = Prefs.liveLimits(a) && Prefs.syncChoice(a) != Prefs.SYNC_UNSET
        val known = LiveLimitText.signText(LiveState.speedLimitKmh, LiveState.speedLimitAgeMs) != LiveLimitText.UNKNOWN
        val show = rec && (known || LiveLimitText.showSign(rec, on, Prefs.liveLimitsConsentVersion(a)))
        signBox.visibility = if (show) View.VISIBLE else View.GONE
        tomtom.visibility = signBox.visibility   // TomTom's notice goes with their limit
        speed.setTextColor(0xFFFFFFFF.toInt())   // white on the dark speedometer; it turns red itself (speedoAlarm)
        if (!show) return
        val t = LiveLimitText.signText(LiveState.speedLimitKmh, LiveState.speedLimitAgeMs)
        sign.value = t
        sign.contentDescription = if (t == LiveLimitText.UNKNOWN) a.getString(R.string.live_limit_sign_unknown)
            else a.getString(R.string.live_limit_sign_desc, t)
    }

    private fun detectionText(): String = when {
        !LiveState.hasGyro -> a.getString(R.string.drive_detect_no_gyro)
        LiveState.forwardKnown -> a.getString(R.string.drive_detect_ready)
        else -> a.getString(R.string.drive_detect_learning)
    }

    companion object {
        private const val KEY_DETAILS = "ui_drive_details"
        /** The strong-bump banner: one deep red in both themes, so the white text stays readable (about 5.6:1). */
        private const val STRONG_BANNER = 0xFFC62828.toInt()
        private const val KEY_HIDE_AUTO = "ui_hide_auto_tip"
    }
}
