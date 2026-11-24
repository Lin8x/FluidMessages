package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Full pages scroll their own content. A scrolling area inside one with no height limit crashes Compose
 * ("measured with an infinity maximum height"), which is what broke the poll and event pages. This scans every
 * FormPage's content for that mistake, and checks that confirmations open as pages rather than small pop-ups.
 */
class FormPageTest {
    private val kotlinSources = File("src/main/kotlin/org/fluidmessages").listFiles { file -> file.extension == "kt" }.orEmpty().toList()
    private val scroll = Regex("""(LazyColumn|LazyVerticalGrid)\(([^)]*)\)|Modifier([^\n]*?)\.verticalScroll\(""")

    /** The text between the bracket at [open] and its matching close, or null when it never closes. */
    private fun block(text: String, open: Int): String? {
        val close = when (text[open]) { '(' -> ')'; '{' -> '}'; else -> return null }
        var depth = 0
        for (index in open until text.length) {
            if (text[index] == text[open]) depth++
            else if (text[index] == close && --depth == 0) return text.substring(open + 1, index)
        }
        return null
    }

    /** Each FormPage call in [text] (not its definition) with the content of its text = { … } lambda. */
    private fun pageContents(text: String): List<String> = Regex("""(?<!fun )\bFormPage\(""").findAll(text).mapNotNull { match ->
        val arguments = block(text, match.range.last) ?: return@mapNotNull null
        val start = arguments.indexOf("text = {")
        if (start < 0) "" else block(arguments, start + "text = ".length).orEmpty()
    }.toList()

    private fun unbounded(content: String) = scroll.findAll(content).map { it.value }
        .filterNot { it.contains("heightIn(max") || it.contains(".height(") }.toList()

    @Test fun noUnboundedScrollingInsideFullPages() {
        val problems = mutableListOf<String>()
        for (file in kotlinSources) for (content in pageContents(file.readText())) {
            unbounded(content).forEach { problems.add("${file.name}: ${it.take(80)}") }
        }
        assertTrue("Scrolling with no height limit inside a full page:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** The scanner must see real pages, or the check above would pass by finding nothing. */
    @Test fun scannerFindsTheMessageAndOptionPages() {
        val source = File("src/main/kotlin/org/fluidmessages/SecurityDialogs.kt").readText()
        val contents = pageContents(source)
        assertTrue("Expected many full pages in SecurityDialogs.kt, found ${contents.size}", contents.size >= 10)
        for (name in listOf("MessagePage", "OptionsPage")) {
            val at = source.indexOf("internal fun $name(")
            assertTrue("$name is missing", at >= 0)
            val body = block(source, source.indexOf('{', source.indexOf(')', at)))!!
            assertTrue("$name should be a FormPage", body.contains("FormPage("))
            // A long message or list scrolls with the page itself; a scroller of its own would have no height limit.
            assertEquals("$name nests its own scrolling", emptyList<String>(), scroll.findAll(body).map { it.value }.toList())
        }
    }

    @Test fun scannerCatchesAnUnboundedScrollerInALongPage() {
        val longMessage = "word ".repeat(2000)
        val sample = """
            FormPage(onDismissRequest = dismiss, title = { Text("Erase everything?") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { Text("$longMessage") }
            }, confirmButton = { DangerButton(dismiss, "Erase") }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
        """
        assertEquals(1, pageContents(sample).size)
        assertEquals(1, unbounded(pageContents(sample).single()).size)
        val fine = sample.replace("Column(Modifier.verticalScroll(rememberScrollState()))", "Column")
        assertEquals(0, unbounded(pageContents(fine).single()).size)
    }

    /** Confirmations, notices, and choice lists open as full pages (SecurityDialogs.page and options), not AlertDialogs. */
    @Test fun noSmallPopUpDialogs() {
        val sources = File("src/main").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList()
        val pattern = Regex("""\bAlertDialog\b|android\.app\.Dialog\b|new Dialog\(""")
        val problems = sources.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> pattern.containsMatchIn(line) && !line.trimStart().startsWith("*") }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim().take(80)}" }
        }
        assertTrue("Use a full page instead of a pop-up dialog:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
