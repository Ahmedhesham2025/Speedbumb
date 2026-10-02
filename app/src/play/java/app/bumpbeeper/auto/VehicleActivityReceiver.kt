package app.bumpbeeper.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.bumpbeeper.BumpService
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Google's activity transitions (play edition).
 *  - IN_VEHICLE enter starts recording: Android 12+ allows an activity-recognition transition to start a foreground
 *    service from the background (if it still refuses, a "tap to start" notification appears).
 *  - WALKING / RUNNING enter: the user left the car, so a recording stops after 1 min parked instead of the setting.
 *  - Nothing else: a car standing at a light also ends IN_VEHICLE, so that alone must not shorten anything.
 */
class VehicleActivityReceiver : BroadcastReceiver() {
    /** What a transition does; replaced in tests. */
    interface Actions {
        fun startRecording(ctx: Context)
        fun userWalking(ctx: Context)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents ?: return
        handle(ctx, events)
    }

    internal fun handle(ctx: Context, events: List<ActivityTransitionEvent>, actions: Actions = Real) {
        if (!AutoDetect.enabled(ctx)) return
        for (e in events) {
            if (e.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue
            when (e.activityType) {
                DetectedActivity.IN_VEHICLE -> actions.startRecording(ctx)
                DetectedActivity.WALKING, DetectedActivity.RUNNING -> actions.userWalking(ctx)
            }
        }
    }

    private object Real : Actions {
        override fun startRecording(ctx: Context) = BumpService.startAuto(ctx, BumpService.SOURCE_VEHICLE)
        override fun userWalking(ctx: Context) = BumpService.userWalking(ctx)
    }
}
