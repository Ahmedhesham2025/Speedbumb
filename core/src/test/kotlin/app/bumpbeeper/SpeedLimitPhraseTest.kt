package app.bumpbeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The spoken road speed limit after the speeding tone (live speed limits), English and Arabic. */
class SpeedLimitPhraseTest {
    @Test fun english() {
        assertEquals("Speed limit 60.", Phrases.speedLimit("en", 60))
        assertEquals("Speed limit 120.", Phrases.speedLimit("en", 120))
        assertEquals("Speed limit 45.", Phrases.speedLimit("fr", 45))   // unknown language: English
    }

    @Test fun arabic() {
        assertEquals("السرعة المسموحة ستين.", Phrases.speedLimit("ar", 60))
        assertEquals("السرعة المسموحة مية.", Phrases.speedLimit("ar", 100))
        assertEquals("السرعة المسموحة مية وعشرين.", Phrases.speedLimit("ar", 120))
        assertEquals("السرعة المسموحة خمسة وأربعين.", Phrases.speedLimit("ar", 45))
        assertEquals("السرعة المسموحة 250.", Phrases.speedLimit("ar", 250))   // out of range: digits
    }

    @Test fun arabicNumbers() {
        assertEquals("عشرين", Phrases.arabicNumber(20))
        assertEquals("تلاتين", Phrases.arabicNumber(30))
        assertEquals("تمانين", Phrases.arabicNumber(80))
        assertEquals("خمستاشر", Phrases.arabicNumber(15))
        assertEquals("عشرة", Phrases.arabicNumber(10))
        assertEquals("مية وخمسة وتلاتين", Phrases.arabicNumber(135))
        assertEquals("مية وخمستاشر", Phrases.arabicNumber(115))
        assertNull(Phrases.arabicNumber(0))
        assertNull(Phrases.arabicNumber(200))
    }
}
