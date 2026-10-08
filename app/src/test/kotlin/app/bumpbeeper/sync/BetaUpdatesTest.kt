package app.bumpbeeper.sync

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import app.bumpbeeper.BuildConfig
import app.bumpbeeper.Prefs
import app.bumpbeeper.R
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Beta updates": version order with betas, picking the newest release, the switch's default, one notification per version. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BetaUpdatesTest {
    private lateinit var app: Application
    private val now = 1_790_000_000_000L
    private val day = 24 * 3600_000L

    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"v$tag","prerelease":$pre,"draft":$draft,"html_url":"https://github.com/Ahmedhesham2025/Speedbumb/releases/tag/v$tag",
           "assets":[{"name":"BumpBeeper-$tag.apk","browser_download_url":"https://github.com/x/BumpBeeper-$tag.apk"},
                     {"name":"BumpBeeper-$tag-google.apk","browser_download_url":"https://github.com/x/BumpBeeper-$tag-google.apk"}]}"""

    private val list = "[" + listOf(release("1.9.0", draft = true), release("1.8.0-beta2", pre = true),
        release("1.8.0-beta3", pre = true), release("1.7.1")).joinToString(",") + "]"

    /** Answers like GitHub and remembers which URLs were asked. */
    private class GitHub(val latest: String, val list: String) : UpdateCheck.Fetch {
        val asked = ArrayList<String>()
        override fun get(url: String, userAgent: String): Pair<Int, String> {
            asked.add(url)
            return 200 to if (url == UpdateCheck.LIST_URL) list else latest
        }
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Prefs.sp(app).edit().clear().commit()
    }

    @Test fun versionOrderKnowsBetas() {
        val order = listOf("1.7.1", "1.8.0-beta2", "1.8.0-beta3", "1.8.0-beta10", "1.8.0", "1.8.1-beta1")
        for (i in 0 until order.size - 1) {
            assertTrue("${order[i]} < ${order[i + 1]}", UpdateCheck.isNewer(order[i + 1], order[i], betas = true))
            assertFalse(UpdateCheck.isNewer(order[i], order[i + 1], betas = true))
        }
        assertTrue("a stable release is newer than its betas", UpdateCheck.isNewer("1.8.0", "1.8.0-beta3"))
        assertFalse("switch off: a beta is never offered", UpdateCheck.isNewer("1.8.0-beta3", "1.7.1"))
        assertEquals(0, UpdateCheck.compare("v1.8.0-beta2", "1.8.0-beta2"))
    }

    @Test fun theNewestReleaseSkipsDraftsAndBetasOnlyWithTheSwitch() {
        assertEquals("v1.8.0-beta3", UpdateCheck.newest(JSONArray(list), betas = true)!!.getString("tag_name"))
        assertEquals("v1.7.1", UpdateCheck.newest(JSONArray(list), betas = false)!!.getString("tag_name"))
    }

    @Test fun switchOnPicksTheNewestBetaForThisEdition() {
        Prefs.setBetaUpdates(app, true)
        val gh = GitHub(release("1.7.1"), list)
        val u = UpdateCheck.check(app, false, current = "1.8.0-beta2", fetch = gh, now = now)!!
        assertEquals("1.8.0-beta3", u.version)
        val google = BuildConfig.FLAVOR == "play"
        assertEquals("https://github.com/x/BumpBeeper-1.8.0-beta3${if (google) "-google" else ""}.apk", u.downloadUrl)
        assertEquals(listOf(UpdateCheck.LIST_URL), gh.asked)
        // Once a day: the next start uses the saved answer.
        assertEquals("1.8.0-beta3", UpdateCheck.check(app, false, current = "1.8.0-beta2", fetch = gh, now = now + day / 2)!!.version)
        assertEquals(1, gh.asked.size)
    }

    @Test fun switchOffKeepsReleasesLatestAndIgnoresBetas() {
        Prefs.setBetaUpdates(app, false)
        val gh = GitHub(release("1.7.1"), list)
        assertNull("1.7.1 installed: the betas in the list are not even asked for",
            UpdateCheck.check(app, false, current = "1.7.1", fetch = gh, now = now))
        assertEquals(listOf(UpdateCheck.LATEST_URL), gh.asked)
        // A beta install that turned betas off still hears about the stable release.
        val stable = GitHub(release("1.8.0"), list)
        assertEquals("1.8.0", UpdateCheck.check(app, true, current = "1.8.0-beta3", fetch = stable, now = now)!!.version)
        // And a beta saved earlier is no longer offered once the switch is off.
        Prefs.setBetaUpdates(app, true)
        UpdateCheck.check(app, true, current = "1.7.1", fetch = gh, now = now)
        Prefs.setBetaUpdates(app, false)
        assertNull(UpdateCheck.check(app, false, current = "1.7.1", fetch = gh, now = now + 1))
    }

    @Test fun theSwitchDefaultsToTheInstalledKind() {
        assertTrue(UpdateCheck.isPreRelease("1.8.0-beta2"))
        assertFalse(UpdateCheck.isPreRelease("1.7.1"))
        val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        assertEquals("unset: on for a beta install, off for a stable one", UpdateCheck.isPreRelease(installed), Prefs.betaUpdates(app))
        Prefs.setBetaUpdates(app, !Prefs.betaUpdates(app))
        assertEquals("the user's choice wins", !UpdateCheck.isPreRelease(installed), Prefs.betaUpdates(app))
    }

    @Test fun oneNotificationPerVersionAlsoAfterARestart() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val u = UpdateCheck.Update("1.8.0-beta3", "https://github.com/x/BumpBeeper-1.8.0-beta3.apk", "https://github.com/r")
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse("no permission: the in-app banner only", UpdateCheck.notifyOnce(app, u))
        assertNull(Prefs.sp(app).getString(Prefs.UPDATE_NOTIFIED, null))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(UpdateCheck.notifyOnce(app, u))
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(app.getString(R.string.update_notif_text, "1.8.0-beta3"), shadowOf(n).contentText.toString())
        assertFalse("same version: never again", UpdateCheck.notifyOnce(app, u))
        // A restart reads the same settings: still not again. A newer version is told once more.
        assertEquals("1.8.0-beta3", Prefs.sp(app).getString(Prefs.UPDATE_NOTIFIED, null))
        assertFalse(UpdateCheck.notifyOnce(app, u.copy()))
        assertTrue(UpdateCheck.notifyOnce(app, u.copy(version = "1.8.0")))
        assertFalse("a link off GitHub is never opened", UpdateCheck.notifyOnce(app, u.copy(version = "9.0.0",
            downloadUrl = "http://evil.example/x.apk", htmlUrl = "https://evil.example")))
    }
}
