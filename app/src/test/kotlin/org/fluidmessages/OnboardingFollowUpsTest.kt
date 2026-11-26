package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup guide without its Communities step: a saved step still lines up, the Communities page shows once after the
 * first community account, the battery words, and Ways to reach in conversation details.
 */
class OnboardingFollowUpsTest {
    private val keys = onboardingSteps.map { it.first }

    @Test fun savedStepLinesUpByKey() {
        for ((index, key) in keys.withIndex()) {
            assertEquals(key, OnboardingScreen.stepKey(index))
            assertEquals(index, onboardingStepIndex(key, 0))
            // An index saved by a version with a different list of steps doesn't move the page.
            assertEquals(index, onboardingStepIndex(key, index + 1))
        }
        // The removed step reopens on the page that came after it.
        assertEquals(keys.indexOf("organized"), onboardingStepIndex("communities", 6))
        // No key, or an unknown one: the saved index, kept within the steps.
        assertEquals(3, onboardingStepIndex(null, 3))
        assertEquals(keys.lastIndex, onboardingStepIndex("gone", 99))
        assertEquals(0, onboardingStepIndex("", -2))
        assertEquals("", OnboardingScreen.stepKey(keys.size))
    }

    @Test fun communitiesPageShowsOnceForCommunityServices() {
        for (protocol in listOf(MessageProtocol.IRC, MessageProtocol.MATRIX, MessageProtocol.XMPP)) {
            assertTrue(protocol.name, CommunitiesIntro.due(false, protocol, true))
            assertFalse(protocol.name, CommunitiesIntro.due(true, protocol, true))
            assertFalse(protocol.name, CommunitiesIntro.due(false, protocol, false))
        }
        for (protocol in listOf(MessageProtocol.EMAIL, MessageProtocol.SMS, MessageProtocol.MMS, MessageProtocol.RCS)) {
            assertFalse(protocol.name, CommunitiesIntro.due(false, protocol, true))
        }
        assertEquals(4, CommunitiesIntro.tips.size)
        assertTrue(CommunitiesIntro.SUBTITLE.endsWith("."))
    }

    @Test fun batteryWordsSayTurningOnIsFree() {
        for (paired in listOf(true, false)) {
            val lines = ServiceBattery.lines(paired)
            assertEquals(3, lines.size)
            assertTrue(lines.first().contains("uses no battery by itself"))
            assertTrue(lines.all { it.endsWith(".") })
            assertTrue(lines.none { it.contains("drain", ignoreCase = true) })
        }
        assertTrue(ServiceBattery.web(true).contains("is set up"))
        assertTrue(ServiceBattery.web(false).contains("computer that stays on"))
    }

    @Test fun welcomePageNamesNoServices() {
        val words = welcomeMessages.map { it.first } + WelcomeWords.tips.flatMap { listOf(it.title, it.line) }
        for (name in listOf("RCS", "SMS", "Matrix", "XMPP", "IRC", "Email", "preview", "Automated")) {
            assertTrue(name, words.none { it.contains(name, ignoreCase = true) })
        }
        assertEquals(3, welcomeMessages.size)
        assertTrue(welcomeMessages.any { it.second } && welcomeMessages.any { !it.second })
        assertEquals(listOf("Text like you always do", "Reach friends on other chat apps too", "Practice with pretend chats first"),
            WelcomeWords.tips.map { it.title })
        assertTrue(WelcomeWords.SERVICES.startsWith("Chat services are"))
    }

    @Test fun waysToReachButton() {
        assertEquals("+ Add another way to reach Maya Chen", ReachWays.button("Maya Chen"))
        assertTrue(ReachWays.detail(true).contains("past chats"))
        assertTrue(ReachWays.detail(false).contains("Accounts"))
    }
}
