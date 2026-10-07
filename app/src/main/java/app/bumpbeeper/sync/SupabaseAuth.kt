package app.bumpbeeper.sync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException

/**
 * Anonymous Supabase session (GoTrue): no name, email or phone number, just a random user id per install.
 *
 * Tokens live in the private `sync_auth` preferences, which backup and device transfer leave out
 * (res/xml/backup_rules.xml, data_extraction_rules.xml): a restored or new phone signs in as a new device.
 * Blocking calls: background threads only.
 */
class SupabaseAuth(
    ctx: Context,
    private val baseUrl: String,
    private val key: String,
    private val transport: Transport = UrlTransport,
    /** False for "forget me": a dead session must not quietly become a new device (the old one's data would stay). */
    private val mayCreate: Boolean = true,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val sp: SharedPreferences = prefs(ctx)

    val userId: String? get() = sp.getString(USER_ID, null)
    val signedIn: Boolean get() = sp.getString(REFRESH, null) != null

    /**
     * A valid access token: the stored one, a refreshed one, or a new anonymous sign-in. Throws [ApiException].
     * One at a time in the whole process, whichever instance asks: refresh tokens rotate, and two refreshes of the same
     * one at once (a sync and a research upload) could otherwise lose the session and sign in as a new device.
     */
    fun accessToken(forceRefresh: Boolean = false): String = synchronized(LOCK) { token(forceRefresh) }

    private fun token(forceRefresh: Boolean): String {
        val token = sp.getString(ACCESS, null)
        val expiresAt = sp.getLong(EXPIRES_AT, 0L)
        if (!forceRefresh && token != null && now() < expiresAt - 60_000) return token
        val refresh = sp.getString(REFRESH, null)
        if (refresh != null) {
            val r = call("$baseUrl/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh))
            if (r.code in 200..299) return save(r.body)
            // 400/401: the refresh token is used up, revoked or its user was deleted. Start over as a new device;
            // the old device's data then expires on the server. Anything else (429, 5xx): try again later.
            if (r.code != 400 && r.code != 401) throw ApiException(Outcome.RETRY, "token refresh: HTTP ${r.code}")
        }
        if (!mayCreate) throw ApiException(Outcome.AUTH, "session expired")
        return signUp()
    }

    private fun signUp(): String {
        val r = call("$baseUrl/auth/v1/signup", JSONObject())
        if (r.code !in 200..299) throw ApiException(Outcome.RETRY, "anonymous sign-in: HTTP ${r.code}")
        return save(r.body)
    }

    private fun call(url: String, body: JSONObject): HttpResult = try {
        transport.post(url, mapOf("apikey" to key, "Content-Type" to "application/json"), body.toString())
    } catch (e: IOException) {
        throw ApiException(Outcome.RETRY, "auth: ${e.javaClass.simpleName}")
    }

    private fun save(body: String): String {
        val j = try { JSONObject(body) } catch (_: Exception) { throw ApiException(Outcome.RETRY, "auth: bad answer") }
        val access = j.optString("access_token", "")
        val refresh = j.optString("refresh_token", "")
        if (access.isEmpty() || refresh.isEmpty()) throw ApiException(Outcome.RETRY, "auth: no session in answer")
        val expiresIn = j.optLong("expires_in", 3600L)
        sp.edit()
            .putString(ACCESS, access)
            .putString(REFRESH, refresh)
            .putLong(EXPIRES_AT, now() + expiresIn * 1000)
            .putString(USER_ID, j.optJSONObject("user")?.optString("id", "")?.takeIf { it.isNotEmpty() } ?: userId)
            .commit()   // synchronous (background thread): refresh tokens rotate, losing the new one loses the device
        return access
    }

    /** Forget the session; the next call signs in as a new anonymous device. Background thread. */
    fun clear() {
        synchronized(LOCK) { sp.edit().clear().commit() }
    }

    companion object {
        private val LOCK = Any()
        /** File name `sync_auth.xml`; excluded from backups by name. */
        const val PREFS = "sync_auth"
        private const val ACCESS = "access_token"
        private const val REFRESH = "refresh_token"
        private const val EXPIRES_AT = "expires_at"
        private const val USER_ID = "user_id"

        fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
