package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NickServ's identify notices folded into one card. Fictional lines in the wording of Atheme (Libera.Chat), Anope, and
 * OFTC's services, for a made-up nick "kit" on example.test.
 */
class NickServTest {
    private val B = "\u0002"
    private val athemeAsk = "This nickname is registered. Please choose a different nickname, or identify via $B/msg NickServ IDENTIFY kit <password>$B"
    private val athemeTime = "You have 30 seconds to identify to your nickname before it is changed."
    private val athemeOk = "You are now identified for ${B}kit$B."
    private val athemeBad = "Invalid password for ${B}kit$B."
    private val athemeLast = "Last login from: ~kit@example.test on Jan 01 12:00:00 2026."
    private val athemeWarning = "kit!~kit@example.test failed to login to kit. There has been 1 failed login attempt since your last successful login."
    private val anopeAsk = "This nickname is registered and protected. If it is your nick, type /msg NickServ IDENTIFY password. Otherwise, please choose a different nick."
    private val anopeChange = "If you do not change within 1 minute, I will change your nick."
    private val anopeOk = "Password accepted - you are now recognized."
    private val anopeBad = "Password incorrect."
    private val oftcOk = "You are successfully identified as kit."
    private val oftcBad = "Identify failed as kit. You may have entered an incorrect password."

    private fun ns(id: String, text: String) = NickServRules.Heard(id, "NickServ", text, true)

    @Test fun linesAreSortedByWhatTheySay() {
        for (text in listOf(athemeAsk, anopeAsk)) assertEquals(text, NickServRules.Kind.ASK, NickServRules.kind(text))
        for (text in listOf(athemeOk, anopeOk, oftcOk, "You are already logged in as kit.")) assertEquals(text, NickServRules.Kind.IDENTIFIED, NickServRules.kind(text))
        for (text in listOf(athemeBad, anopeBad, oftcBad)) assertEquals(text, NickServRules.Kind.FAILED, NickServRules.kind(text))
        for (text in listOf(athemeTime, anopeChange, athemeLast)) assertEquals(text, NickServRules.Kind.NOTE, NickServRules.kind(text))
        // Other things NickServ says stay normal messages, including the failed-login warning and INFO replies.
        for (text in listOf(athemeWarning, "Information on kit (account kit):", "Registered : Jan 01 2026 (1y 2w ago)", "", "x".repeat(500))) {
            assertNull(text, NickServRules.kind(text))
        }
    }

    @Test fun nicksAndPlainWords() {
        assertEquals("kit", NickServRules.nick(athemeOk))
        assertEquals("kit", NickServRules.nick(athemeBad))
        assertEquals("kit", NickServRules.nick(oftcOk))
        assertEquals("kit", NickServRules.nick(oftcBad))
        assertEquals("", NickServRules.nick(anopeOk))
        assertEquals("You are now identified for kit.", NickServRules.plain("NickServ: $athemeOk"))
        assertEquals("Hi there", NickServRules.plain("\u000304,01Hi\u000f there"))
        assertTrue(NickServRules.isNickServ(" nickserv "))
        assertFalse(NickServRules.isNickServ("ChanServ"))
        assertFalse(NickServRules.isNickServ(null))
    }

    @Test fun connectNoticesFoldIntoOneCardEach() {
        val lines = listOf(
            ns("1", athemeAsk), ns("2", athemeTime), ns("3", athemeOk), ns("4", athemeLast),
            ns("5", athemeWarning),
            NickServRules.Heard("6", null, "/msg NickServ INFO kit", false),
            ns("7", anopeAsk), ns("8", anopeChange), ns("9", anopeBad),
        )
        val cards = NickServRules.fold(lines)
        assertEquals(2, cards.size)
        val first = cards[0]
        assertEquals(listOf(0, 3), listOf(first.first, first.last))
        assertEquals(NickServRules.Kind.IDENTIFIED, first.state)
        assertEquals("kit", first.nick)
        assertTrue(first.answered)
        // Nothing is lost: the card keeps every line it folds, in NickServ's words.
        assertEquals(listOf(NickServRules.plain(athemeAsk), athemeTime, "You are now identified for kit.", athemeLast), first.said)
        val second = cards[1]
        assertEquals(listOf(6, 8), listOf(second.first, second.last))
        assertEquals(NickServRules.Kind.FAILED, second.state)
        assertFalse(second.answered)
        assertEquals("Identified as kit", NickServRules.title(first, "other"))
        assertEquals("That password didn’t work", NickServRules.title(second, "kit"))
    }

    @Test fun onlyNickServsIncomingLinesFold() {
        // A person quoting NickServ, your own lines, and channel events never fold.
        val lines = listOf(
            NickServRules.Heard("1", "maya", athemeAsk, true),
            NickServRules.Heard("2", "NickServ", athemeAsk, false),
            NickServRules.Heard("3", "NickServ", athemeAsk, true, event = true),
            NickServRules.Heard("4", "NickServ", athemeTime, true),
        )
        assertTrue(NickServRules.fold(lines).isEmpty())
        // In NickServ's own chat, lines with no author are NickServ's.
        val own = NickServRules.fold(listOf(NickServRules.Heard("1", null, anopeAsk, true)), nickServChat = true)
        assertEquals(NickServRules.Kind.ASK, own.single().state)
        assertTrue(NickServRules.fold(listOf(NickServRules.Heard("1", null, anopeAsk, true))).isEmpty())
    }

    @Test fun theCardSaysWhatToDo() {
        val ask = NickServRules.Card(0, 1, NickServRules.Kind.ASK, "", listOf(athemeAsk), false)
        assertEquals("This nickname is registered", NickServRules.title(ask, "kit"))
        assertTrue(NickServRules.detail(ask, false).contains("never to a channel"))
        assertTrue(NickServRules.detail(ask, true).contains("saved password"))
        assertEquals("NickServ answered below.", NickServRules.detail(ask.copy(answered = true), false))
        assertEquals("Identified", NickServRules.title(ask.copy(state = NickServRules.Kind.IDENTIFIED), ""))
        assertEquals("Identified as kit", NickServRules.title(ask.copy(state = NickServRules.Kind.IDENTIFIED), "kit"))
    }

    @Test fun ircCallButtonsAreOffByDefaultForIrcOnly() {
        val run = Runnable {}
        val options = listOf(ReachOption(ReachOption.MESSAGE, "IRC", "#radio-club", run), ReachOption(ReachOption.CALL, "Meeting link", "Zoom", run),
            ReachOption(ReachOption.VIDEO, "Meeting link", "Zoom", run))
        val irc = listOf(MessageProtocol.IRC)
        assertEquals(listOf(ReachOption.MESSAGE), IrcCalls.filter(options, irc, false).map { it.kind })
        assertEquals(options, IrcCalls.filter(options, irc, true))
        // Other protocols, and a person reached on IRC and SMS, keep their buttons.
        assertEquals(options, IrcCalls.filter(options, listOf(MessageProtocol.MATRIX), false))
        assertEquals(options, IrcCalls.filter(options, listOf(MessageProtocol.IRC, MessageProtocol.SMS), false))
        assertEquals(options, IrcCalls.filter(options, emptyList(), false))
        assertFalse(IrcCalls.meetingRoute(MessageProtocol.IRC, false))
        assertTrue(IrcCalls.meetingRoute(MessageProtocol.IRC, true))
        assertTrue(IrcCalls.meetingRoute(MessageProtocol.XMPP, false))
    }
}
