package app.bumpbeeper.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each edition is offered its own APK from a release that carries both (issue #56). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateAssetTest {
    private val fossUrl = "https://github.com/Ahmedhesham2025/speedo/releases/download/v1.4.0/BumpBeeper-1.4.0.apk"
    private val playUrl = "https://github.com/Ahmedhesham2025/speedo/releases/download/v1.4.0/BumpBeeper-1.4.0-google.apk"

    private fun asset(name: String, url: String) = JSONObject().put("name", name).put("browser_download_url", url)

    /** The relevant part of a GitHub "latest release" answer. */
    private fun release(vararg assets: JSONObject): JSONArray =
        JSONObject().put("tag_name", "v1.4.0").put("assets", JSONArray(assets.toList())).getJSONArray("assets")

    @Test fun fossFirstInTheList() {
        val assets = release(asset("BumpBeeper-1.4.0.apk", fossUrl), asset("BumpBeeper-1.4.0-google.apk", playUrl))
        assertEquals(fossUrl, UpdateCheck.pickApk(assets, "foss"))
        assertEquals(playUrl, UpdateCheck.pickApk(assets, "play"))
    }

    @Test fun googleFirstInTheList() {
        val assets = release(asset("BumpBeeper-1.4.0-google.apk", playUrl), asset("BumpBeeper-1.4.0.apk", fossUrl))
        assertEquals(fossUrl, UpdateCheck.pickApk(assets, "foss"))
        assertEquals(playUrl, UpdateCheck.pickApk(assets, "play"))
    }

    @Test fun nonApkAssetsAreSkipped() {
        val assets = release(
            asset("SHA256SUMS.txt", "https://x/sums"),
            asset("BumpBeeper-1.4.0-google.apk", playUrl),
            asset("BumpBeeper-1.4.0.apk", fossUrl),
        )
        assertEquals(fossUrl, UpdateCheck.pickApk(assets, "foss"))
        assertEquals(playUrl, UpdateCheck.pickApk(assets, "play"))
    }

    @Test fun noMatchingApkMeansReleasePage() {
        // An older single-edition release: the play edition must not be offered the foss APK (and the other way round).
        assertNull(UpdateCheck.pickApk(release(asset("BumpBeeper-1.3.0.apk", fossUrl)), "play"))
        assertNull(UpdateCheck.pickApk(release(asset("BumpBeeper-1.4.0-google.apk", playUrl)), "foss"))
        assertNull(UpdateCheck.pickApk(release(), "foss"))
        assertNull(UpdateCheck.pickApk(null, "play"))
    }
}
