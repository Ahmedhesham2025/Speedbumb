package app.bumpbeeper.ui

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import app.bumpbeeper.BumpDb
import app.bumpbeeper.LiveState
import app.bumpbeeper.MainActivity
import app.bumpbeeper.Prefs
import app.bumpbeeper.R
import app.bumpbeeper.Ui
import app.bumpbeeper.sync.Sync
import java.util.Locale

/**
 * The shared-map question ("Warnings from other drivers") and the words the screens use for the sync status.
 *
 * Asked when the app opens and the user hasn't chosen yet, never while recording. "Decide later" (or Back) asks once
 * more after [TRIPS_BEFORE_ASKING_AGAIN] trips, then never again; Settings → Shared map can always change it.
 * The bookkeeping is UI-only, so it lives in its own preferences file, not in [Prefs].
 */
object SyncChoice {
    const val TRIPS_BEFORE_ASKING_AGAIN = 3
    /** After this many answers the question is never shown again. */
    const val MAX_ASKS = 2
    private const val FILE = "ui_state"
    private const val KEY_ANSWERS = "sync_answers"     // times the question was answered or put off
    private const val KEY_LATER_AT = "sync_later_at"   // wall ms of the last "Decide later"

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Pure rule, unit-tested: should the question be shown now? */
    fun shouldAsk(choice: String, recording: Boolean, answers: Int, tripsSinceLater: Int): Boolean = when {
        choice != Prefs.SYNC_UNSET || recording -> false
        answers <= 0 -> true
        answers < MAX_ASKS -> tripsSinceLater >= TRIPS_BEFORE_ASKING_AGAIN
        else -> false
    }

    /** The user chose in Settings: the first-run question is settled for good. */
    fun markAnswered(ctx: Context) = sp(ctx).edit().putInt(KEY_ANSWERS, MAX_ASKS).apply()

    /** A trip count is being read (so resumes in quick succession don't start several). */
    @Volatile private var checking = false

    /**
     * Shows the question if it is due; called on every resume. The trip count is read off the main thread;
     * [show] runs on the main thread.
     */
    fun maybeAsk(a: MainActivity, show: () -> Unit) {
        val answers = sp(a).getInt(KEY_ANSWERS, 0)
        val choice = Prefs.syncChoice(a)
        if (!shouldAsk(choice, LiveState.recording, answers, if (answers > 0) TRIPS_BEFORE_ASKING_AGAIN else 0)) return
        if (answers <= 0) { show(); return }
        if (checking) return
        checking = true
        val since = sp(a).getLong(KEY_LATER_AT, 0L)
        val app = a.applicationContext
        Thread({
            val trips = try {
                val db = BumpDb(app)
                try { db.trips(TRIPS_BEFORE_ASKING_AGAIN).count { it.startTs > since } } finally { db.close() }
            } catch (_: Exception) { 0 }
            checking = false
            a.runOnUiThread {
                if (!a.isFinishing && !a.isDestroyed &&
                    shouldAsk(Prefs.syncChoice(a), LiveState.recording, sp(a).getInt(KEY_ANSWERS, 0), trips)) show()
            }
        }, "sync-ask").start()
    }

