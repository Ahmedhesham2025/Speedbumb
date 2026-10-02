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
}
