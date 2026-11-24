package org.fluidmessages

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The newer pages draw with the app's palette: no hard-coded black, white, or gray text and no Material defaults.
 * A source check, so it runs without Compose or a device.
 */
class ThemeTokensTest {
    private val source = File("src/main/kotlin/org/fluidmessages").takeIf { it.isDirectory }
        ?: File("app/src/main/kotlin/org/fluidmessages")

    private val pages = listOf("ServersScreen.kt", "Communities.kt", "SettingsPages.kt", "SoundSettings.kt", "Mutes.kt",
        "ContactEditor.kt", "ContactFields.kt", "RecipientPicker.kt", "SyncConflictScreen.kt", "AccountsScreen.kt", "InboxFilters.kt")

    @Test fun pagesUseThePalette() {
        val hardCoded = Regex("""color = Color\.(Black|White|Gray|DarkGray|LightGray)\b|Color\(0x[0-9A-Fa-f]{8}\)|Surface\(color = Color\.Transparent""")
        val found = pages.flatMap { name ->
            File(source, name).readLines().mapIndexedNotNull { index, line ->
                if (hardCoded.containsMatchIn(line) && !line.trim().startsWith("//")) "$name:${index + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Hard-coded colors on themed pages:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun textWithoutItsOwnColorUsesTheInk() {
        // Pages are ComposeViews without a Surface at their root; without this, plain Text falls back to black.
        val theme = File(source, "AppTheme.kt").readText()
        assertTrue(theme.contains("LocalContentColor provides ink"))
        // Errors and Remove use the theme's red, not Material's.
        assertTrue(theme.contains("error = error"))
    }
}
