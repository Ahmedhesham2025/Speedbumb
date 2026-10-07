package app.bumpbeeper.research

import android.content.Context
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.Prefs
import app.bumpbeeper.auto.TripHold
import app.bumpbeeper.sync.FileTransport
import app.bumpbeeper.sync.HttpResult
import app.bumpbeeper.sync.SupabaseAuth
import app.bumpbeeper.sync.Transport
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The Wi-Fi uploader against a fake server: gating, reserve → upload and every answer, pause, holds, open files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchUploaderTest {
    private lateinit var ctx: Context
    private val now = System.currentTimeMillis()
    private val stamp = "20261001T080000"
    private val day = 24 * 3600_000L

    /** Answers like Supabase. [reserve] / [upload]: answers in turn (then "ok" / 200). Uppercase id: the name must not be. */
    private class Server(vararg reserve: String, val upload: ArrayDeque<Int> = ArrayDeque()) : Transport, FileTransport {
        val reserve = ArrayDeque(reserve.toList())
        val calls = ArrayList<String>()
        val reserved = ArrayList<JSONObject>()
        /** Each upload: url, headers, the body sent. */
        val sent = ArrayList<Triple<String, Map<String, String>, ByteArray>>()
        override fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
            val name = url.substringAfterLast('/').substringBefore('?')
            calls.add(name)
            return when (name) {
                "signup", "token" -> HttpResult(200, """{"access_token":"a","expires_in":3600,"refresh_token":"r","user":{"id":"0F8FAD5B-D9CB-469F-A165-70867728950E"}}""")
                "research_reserve" -> {
                    reserved.add(JSONObject(body))
                    when (val a = reserve.removeFirstOrNull() ?: "ok") {
                        "22023" -> HttpResult(400, """{"code":"22023","message":"invalid research file name or size"}""")
                        "503" -> HttpResult(503, "busy")
                        else -> HttpResult(200, "\"$a\"")
                    }
                }
                else -> HttpResult(200, "true")
            }
        }
        override fun postFile(url: String, headers: Map<String, String>, file: File, length: Long): HttpResult {
            calls.add("upload")
            val body = file.readBytes()
            assertEquals("Content-Length is the file's exact size", body.size.toLong(), length)
            sent.add(Triple(url, headers, body))
            val code = upload.removeFirstOrNull() ?: 200
            return if (code == 200) HttpResult(200, """{"Key":"x"}""")
            else HttpResult(if (code == 413) 400 else code, """{"statusCode":"$code","error":"e","message":"m"}""")   // 413 inside a 400
        }
    }

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        Prefs.sp(ctx).edit().clear().commit()
        SupabaseAuth.prefs(ctx).edit().clear().commit()
        ResearchFiles.dir(ctx).deleteRecursively()
        ResearchQueue.clear(ctx)
        TripHold.forgetAll(ctx)
        TripHold.reset()
        TripHold.installResearch()
        Prefs.setResearchState(ctx, true, 1, offPending = false, onPending = false, serverOn = true, note = "")
    }

    @After fun tearDown() {
        TripHold.reset()
        TripHold.installBuiltIns()
        TripHold.installTraining(ctx)
        TripHold.installResearch()
    }

    /** A 1,200 m trip at 10 m/s in two segments (fixes every second, accelerometer lines between), ended 10 minutes ago. */
    private fun trip(s: String = stamp, id: Long = 1, guessed: Boolean = false, toMs: Long = 120_001, part: Boolean = false) {
        ResearchQueue.tripStarted(ctx, s, id, guessed)
        ResearchQueue.tripEnded(ctx, s)
        for (seg in 0..1) {
            val from = seg * 60_000L
            val lines = ArrayList<String>()
            for (ms in from until minOf(toMs, from + 60_000) step 100) {
                lines.add("${ms * 10},a,10,20,9810")   // rr2: t in 0.1 ms
                if (ms % 1000 == 0L) lines.add("${ms * 10},G,${300_000_000 + ms / 1000 * 898},312000000,,1000,0,400,,,,5")
            }
            val name = "rr_0a1b2c3d_${s}_00$seg.csv.gz" + if (part && seg == 1) ResearchWriter.PART else ""
            val f = File(ResearchFiles.dir(ctx).apply { mkdirs() }, name)
            GZIPOutputStream(FileOutputStream(f)).use { it.write((listOf("# format=rr2") + lines).joinToString("\n", postfix = "\n").toByteArray()) }
            f.setLastModified(now - if (part && seg == 1) 1_000 else 10 * 60_000)   // an open file is flushed every 2 s
        }
    }

    private fun run(s: Server, at: Long = now, metered: Boolean = false, maxBytes: Long = ResearchUploader.MAX_FILE_BYTES) =
        ResearchUploader.run(ctx, s, s, at, { metered }, maxBytes)

    private fun name(seg: Int, s: String = stamp) = "rr_0a1b2c3d_${s}_00$seg.csv.gz"
    private fun uploaded(seg: Int) = ResearchQueue.read(ctx) { it.uploadedAt(name(seg)) > 0 }
    private fun dropped(seg: Int) = ResearchQueue.read(ctx) { it.dropped(name(seg)) }

    @Test fun nothingWithoutConsentOrOnAMeteredNetwork() {
        trip()
        val s = Server()
        Prefs.setResearchState(ctx, false, 1, offPending = false, onPending = false, serverOn = false, note = "")
        assertFalse(run(s))
        Prefs.setResearchState(ctx, true, 1, offPending = false, onPending = false, serverOn = true, note = "")
        assertFalse(run(s, metered = true))
        assertTrue("no call at all", s.calls.isEmpty())
    }

    @Test fun reservesThenUploadsExactlyTheTrimmedBytes() {
        trip()
        val s = Server()
        assertFalse(run(s))
        val uid = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(listOf("$uid/${name(0)}", "$uid/${name(1)}"), s.reserved.map { it.getString("name") })
        for ((i, up) in s.sent.withIndex()) {
            val (url, headers, body) = up
            assertEquals("${BuildConfig.SUPABASE_URL}/storage/v1/object/research/$uid/${name(i)}", url)
            assertEquals("application/gzip", headers["Content-Type"])
            assertEquals("Bearer a", headers["Authorization"])
            assertTrue(headers.containsKey("apikey") && !headers.containsKey("x-upsert"))
            assertEquals("reserved bytes = body", body.size.toLong(), s.reserved[i].getLong("bytes"))
            val ts = GZIPInputStream(body.inputStream()).bufferedReader().readLines().filter { !it.startsWith("#") }
                .map { it.substringBefore(',').toLong() }
            assertTrue("nothing from the first or last 300 m", ts.isNotEmpty() && ts.all { it in 310_000L..890_000L })
        }
        assertTrue(uploaded(0) && uploaded(1))
        assertEquals(0, ResearchUploader.status.files)
        assertEquals(now, ResearchUploader.status.lastUpload)
        assertEquals(listOf(uid), ResearchUploader.status.ids)
        assertTrue("trimmed copies go after the upload", ResearchQueue.cacheDir(ctx).list().isNullOrEmpty())
        val again = Server()
        assertFalse(run(again))
        assertTrue("done: nothing sent twice", again.calls.isEmpty())
    }

    @Test fun everyUploadAnswer() {
        // upload answers for segment 0 → retry later?, its state, how many reservations
        data class Case(val answers: List<Int>, val retry: Boolean, val up: Boolean, val drop: Boolean, val reserves: Int)
        val cases = listOf(
            Case(listOf(409), false, true, false, 2),          // stored by an earlier try: done
            Case(listOf(403, 200), false, true, false, 3),     // reserved once more, then stored
            Case(listOf(403, 403), false, false, true, 3),     // twice refused: dropped, the next file goes on
            Case(listOf(413), false, false, true, 2),          // too large: dropped
            Case(listOf(401, 200), false, true, false, 2),     // session refreshed once
            Case(listOf(503), true, false, false, 1),          // server busy: back off, nothing lost
        )
        for (c in cases) {
            setUp()
            trip()
            val s = Server(upload = ArrayDeque(c.answers))
            assertEquals(c.toString(), c.retry, run(s))
            assertEquals(c.toString(), c.up, uploaded(0))
            assertEquals(c.toString(), c.drop, dropped(0) != null)
            assertEquals(c.toString(), c.reserves, s.reserved.size)
            if (c.answers.first() == 401) assertEquals(c.toString(), 2, s.calls.count { it == "token" || it == "signup" })
        }
    }

    @Test fun anInvalidFileIsDroppedAndNeverRetried() {
        trip()
        val s = Server("22023")
        assertFalse(run(s))
        assertEquals("invalid", dropped(0))
        assertTrue("the next file goes on", uploaded(1))
        val again = Server()
        run(again)
        assertTrue(again.reserved.isEmpty())
    }

    @Test fun fullPausesTheQueueForADay() {
        trip()
        assertFalse(run(Server("full")))
        assertFalse(uploaded(0))
        assertEquals(now + ResearchUploader.PAUSE_MS, ResearchUploader.status.pausedUntil)
        assertEquals("full", ResearchUploader.status.pausedWhy)
        val early = Server()
        run(early, at = now + day - 60_000)
        assertTrue("still paused", early.calls.isEmpty())
        run(Server(), at = now + day + 1)
        assertTrue(uploaded(0) && uploaded(1))
        // The daily device limit pauses the same way.
        setUp(); trip()
        run(Server("device_daily"))
        assertEquals("device_daily", ResearchUploader.status.pausedWhy)
    }

    @Test fun noConsentOnTheServerStopsAndSwitchesOffHere() {
        trip()
        assertFalse(run(Server("no_consent")))
        assertFalse(Prefs.researchRecording(ctx))
        assertEquals(ResearchConsent.SESSION_RESET, Prefs.researchNote(ctx))
        assertFalse(uploaded(0))
        assertEquals(0, ResearchUploader.status.files)
    }

    @Test fun aFileBeingWrittenOrATripNotOverIsNeverUploaded() {
        trip(part = true)   // segment 1 is still open
        val s = Server()
        run(s)
        assertTrue(s.calls.isEmpty())
        // Not ended yet (recording, or the app died less than 12 h ago).
        setUp()
        ResearchQueue.tripStarted(ctx, "20261002T080000", 2, guessed = false)
        trip(s = "20261003T080000", id = 3)
        ResearchQueue.edit(ctx) { it.trip("20261003T080000")!!.put("ended", false) }
        val s2 = Server()
        run(s2)
        assertTrue(s2.calls.isEmpty())
        // Ended a moment ago: its last file may still be closing.
        setUp(); trip()
        ResearchFiles.dir(ctx).listFiles()!!.forEach { it.setLastModified(now - 1_000) }
        val s3 = Server()
        run(s3)
        assertTrue(s3.calls.isEmpty())
    }

    @Test fun aHeldTripWaitsForYes() {
        trip(id = 7, guessed = true)
        TripHold.hold(ctx, 7)
        val s = Server()
        run(s)
        assertTrue(s.calls.isEmpty())
        TripHold.confirm(ctx, 7)
        run(s)
        assertTrue(uploaded(0) && uploaded(1))
    }

    @Test fun aShortTripOrAnOversizedFileUploadsNothing() {
        trip(toMs = 59_001)   // 590 m
        val s = Server()
        run(s)
        assertTrue(s.reserved.isEmpty())
        assertEquals("short", ResearchQueue.read(ctx) { it.trip(stamp)!!.getString("skip") })
        setUp(); trip()
        val big = Server()
        run(big, maxBytes = 100)
        assertTrue("skipped before reserving", big.reserved.isEmpty())
        assertTrue(dropped(0)!!.startsWith("too_big"))
    }
}
