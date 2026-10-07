package app.bumpbeeper.sync

import android.content.Context
import android.util.Log
import app.bumpbeeper.LiveState
import app.bumpbeeper.Prefs
import app.bumpbeeper.auto.TripHold
import app.bumpbeeper.research.ResearchConsent
import java.io.File
import java.util.UUID

/**
 * First start after an Android backup restore or a device transfer (#93). settings.xml and bumps.db come along, the
 * shared-map sign-in (sync_auth) doesn't: this phone signs in as a new anonymous device that never agreed to
 * "Help improve detection". So before any network call the training choice is switched off here (nothing to tell
 * the server: the new device never consented), its outbox, pending flags and upload time are cleared, and held data of
 * unconfirmed trips is dropped. Research recording is switched off the same way and its upload queue forgotten (its
 * files live in noBackupFilesDir and never move). The shared-map choice and road speed limits stay as chosen (waiting
 * routes live in noBackupFilesDir and never move); the new device registers itself on its first sync.
 *
 * A restore is noticed by a random install id in noBackupFilesDir, which neither backup nor transfer copies:
 * settings present but no id = restored data.
 */
object RestoreReset {
    private const val TAG = "BumpBeeper"
    private const val MARKER = "install_id"
    /** Set when the reset switched training off: shown once on the next open ([takeNotice]). */
    private const val NOTICE = "restore_notice"

    internal fun marker(ctx: Context) = File(ctx.noBackupFilesDir, MARKER)
    private fun notice(ctx: Context) = File(ctx.noBackupFilesDir, NOTICE)

    /**
     * [app.bumpbeeper.auto.AppStart.onCreate], before anything else of the process runs. A normal start costs one
     * file check; only the first start after a restore touches the database. Returns true when it reset.
     */
    fun check(ctx: Context): Boolean {
        val app = ctx.applicationContext ?: ctx
        val marker = marker(app)
        if (marker.exists()) return false
        val restored = Prefs.sp(app).all.isNotEmpty()   // a fresh install has no settings yet
        if (restored) reset(app)
        try {
            marker.parentFile?.mkdirs()
            marker.writeText(UUID.randomUUID().toString())
        } catch (e: Exception) {
            Log.w(TAG, "install id not written: ${e.javaClass.simpleName}")
        }
        return restored
    }

    internal fun reset(ctx: Context) {
        // Research: off, nothing pending, queue forgotten, first-start question again if it was on. No server call.
        ResearchConsent.resetAfterRestore(ctx)
        val wasOn = Prefs.trainingConsent(ctx)
        // Version 0: this anonymous device never agreed to any consent text.
        Prefs.setTrainingState(ctx, false, 0, wipe = false, sendOn = false, note = "")
        Prefs.sp(ctx).edit().remove(Prefs.TRAINING_UPLOAD_AFTER).commit()
        if (wasOn) {
            try { notice(ctx).writeText("1") } catch (e: Exception) { Log.w(TAG, "restore notice not saved: ${e.javaClass.simpleName}") }
        }
        TripHold.forgetAll(ctx)
        try {
            synchronized(Sync.lock) {
                Sync.withDb(ctx) { db ->
                    TrainingStore(db).clear()
                    val s = SyncStore(db)
                    s.put(TrainingConsent.PAUSED_UNTIL, null)
                    for ((id, _) in s.heldTrips()) s.heldDrop(id)
                }
            }
        } catch (e: Exception) {
            // Consent is off, so nothing of it is sent; held points without a hold are swept by TripHold.expire.
            Log.w(TAG, "restore: outbox not cleared: ${e.javaClass.simpleName}")
        }
        LiveState.trainingQueued = 0
        Log.i(TAG, "restored from a backup: training and research switched off, held data dropped")
    }

    fun noticePending(ctx: Context): Boolean = notice(ctx).exists()

    /** True once after a reset that switched "Help improve detection" off; the screen shows the notice. */
    fun takeNotice(ctx: Context): Boolean = notice(ctx).let { it.exists() && it.delete() }
}
