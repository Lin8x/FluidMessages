package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup guide's service and call cards: one per service, with the lock each one really shows. */
class OnboardingServicesTest {
    @Test fun everyChatServiceHasACard() {
        // MMS is part of the Texts card, the way SMS and MMS share one conversation.
        val expected = MessageProtocol.entries.filter { it != MessageProtocol.MMS }.toSet()
        assertEquals(expected, chatServiceTexts.map { it.first }.toSet())
        assertTrue(chatServiceTexts.all { it.second.name.isNotBlank() && it.second.about.endsWith(".") })
        assertTrue(chatServiceTexts.first { it.first == MessageProtocol.RCS }.second.name.contains("preview"))
    }

    @Test fun chatLocksFollowTheTransports() {
        assertEquals(SecurityTone.RED, chatEncryption(MessageProtocol.SMS).tone)
        assertEquals(SecurityTone.RED, chatEncryption(MessageProtocol.RCS).tone)
        for (protocol in listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC))
            assertEquals(protocol.name, SecurityTone.YELLOW, chatEncryption(protocol).tone)
    }

    @Test fun everyWayToCallHasACard() {
        assertEquals(Calling.Method.values().toList(), callServiceTexts.map { it.first })
        assertEquals(SecurityTone.RED, callEncryption(Calling.Method.PHONE).tone)
        assertEquals(SecurityTone.GREEN, callEncryption(Calling.Method.MATRIX).tone)
        assertEquals(SecurityTone.GREEN, callEncryption(Calling.Method.XMPP).tone)
        assertEquals(SecurityTone.YELLOW, callEncryption(Calling.Method.SIP).tone)
        assertEquals(SecurityTone.YELLOW, callEncryption(Calling.Method.MEETING).tone)
    }
}
