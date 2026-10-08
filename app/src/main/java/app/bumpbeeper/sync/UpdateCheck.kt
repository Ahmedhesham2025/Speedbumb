package app.bumpbeeper.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.Prefs
import app.bumpbeeper.R
import app.bumpbeeper.TraceWriter
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * "Is there a newer version?" — asks GitHub for this app's releases.
 *
 * A plain GET of a public URL; nothing about the user, the phone or their drives is sent (only a User-Agent naming the
 * app version). At most once per 24 h, shared by the app's start and a daily background job; in between, the last
 * answer is reused from the settings. With *Beta updates* on ([Prefs.betaUpdates]) betas count too. A newer version
 * gets one system notification ([notifyOnce]); without the notification permission only the in-app banner shows it.
 */
object UpdateCheck {
    data class Update(val version: String, val downloadUrl: String?, val htmlUrl: String)

    private const val TAG = "BumpBeeper"
    private const val REPO = "https://api.github.com/repos/Ahmedhesham2025/Speedbumb/releases"
    const val LATEST_URL = "$REPO/latest"
    const val LIST_URL = "$REPO?per_page=10"
    private const val EVERY_MS = 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4120
    private const val JOB_ID = 4121
    const val EXTRA_JOB = "update_job"

    /** One GET: (HTTP code, body); null when offline. Swapped for a fake in tests. */
    fun interface Fetch {
        fun get(url: String, userAgent: String): Pair<Int, String>?
    }

