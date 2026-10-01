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
}
