package org.fluidmessages

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nearby's message color: turquoise in every theme, light and dark and increased contrast, with white text that
 * reads at 4.5:1 once the bubble is shaded (accessibleBubbleFill), like the other services' colors.
 */
class NearbyColorTest {
    private val others = listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)

    /** What MessageProtocol.tint shows with no color of your own: the theme's, or Fluid's mint swatch. */
    private fun nearby(theme: MessageStyle.Theme, dark: Boolean, contrast: Boolean): Int =
        theme.protocol(MessageProtocol.NEARBY, dark) ?: MessageStyle.Swatch.MINT.color(dark, contrast)

    private fun contrastWithWhite(color: Color) = 1.05 / (color.luminance() + .05)

    @Test fun everyThemeHasANearbyColor() {
        for (theme in MessageStyle.Theme.values()) for (dark in listOf(false, true)) {
            if (theme == MessageStyle.Theme.FLUID) assertNull(theme.protocol(MessageProtocol.NEARBY, dark))
            else assertNotNull("${theme.label} has no Nearby color", theme.protocol(MessageProtocol.NEARBY, dark))
        }
        // Fluid's is the turquoise (Apple mint) swatch.
        assertEquals(0xff00c8b3.toInt(), nearby(MessageStyle.Theme.FLUID, false, false))
        assertEquals(0xff008575.toInt(), nearby(MessageStyle.Theme.FLUID, false, true))
    }

    @Test fun nearbyStandsApartFromTheThemesOtherServices() {
        for (theme in MessageStyle.Theme.values()) for (dark in listOf(false, true)) {
            val own = theme.protocol(MessageProtocol.NEARBY, dark) ?: continue
            for (protocol in others) assertNotEquals("${theme.label}: Nearby matches ${protocol.label}", theme.protocol(protocol, dark), own)
        }
    }

    @Test fun nearbyBubblesKeepWhiteTextReadable() {
        for (theme in MessageStyle.Theme.values()) for (dark in listOf(false, true)) for (contrast in listOf(false, true)) {
            val base = Color(nearby(theme, dark, contrast))
            val fill = accessibleBubbleFill(base)
            assertEquals(Color.White, bubbleTextColor(base))
            assertTrue("${theme.label} dark=$dark contrast=$contrast: ${contrastWithWhite(fill)}", contrastWithWhite(fill) >= 4.49)
        }
    }

    @Test fun darkModeLiftsTowardWhite() {
        assertEquals(0xff38d4c3.toInt(), MessageStyle.Theme.lifted(0x00c8b3))
        assertEquals(0xffffffff.toInt(), MessageStyle.Theme.lifted(0xffffff))
    }

    @Test fun backupsAndSettingsSyncKeepNearby() {
        val keys = Backup.THEME.toSet()
        assertTrue("protocolNEARBYColor" in keys && "protocolNEARBYColorSwatch" in keys)
    }
}
