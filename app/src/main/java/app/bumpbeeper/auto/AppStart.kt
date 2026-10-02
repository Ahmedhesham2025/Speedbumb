package app.bumpbeeper.auto

import android.app.Application

/**
 * Runs first in every process (app, service, or a notification button in a fresh process): registers what must be
 * known before anything else runs. Keep it tiny.
 */
class AppStart : Application() {
    override fun onCreate() {
        super.onCreate()
        // Held data of trips that started by themselves (#49). Training samples register their listeners here too.
        TripHold.installBuiltIns()
    }
}
