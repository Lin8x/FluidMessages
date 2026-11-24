package org.fluidmessages

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDirectoryTest {
    private val host = Regex("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")

    @Test
    fun ircNetworksAreValidTlsHostsWithoutDuplicates() {
        val irc = ServerDirectory.irc
        assertTrue(irc.size >= 12)
        for (name in listOf("Libera.Chat", "OFTC", "EFnet", "IRCnet", "Undernet", "DALnet", "QuakeNet", "Rizon", "hackint",
                "Snoonet", "GIMPNet", "EsperNet")) assertTrue("$name is missing", irc.any { it.name == name })
        for (entry in irc) {
            assertTrue(entry.address, host.matches(entry.address))
            // Every network FluidMessages can connect to is on a TLS port; one without TLS can't be picked.
            if (entry.tls) assertEquals(entry.name, 6697, entry.port) else assertFalse(entry.port in listOf(6697, 6679))
            assertEquals(mapOf("server" to entry.address, "port" to entry.port.toString()), entry.preset)
        }
        assertEquals(irc.size, irc.map { it.name.lowercase() }.toSet().size)
        assertEquals(irc.size, irc.map { it.address }.toSet().size)
    }

    @Test
    fun matrixAndXmppEntriesAreValidWithoutDuplicates() {
        for (entry in ServerDirectory.matrix) {
            assertTrue(entry.address, Regex("https://[a-z0-9.-]+").matches(entry.address))
            assertTrue(host.matches(entry.address.removePrefix("https://")))
            assertEquals(mapOf("homeserver" to entry.address), entry.preset)
        }
        for (entry in ServerDirectory.xmpp) {
            assertTrue(entry.address, host.matches(entry.address))
            assertEquals(mapOf(ServerDirectory.XMPP_DOMAIN to entry.address), entry.preset)
        }
        for (list in listOf(ServerDirectory.matrix, ServerDirectory.xmpp)) {
            assertEquals(list.size, list.map { it.name }.toSet().size)
            assertEquals(list.size, list.map { it.address }.toSet().size)
        }
        assertTrue(ServerDirectory.matrix.any { it.name == "matrix.org" })
        assertTrue(ServerDirectory.xmpp.first { it.name == "conversations.im" }.about.contains("paid"))
        for (entry in ServerDirectory.irc + ServerDirectory.matrix + ServerDirectory.xmpp) assertTrue(entry.about.isNotBlank())
    }

    @Test
    fun theWebKeepsTheSameServers() {
        // server/server-directory.js is FluidMessages Web's copy of the same list.
        val web = listOf(File("../server/server-directory.js"), File("server/server-directory.js")).firstOrNull { it.exists() }?.readText()
            ?: return
        for (entry in ServerDirectory.irc + ServerDirectory.matrix + ServerDirectory.xmpp) {
            assertTrue("${entry.address} isn't in server-directory.js", web.contains("'" + entry.address + "'"))
        }
        assertFalse(web.contains("discord", ignoreCase = true))
    }

    @Test
    fun searchMatchesEveryWordInNameAddressOrDescription() {
        assertEquals(listOf("Libera.Chat"), ServerDirectory.search("irc", "libera").map { it.name })
        assertEquals(listOf("OFTC"), ServerDirectory.search("irc", "  debian TOR ").map { it.name })
        assertEquals(listOf("hackint"), ServerDirectory.search("irc", "irc.hackint").map { it.name })
        assertEquals(ServerDirectory.irc, ServerDirectory.search("irc", ""))
        assertTrue(ServerDirectory.search("irc", "nothing like this").isEmpty())
        assertEquals(listOf("conversations.im"), ServerDirectory.search("xmpp", "paid").map { it.name })
        assertEquals(listOf("mozilla.org"), ServerDirectory.search("matrix", "Mozílla").map { it.name })
        assertTrue(ServerDirectory.search("email", "").isEmpty())
    }

    @Test
    fun xmppAddressAddsThePickedDomain() {
        assertEquals("sam@yax.im", ServerDirectory.xmppAddress(" sam ", "yax.im"))
        assertEquals("sam@example.test", ServerDirectory.xmppAddress("sam@example.test", "yax.im"))
        assertEquals("sam", ServerDirectory.xmppAddress("sam", null))
        assertEquals("", ServerDirectory.xmppAddress("", "yax.im"))
    }

    @Test
    fun knownNetworksNameTheirServers() {
        assertEquals("EsperNet", AccountKeys.serverName("irc:sam@irc.esper.net"))
        assertEquals("irc.example.test", AccountKeys.serverName("irc:sam@irc.example.test"))
        assertFalse(AccountKeys.help("irc").contains("Discord"))
        assertFalse(AccountKeys.help("matrix").contains("Discord"))
    }

    @Test
    fun foundRoomsFilterAsYouType() {
        val rooms = listOf(
            FoundRoom("#gardening", "#gardening", "", false, "#gardening", topic = "Seeds, soil, and Saturday swaps"),
            FoundRoom("!chess:example.test", "Chess club", "", false, "#chess:example.test", alias = "#chess:example.test"),
            FoundRoom("radio@conference.example.test", "Ham radio", "", true, "radio@conference.example.test"),
        )
        assertEquals(listOf("#gardening"), RoomFilter.filter(rooms, "soil").map { it.target })
        assertEquals(listOf("!chess:example.test"), RoomFilter.filter(rooms, "#chess").map { it.target })
        assertEquals(listOf("radio@conference.example.test"), RoomFilter.filter(rooms, "HAM conference").map { it.target })
        assertEquals(rooms, RoomFilter.filter(rooms, " "))
        assertTrue(RoomFilter.filter(rooms, "knitting").isEmpty())
    }

    @Test
    fun channelsTabSearchKeepsMatchingChannelsUnderTheirServer() {
        val libera = "irc:sam@irc.libera.chat"
        val matrix = "matrix:@sam:example.test"
        val servers = ChannelDirectory.group(listOf(libera to "irc", matrix to "matrix"), listOf(
            ChannelLink("chat-1", "#gardening", "irc", libera, true),
            ChannelLink("chat-2", "#radio", "irc", libera, true),
            ChannelLink("chat-3", "Book club", "matrix", matrix, true),
        ))
        assertEquals(servers, ChannelDirectory.filter(servers, ""))
        val found = ChannelDirectory.filter(servers, "garden")
        assertEquals(listOf("Libera.Chat"), found.map { it.name })
        assertEquals(listOf("#gardening"), found.single().channels.map { it.title })
        // The server's name finds all its channels.
        assertEquals(listOf("#gardening", "#radio"), ChannelDirectory.filter(servers, "libera").single().channels.map { it.title })
        assertTrue(ChannelDirectory.filter(servers, "knitting").isEmpty())
    }
}
