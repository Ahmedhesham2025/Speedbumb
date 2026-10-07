package app.bumpbeeper.auto

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import app.bumpbeeper.MainActivity
import app.bumpbeeper.R
import app.bumpbeeper.research.ResearchFiles
import app.bumpbeeper.sync.RestoreReset

/**
 * Runs first in every process (app, service, or a notification button in a fresh process): registers what must be
 * known before anything else runs. Keep it tiny.
 */
class AppStart : Application() {
    override fun onCreate() {
        super.onCreate()
        // First start after a backup restore (#93): reset the training state before any sync can run.
        RestoreReset.check(this)
        // Held data of trips that started by themselves (#49): shared-map points, the speed-limit route, training samples.
        TripHold.installBuiltIns()
        TripHold.installTraining(this)
        if (RestoreReset.noticePending(this)) registerActivityLifecycleCallbacks(RestoreNotice())
        // Research files: 14 days / 2 GB at most, also after research recording was switched off (background thread).
        ResearchFiles.tidyLater(this)
    }

    /** "Restored from a backup: 'Help improve detection' was switched off", once, when the app is next opened. */
    private inner class RestoreNotice : ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) {
            if (a !is MainActivity || a.isFinishing) return
            unregisterActivityLifecycleCallbacks(this)
            if (!RestoreReset.takeNotice(a)) return
            AlertDialog.Builder(a).setMessage(R.string.restore_training_off).setPositiveButton(android.R.string.ok, null).show()
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}
