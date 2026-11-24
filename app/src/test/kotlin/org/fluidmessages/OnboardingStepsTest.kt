package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup guide stays a reasonable length and covers the newer features, with the web guide's page titles. */
class OnboardingStepsTest {
    private val keys = onboardingSteps.map { it.first }
    private val titles = onboardingSteps.map { it.second }

    @Test fun lengthAndOrder() {
        assertTrue("${onboardingSteps.size} steps", onboardingSteps.size in 15..17)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(titles.size, titles.toSet().size)
        assertEquals("welcome", keys.first())
        assertEquals("ready", keys.last())
        assertTrue(onboardingSteps.all { it.third.isNotBlank() && it.third.endsWith(".") })
    }

    @Test fun coversTheNewerFeatures() {
        for (key in listOf("communities", "organized", "disappear", "sounds", "computer", "tabs")) assertTrue(key, key in keys)
    }

    @Test fun sharedPagesUseTheWebTitles() {
        // The pages FluidMessages Web's setup guide also has, by the same names.
        for (title in listOf("Welcome", "Two tabs", "One inbox", "Who can read it?", "Keep chats private", "Disappearing messages",
                "More than text", "Quick moves", "Pick your colors", "Chat services", "Calls", "You’re ready")) {
            assertTrue(title, title in titles)
        }
    }
}
