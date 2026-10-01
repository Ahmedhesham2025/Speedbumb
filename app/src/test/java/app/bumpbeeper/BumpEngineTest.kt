package app.bumpbeeper

import org.junit.Test

/** Right-click → Run 'BumpEngineTest' in Android Studio. Runs on your computer, no phone needed. */
class BumpEngineTest {
    @Test fun learnThenBeep() = Scenarios.learnThenBeep()
    @Test fun otherDirection() = Scenarios.otherDirection()
    @Test fun handlingIgnored() = Scenarios.handlingIgnored()
    @Test fun parkedAndNoGps() = Scenarios.parkedAndNoGps()
    @Test fun crawlVersusRemoved() = Scenarios.crawlVersusRemoved()
    @Test fun userMute() = Scenarios.userMute()
    @Test fun potholeVsBump() = Scenarios.potholeVsBump()
    @Test fun potholeVsBumpNoGyro() = Scenarios.potholeVsBumpNoGyro()
    @Test fun potholeSidesAndCounts() = Scenarios.potholeSidesAndCounts()
    @Test fun fastPothole() = Scenarios.fastPothole()
    @Test fun quietWhenSlow() = Scenarios.quietWhenSlow()
    @Test fun missReportsNearbyJolt() = Scenarios.missReportsNearbyJolt()
}
