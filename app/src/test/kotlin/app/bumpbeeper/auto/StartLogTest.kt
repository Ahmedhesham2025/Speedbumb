package app.bumpbeeper.auto

import app.bumpbeeper.auto.StartLog.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class StartLogTest {
    @Test fun repeatsAreKeptOnceOnlyTheLastTwelveStayAndItSurvivesARoundTrip() {
        var e = StartLog.Entries()
        e = e.plus(1, Reason.MOTION_WALKING).plus(2, Reason.MOTION_WALKING)   // motion fires again and again
        assertEquals(1, e.list.size)
        for (i in 0 until 20) e = e.plus(10L + i, if (i % 2 == 0) Reason.MOTION_CHECK else Reason.SKIP_NO_FIX)
        assertEquals(StartLog.MAX, e.list.size)
        assertEquals(29L to Reason.SKIP_NO_FIX, e.list.last())
        assertEquals(e.list, StartLog.Entries.decode(e.encode()).list)
        assertEquals(emptyList<Pair<Long, Reason>>(), StartLog.Entries.decode("junk;5:NO_SUCH").list)
    }
}
