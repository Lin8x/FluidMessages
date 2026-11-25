package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Connecting to IRC networks: refusals in plain words, nicknames, and the inbox's connection mark. Fictional names. */
class IrcConnectionTest {
    @Test fun saslOnlyConnectionsSayWhatToDo() {
        val problem = IrcRules.explain("ERROR", "Closing Link: 192.0.2.7 (SASL authentication is required from this network)",
            "Harbor Net", "kit", false)
        assertNotNull(problem)
        assertTrue(problem!!.fatal)
        assertTrue(problem.text, problem.text.contains("registered nickname"))
        assertTrue(problem.text, problem.text.contains("Password in the IRC account settings"))
        // The network's own words are quoted.
        assertTrue(problem.text, problem.text.contains("SASL authentication is required"))
        // With a SASL password set, the advice is to check it instead.
        assertTrue(IrcRules.explain("ERROR", "You need to identify via SASL to use this server", "Harbor Net", "kit", true)!!
            .text.contains("Check the account name and password"))
        assertTrue(IrcRules.needsSasl("*** Notice -- You need to identify via SASL to use this server"))
        assertFalse(IrcRules.needsSasl("*** Looking up your hostname..."))
        assertFalse(IrcRules.needsSasl("The server supports SASL"))
    }

    @Test fun refusalsAreFatalAndDropsAreNot() {
        assertTrue(IrcRules.explain("465", "You are banned from this server", "Harbor Net", "kit", false)!!.fatal)
        assertTrue(IrcRules.explain("ERROR", "Closing Link: host (K-Lined)", "Harbor Net", "kit", false)!!.fatal)
        val nick = IrcRules.explain("432", "Erroneous Nickname", "Harbor Net", "9kit", false)!!
        assertTrue(nick.fatal)
        assertTrue(nick.text, nick.text.contains("“9kit”"))
        assertTrue(IrcRules.explain("433", "", "Harbor Net", "kit", false)!!.fatal)
        val dropped = IrcRules.explain("ERROR", "Closing Link: host (Ping timeout: 240 seconds)", "Harbor Net", "kit", false)!!
        assertFalse(dropped.fatal)
        assertEquals("The connection to Harbor Net dropped.", dropped.text)
        // Your own Quit isn't a problem, and a wrong SASL password only matters when one is set.
        assertNull(IrcRules.explain("ERROR", "Closing Link: host (Client Quit)", "Harbor Net", "kit", false))
        assertNull(IrcRules.explain("904", "SASL authentication failed", "Harbor Net", "kit", false))
        val sasl = IrcRules.explain("904", "SASL authentication failed", "Harbor Net", "kit", true)!!
        assertFalse(sasl.fatal)
        assertTrue(sasl.text, sasl.text.contains("account name"))
        assertNull(IrcRules.explain("001", "Welcome", "Harbor Net", "kit", false))
    }

    @Test fun nicknamesAreCheckedBeforeConnecting() {
        assertNull(IrcRules.nickProblem("kit"))
        assertNull(IrcRules.nickProblem("kit_away"))
        assertNull(IrcRules.nickProblem("[kit]"))
        assertNotNull(IrcRules.nickProblem(""))
        assertTrue(IrcRules.nickProblem("9kit")!!.contains("start"))
        assertTrue(IrcRules.nickProblem("-kit")!!.contains("start"))
        assertTrue(IrcRules.nickProblem("kit.rivera")!!.contains("No spaces or dots"))
        assertTrue(IrcRules.nickProblem("k".repeat(40))!!.contains("too long"))
    }

    @Test fun connectionMarkStates() {
        assertEquals("connected", IrcRules.connectionState("connected"))
        for (state in listOf("connecting", "reconnecting")) assertEquals("connecting", IrcRules.connectionState(state))
        for (state in listOf("disconnected", "paused", "offline")) assertEquals("disconnected", IrcRules.connectionState(state))
        // Nothing known yet (the phone's IRC never started, the web hasn't said): still a mark, not connected.
        for (state in listOf("", "something")) assertEquals("disconnected", IrcRules.connectionState(state))
        // The service turned off, or the connection turned off by you (Disconnect, Reconnect automatically off).
        assertEquals("off", IrcRules.connectionState("off"))
        assertEquals("off", IrcRules.connectionState("disconnected", true))
        assertEquals("connected", IrcRules.connectionState("connected", true))
        assertEquals("Connected", IrcRules.connectionLabel("connected"))
        assertEquals("Connecting", IrcRules.connectionLabel("connecting"))
        assertEquals("Not connected", IrcRules.connectionLabel("disconnected"))
        assertEquals("Turned off", IrcRules.connectionLabel("off"))
        assertEquals("", IrcRules.connectionLabel(""))
    }

    @Test fun connectionSectionSaysWhatIsHappening() {
        assertEquals("Connected to Harbor Net as kit", IrcRules.connectionSummary("connected", "Harbor Net", "kit", "", false))
        assertEquals("Connected to Lakeside Commons", IrcRules.connectionSummary("connected", "Lakeside Commons", "", "", false))
        assertEquals("Connecting to Harbor Net…", IrcRules.connectionSummary("connecting", "Harbor Net", "kit", "", false))
        assertEquals("Not connected to Harbor Net · The connection to Harbor Net dropped.",
            IrcRules.connectionSummary("disconnected", "Harbor Net", "kit", "The connection to Harbor Net dropped.", false))
        assertEquals("Turned off · Tap Connect to connect to Harbor Net", IrcRules.connectionSummary("off", "Harbor Net", "kit", "", false))
        assertTrue(IrcRules.connectionSummary("off", "Harbor Net", "kit", "", true).contains("Settings → Chat services"))
        // The sample's not-connected channel says so in words too.
        val room = ConnectionNow.sample("Design Studio")
        assertEquals("disconnected", room.state)
        assertTrue(room.summary, room.summary.startsWith("Not connected to Lakeside Commons"))
        assertEquals("connected", ConnectionNow.sample("#radio-club").state)
        // The Connections page agrees with the chats' marks.
        assertEquals("disconnected", SampleConnections.now("sample:matrix", true).state)
        assertEquals("connected", SampleConnections.now("sample:irc", true).state)
    }

    @Test fun sampleInboxShowsEveryConnectionMark() {
        assertEquals("connected", SampleInbox.connection("#trail-maps"))
        assertEquals("connected", SampleInbox.connection("#radio-club"))
        assertEquals("connecting", SampleInbox.connection(SampleInbox.SECOND_ACCOUNT_CHAT))
        assertEquals("disconnected", SampleInbox.connection("Design Studio"))
        // Texts and email have no connection mark.
        assertEquals("", SampleInbox.connection("Maya Chen"))
        assertEquals("", SampleInbox.connection("Leah Morgan"))
        // The disconnected rows are a sample server's rooms, so they show in the sample inbox and Communities.
        assertEquals("sample:matrix", SampleInbox.serverOf("Design Studio"))
    }

    @Test fun ircGuideExplainsRegisteringANickname() {
        val signIn = ServiceGuides.of(MessageProtocol.IRC).signIn
        assertTrue(signIn.size > 2)
        val words = signIn.joinToString(" ")
        assertTrue(words.contains("Libera.Chat"))
        assertTrue(words.contains("/msg NickServ REGISTER"))
        assertTrue(words.contains("Account name"))
        for (protocol in listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL)) {
            assertTrue(ServiceGuides.of(protocol).signIn.isEmpty())
        }
    }
}
