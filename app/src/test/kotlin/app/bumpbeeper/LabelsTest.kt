package app.bumpbeeper

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Label mode, as far as it can be exercised without a running recording service.
 * TODO(emulator): "undo never takes the count below 0" and "count unchanged when the write fails" live in
 * BumpService.postLabel and need the service running in label mode (see docs/test-plan.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() = LiveState.resetTrip()

    @Test fun labelKindsMatchTheTraceContract() {
        // The replay tool scores bump / pothole_l / pothole_r and drops the label before an undo: renaming
        // any of these silently breaks ground truth in every recording.
        assertEquals(listOf("bump", "pothole_l", "pothole_r", "rough", "undo"), Labels.ALL)
        assertEquals(Labels.ALL.size, Labels.ALL.toSet().size)
    }

    @Test fun unknownKindsAreRejected() {
        for (kind in listOf("", "pothole", "BUMP", "bump ", "pothole_left", "undo;bump", "bump,1")) {
            assertFalse("'$kind' must be rejected", BumpService.label(ctx, kind))
        }
        assertEquals(0, LiveState.labelCount)
        assertEquals("", LiveState.lastLabel)
    }

    @Test fun knownKindsAreRefusedWhenNotRecording() {
        LiveState.labelMode = false
        for (kind in Labels.ALL) assertFalse(BumpService.label(ctx, kind))
        assertEquals("nothing written, so nothing counted", 0, LiveState.labelCount)
    }

    @Test fun newTripClearsLabelCounters() {
        LiveState.labelCount = 4
        LiveState.lastLabel = Labels.ROUGH
        LiveState.resetTrip()
        assertEquals(0, LiveState.labelCount)
        assertEquals("", LiveState.lastLabel)
    }
}
