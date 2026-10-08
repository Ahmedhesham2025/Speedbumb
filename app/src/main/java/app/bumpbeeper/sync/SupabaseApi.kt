package app.bumpbeeper.sync

import app.bumpbeeper.Confidence
import app.bumpbeeper.Observation
import app.bumpbeeper.Severity
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** One HTTP answer: status code, body text and the server's clock from the `Date` header (0 when missing). */
class HttpResult(val code: Int, val body: String, val serverDateMs: Long = 0L)

/** Sends one POST. Swapped for a fake in tests, so no test ever touches the network. Throws IOException when offline. */
fun interface Transport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResult
}

/** The real transport: HttpURLConnection, 10 s timeouts. Never call it on the main thread. */
object UrlTransport : Transport by HttpTransport(10_000)

/** HttpURLConnection with [timeoutMs] to connect and to read. Never call it on the main thread. */
class HttpTransport(private val timeoutMs: Int) : Transport {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResult(code, text, conn.date)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Wraps a [Transport] and remembers how far the phone's clock runs ahead of the server's (from the `Date`
 * header of any answer). A phone clock set hours ahead would otherwise date observations in the future and
 * the server would reject whole batches.
 */
class ClockWatch(private val inner: Transport, private val now: () -> Long = System::currentTimeMillis) : Transport {
    /** Phone time minus server time at the last answer that had a `Date` header; 0 until then. */
    @Volatile var aheadMs: Long = 0L
        private set

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
        val r = inner.post(url, headers, body)
        if (r.serverDateMs > 0) aheadMs = now() - r.serverDateMs
        return r
    }

    /** How much to move observation times back: the skew when the phone is more than [TOLERANCE_MS] ahead, else 0. */
    fun correctionMs(): Long = if (aheadMs > TOLERANCE_MS) aheadMs else 0L

    companion object {
        const val TOLERANCE_MS = 60 * 60 * 1000L
    }
}

/** What the sync should do after a failed call. */
enum class Outcome {
    /** 22023: the server will never accept this data. Drop it, never retry. */
    DROP,
    /** 42501: device not registered, or sharing is off on the server. Register again / stop uploading. */
    NOT_ALLOWED,
    /** 54000: daily cap reached. Try again tomorrow. */
    LIMIT,
    /** 53100: the server's storage for this data is full. Back off for days. */
    FULL,
    /** 401 / expired token: refresh the session (handled inside [SupabaseApi]). */
    AUTH,
    /** Offline, timeout, 5xx or anything unexpected: try again later. */
    RETRY,
}

/** [httpCode] and [pgCode] (PostgREST's `code`) of a server answer; 0 and "" when there was none (offline, sign-in). */
class ApiException(val outcome: Outcome, message: String, val httpCode: Int = 0, val pgCode: String = "") : Exception(message) {
    /** This server has no such RPC (HTTP 404 / PGRST202): its migration isn't applied yet, or was rolled back. */
    val missingRpc: Boolean get() = httpCode == 404 || pgCode == "PGRST202"
}

object ApiErrors {
    /** PostgREST's error `code` in an answer body; "" when there is none. */
    fun code(body: String): String = try { JSONObject(body).optString("code", "") } catch (_: Exception) { "" }

    /** Maps a non-2xx PostgREST answer (`{"code": "22023", "message": …}`) to what to do. */
    fun classify(httpCode: Int, body: String): Outcome {
        val code = code(body)
        return when {
            code == "22023" -> Outcome.DROP
            code == "42501" -> Outcome.NOT_ALLOWED
            code == "54000" -> Outcome.LIMIT
            code == "53100" -> Outcome.FULL
            httpCode == 401 || code.startsWith("PGRST30") -> Outcome.AUTH   // PGRST301/303: bad or expired JWT
            else -> Outcome.RETRY
        }
    }

    /** Short text for the status card. */
    fun describe(o: Outcome): String = when (o) {
        Outcome.DROP -> "server rejected some data"
        Outcome.NOT_ALLOWED -> "sharing is off on the server"
        Outcome.LIMIT -> "daily upload limit reached"
        Outcome.FULL -> "server storage full, trying again in a few days"
        Outcome.AUTH -> "sign-in failed"
        Outcome.RETRY -> "offline or server busy"
    }
}

