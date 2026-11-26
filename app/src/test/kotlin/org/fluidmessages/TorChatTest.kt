package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tor per chat: which chats can use Tor and why not, the words, and that the onion shows only while routed. */
class TorChatTest {
    private val internet = listOf(MessageProtocol.IRC, MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL)

    @Test fun decisionTable() {
        data class Case(val protocol: MessageProtocol, val handler: TorChat.Handler, val local: Boolean, val held: String,
                        val allowed: Boolean, val why: String)
        val xmpp = "XMPP on FluidMessages Web can't go through Tor yet"
        val cases = listOf(
            // Texts and phone calls use the phone plan, wherever they're handled.
            Case(MessageProtocol.SMS, TorChat.Handler.PHONE, false, "", false, TorChat.TEXTS),
            Case(MessageProtocol.MMS, TorChat.Handler.WEB, false, "", false, TorChat.TEXTS),
            Case(MessageProtocol.RCS, TorChat.Handler.NONE, false, "", false, TorChat.TEXTS),
            Case(MessageProtocol.NEARBY, TorChat.Handler.PHONE, false, "", false, TorChat.NEARBY),
            // Internet chats on this phone can.
            Case(MessageProtocol.IRC, TorChat.Handler.PHONE, false, "", true, ""),
            Case(MessageProtocol.MATRIX, TorChat.Handler.PHONE, false, "", true, ""),
            Case(MessageProtocol.XMPP, TorChat.Handler.PHONE, false, "", true, ""),
            Case(MessageProtocol.EMAIL, TorChat.Handler.PHONE, false, "", true, ""),
            // On FluidMessages Web, unless it can't route that service there.
            Case(MessageProtocol.IRC, TorChat.Handler.WEB, false, "", true, ""),
            Case(MessageProtocol.XMPP, TorChat.Handler.WEB, false, xmpp, false, xmpp),
            // No account to connect with yet.
            Case(MessageProtocol.MATRIX, TorChat.Handler.NONE, false, "", false, TorChat.NO_SERVER),
        )
        for (case in cases) {
            val choice = TorChat.choice(case.protocol, case.handler, case.local, case.held, "Harbor Net")
            assertEquals(case.toString(), case.allowed, choice.allowed)
            assertEquals(case.toString(), case.why, choice.why)
        }
        // Your own network: Tor can't reach it, and it says so with the server's name.
        val home = TorChat.choice(MessageProtocol.IRC, TorChat.Handler.PHONE, true, "", "nas.local")
        assertFalse(home.allowed)
        assertTrue(home.why, home.why.startsWith("nas.local is on your own network"))
        // A disabled switch always says why.
        for (protocol in MessageProtocol.values()) for (handler in TorChat.Handler.values()) for (local in listOf(false, true)) {
            val choice = TorChat.choice(protocol, handler, local)
            assertEquals("$protocol $handler $local", !choice.allowed, choice.why.isNotEmpty())
        }
    }

    @Test fun theSwitchSaysItAppliesToTheWholeConnection() {
        assertEquals("Also applies to other chats on Libera.Chat", TorChat.scope("Libera.Chat", TorChat.Handler.PHONE))
        assertEquals("Also applies to other chats on Libera.Chat, on FluidMessages Web", TorChat.scope("Libera.Chat", TorChat.Handler.WEB))
    }

    @Test fun statusWords() {
        assertEquals("Through Tor", TorChat.words(TorChat.Route.TOR))
        assertEquals("Waiting for Tor", TorChat.words(TorChat.Route.WAIT))
        assertEquals("Not through Tor", TorChat.words(TorChat.Route.DIRECT))
        assertEquals(TorChat.Route.TOR, TorChat.route(TorRoute.Way.TOR))
        assertEquals(TorChat.Route.WAIT, TorChat.route(TorRoute.Way.WAIT))
        assertEquals(TorChat.Route.DIRECT, TorChat.route(TorRoute.Way.DIRECT))
        assertEquals(TorChat.Route.DIRECT, TorChat.route(TorRoute.Way.BLOCKED))
        assertEquals(TorChat.Route.TOR, TorChat.route("tor"))
        assertEquals(TorChat.Route.WAIT, TorChat.route("wait"))
        assertEquals(TorChat.Route.DIRECT, TorChat.route(""))
    }

    @Test fun theMarkShowsOnlyWhileRouted() {
        val allowed = TorChat.Choice(true, "")
        assertTrue(TorChat.marked(allowed, TorChat.Route.TOR))
        assertFalse(TorChat.marked(allowed, TorChat.Route.WAIT))
        assertFalse(TorChat.marked(allowed, TorChat.Route.DIRECT))
        assertFalse(TorChat.marked(TorChat.Choice(false, TorChat.TEXTS), TorChat.Route.TOR))
        // The sample: Harbor Net's channels show it, texts and other chats don't.
        assertTrue(TorChat.sample("#radio-club", MessageProtocol.IRC).marked)
        assertTrue(TorChat.sample("#trail-maps", MessageProtocol.IRC).marked)
        assertFalse(TorChat.sample("Book Swap", MessageProtocol.MATRIX).marked)
        assertFalse(TorChat.sample("Maya Chen", MessageProtocol.SMS).marked)
        assertFalse(TorChat.sample("#radio-club", MessageProtocol.IRC, false).marked)
    }

    @Test fun eachConnectionsServers() {
        assertEquals(listOf("irc.libera.chat"), TorChat.hosts(MessageProtocol.IRC, mapOf("server" to "IRC.Libera.Chat")))
        assertEquals(listOf("matrix.example.org"), TorChat.hosts(MessageProtocol.MATRIX, mapOf("homeserver" to "https://matrix.example.org:8448/")))
        assertEquals(listOf("example.org"), TorChat.hosts(MessageProtocol.XMPP, mapOf("jid" to "kit@example.org/phone")))
        assertEquals(listOf("xmpp.example.org"), TorChat.hosts(MessageProtocol.XMPP, mapOf("jid" to "kit@example.org", "host" to "xmpp.example.org")))
        assertEquals(listOf("imap.example.org", "smtp.example.org"),
            TorChat.hosts(MessageProtocol.EMAIL, mapOf("imap_host" to "imap.example.org", "smtp_host" to "smtp.example.org")))
        assertEquals(listOf("mail.example.org"), TorChat.hosts(MessageProtocol.EMAIL, mapOf("imap_host" to "mail.example.org", "smtp_host" to "mail.example.org")))
        for (protocol in internet) assertTrue(TorChat.hosts(protocol, emptyMap()).isEmpty())
        assertTrue(TorChat.hosts(MessageProtocol.SMS, mapOf("server" to "x")).isEmpty())
    }
}
