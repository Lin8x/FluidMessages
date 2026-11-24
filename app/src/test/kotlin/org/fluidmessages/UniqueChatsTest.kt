package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One chat per address, service, and account, compared the way each service does (as protocol_gateway.target_key). */
class UniqueChatsTest {
    @Test fun sameAddressServiceAndAccountIsTheSameChat() {
        assertTrue(AccountKeys.sameChat("email", "Maya@Example.TEST", "email:me@example.test", "email", "maya@example.test", "email:me@example.test"))
        assertTrue(AccountKeys.sameChat("xmpp", "Maya@Chat.Example", "", "xmpp", "maya@chat.example", ""))
        assertTrue(AccountKeys.sameChat("matrix", "@Maya:Example.org", "m", "matrix", "@maya:example.org", "m"))
        // IRC folds case by RFC 1459: [ ] \ ~ are the capitals of { } | ^.
        assertTrue(AccountKeys.sameChat("irc", "#Radio[Club]", "irc:riley@irc.example.test", "irc", "#radio{club}", "irc:riley@irc.example.test"))
        assertEquals("kestrel|^", AccountKeys.targetKey("irc", " Kestrel\\~ "))
    }

    @Test fun anotherAccountOrServiceIsAnotherChat() {
        assertFalse(AccountKeys.sameChat("email", "maya@example.test", "email:me@example.test", "email", "maya@example.test", "email:me@work.example.test"))
        assertFalse(AccountKeys.sameChat("irc", "#radio", "irc:riley@irc.one.test", "irc", "#radio", "irc:riley@irc.two.test"))
        assertFalse(AccountKeys.sameChat("email", "maya@example.test", "", "xmpp", "maya@example.test", ""))
        // Its chat key carries the other account's tag, so the two never collide.
        assertNotEquals(AccountKeys.threadAddress("maya", "email:me@work.example.test"), "maya")
    }

    @Test fun matrixRoomIdsKeepTheirCase() {
        assertEquals("!AbC:example.org", AccountKeys.targetKey("matrix", "!AbC:Example.org"))
        assertFalse(AccountKeys.sameChat("matrix", "!AbC:example.org", "m", "matrix", "!abc:example.org", "m"))
        // A Matrix room bridged into IRC or XMPP is compared as a Matrix room.
        assertEquals("!AbC:example.org", AccountKeys.targetKey("irc", "!AbC:EXAMPLE.org"))
    }
}
