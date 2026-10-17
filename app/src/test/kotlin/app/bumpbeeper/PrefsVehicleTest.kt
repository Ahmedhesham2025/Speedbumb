package app.bumpbeeper

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The car the engine learned (E2) is kept between trips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsVehicleTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        Prefs.sp(ctx).edit().clear().commit()
    }

    @Test fun freshCarByDefault() {
        val v = Prefs.vehicle(ctx)
        assertEquals(2.6, v.wheelbaseM, 1e-9)
        assertEquals(0, v.hits)
    }

    @Test fun learnedCarComesBack() {
        val v = VehicleProfile()
        repeat(5) { v.learn(1000.0, 2.9) }   // 2.9 m measured five times
        Prefs.setVehicle(ctx, v)
        val back = Prefs.vehicle(ctx)
        assertEquals(v.wheelbaseM, back.wheelbaseM, 1e-4)
        assertEquals(5, back.hits)
    }

    @Test fun unreadableValueGivesAFreshCar() {
        Prefs.sp(ctx).edit().putString(Prefs.VEHICLE, "garbage").commit()
        assertEquals(2.6, Prefs.vehicle(ctx).wheelbaseM, 1e-9)
    }
}
