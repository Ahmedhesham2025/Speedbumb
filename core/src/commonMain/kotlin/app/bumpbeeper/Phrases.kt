package app.bumpbeeper

/** What the voice says. Kept short: a warning has to finish before the spot. Unknown languages get English. */
object Phrases {
    /** A confirmed spot in the strong band ahead: "Strong bump ahead." / «مطب قوي قدام.» */
    fun strongBump(lang: String): String = if (lang == "ar") "مطب قوي قدام." else "Strong bump ahead."

    /**
     * A group of [count] spots ahead ("3 bumps ahead."), adding that one of them is strong when [anyStrong].
     * Null below 3: a group is at least 3 spots, and 2 would need the Arabic dual form, which this doesn't
     * build; the caller then plays the first spot's own sound.
     */
    fun cluster(lang: String, count: Int, anyStrong: Boolean): String? = when {
        count < 3 -> null
        // Arabic counts 3–10 take the plural, 11 and up the singular.
        lang == "ar" -> "$count ${if (count in 3..10) "مطبات" else "مطب"} قدام${if (anyStrong) "، منهم واحد قوي" else ""}."
        else -> "$count bumps ahead${if (anyStrong) ", one strong" else ""}."
    }

    /**
     * The road's speed limit, said after the speeding tone: "Speed limit 60." / «السرعة المسموحة ستين.»
     * Arabic is a draft (Egyptian spoken numbers, units before tens as in «خمسة وأربعين»); limits outside 1..199
     * are left as digits, which the Arabic voice reads out on its own.
     */
    fun speedLimit(lang: String, kmh: Int): String =
        if (lang == "ar") "السرعة المسموحة ${arabicNumber(kmh) ?: kmh.toString()}." else "Speed limit $kmh."

    private val AR_UNITS = listOf("", "واحد", "اتنين", "تلاتة", "أربعة", "خمسة", "ستة", "سبعة", "تمانية", "تسعة")
    private val AR_TEENS = listOf("عشرة", "حداشر", "اتناشر", "تلاتاشر", "أربعتاشر", "خمستاشر", "ستاشر", "سبعتاشر", "تمنتاشر", "تسعتاشر")
    private val AR_TENS = listOf("", "", "عشرين", "تلاتين", "أربعين", "خمسين", "ستين", "سبعين", "تمانين", "تسعين")

    /** 1..199 in spoken Egyptian Arabic, else null. */
    fun arabicNumber(n: Int): String? = when {
        n !in 1..199 -> null
        n == 100 -> "مية"
        n > 100 -> "مية و" + arabicNumber(n - 100)
        n < 10 -> AR_UNITS[n]
        n < 20 -> AR_TEENS[n - 10]
        n % 10 == 0 -> AR_TENS[n / 10]
        else -> AR_UNITS[n % 10] + " و" + AR_TENS[n / 10]
    }
}
