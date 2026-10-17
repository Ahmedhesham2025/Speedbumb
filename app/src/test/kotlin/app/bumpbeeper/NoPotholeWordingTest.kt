package app.bumpbeeper

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Since v2 every spot is a bump with a severity; there are no potholes on screen any more (old pothole spots are
 * handled in code as legacy, and keep their identifiers there). A plain unit test, no Android: it reads every
 * `values*` strings*.xml (strings, notifications) and fails on "pothole" (any case) or «حفر» (حفرة, الحفر),
 * names and text alike.
 */
class NoPotholeWordingTest {
    private val banned = listOf(Regex("pothole", RegexOption.IGNORE_CASE), Regex("حفر"))

    /** Gradle runs unit tests in the module folder; an IDE may run them from the repository root. */
    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
        ?: throw AssertionError("res folder not found from ${File(".").absolutePath}")

    @Test fun noStringsFileMentionsPotholes() {
        val files = res().listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .flatMap { d -> d.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().toList() }
        assertTrue("English and Arabic at least", files.map { it.parentFile?.name }.toSet().containsAll(listOf("values", "values-ar")))
        val hits = files.flatMap { f ->
            f.readLines(Charsets.UTF_8).withIndex()
                .filter { (_, line) -> banned.any { it.containsMatchIn(line) } }
                .map { (i, line) -> "${f.parentFile?.name}/${f.name}:${i + 1}: ${line.trim().take(120)}" }
        }
        if (hits.isNotEmpty()) fail("Pothole wording is back in the UI strings:\n" + hits.joinToString("\n"))
    }
}
