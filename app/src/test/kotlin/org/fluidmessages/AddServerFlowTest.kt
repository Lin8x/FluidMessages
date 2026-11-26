package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Add server's order: Communities page, Choose a service, help page, servers, form (AddServerFlow). */
class AddServerFlowTest {
    @Test fun communitiesPageOnlyWithNoCommunityServer() {
        assertTrue(AddServerFlow.introDue(emptySet()))
        // Email (or anything else) isn't a community server.
        assertTrue(AddServerFlow.introDue(setOf("email")))
        for (service in listOf("irc", "matrix", "xmpp")) {
            assertFalse(service, AddServerFlow.introDue(setOf(service)))
            assertFalse(service, AddServerFlow.introDue(setOf("email", service.uppercase())))
        }
    }

    @Test fun helpPageOnlyWithNoAccountOnThatService() {
        for (protocol in listOf(MessageProtocol.IRC, MessageProtocol.MATRIX, MessageProtocol.XMPP)) {
            assertTrue(protocol.name, AddServerFlow.guideDue(protocol, emptySet()))
            assertFalse(protocol.name, AddServerFlow.guideDue(protocol, setOf(protocol.name.lowercase())))
        }
        // One IRC network already: Matrix still gets its help page, another IRC network goes straight to the list.
        val added = setOf("irc")
        assertTrue(AddServerFlow.guideDue(MessageProtocol.MATRIX, added))
        assertTrue(AddServerFlow.guideDue(MessageProtocol.XMPP, added))
        assertFalse(AddServerFlow.guideDue(MessageProtocol.IRC, added))
        // A second Matrix account skips the help page.
        assertFalse(AddServerFlow.guideDue(MessageProtocol.MATRIX, setOf("irc", "matrix")))
    }

    @Test fun backFromTheListGoesWhereYouCameFrom() {
        assertEquals("guide", AddServerFlow.listBack(true))
        assertEquals("services", AddServerFlow.listBack(false))
    }
}