/**
 * A confirmed spot as `spots_near` returns it. Nullable columns stay null. [kind] `"pothole"` marks an old (legacy)
 * pothole spot. [band] ("mild" / "moderate" / "strong"), [confidence] ("soft" / "full") and [nHits] (hits in total,
 * one phone twice counts) come from `spots_near_v2` only: null from `spots_near`.
 */
class SpotRow(
    val id: Long, val lat: Double, val lon: Double, val heading: Double?, val kind: String?, val side: String?,
    val severity: Double?, val nDevices: Int,
    val band: String? = null, val confidence: String? = null, val nHits: Int? = null,
) {
    /** An old pothole spot: the engine keeps it a soft "maybe" until it is felt again. */
    val legacy: Boolean get() = kind == "pothole"
}

/**
 * The upload format of `submit_observations` (see supabase/migrations/20261005000001_core.sql).
 *
 * `kind_score`: the engine's [Observation.kindScore] runs -1 (speed bump) .. +1 (pothole), but the server column
 * is `check (between 0 and 1)` and its aggregation calls `>= 0.5` a pothole, so the phone sends `(kindScore + 1) / 2`.
 * Every element says `"schema": 2` ([SCHEMA], supabase/README.md): this build's spots are bumps with a severity, so
 * its hit on an old pothole spot makes it an ordinary bump on the server too.
 */
object ObservationJson {
    /** The upload format version: 2 = bumps with a severity (missing = 1, a 1.7.x phone). */
    const val SCHEMA = 2

    /** Fields the server rejects a whole batch for (22023) are made to fit here instead. */
    fun toJson(o: Observation): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("client_obs_id", o.clientId)
        put("kind", o.kind)
        put("lat", o.lat)
        put("lon", o.lon)
        if (!o.heading.isNaN()) put("heading", ((o.heading.roundToInt() % 360) + 360) % 360)
        if (!o.speedKmh.isNaN()) put("speed_kmh", o.speedKmh.roundToInt().coerceIn(0, 250))
        put("peak", fit(o.peak, 0.0, 100.0))
        // The engine scores -1 (speed bump) .. +1 (pothole); the server stores 0..1 and calls >= 0.5 a pothole.
        put("kind_score", fit((o.kindScore + 1) / 2, 0.0, 1.0))
        put("side_score", fit(o.sideScore, -1.0, 1.0))
        put("observed_at", isoUtc(o.wallTimeMs))
    }

    private fun fit(x: Double, lo: Double, hi: Double) = if (x.isNaN()) 0.0 else x.coerceIn(lo, hi)

    /** e.g. 2026-10-02T13:45:07Z (seconds; the server rounds down to the hour). */
    fun isoUtc(ms: Long): String = isoFormat().format(Date(ms))

    /** Moves `observed_at` of an outbox element [shiftMs] earlier (clock skew). Unreadable times stay as they are. */
    fun shiftObservedAt(o: JSONObject, shiftMs: Long): JSONObject {
        if (shiftMs == 0L) return o
        val at = try { isoFormat().parse(o.optString("observed_at", "")) } catch (_: Exception) { null } ?: return o
        return o.put("observed_at", isoUtc(at.time - shiftMs))
    }

    private fun isoFormat() =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** The server only takes points in this box (Egypt / MENA); anything outside would reject the whole batch. */
    fun inServiceArea(lat: Double, lon: Double) = lat in 12.0..38.0 && lon in 24.0..60.0
}

/**
 * Calls the backend's RPCs (`POST /rest/v1/rpc/<name>`) as the anonymous user of [auth].
 * Every method blocks and throws [ApiException]; call from a background thread only.
 */