    /**
     * The full-screen question. Back counts as "Decide later"; a plain dismiss() (recording started, screen rotated)
     * counts as nothing, so it is asked again later.
     */
    fun dialog(a: MainActivity, onChosen: () -> Unit): Dialog {
        fun dp(v: Int) = Ui.dp(a, v)
        val d = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
        var answered = false
        fun answer(choice: String?) {
            if (answered) return
            answered = true
            val p = sp(a)
            if (choice == null) {
                p.edit().putInt(KEY_ANSWERS, p.getInt(KEY_ANSWERS, 0) + 1).putLong(KEY_LATER_AT, System.currentTimeMillis()).apply()
            } else {
                markAnswered(a)
                Sync.setChoice(a, choice)
                a.toast(a.getString(if (choice == Prefs.SYNC_SHARE) R.string.sync_toast_sharing else R.string.sync_toast_receiving))
            }
            if (d.isShowing) d.dismiss()
            onChosen()
        }

        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        fun add(v: View, top: Int) = col.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })

        add(Ui.text(a, 13f, Ui.ACCENT, bold = true, value = a.getString(R.string.sync_ask_kicker)), 0)
        add(Ui.text(a, 26f, Ui.TEXT, bold = true, value = a.getString(R.string.sync_ask_title)), 6)
        add(Ui.text(a, 16f, Ui.DIM, value = a.getString(R.string.sync_ask_intro)).apply { setLineSpacing(0f, 1.15f) }, 10)

        val bullets = Ui.card(a)
        listOf(R.string.sync_ask_point_route, R.string.sync_ask_point_ends, R.string.sync_ask_point_identity,
            R.string.sync_ask_point_area).forEachIndexed { i, res ->
            bullets.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.text(a, 16f, Ui.GREEN, bold = true, value = "✓"), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
                addView(Ui.text(a, 16f, Ui.TEXT, value = a.getString(res)).apply { setLineSpacing(0f, 1.15f) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) topMargin = dp(12) })
        }
        add(bullets, 20)

        // The exact list, folded away so the screen stays short.
        val details = Ui.text(a, 14f, Ui.DIM, value = a.getString(R.string.sync_ask_details)).apply {
            setLineSpacing(0f, 1.15f)
            visibility = View.GONE
        }
        val more = Ui.text(a, 14f, Ui.ACCENT, bold = true, value = a.getString(R.string.sync_ask_what_sent)).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        add(more, 6)
        add(details, 0)

        fun big(label: String, style: Ui.Style, onClick: () -> Unit) = Ui.button(a, label, style, onClick).apply {
            minHeight = dp(56)
            textSize = 17f
        }
        add(big(a.getString(R.string.sync_choice_share), Ui.Style.PRIMARY) { answer(Prefs.SYNC_SHARE) }, 28)
        add(big(a.getString(R.string.sync_choice_receive), Ui.Style.SECONDARY) { answer(Prefs.SYNC_RECEIVE) }, 10)
        add(big(a.getString(R.string.sync_choice_later), Ui.Style.QUIET) { answer(null) }, 4)
        add(Ui.text(a, 13f, Ui.DIM, value = a.getString(R.string.sync_ask_footer)).apply { gravity = Gravity.CENTER_HORIZONTAL }, 8)

        d.setContentView(ScrollView(a).apply {
            isFillViewport = true
            setBackgroundColor(Ui.BG)
            addView(col)
        })
        d.setOnCancelListener { answer(null) }
        return d
    }

    // ---------------------------------------------------------------- status words (Settings and Drive)

    fun choiceName(ctx: Context, choice: String): String = ctx.getString(when (choice) {
        Prefs.SYNC_SHARE -> R.string.sync_choice_share
        Prefs.SYNC_RECEIVE -> R.string.sync_choice_receive
        else -> R.string.sync_choice_off
    })

    fun lastSynced(ctx: Context, lastAt: Long, now: Long = System.currentTimeMillis()): String = when {
        lastAt <= 0L -> ctx.getString(R.string.sync_last_never)
        now - lastAt < DateUtils.MINUTE_IN_MILLIS -> ctx.getString(R.string.sync_last_just_now)
        else -> ctx.getString(R.string.sync_last_at,
            DateUtils.getRelativeTimeSpanString(lastAt, now, DateUtils.MINUTE_IN_MILLIS).toString())
    }

    fun count(n: Int): String = String.format(Locale.US, "%,d", n)

    fun spots(ctx: Context, n: Int): String = ctx.resources.getQuantityString(R.plurals.sync_spots_near, n, count(n))

    fun pending(ctx: Context, n: Int): String = ctx.resources.getQuantityString(R.plurals.sync_pending, n, count(n))

    /** Plain words for [LiveState.syncLastError] (the sync writes short English reasons); "" when there is none. */
    fun error(ctx: Context, e: String): String = when {
        e.isEmpty() -> ""
        e.contains("limit") -> ctx.getString(R.string.sync_error_limit)
        e.contains("delete") -> ctx.getString(R.string.sync_error_delete)
        e.contains("offline") -> ctx.getString(R.string.sync_error_offline)   // the only error that retries by itself
        else -> ctx.getString(R.string.sync_error_other)
    }

    /** The small line on the Drive tab ("" when the shared map is off). */
    fun driveLine(ctx: Context): String = when {
        Prefs.syncChoice(ctx) == Prefs.SYNC_UNSET -> ""
        LiveState.syncRemoteSpots > 0 -> ctx.resources.getQuantityString(R.plurals.drive_sync_line,
            LiveState.syncRemoteSpots, count(LiveState.syncRemoteSpots))
        else -> ctx.getString(R.string.drive_sync_on)
    }
}
