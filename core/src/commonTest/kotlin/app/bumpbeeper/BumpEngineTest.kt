package app.bumpbeeper

import kotlin.test.Test

/** Right-click → Run 'BumpEngineTest' in Android Studio. Runs on your computer, no phone needed. */
class BumpEngineTest {
    @Test fun learnThenBeep() = Scenarios.learnThenBeep()
    @Test fun otherDirection() = Scenarios.otherDirection()
    @Test fun handlingIgnored() = Scenarios.handlingIgnored()
    @Test fun parkedAndNoGps() = Scenarios.parkedAndNoGps()
    @Test fun crawlVersusRemoved() = Scenarios.crawlVersusRemoved()
    @Test fun userMute() = Scenarios.userMute()
    @Test fun softThenFull() = Scenarios.softThenFull()
    @Test fun legacySpotSoftUntilFelt() = Scenarios.legacySpotSoftUntilFelt()
    @Test fun dipIsABump() = Scenarios.dipIsABump()
    @Test fun noGyroStillLearns() = Scenarios.noGyroStillLearns()
    @Test fun severityCountsAndSounds() = Scenarios.severityCountsAndSounds()
    @Test fun fastDipRejected() = Scenarios.fastDipRejected()
    @Test fun quietWhenSlow() = Scenarios.quietWhenSlow()
    @Test fun missReportsNearbyJolt() = Scenarios.missReportsNearbyJolt()
    @Test fun calmDrivingScoresHigh() = Scenarios.calmDrivingScoresHigh()
    @Test fun speedingAndHardBraking() = Scenarios.speedingAndHardBraking()
    @Test fun swerving() = Scenarios.swerving()
    @Test fun speedBumpsTakenFast() = Scenarios.speedBumpsTakenFast()
    @Test fun phoneHandledWhileDriving() = Scenarios.phoneHandledWhileDriving()
    @Test fun pocketShiftNotACorner() = Scenarios.pocketShiftNotACorner()
    @Test fun sharpTurnCounted() = Scenarios.sharpTurnCounted()
    @Test fun pocketModeExcusesOnlyJostles() = Scenarios.pocketModeExcusesOnlyJostles()
    @Test fun unlockInPocketIsPhoneUse() = Scenarios.unlockInPocketIsPhoneUse()
    @Test fun mountedScreenOnIsNoPhoneUse() = Scenarios.mountedScreenOnIsNoPhoneUse()
    @Test fun handHeldCallIsPhoneUse() = Scenarios.handHeldCallIsPhoneUse()
    @Test fun brakingNeedsTheGpsSpeed() = Scenarios.brakingNeedsTheGpsSpeed()
    @Test fun repeatedHandlingIsPhoneUse() = Scenarios.repeatedHandlingIsPhoneUse()
    @Test fun fastBumpVersusRoadJoint() = Scenarios.fastBumpVersusRoadJoint()
}
