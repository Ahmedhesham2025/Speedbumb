package app.bumpbeeper

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.bumpbeeper.auto.StartLog
import app.bumpbeeper.crash.CrashLog
import app.bumpbeeper.research.ResearchUploader
import app.bumpbeeper.sync.TrainingConsent
import app.bumpbeeper.ui.AutoDetectText
import app.bumpbeeper.ui.ResearchChoice
import app.bumpbeeper.ui.SyncChoice
import java.util.Locale

/**
 * Settings → Diagnostics: what the app is doing, read-only and in plain text, for test drives and support. Nothing
 * here is uploaded, and it never shows a position or a token. "Mark" puts a mark into this trip's research file (and
 * the debug recording in label mode) each time a test-drive passenger picks up the phone.
 */
class DiagnosticsPage(private val a: MainActivity, private val onClose: () -> Unit = {}) : Page {
    private fun dp(v: Int) = Ui.dp(a, v)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var markBtn: TextView
    private lateinit var markWhy: TextView
    /** The newest saved crash, made short ([shortError]); read off the main thread in [onShow]. */
    @Volatile private var lastCrash = ""

    override val view: View = build()

    private lateinit var groups: LinearLayout
    private lateinit var graph: JoltGraphView
    private lateinit var lastEvent: TextView
    private lateinit var driveEvent: TextView
    private lateinit var ignored: TextView
    private var shown: List<String> = emptyList()

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        fun add(v: View, top: Int = 0) = col.addView(v, full(top))

