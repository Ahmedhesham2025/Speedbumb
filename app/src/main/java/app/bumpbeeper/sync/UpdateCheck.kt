package app.bumpbeeper.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.Prefs
import app.bumpbeeper.TraceWriter
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * "Is there a newer version?" — asks GitHub for the latest release of this app.
 *
 * The only network call the app makes. It is a plain GET of a public URL; nothing about the user,
 * the phone or their drives is sent (only a User-Agent naming the app version). At most once per 24 h;
 * in between, the last answer is reused from the settings.
 */
object UpdateCheck {
    data class Update(val version: String, val downloadUrl: String?, val htmlUrl: String)

    private const val TAG = "BumpBeeper"
    private const val API = "https://api.github.com/repos/Ahmedhesham2025/Speedbumb/releases/latest"
    private const val EVERY_MS = 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000

    /**
     * Finds out on a background thread whether a newer release exists, then calls [callback] on the main
     * thread with it, or with null (up to date, no release yet, offline, development build, or any error).
     * Never throws. [force] skips the once-per-24-h limit (e.g. a "Check now" button).
     */
    fun latest(ctx: Context, force: Boolean = false, callback: (Update?) -> Unit) {
        val app = ctx.applicationContext ?: ctx
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = try {
                check(app, force)
            } catch (e: Throwable) {
                Log.w(TAG, "update check failed", e)
                null
            }
            main.post { callback(result) }
        }, "update-check").start()
    }

    private fun check(ctx: Context, force: Boolean): Update? {
        val current = TraceWriter.appVersion(ctx)
        if (isDevBuild(current)) return null
        val sp = Prefs.sp(ctx)
        val now = System.currentTimeMillis()
        val last = sp.getLong(Prefs.UPDATE_CHECKED_AT, 0L)
        if (!force && (now - last) in 0L until EVERY_MS) return cached(ctx, current)
        // Count this as a check whatever happens next (403 rate limit, 5xx, offline, bad JSON), so a
        // failing check backs off for 24 h instead of retrying on every app start.
        sp.edit().putLong(Prefs.UPDATE_CHECKED_AT, now).apply()

        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "BumpBeeper/$current")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                // No release published yet.
                sp.edit().remove(Prefs.UPDATE_VERSION).apply()
                return null
            }
            if (code != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val html = json.optString("html_url", "https://github.com/Ahmedhesham2025/Speedbumb/releases/latest")
            // Each release carries one APK per edition; offer the one matching this install (else the release page).
            val download = pickApk(json.optJSONArray("assets"), BuildConfig.FLAVOR)
            val version = tag.removePrefix("v").removePrefix("V")
            // A pre-release (1.3.0-rc1) is never offered; keep whatever stable answer was saved before.
            if (isPreRelease(version)) return cached(ctx, current)
            val edit = sp.edit()
            if (version.isNotEmpty()) {
                edit.putString(Prefs.UPDATE_VERSION, version).putString(Prefs.UPDATE_URL, download ?: "")
                    .putString(Prefs.UPDATE_HTML_URL, html)
            } else {
                edit.remove(Prefs.UPDATE_VERSION)
            }
            edit.apply()
            return if (version.isNotEmpty() && isNewer(version, current)) Update(version, download, html) else null
        } finally {
            conn.disconnect()
        }
    }

    /** The answer saved by the last check, if it is still newer than what is installed. */
    private fun cached(ctx: Context, current: String): Update? {
        val sp = Prefs.sp(ctx)
        val version = sp.getString(Prefs.UPDATE_VERSION, null) ?: return null
        if (!isNewer(version, current)) return null
        val html = sp.getString(Prefs.UPDATE_HTML_URL, null) ?: return null
        val download = sp.getString(Prefs.UPDATE_URL, null)?.takeIf { it.isNotEmpty() }
        return Update(version, download, html)
    }

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

    /** A pre-release tag such as 1.3.0-rc1 (anything after a '-' following X.Y.Z). Never offered as an update. */
    fun isPreRelease(version: String): Boolean =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('+').contains('-')

    /** True when [candidate] (X.Y.Z) is a higher version than [current]. Unparseable or pre-release → false. */
    fun isNewer(candidate: String, current: String): Boolean {
        if (isPreRelease(candidate)) return false
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until 3) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return false
    }

    private fun parse(v: String): IntArray? {
        val core = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val out = IntArray(3)
        for ((i, p) in parts.withIndex()) out[i] = p.toIntOrNull() ?: return null
        return out
    }
}
