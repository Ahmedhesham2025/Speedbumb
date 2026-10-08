package app.bumpbeeper.ui

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import app.bumpbeeper.LiveState
import app.bumpbeeper.MainActivity
import app.bumpbeeper.Prefs
import app.bumpbeeper.R
import app.bumpbeeper.auto.AutoDetect
import app.bumpbeeper.sync.TrainingConsent
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
 * Privacy gates on the real screen: the disclosure comes before any permission request, only "Continue" turns
 * auto-detect on, only "Turn on" in the consent dialog turns "Help improve detection" on, and a rotation during the
 * walk-through is not taken as an answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoDetectFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<MainActivity>

    @Before fun setUp() {
        // The shared-map question is answered, so no other dialog opens on resume.
        Prefs.sp(app).edit().clear().putString(Prefs.SYNC_CHOICE, Prefs.SYNC_RECEIVE).commit()
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

    private fun findCheckBox(v: View, text: String): CheckBox? {
        if (v is CheckBox && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findCheckBox(v.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun trainingSwitch(): CheckBox {
        val a = controller.get()
        a.select(MainActivity.TAB_SETTINGS)
        idle()
        return findCheckBox(a.window.decorView, app.getString(R.string.train_switch)) ?: throw AssertionError("no training switch")
    }

    @Test fun consentCancelOrBackLeavesTrainingOff() {
        val box = trainingSwitch()
        box.performClick(); idle()
        assertEquals(app.getString(R.string.train_consent_title), title(latest()))
        click(latest(), DialogInterface.BUTTON_NEGATIVE)
        assertFalse(Prefs.trainingConsent(app))
        assertFalse(box.isChecked)

        box.performClick(); idle()
        latest().cancel(); idle()   // back button / tap outside
        assertFalse(Prefs.trainingConsent(app))
        assertFalse(box.isChecked)
    }

    @Test fun turnOnStoresTheCurrentConsentVersion() {
        val box = trainingSwitch()
        box.performClick(); idle()
        click(latest(), DialogInterface.BUTTON_POSITIVE)
        assertTrue(Prefs.trainingConsent(app))
        assertEquals(TrainingConsent.TRAINING_CONSENT_VERSION, Prefs.trainingConsentVersion(app))
        assertTrue(box.isChecked)
    }
}