    private val http = Fetch { url, ua ->
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            code to if (code == HttpURLConnection.HTTP_OK) conn.inputStream.bufferedReader().use { it.readText() } else ""
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Finds out on a background thread whether a newer release exists, then calls [callback] on the main
     * thread with it, or with null (up to date, no release yet, offline, development build, or any error).
     * Never throws. [force] skips the once-per-24-h limit. Also makes sure the daily background check exists.
     */
    fun latest(ctx: Context, force: Boolean = false, callback: (Update?) -> Unit) {
        val app = ctx.applicationContext ?: ctx
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = run(app, force)
            main.post { callback(result) }
        }, "update-check").start()
        ensureDaily(app)
    }

    /** Blocking (app start's thread, or the daily job): check, then notify once about a newer version. Never throws. */
    fun run(ctx: Context, force: Boolean = false): Update? = try {
        check(ctx, force)?.also { notifyOnce(ctx, it) }
    } catch (e: Throwable) {
        Log.w(TAG, "update check failed", e)
        null
    }

    internal fun check(
        ctx: Context, force: Boolean, current: String = TraceWriter.appVersion(ctx), fetch: Fetch = http,
        now: Long = System.currentTimeMillis(),
    ): Update? {
        if (isDevBuild(current)) return null
        val betas = Prefs.betaUpdates(ctx)
        val sp = Prefs.sp(ctx)
        val last = sp.getLong(Prefs.UPDATE_CHECKED_AT, 0L)
        if (!force && (now - last) in 0L until EVERY_MS) return cached(ctx, current, betas)
        // Count this as a check whatever happens next (403 rate limit, 5xx, offline, bad JSON), so a
        // failing check backs off for 24 h instead of retrying on every app start.
        sp.edit().putLong(Prefs.UPDATE_CHECKED_AT, now).apply()
        val (code, body) = fetch.get(if (betas) LIST_URL else LATEST_URL, "BumpBeeper/$current") ?: return null
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            sp.edit().remove(Prefs.UPDATE_VERSION).apply()   // no release published yet
            return null
        }
        if (code != HttpURLConnection.HTTP_OK) return null
        // Betas: the newest of the last releases (drafts skipped). Otherwise GitHub's latest stable release.
        val json = (if (betas) newest(JSONArray(body), true) else JSONObject(body)) ?: return cached(ctx, current, betas)
        val version = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
        // A pre-release from releases/latest (shouldn't happen) is not offered: keep what was saved before.
        if (version.isEmpty() || (!betas && isPreRelease(version))) return cached(ctx, current, betas)
        val html = json.optString("html_url", "https://github.com/Ahmedhesham2025/Speedbumb/releases")
        // Each release carries one APK per edition; offer the one matching this install (else the release page).
        val download = pickApk(json.optJSONArray("assets"), BuildConfig.FLAVOR)
        sp.edit().putString(Prefs.UPDATE_VERSION, version).putString(Prefs.UPDATE_URL, download ?: "")
            .putString(Prefs.UPDATE_HTML_URL, html).apply()
        return if (isNewer(version, current, betas)) Update(version, download, html) else null
    }

    /** The answer saved by the last check, if it is still newer than what is installed. */
    private fun cached(ctx: Context, current: String, betas: Boolean): Update? {
        val sp = Prefs.sp(ctx)
        val version = sp.getString(Prefs.UPDATE_VERSION, null) ?: return null
        if (!isNewer(version, current, betas)) return null
        val html = sp.getString(Prefs.UPDATE_HTML_URL, null) ?: return null
        val download = sp.getString(Prefs.UPDATE_URL, null)?.takeIf { it.isNotEmpty() }
        return Update(version, download, html)
    }

    /** The newest release in a `releases` list by version: drafts skipped, pre-releases only when [betas]. */
    fun newest(releases: JSONArray, betas: Boolean): JSONObject? {
        var best: JSONObject? = null
        var bestVersion = ""
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            if (r.optBoolean("draft", false)) continue
            val v = r.optString("tag_name", "").removePrefix("v").removePrefix("V")
            if (parse(v) == null || (!betas && (r.optBoolean("prerelease", false) || isPreRelease(v)))) continue
            if (best == null || (compare(v, bestVersion) ?: 0) > 0) { best = r; bestVersion = v }
        }
        return best
    }

    // ---------------------------------------------------------------- notification and daily job

    /**
     * One system notification per newer version ("Bump Beeper X is available — tap to download"), never repeated,
     * also after a restart ([Prefs.UPDATE_NOTIFIED]). Tapping opens the APK (or release page) on GitHub. Without the
     * notification permission nothing is posted (and nothing remembered): the in-app banner still shows it.
     */
    fun notifyOnce(ctx: Context, u: Update): Boolean {
        val sp = Prefs.sp(ctx)
        if (sp.getString(Prefs.UPDATE_NOTIFIED, null) == u.version) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        val url = listOfNotNull(u.downloadUrl, u.htmlUrl).firstOrNull { trusted(it) } ?: return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT).apply { description = ctx.getString(R.string.notif_channel_updates_desc) })
        val open = PendingIntent.getActivity(ctx, NOTIF_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bump)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.update_notif_text, u.version))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) { return false }
        sp.edit().putString(Prefs.UPDATE_NOTIFIED, u.version).apply()
        return true
    }

    /** Only GitHub's own https pages and downloads are opened. */
    fun trusted(s: String): Boolean {
        val uri = try { URI(s) } catch (_: Exception) { return false }
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.userInfo != null) return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host == "github.com" || host.endsWith(".github.com") || host == "objects.githubusercontent.com"
    }

    /** The daily background check (SyncJob): any network, battery not low; it shares the once-a-day limit. */
    fun ensureDaily(ctx: Context) {
        if (isDevBuild(TraceWriter.appVersion(ctx))) return
        try {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(EVERY_MS)
                .setPersisted(true)
                .setExtras(PersistableBundle().apply { putInt(EXTRA_JOB, 1) })
                .build())
        } catch (e: Exception) {
            Log.w(TAG, "daily update check not scheduled", e)
        }
    }

    // ---------------------------------------------------------------- versions

    /**
     * The download URL of this edition's APK among a release's [assets]: `BumpBeeper-<ver>-google.apk` for the
     * play edition, `BumpBeeper-<ver>.apk` for foss. Null when the release has no matching APK (the caller then
     * points at the release page), so a foss install is never offered the Google build or the other way round.
     */
    fun pickApk(assets: JSONArray?, flavor: String): String? {
        if (assets == null) return null
        val wantGoogle = flavor == "play"
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "").lowercase(Locale.US)
            val url = a.optString("browser_download_url", "")
            if (!name.endsWith(".apk") || url.isEmpty()) continue
            if (name.contains("-google") == wantGoogle) return url
        }
        return null
    }

    /** Local and development builds never offer updates. */
    fun isDevBuild(version: String): Boolean =
        version.isEmpty() || version == "unknown" || version.contains("-local") || version.contains("-dev")

    /** A pre-release tag such as 1.3.0-rc1 or 1.8.0-beta2 (anything after a '-' following X.Y.Z). */
    fun isPreRelease(version: String): Boolean = clean(version).contains('-')

    /**
     * True when [candidate] is a higher version than [current]: 1.7.1 < 1.8.0-beta2 < 1.8.0-beta3 < 1.8.0. A
     * pre-release [candidate] counts only with [betas]; a stable release is newer than its own betas. Unparseable → false.
     */
    fun isNewer(candidate: String, current: String, betas: Boolean = false): Boolean {
        if (!betas && isPreRelease(candidate)) return false
        return (compare(candidate, current) ?: return false) > 0
    }

    /** Version order (X.Y.Z, then a stable release after its pre-releases, betaN by N); null if unparseable. */
    fun compare(a: String, b: String): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0 until 3) if (x[i] != y[i]) return x[i].compareTo(y[i])
        val pa = pre(a)
        val pb = pre(b)
        return when {
            pa == null && pb == null -> 0
            pa == null -> 1
            pb == null -> -1
            else -> comparePre(pa, pb)
        }
    }

    private fun clean(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')

    private fun pre(v: String): String? = clean(v).substringAfter('-', "").lowercase(Locale.US).ifEmpty { null }

    private val PRE = Regex("""([a-z]*)\.?(\d*)""")

    /** "beta2" < "beta10" < "rc1": the label alphabetically, then its number. */
    private fun comparePre(a: String, b: String): Int {
        val ma = PRE.matchEntire(a)
        val mb = PRE.matchEntire(b)
        if (ma == null || mb == null) return a.compareTo(b)
        val label = ma.groupValues[1].compareTo(mb.groupValues[1])
        if (label != 0) return label
        return (ma.groupValues[2].toIntOrNull() ?: 0).compareTo(mb.groupValues[2].toIntOrNull() ?: 0)
    }

    private fun parse(v: String): IntArray? {
        val core = clean(v).substringBefore('-')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val out = IntArray(3)
        for ((i, p) in parts.withIndex()) out[i] = p.toIntOrNull() ?: return null
        return out
    }
}
