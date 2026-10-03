package app.bumpbeeper

/** What the voice says. "Keep left" means: move to the left within your lane, around the pothole. */
object Phrases {
    fun pothole(lang: String, side: Side): String = if (lang == "ar") {
        when (side) {
            Side.RIGHT -> "حفرة على اليمين. خليك شمال."
            Side.LEFT -> "حفرة على الشمال. خليك يمين."
            Side.UNKNOWN -> "حفرة قدام."
        }
    } else {
        when (side) {
            Side.RIGHT -> "Pothole on the right. Keep left."
            Side.LEFT -> "Pothole on the left. Keep right."
            Side.UNKNOWN -> "Pothole ahead."
        }
    }

    /**
     * A group of [count] spots ahead ("3 bumps ahead."), naming a harsh pothole among them if there is one.
     * [kind] null = mixed. Kept short: it has to finish before the first spot.
     * Null below 3: a group is at least 3 spots, and 2 would need the Arabic dual form, which this doesn't
     * build; the caller then plays the first spot's own sound.
     */
    fun cluster(lang: String, count: Int, kind: BumpKind?, harshSide: Side?): String? = if (count < 3) null else if (lang == "ar") {
        // Arabic counts 3–10 take the plural, 11 and up the singular.
        val plural = count in 3..10
        val noun = when (kind) {
            BumpKind.BUMP -> if (plural) "مطبات" else "مطب"
            BumpKind.POTHOLE -> if (plural) "حفر" else "حفرة"
            else -> if (plural) "عقبات" else "عقبة"
        }
        val harsh = when (harshSide) {
            null -> ""
            Side.RIGHT -> "، حفرة عنيفة على اليمين"
            Side.LEFT -> "، حفرة عنيفة على الشمال"
            Side.UNKNOWN -> "، منهم حفرة عنيفة"
        }
        "$count $noun قدام$harsh."
    } else {
        val noun = when (kind) {
            BumpKind.BUMP -> "bumps"
            BumpKind.POTHOLE -> "potholes"
            else -> "hazards"
        }
        val harsh = when (harshSide) {
            null -> ""
            Side.RIGHT -> ", harsh pothole on the right"
            Side.LEFT -> ", harsh pothole on the left"
            Side.UNKNOWN -> ", one harsh pothole"
        }
        "$count $noun ahead$harsh."
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
