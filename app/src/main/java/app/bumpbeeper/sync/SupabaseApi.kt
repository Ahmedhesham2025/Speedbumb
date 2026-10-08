package app.bumpbeeper.sync

import android.net.Network
import app.bumpbeeper.Observation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

/** Sends a file as the raw body of one POST, exactly [length] bytes (Storage uploads). Swapped for a fake in tests. */
fun interface FileTransport {
    fun postFile(url: String, headers: Map<String, String>, file: File, length: Long): HttpResult
}

/** The real file transport. Never call it on the main thread. */
object StorageTransport : FileTransport by HttpFileTransport()

/**
 * HttpURLConnection with a fixed-length body ([HttpURLConnection.setFixedLengthStreamingMode]): the Content-Length is
 * exactly [length], never chunked, and a file of another size fails before anything is stored. 15 s to connect,
 * [readMs] for the answer. With a [network] (a job's), only over that network: never another one it falls back to.
 */
class HttpFileTransport(private val readMs: Int = 120_000, private val network: Network? = null) : FileTransport {
    override fun postFile(url: String, headers: Map<String, String>, file: File, length: Long): HttpResult {
        if (file.length() != length) throw IOException("file size changed")
        val conn = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readMs
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(length)
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            file.inputStream().use { input ->
                conn.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var left = length
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("file shorter than its Content-Length")
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResult(code, text, conn.date)
        } finally {
            conn.disconnect()
        }
    }
}

/** HttpURLConnection with [timeoutMs] to connect and to read, over [network] if given. Never on the main thread. */
class HttpTransport(private val timeoutMs: Int, private val network: Network? = null) : Transport {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
        val conn = (network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
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

class ApiException(val outcome: Outcome, message: String) : Exception(message)

object ApiErrors {
    /** Maps a non-2xx PostgREST answer (`{"code": "22023", "message": …}`) to what to do. */
    fun classify(httpCode: Int, body: String): Outcome {
        val code = try { JSONObject(body).optString("code", "") } catch (_: Exception) { "" }
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
 */
object ObservationJson {
    /** Fields the server rejects a whole batch for (22023) are made to fit here instead. */
    fun toJson(o: Observation): JSONObject = JSONObject().apply {
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
            throw ApiException(if (outcome == Outcome.AUTH) Outcome.RETRY else outcome, "$name: HTTP ${r.code}")
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

    fun spotsNear(lat: Double, lon: Double, radiusM: Int): List<SpotRow> {
        val body = rpc("spots_near", JSONObject().put("lat", lat).put("lon", lon).put("radius_m", radiusM))
        return parseSpots(body)
    }

    fun submitCrashReport(appVersion: String, model: String, stack: String) {
        rpc("submit_crash_report", JSONObject().put("app_version", appVersion.take(40)).put("model", model.take(40))
            .put("stack", stack.take(8192)))
    }

    fun forgetMe() {
        rpc("forget_me", JSONObject())
    }

    /**
     * Uploads [file] (exactly [length] bytes: the size reserved) to Storage as `<bucket>/<path>`: one plain POST with
     * this user's JWT, no upsert. Returns the status ([storageStatus]); an expired session is refreshed once.
     * Throws [ApiException] (RETRY) when offline.
     */
    fun upload(bucket: String, path: String, file: File, length: Long, contentType: String, files: FileTransport): Int {
        var token = auth.accessToken()
        for (attempt in 0..1) {
            val r = try {
                files.postFile("$baseUrl/storage/v1/object/$bucket/$path",
                    mapOf("apikey" to key, "Authorization" to "Bearer $token", "Content-Type" to contentType), file, length)
            } catch (e: IOException) {
                throw ApiException(Outcome.RETRY, "upload: ${e.javaClass.simpleName}")
            }
            val code = storageStatus(r)
            if (code == 401 && attempt == 0) { token = auth.accessToken(forceRefresh = true); continue }
            return code
        }
        return 401
    }

    companion object {
        /**
         * Storage's answer as one status: its JSON `statusCode` when an error carries one (some versions answer 400 with
         * the real status inside), and 401 for a bad or expired JWT.
         */
        fun storageStatus(r: HttpResult): Int {
            if (r.code in 200..299) return r.code
            val j = try { JSONObject(r.body) } catch (_: Exception) { null } ?: return r.code
            if ((j.optString("error", "") + " " + j.optString("message", "")).contains("jwt", ignoreCase = true)) return 401
            return j.optString("statusCode", "").toIntOrNull() ?: r.code
        }

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
    }
}
