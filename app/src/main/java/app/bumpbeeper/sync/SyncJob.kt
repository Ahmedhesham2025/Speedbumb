package app.bumpbeeper.sync

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/** Runs [Sync.run] on its own thread when JobScheduler says there is network. Scheduled by [Sync]. */
class SyncJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val extras = params.extras
        val app = applicationContext
        Thread({
            val retry = try {
                Sync.run(
                    app, extras.getInt(Sync.EXTRA_PULL_ONLY, 0) == 1,
                    extras.getDouble(Sync.EXTRA_LAT, Double.NaN), extras.getDouble(Sync.EXTRA_LON, Double.NaN),
                )
            } catch (e: Exception) {
                Log.w("BumpBeeper", "sync run failed", e)
                true
            }
            jobFinished(params, retry)
        }, "bump-sync").start()
        return true   // still working on the thread
    }

    /** Network went away mid-run: whatever finished is saved; let JobScheduler try again. */
    override fun onStopJob(params: JobParameters): Boolean = true
}
