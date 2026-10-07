package app.bumpbeeper.sync

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import app.bumpbeeper.research.ResearchConsent
import app.bumpbeeper.research.ResearchUploader

/**
 * Runs [Sync.run] (or [SpeedLimitSync.run], the training or research jobs) on its own thread when JobScheduler says
 * there is network. Scheduled by [Sync], [SpeedLimitSync], [TrainingConsent], [ResearchConsent] and [ResearchUploader].
 */
class SyncJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val extras = params.extras
        val app = applicationContext
        Thread({
            val retry = try {
                val research = extras.getInt(ResearchConsent.EXTRA_JOB, 0)
                if (research == ResearchConsent.JOB_CONSENT) ResearchConsent.run(app)
                else if (research == ResearchConsent.JOB_UPLOAD) ResearchUploader.run(app)
                else if (extras.getInt(SpeedLimitSync.EXTRA_JOB, 0) == 1) SpeedLimitSync.run(app)
                else if (extras.getInt(TrainingConsent.EXTRA_JOB, 0) == 1) TrainingConsent.run(app)
                else Sync.run(
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

    /**
     * Network went away mid-run (for research uploads also: no Wi-Fi any more, or the battery is low): whatever finished
     * is saved; research stops before its next file. Let JobScheduler try again.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        if (params.extras.getInt(ResearchConsent.EXTRA_JOB, 0) == ResearchConsent.JOB_UPLOAD) ResearchUploader.stopRequested = true
        return true
    }
}
