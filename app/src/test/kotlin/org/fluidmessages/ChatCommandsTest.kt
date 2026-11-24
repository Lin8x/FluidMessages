package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The slash command parser shared by the phone's chats (ChatCommands) and the IRC rules DirectIrc uses (IrcRules). */
class ChatCommandsTest {
    private fun refused(family: String, text: String): String {
        try {
            ChatCommands.parse(family, text)
        } catch (error: ChatCommandException) {
            return error.message.orEmpty()
        }
        fail("$text on $family wasn't refused")
        return ""
    }

    @Test fun parsesCommandsAndAliases() {
        assertEquals(ChatCommand("me", "waves", listOf("waves")), ChatCommands.parse("irc", "/me waves"))
        assertEquals("join", ChatCommands.parse("irc", "/J #radio-club")?.name)
        assertEquals("quote", ChatCommands.parse("irc", "/raw PING x")?.name)
        assertEquals("part", ChatCommands.parse("matrix", "/leave")?.name)
        assertEquals(ChatCommand("away", "", emptyList()), ChatCommands.parse("irc", "/back"))
        assertEquals(listOf("kestrel", "too", "loud"), ChatCommands.parse("irc", "/kick  kestrel too loud")?.argv)
    }

    @Test fun ordinaryTextStaysText() {
        assertNull(ChatCommands.parse("irc", "hello"))
        assertNull(ChatCommands.parse("irc", "//shrug"))
        assertNull(ChatCommands.parse("irc", "/ spaced"))
        // In texts and email, words that aren't commands are left alone.
        assertNull(ChatCommands.parse("sms", "/shrug"))
        assertNull(ChatCommands.parse("email", "/s"))
    }

    @Test fun friendlyErrors() {
        assertTrue(refused("irc", "/frobnicate now").startsWith("Unknown command /frobnicate"))
        assertTrue(refused("matrix", "/whois maya").contains("isn’t available on Matrix"))
        assertEquals("Usage: /join #channel", refused("xmpp", "/join"))
        assertEquals("Usage: /msg nick message", refused("irc", "/msg kestrel"))
        // Commands never go to SMS or email as text.
        assertTrue(refused("sms", "/me waves").startsWith("Commands work in IRC, Matrix, and XMPP chats"))
        assertTrue(refused("email", "/join #radio").contains("Start with //"))
    }

    @Test fun readingWhatWasTyped() {
        assertEquals(TypedLine.Text("/join is a word", literal = true), ChatCommands.read(MessageProtocol.IRC, "#radio", "//join is a word"))
        assertEquals(TypedLine.Text("/me waves"), ChatCommands.read(MessageProtocol.IRC, "#radio", "/me waves"))
        val command = ChatCommands.read(MessageProtocol.MATRIX, "!room:example.org", "/topic Field day") as TypedLine.Command
        assertEquals("matrix", command.family)
        assertEquals("Field day", command.command.args)
        // A bridged IRC room in Matrix uses Matrix's commands.
        assertTrue(ChatCommands.read(MessageProtocol.IRC, "!bridge:example.org", "/whois kestrel") is TypedLine.Refused)
        assertTrue(ChatCommands.read(MessageProtocol.SMS, null, "/me waves") is TypedLine.Refused)
        assertEquals(TypedLine.Text("/shrug"), ChatCommands.read(MessageProtocol.SMS, null, "/shrug"))
    }

    @Test fun hintsMatchWhatIsTyped() {
        assertEquals(listOf("me", "msg", "mode"), ChatCommands.hints("irc", "/m").map { it.name })
        assertEquals(listOf("me", "msg"), ChatCommands.hints("matrix", "/m").map { it.name })
        assertTrue(ChatCommands.hints("irc", "/me ").isEmpty())
        assertTrue(ChatCommands.hints("sms", "/").isEmpty())
        assertEquals(8, ChatCommands.hints("irc", "/").size) // At most eight at a time.
        assertEquals(listOf("part"), ChatCommands.hints("xmpp", "/pa").map { it.name })
        assertTrue(ChatCommands.helpText("irc").contains("/whois nick"))
        assertFalse(ChatCommands.helpText("xmpp").contains("/whois"))
    }

    @Test fun actionsAndEventKinds() {
        assertEquals("* maya waves", ChatCommands.actionText("/me waves", "maya", false))
        assertEquals("* you wave back", ChatCommands.actionText("/me wave back", null, true))
        assertNull(ChatCommands.actionText("waves", "maya", false))
        assertTrue(ChatCommands.hideable("join") && ChatCommands.hideable("quit"))
        assertFalse(ChatCommands.hideable("kick") || ChatCommands.hideable("topic") || ChatCommands.hideable(null))
    }

    @Test fun ircRules() {
        assertTrue(IrcRules.isService("NickServ") && IrcRules.isService("chanserv") && IrcRules.isService("RadioBot"))
        assertFalse(IrcRules.isService("kestrel"))
        assertEquals("kit_", IrcRules.fallbackNick("kit", 1))
        assertEquals("kit__", IrcRules.fallbackNick("kit", 2))
        assertEquals("kit1", IrcRules.fallbackNick("kit", 3))
        assertEquals(30, IrcRules.fallbackNick("a".repeat(30), 1).length)
        assertTrue(IrcRules.mentions("kit: bring the analyzer?", "kit"))
        assertTrue(IrcRules.mentions("thanks KIT!", "kit_", "kit"))
        assertFalse(IrcRules.mentions("kitten pictures", "kit"))
        assertFalse(IrcRules.mentions("kit_ is someone else", "kit"))
        assertEquals(400, IrcRules.limit("PRIVMSG", "#radio", "kit"))
        val long = "#" + "a".repeat(45)
        assertTrue(IrcRules.limit("PRIVMSG", long, "fluidtest") + "PRIVMSG $long :".length + "fluidtest".length + 76 <= 510)
    }

    @Test fun backoffSchedule() {
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 600_000L),
            (0..6).map { IrcRules.backoffMillis(it, 0.5) })
        assertEquals(12_000L, IrcRules.backoffMillis(0, 0.0))
        assertEquals(18_000L, IrcRules.backoffMillis(0, 1.0))
        assertEquals(720_000L, IrcRules.backoffMillis(40, 1.0)) // Capped at 10 minutes, plus jitter.
    }

    @Test fun hourlyReconnectCap() {
        val budget = ReconnectBudget()
        val start = 1_000_000L
        assertEquals(List(5) { true }, List(5) { budget.tryUse(start + it * 60_000L) })
        assertFalse(budget.tryUse(start + 10 * 60_000L)) // The sixth in an hour waits for Reconnect.
        assertTrue(budget.tryUse(start + 3_600_000L))    // An hour after the first, one is free again.
        budget.reset()                                   // Reconnect by hand starts over.
        assertTrue(budget.tryUse(start + 3_600_001L))
    }

    @Test fun rejoinPolicy() {
        val off = setOf("#quiet")
        assertEquals(listOf("#radio", "#garden"), IrcRules.keepJoined(listOf("#radio", "#quiet", "#garden")) { it !in off })
        assertTrue(IrcRules.keepJoined(listOf("#quiet")) { it !in off }.isEmpty())
    }

    @Test fun floodPacing() {
        var now = 0L
        val flood = IrcFlood(burst = 3, everyMillis = 2000) { now }
        assertEquals(listOf(0L, 0L, 0L), List(3) { flood.wait() })
        assertEquals(2000L, flood.wait())
        now = 2000
        assertEquals(2000L, flood.wait())
        now = 20_000
        assertEquals(0L, flood.wait())
    }
}