        // App bar: back (closes), title.
        add(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = ColorStateList.valueOf(Ui.DIM)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = a.getString(R.string.common_close)
                isClickable = true
                setOnClickListener { onClose() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(Ui.text(a, 20f, Ui.TEXT, bold = true, value = a.getString(R.string.diag_title)),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        })
        add(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.diag_hint)).apply { setPadding(dp(4), 0, dp(4), 0) })

        // Mark: a big button for the passenger on a test drive.
        add(Ui.section(a, a.getString(R.string.diag_group_mark)))   // not "Mark": the button alone has that text
        val mark = Ui.card(a)
        markBtn = Ui.bigPill(a, a.getString(R.string.diag_mark), R.drawable.ic_tag, Ui.PRIMARY, Ui.ON_PRIMARY) { tapMark() }.apply {
            minHeight = dp(72)
        }
        markWhy = Ui.text(a, 13f, Ui.DIM).apply { setPadding(0, dp(8), 0, 0) }
        mark.addView(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.diag_mark_hint)).apply { setPadding(0, 0, 0, dp(10)) })
        mark.addView(markBtn)
        mark.addView(markWhy)
        add(mark)

        // The jolt meter and the last events (they were on the Drive screen).
        add(Ui.section(a, a.getString(R.string.diag_group_live)))
        val live = Ui.card(a)
        graph = JoltGraphView(a)
        live.addView(graph, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110)))
        live.addView(Ui.text(a, 12f, Ui.DIM, value = a.getString(R.string.drive_meter_hint)).apply { setPadding(0, dp(6), 0, dp(4)) })
        live.addView(Ui.divider(a))
        lastEvent = Ui.text(a, 15f, Ui.TEXT, bold = true)
        driveEvent = Ui.text(a, 14f, Ui.WARN)
        ignored = Ui.text(a, 13f, Ui.DIM)
        live.addView(lastEvent)
        live.addView(driveEvent, full(4))
        live.addView(ignored, full(4))
        add(live)

        // Status rows, one card per group of lines().
        groups = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        add(groups)
        return ScrollView(a).apply { setBackgroundColor(Ui.BG); addView(col) }
    }

    private fun tapMark() {
        markBtn.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        val ok = BumpService.mark(a)
        Toast.makeText(a, a.getString(if (ok) R.string.diag_mark_saved else R.string.diag_mark_not_saved), Toast.LENGTH_SHORT).show()
    }

    /** Opened: the research queue and the last crash are read in the background (file I/O), then shown. */
    override fun onShow() {
        val app = a.applicationContext
        Thread({
            try { ResearchUploader.publish(app) } catch (_: Exception) {}
            lastCrash = try { CrashLog.list(app).firstOrNull()?.readText()?.let { shortError(it) } ?: "" } catch (_: Exception) { "" }
            ui.post { tick() }
        }, "diagnostics").start()
        tick()
    }

    override fun tick() {
        val markable = BumpService.markable()
        markBtn.isEnabled = markable
        markBtn.alpha = if (markable) 1f else 0.4f
        val why = when {
            !LiveState.recording -> a.getString(R.string.diag_mark_no_drive)
            !markable -> a.getString(R.string.diag_mark_no_target)
            else -> a.getString(R.string.diag_mark_ready)
        }
        if (markWhy.text.toString() != why) markWhy.text = why

        graph.threshold = Prefs.thresholdFor(Prefs.sensitivity(a)).toFloat()
        graph.invalidate()
        lastEvent.text = LiveState.lastEvent.ifEmpty { a.getString(R.string.drive_nothing_yet) }
        driveEvent.text = LiveState.lastDriveEvent
        driveEvent.visibility = if (LiveState.lastDriveEvent.isEmpty()) View.GONE else View.VISIBLE
        ignored.text = LiveState.lastIgnored
        ignored.visibility = if (LiveState.lastIgnored.isEmpty()) View.GONE else View.VISIBLE

        val lines = lines(a, lastCrash)
        if (lines != shown) {
            shown = lines
            showGroups(lines)
        }
    }

    /**
     * The same lines as before, as cards: one per group (they are separated by blank lines), a row per line with
     * an OK, warning or neutral dot. "Name: value" lines put the value at the end.
     */
    private fun showGroups(lines: List<String>) {
        groups.removeAllViews()
        val yes = a.getString(R.string.diag_yes)
        val no = a.getString(R.string.diag_no)
        val ok = a.getString(R.string.diag_ok)
        val titles = listOf(R.string.diag_group_perms, R.string.diag_group_service, R.string.diag_group_uploads,
            R.string.diag_group_start, R.string.diag_group_app)
        val split = ArrayList<MutableList<String>>().apply { add(ArrayList()) }
        for (l in lines) if (l.isEmpty()) split.add(ArrayList()) else split.last().add(l)
        split.filter { it.isNotEmpty() }.forEachIndexed { g, group ->
            groups.addView(Ui.section(a, a.getString(titles.getOrElse(g) { R.string.diag_group_app })))
            val card = Ui.card(a, 0).apply { setPadding(0, dp(4), 0, dp(4)) }
            for (line in group) {
                val at = line.lastIndexOf(": ")
                val key = if (at > 0) line.substring(0, at) else line
                val value = if (at > 0) line.substring(at + 2) else ""
                val state = when {
                    value == yes || value == ok || value.startsWith(ok) -> Ui.OK
                    value == no -> Ui.WARN
                    else -> Ui.FAINT
                }
                card.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(44)
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                    addView(View(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(state) } },
                        LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
                    addView(Ui.text(a, 14f, Ui.TEXT, value = key).apply { setTextIsSelectable(true) },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    if (value.isNotEmpty()) addView(Ui.text(a, 14f, Ui.DIM, value = value).apply {
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                        setPadding(dp(8), 0, 0, 0)
                    })
                })
            }
            groups.addView(card)
        }
    }

    companion object {
        /** Settings → Diagnostics, full screen; refreshed every second while open. */
        fun open(a: MainActivity): Dialog {
            val d = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
            val page = DiagnosticsPage(a) { d.dismiss() }
            val ui = Handler(Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() { page.tick(); ui.postDelayed(this, 1000) }
            }
            d.setContentView(page.view)
            d.window?.setBackgroundDrawable(ColorDrawable(Ui.BG))
            d.setOnDismissListener { ui.removeCallbacks(tick) }
            d.show()
            page.onShow()
            ui.postDelayed(tick, 1000)
            return d
        }

        /**
         * The diagnostics text: permissions, battery, service, GPS (age and accuracy only), sensor rate, the three
         * upload queues, the last error ([lastCrash], already short) and the app build. Main thread; cheap.
         */
        fun lines(ctx: Context, lastCrash: String): List<String> {
            fun yn(b: Boolean) = ctx.getString(if (b) R.string.diag_yes else R.string.diag_no)
            fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            val out = ArrayList<String>()
            out += ctx.getString(R.string.diag_perm_fine, yn(has(Manifest.permission.ACCESS_FINE_LOCATION)))
            out += ctx.getString(R.string.diag_perm_background, yn(has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
            out += ctx.getString(R.string.diag_perm_notifications,
                if (Build.VERSION.SDK_INT >= 33) yn(has(Manifest.permission.POST_NOTIFICATIONS)) else ctx.getString(R.string.diag_not_needed))
            out += ctx.getString(R.string.diag_perm_activity,
                if (BuildConfig.FLAVOR == "play") yn(has(Manifest.permission.ACTIVITY_RECOGNITION)) else ctx.getString(R.string.diag_not_used))
            val pm = ctx.getSystemService(PowerManager::class.java)
            out += ctx.getString(R.string.diag_battery, yn(pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true))
            out += ""
            out += ctx.getString(when {
                LiveState.recording -> R.string.diag_service_recording
                LiveState.watching -> R.string.diag_service_watching
                else -> R.string.diag_service_off
            })
            val fixAt = LiveState.lastFixAtMs
            out += if (fixAt <= 0L) ctx.getString(R.string.diag_gps_none)
                else ctx.getString(R.string.diag_gps, (SystemClock.elapsedRealtime() - fixAt) / 1000, LiveState.accuracyM.toInt())
            val askedHz = 1_000_000 / Prefs.sensorPeriodUs(ctx)
            out += if (LiveState.recording && LiveState.sensorHz > 0) {
                ctx.getString(R.string.diag_sensor, String.format(Locale.US, "%.0f", LiveState.sensorHz), askedHz)
            } else ctx.getString(R.string.diag_sensor_asked, askedHz)
            out += ""
            if (Prefs.syncChoice(ctx) == Prefs.SYNC_UNSET) {
                out += ctx.getString(R.string.diag_sync_off)
            } else {
                out += SyncChoice.lastSynced(ctx, LiveState.syncLastAt)
                out += ctx.getString(R.string.diag_sync_result, SyncChoice.error(ctx, LiveState.syncLastError).ifEmpty { ctx.getString(R.string.diag_ok) })
                out += ctx.getString(R.string.diag_sync_queue, LiveState.syncPending)
            }
            val (rInfo, rProblem) = ResearchChoice.status(ctx, ResearchUploader.status)
            out += ctx.getString(R.string.diag_research, yn(Prefs.researchRecording(ctx)))
            out += listOf(rInfo, rProblem).filter { it.isNotEmpty() }
            val st = TrainingConsent.status(ctx)
            val (tInfo, tProblem) = AutoDetectText.training(ctx, st)
            out += ctx.getString(R.string.diag_training, yn(st.enabled), st.queued)
            out += listOf(tInfo, tProblem).filter { it.isNotEmpty() }
            out += ""
            out += ctx.getString(R.string.diag_start_log)
            out += StartLog.lines(ctx).ifEmpty { listOf(ctx.getString(R.string.diag_none)) }.map { "  $it" }
            out += ""
            out += ctx.getString(R.string.diag_last_error, lastCrash.ifEmpty { ctx.getString(R.string.diag_none) })
            out += ctx.getString(R.string.diag_app, TraceWriter.appVersion(ctx), BuildConfig.VERSION_CODE, BuildConfig.FLAVOR, BuildConfig.BUILD_TYPE)
            return out
        }

        /** A long run of letters and digits; replaced when it has a digit (a token, key or id; class names don't). */
        private val TOKEN = Regex("""[A-Za-z0-9_\-]{20,}""")

        /**
         * A saved crash in one short line: the exception's first line (after the header), with anything that looks
         * like a position or a file path ([CrashLog.scrub]) or a token (a long run of letters and digits) replaced.
         */
        fun shortError(crash: String): String {
            val stack = crash.substringAfter("\n\n", crash)
            val first = stack.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
            val clean = CrashLog.scrub(first).replace(TOKEN) { m -> if (m.value.any { it.isDigit() }) "<token>" else m.value }
            val time = crash.lineSequence().firstOrNull { it.startsWith("time=") }?.substringAfter('=')?.take(16)
            return (if (time != null) "$time " else "") + clean.take(140)
        }
    }
}
