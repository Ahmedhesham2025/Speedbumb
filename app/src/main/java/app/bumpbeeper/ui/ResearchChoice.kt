package app.bumpbeeper.ui

import android.app.Dialog
import android.content.Context
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.LiveState
import app.bumpbeeper.MainActivity
import app.bumpbeeper.Prefs
import app.bumpbeeper.R
import app.bumpbeeper.Ui
import app.bumpbeeper.research.ResearchConsent
import app.bumpbeeper.research.ResearchFiles
import app.bumpbeeper.research.ResearchUploader
import app.bumpbeeper.sync.SupabaseAuth
import java.util.Locale

/**
 * The research recordings question ([ResearchConsent.RESEARCH_CONSENT_VERSION]) and the words Settings uses for it.
 * Asked once at first start, after the shared-map question and never while recording; Settings asks it again before
 * switching research on. Back counts as "No thanks"; a plain dismiss() (recording started, rotation) counts as nothing,
 * so the first-start question comes again on a later resume.
 */
object ResearchChoice {
    /** Pure rule, unit-tested: show the first-start question now? */
    fun shouldAsk(asked: Int, on: Boolean, recording: Boolean, mapQuestionOpen: Boolean): Boolean =
        asked < ResearchConsent.RESEARCH_CONSENT_VERSION && !on && !recording && !mapQuestionOpen

    fun due(ctx: Context, mapQuestionOpen: Boolean): Boolean =
        shouldAsk(Prefs.researchAsked(ctx), Prefs.researchRecording(ctx), LiveState.recording, mapQuestionOpen)

    /** The full-screen question; the answer is applied ([ResearchConsent.setEnabled]), then [answered] (true = Yes). */
    fun dialog(a: MainActivity, answered: (Boolean) -> Unit): Dialog {
        fun dp(v: Int) = Ui.dp(a, v)
        val d = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
        var done = false
        fun answer(yes: Boolean) {
            if (done) return
            done = true
            Prefs.setResearchAsked(a, ResearchConsent.RESEARCH_CONSENT_VERSION)
            ResearchConsent.setEnabled(a, yes, ResearchConsent.RESEARCH_CONSENT_VERSION)
            if (yes) a.toast(a.getString(R.string.research_on_toast))
            if (d.isShowing) d.dismiss()
            answered(yes)
        }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        fun add(v: View, top: Int) = col.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        fun para(size: Float, color: Int, s: String) = Ui.text(a, size, color, value = s).apply { setLineSpacing(0f, 1.15f) }

        add(Ui.text(a, 13f, Ui.ACCENT, bold = true, value = a.getString(R.string.research_ask_kicker)), 0)
        add(Ui.text(a, 24f, Ui.TEXT, bold = true, value = a.getString(R.string.research_ask_title)), 6)
        add(para(16f, Ui.DIM, a.getString(R.string.research_ask_intro)), 10)
        val bullets = Ui.card(a)
        listOf(R.string.research_ask_point_what, R.string.research_ask_point_upload, R.string.research_ask_point_never,
            R.string.research_ask_point_use).forEachIndexed { i, res ->
            bullets.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.text(a, 16f, Ui.GREEN, bold = true, value = "✓"), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
                addView(para(15f, Ui.TEXT, a.getString(res)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) topMargin = dp(12) })
        }
        add(bullets, 20)
        // The whole list, folded away so the screen stays short.
        val details = para(14f, Ui.DIM, a.getString(R.string.research_ask_details, ResearchFiles.SHARE_COPY_FOLDER)).apply { visibility = View.GONE }
        add(Ui.text(a, 14f, Ui.ACCENT, bold = true, value = a.getString(R.string.research_ask_what)).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }, 6)
        add(details, 0)
        fun big(label: String, style: Ui.Style, yes: Boolean) = Ui.button(a, label, style) { answer(yes) }.apply {
            minHeight = dp(56)
            textSize = 17f
        }
        add(big(a.getString(R.string.research_ask_yes), Ui.Style.PRIMARY, true), 28)
        add(big(a.getString(R.string.research_ask_no), Ui.Style.SECONDARY, false), 10)
        add(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.research_ask_footer)).apply { gravity = Gravity.CENTER_HORIZONTAL }, 8)
        d.setContentView(ScrollView(a).apply {
            isFillViewport = true
            setBackgroundColor(Ui.BG)
            addView(col)
        })
        d.setOnCancelListener { answer(false) }
        return d
    }

    // ---------------------------------------------------------------- Settings

    /** Settings: what the uploader is doing ("" = nothing to say) and a problem line ("" = none). */
    fun status(ctx: Context, st: ResearchUploader.Status, now: Long = System.currentTimeMillis()): Pair<String, String> {
        val on = Prefs.researchRecording(ctx)
        fun ago(t: Long) = DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString()
        val info = ArrayList<String>()
        if (on && Prefs.researchOnPending(ctx)) info += ctx.getString(R.string.research_status_pending_on)
        if (!on && Prefs.researchOffPending(ctx)) info += ctx.getString(R.string.research_status_pending_off)
        if (on && st.files > 0) info += ctx.getString(R.string.research_status_waiting, st.files, String.format(Locale.US, "%.1f", st.bytes / 1_000_000.0))
        if (on || st.lastUpload > 0) {
            info += if (st.lastUpload > 0) ctx.getString(R.string.research_status_last, ago(st.lastUpload)) else ctx.getString(R.string.research_status_last_never)
        }
        val problem = when {
            !on && Prefs.researchNote(ctx) == ResearchConsent.SESSION_RESET -> ctx.getString(R.string.research_session_reset)
            on && st.pausedUntil > now -> ctx.getString(when (st.pausedWhy) {
                "device_daily" -> R.string.research_status_paused_daily
                "refused" -> R.string.research_status_paused_refused
                else -> R.string.research_status_paused_full
            }, ago(st.pausedUntil))
            else -> ""
        }
        return info.joinToString("\n") to problem
    }

    /** The anonymous account id the files go up under (their folder on the server), or null before the first sign-in. */
    fun researchId(ctx: Context): String? =
        SupabaseAuth(ctx, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY).userId?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US)

    /** "Research ID: …", plus the earlier ones files went up under (a reset anonymous ID). */
    fun idText(ctx: Context, current: String?, used: List<String>): String {
        val first = if (current == null) ctx.getString(R.string.research_id_none) else ctx.getString(R.string.research_id, current)
        val earlier = used.filter { it != current }
        return if (earlier.isEmpty()) first else first + "\n" + ctx.getString(R.string.research_id_earlier, earlier.joinToString(", "))
    }
}
