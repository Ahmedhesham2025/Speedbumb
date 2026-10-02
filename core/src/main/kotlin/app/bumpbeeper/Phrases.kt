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
     */
    fun cluster(lang: String, count: Int, kind: BumpKind?, harshSide: Side?): String = if (lang == "ar") {
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
}
