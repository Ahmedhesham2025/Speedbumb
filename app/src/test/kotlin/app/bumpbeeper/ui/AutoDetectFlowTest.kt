package app.bumpbeeper.ui

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import app.bumpbeeper.LiveState
import app.bumpbeeper.MainActivity
import app.bumpbeeper.Prefs
import app.bumpbeeper.R
import app.bumpbeeper.auto.AutoDetect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

/**
 * The privacy-critical gates on the real screen: the prominent disclosure comes before any permission request and only
 * "Continue" turns auto-detect on; a rotation
 * during the walk-through is not taken as an answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoDetectFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<MainActivity>

    @Before fun setUp() {
        // The shared-map question is answered, so no other dialog opens on resume.
        Prefs.sp(app).edit().clear().putString(Prefs.SYNC_CHOICE, Prefs.SYNC_RECEIVE).commit()
        app.getSharedPreferences("sync_choice", 0).edit().clear().commit()
        LiveState.recording = false
        LiveState.watching = false
        ShadowAlertDialog.reset()
        ShadowToast.reset()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun latest(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    private fun click(d: AlertDialog, which: Int) { d.getButton(which).performClick(); idle() }
    private fun title(d: AlertDialog): String = shadowOf(d).title.toString()
    private fun requested(a: MainActivity) = shadowOf(a).lastRequestedPermission?.requestedPermissions?.toList()

    @Test fun disclosureComesFirstAndContinueTurnsItOn() {
        val a = controller.get()
        var answer: Boolean? = null
        a.turnOnAutoDetect { answer = it }
        idle()
        val d = latest()
        assertEquals(app.getString(R.string.auto_disclosure_title), title(d))
        assertNull("no permission request before the disclosure is answered", requested(a))
        assertFalse(AutoDetect.enabled(app))
        click(d, DialogInterface.BUTTON_POSITIVE)
        assertEquals(true, answer)
        assertTrue(AutoDetect.enabled(app))
        assertTrue(requested(a)!!.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test fun noThanksLeavesItOffAndAsksNothing() {
        val a = controller.get()
        var answer: Boolean? = null
        a.turnOnAutoDetect { answer = it }
        idle()
        click(latest(), DialogInterface.BUTTON_NEGATIVE)
        assertEquals(false, answer)
        assertFalse(AutoDetect.enabled(app))
        assertNull(requested(a))
    }

    @Test fun disclosureCannotBeDismissed() {
        controller.get().turnOnAutoDetect { }
        idle()
        assertFalse(shadowOf(latest()).isCancelable)
    }

    @Test fun rotationIsNotAnAnswer() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val old = controller.get()
        old.turnOnAutoDetect { }
        idle()
        click(latest(), DialogInterface.BUTTON_POSITIVE)
        val why = latest()
        assertEquals(app.getString(R.string.auto_bg_title), title(why))
        assertTrue(why.isShowing)

        controller.recreate()
        idle()
        val fresh = controller.get()
        assertNotSame(old, fresh)
        assertNull("no \"needs Allow all the time\" after a rotation", ShadowToast.getLatestToast())
        // The new screen shows the same question again, not the "refused" dialog.
        val again = latest()
        assertNotSame(why, again)
        assertTrue(again.isShowing)
        assertEquals(app.getString(R.string.auto_bg_title), title(again))
        assertTrue(AutoDetect.enabled(app))
    }
}
