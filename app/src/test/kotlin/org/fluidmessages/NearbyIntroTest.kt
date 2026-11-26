package org.fluidmessages

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyIntroTest {
    // What is Nearby?: shown the first time Nearby is turned on, before the name step

    @Test fun showsTheFirstTimeOnly() {
        assertTrue("never seen, not set up", NearbyIntro.due(seen = false, setUp = false))
        assertFalse("seen once already", NearbyIntro.due(seen = true, setUp = false))
        // Someone who set Nearby up before this page existed isn't shown it when they come back.
        assertFalse("already set up", NearbyIntro.due(seen = false, setUp = true))
        assertFalse(NearbyIntro.due(seen = true, setUp = true))
    }

    @Test fun buttonsDependOnWhetherNearbyIsOn() {
        assertEquals("Turn on Nearby", NearbyIntro.primary(on = false))
        assertTrue(NearbyIntro.showNotNow(on = false))
        assertEquals("Open Nearby", NearbyIntro.primary(on = true))
        assertFalse(NearbyIntro.showNotNow(on = true))
    }

    @Test fun saysWhatItIsWhoItsForAndWhatItCantDo() {
        val text = NearbyIntro.text().joinToString("\n")
        for (words in listOf("no internet and no cell signal", "same Wi-Fi", "Bluetooth", "blackouts", "events", "camping",
            "school or office Wi-Fi", "Tap someone to chat", "Only you and the person", "pass a message along", "Neighborhood",
            "far away", "can’t make calls", "Only Stay discoverable keeps it running in the background")) {
            assertTrue(words, text.contains(words))
        }
        assertEquals(listOf("How it works", "What it can’t do", "Battery"), NearbyIntro.parts.map { it.title })
        assertFalse(text.contains("Discord"))
    }

    // Staying in touch without internet: rows and the docs copy

    @Test fun theGuideRowsHaveSteadyUniqueKeys() {
        for (openNearby in listOf(true, false)) {
            val rows = OfflineGuide.rows(openNearby)
            assertEquals(rows.size, rows.map { it.key }.toSet().size)
            assertEquals(openNearby, rows.any { it.kind == OfflineGuide.Kind.OPEN_NEARBY })
            assertEquals(OfflineGuide.sections.sumOf { it.tips.size }, rows.count { it.kind == OfflineGuide.Kind.TIP })
            assertEquals(OfflineGuide.sections.size, rows.count { it.kind == OfflineGuide.Kind.SECTION })
        }
        // The same rows keep the same keys whether or not the Open Nearby tip is there.
        assertEquals(OfflineGuide.rows(false).map { it.key }, OfflineGuide.rows(true).map { it.key }.filter { it != "open-nearby" })
        assertEquals(0L, OfflineGuide.revealDelay(0))
        assertEquals(OfflineGuide.revealDelay(8), OfflineGuide.revealDelay(30))
    }

    @Test fun theGuideSectionsMatchTheDocs() {
        val file = listOf(File("../docs/offline-guide.md"), File("docs/offline-guide.md")).first { it.isFile }
        val doc = file.readText().replace("'", "’")
        val lines = doc.lines()
        assertEquals("# " + OfflineGuide.TITLE, lines.first())
        assertTrue(doc.contains(OfflineGuide.SUBTITLE))
        // The ## headings, in order, are the app's sections; each section's bullets are its tips, in order.
        val headings = lines.filter { it.startsWith("## ") }.map { it.removePrefix("## ") }
        assertEquals(OfflineGuide.sections.map { it.title }, headings)
        for (section in OfflineGuide.sections) {
            val start = lines.indexOf("## " + section.title)
            val body = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
            val bullets = body.filter { it.startsWith("- ") }.map { it.replace("**", "") }
            assertEquals(section.title, section.tips.size, bullets.size)
            section.tips.zip(bullets).forEach { (tip, bullet) ->
                assertTrue(bullet, bullet.startsWith("- " + tip.title))
                val line = tip.line.trimEnd('.')
                assertTrue(bullet, bullet.contains(line) || bullet.contains(line.replaceFirstChar { it.lowercase() }))
            }
            if (section.note.isNotEmpty()) assertTrue(section.note, body.any { it.trim() == section.note })
        }
    }
}
