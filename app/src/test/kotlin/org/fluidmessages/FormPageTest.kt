package org.fluidmessages

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Full pages scroll their own content. A scrolling area inside one with no height limit crashes Compose
 * ("measured with an infinity maximum height"), which is what broke the poll and event pages. This scans every
 * FormPage's content for that mistake.
 */
class FormPageTest {
    @Test fun noUnboundedScrollingInsideFullPages() {
        val problems = mutableListOf<String>()
        val sources = File("src/main/kotlin/org/fluidmessages").listFiles { file -> file.extension == "kt" }.orEmpty()
        for (file in sources) {
            val text = file.readText()
            var at = text.indexOf("FormPage(")
            while (at >= 0) {
                val start = text.indexOf("text = {", at)
                val end = text.indexOf("confirmButton", start.coerceAtLeast(at))
                if (start in at..(at + 400) && end > start) {
                    val content = text.substring(start, end)
                    for (match in Regex("""(LazyColumn|LazyVerticalGrid)\(([^)]*)\)|Modifier([^\n]*?)\.verticalScroll\(""").findAll(content)) {
                        val call = match.value
                        val bounded = call.contains("heightIn(max") || call.contains(".height(")
                        if (!bounded) problems.add("${file.name}: ${call.take(80)}")
                    }
                }
                at = text.indexOf("FormPage(", at + 1)
            }
        }
        assertTrue("Scrolling with no height limit inside a full page:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
