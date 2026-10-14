package app.bumpbeeper

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var body: TextView
    /** The newest saved crash, made short ([shortError]); read off the main thread in [onShow]. */
    @Volatile private var lastCrash = ""

    override val view: View = build()

    private fun build(): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        fun add(v: View, top: Int = 0) = col.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        add(Ui.text(a, 22f, Ui.TEXT, bold = true, value = a.getString(R.string.diag_title)))
        add(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.diag_hint)), 4)

        val mark = Ui.card(a)
        markBtn = Ui.button(a, a.getString(R.string.diag_mark), Ui.Style.PRIMARY) { tapMark() }
        markWhy = Ui.text(a, 13f, Ui.DIM).apply { setPadding(0, dp(8), 0, 0) }
        mark.addView(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.diag_mark_hint)).apply { setPadding(0, 0, 0, dp(8)) })
        mark.addView(markBtn)
        mark.addView(markWhy)
        add(mark, 12)

        val card = Ui.card(a)
        body = Ui.text(a, 14f, Ui.TEXT).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }
        card.addView(body)
        add(card, 12)
        add(Ui.button(a, a.getString(R.string.common_close)) { onClose() }, 16)
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
        val t = lines(a, lastCrash).joinToString("\n")
        if (body.text.toString() != t) body.text = t
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
