package app.bumpbeeper.auto

import android.app.Application

/**
 * Runs first in every process (app, service, or a notification button in a fresh process): registers what must be
 * known before anything else runs. Keep it tiny.
 */
class AppStart : Application() {
    override fun onCreate() {
        super.onCreate()
        // Held data of trips that started by themselves (#49): shared-map points, the speed-limit route, training samples.
        TripHold.installBuiltIns()
        TripHold.installTraining(this)
    }
}