class SupabaseApi(
    private val baseUrl: String,
    private val key: String,
    private val auth: SupabaseAuth,
    private val transport: Transport = UrlTransport,
) {
    fun rpc(name: String, args: JSONObject): String {
        var token = auth.accessToken()
        for (attempt in 0..1) {
            val r = try {
                transport.post(
                    "$baseUrl/rest/v1/rpc/$name",
                    mapOf("apikey" to key, "Authorization" to "Bearer $token", "Content-Type" to "application/json"),
                    args.toString(),
                )
            } catch (e: IOException) {
                throw ApiException(Outcome.RETRY, "$name: ${e.javaClass.simpleName}")
            }
            if (r.code in 200..299) return r.body
            val outcome = ApiErrors.classify(r.code, r.body)
            // An expired or revoked session: refresh (or sign in again) once, then give up for now.
            if (outcome == Outcome.AUTH && attempt == 0) { token = auth.accessToken(forceRefresh = true); continue }
            throw ApiException(if (outcome == Outcome.AUTH) Outcome.RETRY else outcome, "$name: HTTP ${r.code}", r.code, ApiErrors.code(r.body))
        }
        throw ApiException(Outcome.RETRY, name)
    }

    fun registerDevice(appVersion: String, osApi: Int, consentVersion: Int, shareEnabled: Boolean) {
        rpc("register_device", JSONObject().put("app_version", appVersion.take(40)).put("os_api", osApi)
            .put("consent_version", consentVersion).put("share_enabled", shareEnabled))
    }

    /** Returns the client ids the server now holds (accepted now or before). */
    fun submitObservations(batch: JSONArray): Set<String> {
        val body = rpc("submit_observations", JSONObject().put("batch", batch))
        val arr = parseArray(body) ?: return emptySet()
        return (0 until arr.length()).mapNotNullTo(HashSet()) { arr.optString(it, "").takeIf { s -> s.isNotEmpty() } }
    }

    /** `spots_near`, the 1.7.x answer: no band, confidence or total hits. Use [spotsNearV2] first. */
    fun spotsNear(lat: Double, lon: Double, radiusM: Int): List<SpotRow> {
        val body = rpc("spots_near", JSONObject().put("lat", lat).put("lon", lon).put("radius_m", radiusM))
        return parseSpots(body)
    }

    /**
     * `spots_near_v2`: the same spots with their band, confidence, hits in total and legacy flag. Null when this server
     * has no v2 ([ApiException.missingRpc]); the caller then falls back to [spotsNear].
     */
    fun spotsNearV2(lat: Double, lon: Double, radiusM: Int): List<SpotRow>? {
        val body = try {
            rpc("spots_near_v2", JSONObject().put("lat", lat).put("lon", lon).put("radius_m", radiusM))
        } catch (e: ApiException) {
            if (e.missingRpc) return null else throw e
        }
        return parseSpotsV2(body)
    }

    fun submitCrashReport(appVersion: String, model: String, stack: String) {
        rpc("submit_crash_report", JSONObject().put("app_version", appVersion.take(40)).put("model", model.take(40))
            .put("stack", stack.take(8192)))
    }

    fun forgetMe() {
        rpc("forget_me", JSONObject())
    }

    companion object {
        private fun parseArray(body: String): JSONArray? = try { JSONArray(body) } catch (_: Exception) { null }

        private fun JSONObject.numOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
        private fun JSONObject.strOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)

        fun parseSpots(body: String): List<SpotRow> {
            val arr = parseArray(body) ?: return emptyList()
            val out = ArrayList<SpotRow>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val lat = o.numOrNull("latitude") ?: continue
                val lon = o.numOrNull("longitude") ?: continue
                out.add(SpotRow(o.optLong("id", -1), lat, lon, o.numOrNull("heading"), o.strOrNull("kind"),
                    o.strOrNull("side"), o.numOrNull("severity"), o.optInt("n_devices", 0)))
            }
            return out.filter { it.id >= 0 }
        }

        /**
         * A `spots_near_v2` answer. A legacy spot is cached as kind "pothole" (how `spots_near` reports it, and what the
         * engine keeps soft until felt) and always "soft"; any other spot is a "bump". Bands and confidences this
         * version doesn't know are left null.
         */
        fun parseSpotsV2(body: String): List<SpotRow> {
            val arr = parseArray(body) ?: return emptyList()
            val out = ArrayList<SpotRow>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val lat = o.numOrNull("lat") ?: continue
                val lon = o.numOrNull("lon") ?: continue
                val legacy = o.optBoolean("legacy", false)
                val devices = o.optInt("n_devices", 0)
                out.add(SpotRow(o.optLong("id", -1), lat, lon, o.numOrNull("heading"), if (legacy) "pothole" else "bump", null,
                    o.numOrNull("severity"), devices,
                    band = o.strOrNull("severity_band")?.takeIf { b -> Severity.values().any { it.label == b } },
                    confidence = if (legacy) Confidence.SOFT.label
                        else o.strOrNull("confidence")?.takeIf { c -> Confidence.values().any { it.label == c } },
                    nHits = o.optInt("n_hits", devices).coerceAtLeast(devices)))
            }
            return out.filter { it.id >= 0 }
        }
    }
}
