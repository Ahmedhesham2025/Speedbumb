package app.bumpbeeper

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The phone placement setting reaches the driving monitor's config (pocket mode, mounted checks). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsPlacementTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        Prefs.sp(ctx).edit().clear().commit()
    }

    /** What BumpService does at each trip start. */
    private fun tripStartConfig() = DrivingConfig().also { Prefs.applyTo(it, ctx) }

    @Test fun defaultIsUnknown() = assertEquals("unknown", tripStartConfig().placement)

    @Test fun everyChoiceIsCopied() {
        for (p in Prefs.PLACEMENTS) {
            Prefs.setPlacement(ctx, p)
            assertEquals(p, tripStartConfig().placement)
        }
    }

    @Test fun changeAppliesAtTheNextTripStart() {
        Prefs.setPlacement(ctx, "mounted")
        val first = tripStartConfig()
        Prefs.setPlacement(ctx, "pocket")
        assertEquals("mounted", first.placement)   // the running trip keeps its config
        assertEquals("pocket", tripStartConfig().placement)
    }

    @Test fun badStoredValueIsUnknown() {
        Prefs.sp(ctx).edit().putString(Prefs.PLACEMENT, "dashboard").commit()
        assertEquals("unknown", tripStartConfig().placement)
    }
}
