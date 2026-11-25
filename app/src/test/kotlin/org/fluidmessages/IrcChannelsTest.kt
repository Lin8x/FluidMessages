package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Joining by name and browsing the public channel list on the phone's own IRC connection. Fictional rows only. */
class IrcChannelsTest {
    private fun row(name: String, people: String, topic: String = "") = listOf("sam", name, people, topic)

    @Test fun channelNamesWorkWithOrWithoutTheHash() {
        assertEquals("#linux", IrcChannels.channelName("linux"))
        assertEquals("#linux", IrcChannels.channelName("  #linux "))
        assertEquals("#linux", IrcChannels.channelName("#linux, #python"))
        assertEquals("##overflow", IrcChannels.channelName("##overflow"))
        assertEquals("&local", IrcChannels.channelName("&local"))
        for (bad in listOf("", "   ", "#", "#" + "a".repeat(50), "bell\u0007")) assertNull(bad, IrcChannels.channelName(bad))
        assertTrue(IrcChannels.looksLikeChannel(" #python "))
        assertFalse(IrcChannels.looksLikeChannel("python"))
        assertFalse(IrcChannels.looksLikeChannel("#python help"))
        assertFalse(IrcChannels.looksLikeChannel("#"))
    }

    @Test fun listRowsAreParsedAndCleaned() {
        assertEquals(IrcChannelRow("#garden", 42, "Tomatoes and beans"), IrcChannels.row(row("#garden", "42", "Tomatoes and beans")))
        // Mode prefixes, colors, and control characters never reach the page.
        assertEquals("Welcome to the pond", IrcChannels.row(row("#pond", "7", "[+nt] \u000304,01Welcome\u000f to the \u0002pond\u0002"))!!.topic)
        assertEquals(IrcChannels.TOPIC_LENGTH, IrcChannels.row(row("#long", "1", "x".repeat(900)))!!.topic.length)
        assertEquals(0, IrcChannels.row(row("#odd", "many"))!!.people)
        assertEquals(IrcChannelRow("#bare", 3, ""), IrcChannels.row(listOf("sam", "#bare", "3")))
        // Hidden channels show as "*"; short replies are skipped.
        assertNull(IrcChannels.row(row("*", "5", "")))
        assertNull(IrcChannels.row(listOf("sam", "#short")))
        assertEquals("42 people · Tomatoes and beans", IrcChannels.detail(IrcChannelRow("#garden", 42, "Tomatoes and beans")))
        assertEquals("1 person", IrcChannels.detail(IrcChannelRow("#solo", 1, "")))
    }

    @Test fun theListKeepsOnlyTheBusiestMatchesUpToTheCap() {
        val list = IrcChannelList("", 3)
        for (index in 1..5000) list.add(row("#room$index", index.toString(), "Room number $index"))
        assertEquals(listOf("#room5000", "#room4999", "#room4998"), list.result().map { it.name })
        assertEquals(5000, list.total)
        // The cap itself is capped, however much a caller asks for.
        val wide = IrcChannelList("", 100_000)
        for (index in 1..500) wide.add(row("#wide$index", index.toString()))
        assertEquals(IrcChannels.MAX_LIMIT, wide.result().size)
    }

    @Test fun theListFiltersByEveryWordInNameOrTopic() {
        val list = IrcChannelList("Garden  HELP")
        list.add(row("#garden", "40", "Ask for help here"))
        list.add(row("#garden-chat", "90", "Off-topic"))
        list.add(row("#help", "70", "The garden desk"))
        list.add(row("#café", "5", "garden help in French"))
        assertEquals(listOf("#help", "#garden", "#café"), list.result().map { it.name })
        // The same channel twice (two replies overlapping) is listed once, whatever its capitals.
        val twice = IrcChannelList("")
        twice.add(row("#Pond", "4"))
        twice.add(row("#pond", "4"))
        assertEquals(1, twice.result().size)
    }

    @Test fun theNetworkFiltersWhereItCan() {
        assertEquals("CMNTU", IrcChannels.elist(listOf("sam", "CHANTYPES=#", "ELIST=cmntu", "are supported by this server")))
        assertEquals("", IrcChannels.elist(listOf("sam", "CHANTYPES=#")))
        assertEquals("LIST *python*", IrcChannels.command(" Python  help ", "CMNTU"))
        assertEquals("LIST *python*", IrcChannels.command("#python", "M"))
        assertEquals("LIST py*", IrcChannels.command("py*", "M"))
        assertEquals("LIST >" + IrcChannels.BUSY_ABOVE, IrcChannels.command("", "CTU"))
        // A network that can't filter sends everything; the cap and filter apply on the phone.
        assertEquals("LIST", IrcChannels.command("python", ""))
        assertEquals("LIST", IrcChannels.command("python", "CTU"))
        assertEquals("LIST", IrcChannels.command("", ""))
        // Nothing typed can add a second command or parameter.
        for (odd in listOf("a b\r\nQUIT", "x,y z", "tab\there")) {
            val line = IrcChannels.command(odd, "M")
            assertFalse(line, line.contains('\r') || line.contains('\n') || line.contains(','))
            assertEquals(line, 2, line.split(" ").size)
        }
    }

    @Test fun refusalsAndWaitingAreInPlainWords() {
        assertTrue(IrcChannels.joinError("473", "#pond", "Libera.Chat", "Cannot join channel (+i)").contains("invite-only"))
        assertTrue(IrcChannels.joinError("477", "#pond", "Libera.Chat", "").contains("signed in to their nickname"))
        assertEquals("Couldn’t join #pond. Libera.Chat said: “Try later”", IrcChannels.joinError("999", "#pond", "Libera.Chat", " Try later "))
        assertEquals("Couldn’t join #pond.", IrcChannels.joinError("999", "#pond", "Libera.Chat", ""))
        for (state in listOf("", "connecting", "reconnecting", "offline", "disconnected")) {
            val (line, button) = IrcChannels.waiting(state, "Libera.Chat", true)
            assertTrue(line, line.contains("Libera.Chat"))
            // Nothing in this flow sends anyone to the web app.
            assertFalse(line, line.contains("Web"))
            if (state != "connecting") assertTrue(state, button.isNotEmpty())
        }
        assertTrue(IrcChannels.waiting("connected", "Libera.Chat", false).first.contains("turned off"))
    }

    @Test fun popularChannelsAreAStartingPointForKnownNetworks() {
        assertEquals(listOf("#libera", "#linux", "#python", "#ubuntu", "#archlinux"), IrcChannels.popular("IRC.Libera.Chat"))
        assertTrue(IrcChannels.popular("irc.example.org").isEmpty())
        for (entry in ServerDirectory.irc) for (name in IrcChannels.popular(entry.address)) assertEquals(name, IrcChannels.channelName(name))
    }
}
