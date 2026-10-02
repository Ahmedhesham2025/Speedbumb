package app.bumpbeeper

import android.os.Looper
import app.bumpbeeper.sync.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Is there a newer version?" without the network: version comparison, pre-releases and dev builds.
 * The 24-h back-off needs an injectable clock and network (see the android-platform issue linked in the PR).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckTest {

    @Test fun comparesEachPartAsANumberNotAsText() {
        assertTrue(UpdateCheck.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdateCheck.isNewer("1.9.0", "1.10.0"))
        assertTrue(UpdateCheck.isNewer("2.0.0", "1.99.99"))
        assertTrue(UpdateCheck.isNewer("1.2.10", "1.2.9"))
        assertFalse(UpdateCheck.isNewer("1.2.9", "1.2.10"))
    }

    @Test fun vPrefixIsIgnoredOnEitherSide() {
        assertTrue(UpdateCheck.isNewer("v1.3.0", "1.2.0"))
        assertTrue(UpdateCheck.isNewer("V1.3.0", "v1.2.0"))
        assertFalse(UpdateCheck.isNewer("v1.2.0", "1.2.0"))
    }

    @Test fun equalVersionsAreNotAnUpdate() {
        assertFalse(UpdateCheck.isNewer("1.2.0", "1.2.0"))
        assertFalse(UpdateCheck.isNewer("0.0.1", "0.0.1"))
    }

    @Test fun missingPartsCountAsZero() {
        assertTrue(UpdateCheck.isNewer("1.3", "1.2.9"))
        assertFalse(UpdateCheck.isNewer("1.2", "1.2.0"))
        assertTrue(UpdateCheck.isNewer("2", "1.9.9"))
    }

    @Test fun preReleaseIsNeverOffered() {
        assertTrue(UpdateCheck.isPreRelease("1.3.0-rc1"))
        assertTrue(UpdateCheck.isPreRelease("v1.3.0-beta.2"))
        assertTrue(UpdateCheck.isPreRelease("1.3.0-rc1+build.7"))
        assertFalse(UpdateCheck.isPreRelease("1.3.0"))
        assertFalse(UpdateCheck.isPreRelease("v1.3.0"))
        assertFalse("build metadata alone is not a pre-release", UpdateCheck.isPreRelease("1.3.0+build.7"))
        assertFalse(UpdateCheck.isNewer("1.3.0-rc1", "1.2.0"))
        assertFalse(UpdateCheck.isNewer("v9.0.0-rc1", "1.0.0"))
    }

    @Test fun garbageVersionsAreNeverNewer() {
        assertFalse(UpdateCheck.isNewer("", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("latest", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("1.2.3.4", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("1.x.0", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("2.0.0", "unknown"))
    }

    @Test fun devAndLocalBuildsAreRecognised() {
        assertTrue(UpdateCheck.isDevBuild("0.0.0-local"))
        assertTrue(UpdateCheck.isDevBuild("0.0.0-local-dev"))   // debug build from Android Studio
        assertTrue(UpdateCheck.isDevBuild("1.2.0-dev"))         // PR test build
        assertTrue(UpdateCheck.isDevBuild("unknown"))
        assertTrue(UpdateCheck.isDevBuild(""))
        assertFalse(UpdateCheck.isDevBuild("1.2.0"))
    }

    @Test fun devBuildNeverChecksAndAnswersNull() {
        // The unit-test build is a debug build (version "...-dev"), so latest() must stop before any network
        // call and must not record a check time.
        val ctx = RuntimeEnvironment.getApplication()
        assertTrue(UpdateCheck.isDevBuild(TraceWriter.appVersion(ctx)))
        Prefs.sp(ctx).edit().putString(Prefs.UPDATE_VERSION, "99.0.0").putString(Prefs.UPDATE_HTML_URL, "https://x").apply()

        var answered = false
        var result: UpdateCheck.Update? = UpdateCheck.Update("x", null, "x")
        UpdateCheck.latest(ctx, force = true) { answered = true; result = it }
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!answered && System.nanoTime() < deadline) {
            main.idle()
            Thread.sleep(5)
        }
        assertTrue("callback ran on the main thread", answered)
        assertNull("a cached newer version is not offered to a dev build", result)
        assertEquals(0L, Prefs.sp(ctx).getLong(Prefs.UPDATE_CHECKED_AT, 0L))
    }
}
