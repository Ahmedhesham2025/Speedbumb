package app.bumpbeeper

import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
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
    private lateinit var map: BumpMapView
    private lateinit var autoChip: TextView
    private lateinit var readyHint: TextView
    private lateinit var lastTripRow: LinearLayout
    private lateinit var lastTripBadge: TextView
    private lateinit var lastTripTitle: TextView
    private var mapCentred = false

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
        map = BumpMapView(a).apply { radiusM = 600.0 }
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
            val loaded = try { db.loadBumps() } finally { db.close() }
            a.runOnUiThread {
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

    // Recording: the map follows you; the next bump on top; speed, limit and score in the sheet; Stop.
    private lateinit var nextCard: LinearLayout
    private lateinit var nextGlyph: ImageView
    private lateinit var nextDist: TextView
    private lateinit var nextWhat: TextView
    private lateinit var nextBeeps: LinearLayout
    private lateinit var nextSlow: TextView
    private lateinit var thenChip: TextView
    private lateinit var edge: View
    private lateinit var scoreRing: ScoreRingView
    private lateinit var tripChip: TextView
    private lateinit var placeChip: TextView
    private lateinit var detailsToggle: TextView
    private lateinit var details: LinearLayout
    private var nearShown: Boolean? = null

    private fun buildLive(): View {
        val o = Ui.Space.OVERLAY

        // Top: the next bump (severity colour, distance, beeps), then the status chips.
        nextGlyph = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.rounded(a, 0x38FFFFFF, 16)
        }
        nextDist = Ui.text(a, Ui.Type.NEXT, bold = true)
        nextWhat = Ui.text(a, 15f, bold = true)
        nextBeeps = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            repeat(3) { i ->
                addView(View(a), LinearLayout.LayoutParams(dp(8), dp(8)).apply { if (i > 0) marginStart = dp(4) })
            }
        }
        nextSlow = Ui.text(a, 16f, bold = true).apply { setPadding(0, dp(12), 0, 0) }
        nextCard = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = Ui.dpf(a, 6f)
            visibility = View.GONE
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(nextGlyph, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, dp(8), 0)
                    addView(nextDist)
                    addView(nextWhat)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(nextBeeps, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = Gravity.TOP; topMargin = dp(6) })
            })
            addView(nextSlow, full())
        }
        thenChip = Ui.chip(a, "").apply { setTextColor(Ui.DIM); visibility = View.GONE }
        status = Ui.chip(a, "")
        offline = Ui.chip(a, a.getString(R.string.drive_offline_banner), R.drawable.ic_cloud_off).apply {
            background = Ui.rounded(a, Ui.WARN_CONT, Ui.Shape.CHIP)
            setTextColor(Ui.WARN)
            // The Arabic line is long: wrap to two lines rather than cut it.
            maxLines = 2
            ellipsize = null
            setPadding(paddingLeft, dp(6), paddingRight, dp(6))
            visibility = View.GONE
        }
        val chips = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(status, wrap())
            addView(offline, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }
        val top = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(o), dp(o), dp(o), 0)
            addView(nextCard, full())
            addView(thenChip, wrap().apply { topMargin = dp(8) })
            addView(chips, full(8))
        }
        // A strong bump close by: a red edge around the whole screen, seen from the corner of the eye.
        edge = View(a).apply {
            background = GradientDrawable().apply { setColor(0); setStroke(dp(6), Ui.SEV_STRONG) }
            visibility = View.GONE
        }

        // Sheet: speed (+ the road's limit) and the score, chips, then False alarm and Stop.
        speed = Ui.text(a, Ui.Type.SPEED, bold = true).apply { includeFontPadding = false }
        sign = SpeedSignView(a)
        signBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(sign, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(Ui.text(a, Ui.Type.CAPTION, Ui.DIM, value = SpeedLimitScoring.ATTRIBUTION).apply { gravity = Gravity.CENTER })
        }
        scoreRing = ScoreRingView(a).apply { caption = a.getString(R.string.drive_score_short) }
        val dash = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speed, wrap())
            addView(signBox, wrap().apply { marginStart = dp(14) })
            addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
            addView(scoreRing, LinearLayout.LayoutParams(dp(76), dp(76)))
        }
        tripChip = Ui.chip(a, "", floating = false)
        placeChip = Ui.chip(a, "", R.drawable.ic_phone, floating = false)
        gps = Ui.chip(a, "", floating = false)
        val meta = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tripChip, wrap())
            addView(placeChip, wrap().apply { marginStart = dp(6) })
            addView(gps, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        }
        muteBtn = Ui.button(a, a.getString(R.string.drive_false_alarm), Ui.Style.SECONDARY) { a.muteLast() }.apply {
            minHeight = dp(Ui.Space.DRIVE_TARGET)
            contentDescription = a.getString(R.string.drive_mute_last)
        }
        startBtn = Ui.bigPill(a, a.getString(R.string.drive_stop_short), R.drawable.ic_stop, Ui.ERR_CONT, Ui.ERR) { a.toggleRecording() }.apply {
            contentDescription = a.getString(R.string.drive_stop_desc)
        }
        val actions = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(muteBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f).apply { marginStart = dp(10) })
        }
        detailsToggle = Ui.text(a, 14f, Ui.PRIMARY, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            isClickable = true
            setOnClickListener { toggleDetails() }
        }
        details = buildDetails()
        val sheet = Ui.sheet(a).apply {
            addView(dash, full())
            addView(meta, full(10))
            addView(buildLabelPanel(), full(10))
            addView(actions, full(12))
            addView(detailsToggle, full(4))
            addView(details, full())
        }
        showDetails(Prefs.sp(a).getBoolean(KEY_DETAILS, false))
        val me = Ui.fab(a, R.drawable.ic_my_location, a.getString(R.string.drive_me)) { centreOnMe() }
        // The sheet scrolls when the trip details are open on a small screen; the map stays behind it.
        val bottom = ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(me, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    gravity = Gravity.END
                    marginEnd = dp(o)
                    bottomMargin = dp(o)
                })
                addView(sheet, full())
            })
        }
        return FrameLayout(a).apply {
            addView(edge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    /** Folded away by default: this trip's numbers (what just happened and the jolt meter are in Diagnostics). */
    private fun buildDetails(): LinearLayout {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val tiles = listOf(
            R.string.drive_tile_distance, R.string.drive_tile_time, R.string.drive_tile_score,
            R.string.drive_tile_warnings, R.string.drive_tile_bumps, R.string.drive_tile_strong,
        ).map { Ui.tile(a, a.getString(it)) }
        tDist = tiles[0].second; tTime = tiles[1].second; tScore = tiles[2].second
        tWarn = tiles[3].second; tBumps = tiles[4].second; tHoles = tiles[5].second
        col.addView(Ui.grid(a, tiles.map { it.first }, 3), full(4))

        return col
    }

    private fun toggleDetails() {
        val on = !Prefs.sp(a).getBoolean(KEY_DETAILS, false)
        Prefs.sp(a).edit().putBoolean(KEY_DETAILS, on).apply()
        showDetails(on)
    }

    private fun showDetails(on: Boolean) {
        detailsToggle.text = a.getString(if (on) R.string.drive_details_open else R.string.drive_details_closed)
        details.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** The next-bump card from the engine's spots ahead (copied numbers only), and the strong-bump state. */
    private fun showNext(rec: Boolean) {
        val ahead = if (rec) NextBump.from(LiveState.spotsAhead) else emptyList()
        val n = ahead.firstOrNull()
        nextCard.visibility = if (n == null) View.GONE else View.VISIBLE
        val then = ahead.getOrNull(1)
        thenChip.visibility = if (then == null) View.GONE else View.VISIBLE
        if (then != null) thenChip.text = a.getString(R.string.drive_next_then, sevName(then), distText(then.distanceM))
        val near = n != null && NextBump.near(n)
        edge.visibility = if (near) View.VISIBLE else View.GONE
        if (n == null) return
        val bg = if (n.maybe) Ui.SURFACE else Ui.sevColor(n.severity)
        val ink = if (n.maybe) Ui.TEXT else Ui.sevInk(n.severity)
        nextCard.background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = Ui.dpf(a, 22f)
            if (n.maybe) setStroke(dp(2), Ui.SEV_MAYBE, Ui.dpf(a, 6f), Ui.dpf(a, 4f))
        }
        nextGlyph.setImageResource(if (near) R.drawable.ic_warning else R.drawable.ic_bump)
        nextGlyph.imageTintList = ColorStateList.valueOf(ink)
        nextDist.text = distText(n.distanceM)
        nextDist.setTextColor(ink)
        nextDist.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (near) Ui.Type.NEXT_NEAR else Ui.Type.NEXT)
        nextWhat.text = sevName(n)
        nextWhat.setTextColor(ink)
        for (i in 0 until nextBeeps.childCount) {
            nextBeeps.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i < n.beeps) ink else (ink and 0x00FFFFFF) or 0x4D000000)
            }
        }
        nextBeeps.contentDescription = a.resources.getQuantityString(R.plurals.drive_next_beeps, n.beeps, n.beeps)
        nextCard.contentDescription = nextWhat.text.toString() + ", " + nextDist.text
        nextSlow.visibility = if (near) View.VISIBLE else View.GONE
        if (near) {
            nextSlow.text = a.getString(R.string.drive_slow_to, EngineConfig().approachFastKmh.toInt())
            nextSlow.setTextColor(ink)
        }
        if (nearShown != near) {
            nearShown = near
            nextCard.setPadding(dp(16), dp(if (near) 18 else 14), dp(16), dp(if (near) 18 else 14))
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

    /** "47 km/h" with the unit smaller than the number, in either language. */
    private fun speedText(kmh: String): CharSequence {
        val full = a.getString(R.string.drive_speed_kmh, kmh)
        val at = full.indexOf(kmh)
        return SpannableString(full).apply {
            if (at > 0) setSpan(RelativeSizeSpan(0.3f), 0, at, 0)
            if (at >= 0 && at + kmh.length < full.length) setSpan(RelativeSizeSpan(0.3f), at + kmh.length, full.length, 0)
        }
    }

    private fun placeName(): String = when (Prefs.placement(a)) {
        "mounted" -> a.getString(R.string.settings_place_mounted)
        "cupholder" -> a.getString(R.string.settings_place_cupholder)
        "pocket" -> a.getString(R.string.settings_place_pocket)
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

    override fun onShow() {
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
        if (wasRecording != rec) {
            wasRecording = rec
            map.radiusM = if (rec) 400.0 else 600.0
            readyView.visibility = if (rec) View.GONE else View.VISIBLE
            liveView.visibility = if (rec) View.VISIBLE else View.GONE
            if (!rec) loadMap()   // a trip just ended: its new bumps
        }
        val gpsOk = rec && LiveState.lastFixAtMs > 0 && SystemClock.elapsedRealtime() - LiveState.lastFixAtMs < 5000
        status.text = when {
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

        speed.text = if (gpsOk) speedText(String.format(Locale.US, "%.0f", LiveState.speedKmh))
            else if (rec) speedText("–") else ""
        showNext(rec)
        val sc = LiveState.liveScore
        if (scoreRing.score != sc) scoreRing.score = sc
        tripChip.text = a.getString(R.string.drive_km, String.format(Locale.US, "%.1f", LiveState.tripKm)) + " · " +
            Ui.duration(LiveState.tripMovingS.toLong())
        val place = placeName()
        if (placeChip.text.toString() != place) placeChip.text = place
        placeChip.visibility = if (place.isEmpty()) View.GONE else View.VISIBLE
        showSign(rec, gpsOk)
        gps.text = when {
            !rec -> if (a.autoStartOn()) a.getString(R.string.drive_gps_auto, Prefs.carName(a)) else a.getString(R.string.drive_gps_tap_start)
            gpsOk -> a.getString(R.string.drive_gps_accuracy, String.format(Locale.US, "%.0f", LiveState.accuracyM), detectionText())
            else -> a.getString(R.string.drive_gps_looking)
        }

        val last = LiveState.lastTripScore
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
        detailsToggle.visibility = if (labelsLive) View.GONE else View.VISIBLE
        if (labelsLive) details.visibility = View.GONE
        else if (details.visibility == View.GONE && Prefs.sp(a).getBoolean(KEY_DETAILS, false)) details.visibility = View.VISIBLE

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
        val show = LiveLimitText.showSign(rec, on, Prefs.liveLimitsConsentVersion(a))
        signBox.visibility = if (show) View.VISIBLE else View.GONE
        speed.setTextColor(if (show && gpsOk) LiveLimitText.speedColor(LiveState.overLimit) else Ui.TEXT)
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
        private const val KEY_HIDE_AUTO = "ui_hide_auto_tip"
    }
}
