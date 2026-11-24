package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sample inbox's made-up data holds together, so every sample filter, server, and mute has something to show. */
class SampleInboxTest {
    private val names = SampleInbox.names()
    private fun chat(name: String) = SampleInbox.CHATS.first { it[0] == name }

    @Test fun chatsAreWellFormedAndUnique() {
        assertEquals(names.size, names.toSet().size)
        for (chat in SampleInbox.CHATS) {
            assertEquals(chat[0], 6, chat.size)
            // Every field is filled in, except the request's reply: nobody has answered it yet.
            assertTrue(chat[0], chat.withIndex().all { (index, value) -> value.isNotBlank() || index == 3 && chat[0] == SampleInbox.REQUEST })
        }
        // Every service the inbox filters by has a sample chat: texts, groups, and each internet service.
        val services = SampleInbox.CHATS.map { it[5] }.toSet()
        assertTrue(services.containsAll(listOf("SMS", "RCS", "MMS", "Group", "Email", "Matrix", "XMPP", "IRC")))
    }

    @Test fun groupsHaveMembersAndRealChats() {
        for ((group, members) in SampleInbox.GROUPS) {
            assertTrue(group, group in names)
            assertTrue(group, members.size >= 3)
        }
        // Both Groups and Direct messages have chats.
        assertTrue(names.any { it in SampleInbox.GROUPS || chat(it)[5] == "Group" })
        assertTrue(names.any { it !in SampleInbox.GROUPS && chat(it)[5] != "Group" })
    }

    @Test fun everyServerHasChannelsThatAreItsOwn() {
        assertTrue(SampleInbox.SERVERS.size >= 2)
        assertEquals(SampleInbox.SERVERS.size, SampleInbox.SERVERS.map { it.id }.toSet().size)
        val channels = SampleInbox.SERVERS.flatMap { it.channels }
        assertEquals("A channel is on one server only", channels.size, channels.toSet().size)
        for (server in SampleInbox.SERVERS) {
            assertTrue(server.name, server.channels.isNotEmpty())
            assertTrue(server.name, SampleInbox.directory(server.id).isNotEmpty())
            val service = when (server.service) { "irc" -> "IRC"; "matrix" -> "Matrix"; else -> "XMPP" }
            for (channel in server.channels) {
                assertTrue(channel, channel in names)
                assertEquals(channel, service, chat(channel)[5])
                assertTrue("$channel is a group", channel in SampleInbox.GROUPS)
                assertEquals(server.id, SampleInbox.serverOf(channel))
            }
        }
        assertEquals("", SampleInbox.serverOf("Maya Chen"))
        // Some community channels start unread, so the server headers show a count.
        assertTrue(channels.any { it in SampleInbox.UNREAD })
    }

    @Test fun favoritesAndFoldersReferToSampleChats() {
        assertTrue(SampleInbox.FAVORITES.isNotEmpty())
        for (favorite in SampleInbox.FAVORITES) {
            assertTrue(favorite, favorite.startsWith("demo:"))
            assertTrue(favorite, favorite.removePrefix("demo:") in names)
        }
        for (name in SampleInbox.UNREAD + SampleInbox.ARCHIVED + SampleInbox.BLOCKED) assertTrue(name, name in names)
        assertTrue(SampleInbox.ARCHIVED.intersect(SampleInbox.BLOCKED).isEmpty())
        assertEquals("Archived", SampleInbox.folderOf(SampleInbox.ARCHIVED.first()))
        assertEquals("Blocked", SampleInbox.folderOf(SampleInbox.BLOCKED.first()))
        assertEquals("Inbox", SampleInbox.folderOf("Maya Chen"))
        // Unread has chats in the inbox itself, not only in Archived or Blocked.
        assertTrue(SampleInbox.UNREAD.any { SampleInbox.folderOf(it) == "Inbox" })
        assertTrue(SampleInbox.SECOND_ACCOUNT_CHAT in names)
    }

    @Test fun startingMutesAreOneTimedChatAndOneServer() {
        val chats = SampleInbox.MUTES.keys.filter { it.startsWith("demo:") }
        val servers = SampleInbox.MUTES.keys.filter { it.startsWith(Mutes.SERVER) }
        assertEquals(SampleInbox.MUTES.size, chats.size + servers.size)
        assertTrue(chats.isNotEmpty() && servers.isNotEmpty())
        for (key in chats) assertTrue(key, key.removePrefix("demo:") in names)
        for (key in servers) assertNotNull(key, SampleInbox.server(key.removePrefix(Mutes.SERVER)))
        // Timed, so the bell says "Muted until …".
        assertTrue(chats.all { SampleInbox.MUTES.getValue(it) > 0 })
        // A muted server mutes its channels, the same rule live chats use.
        val server = servers.first().removePrefix(Mutes.SERVER)
        val channel = "demo:" + SampleInbox.server(server)!!.channels.first()
        val now = 1_000_000L
        val until = SampleInbox.MUTES.mapValues { now + it.value }
        assertTrue(Mutes.chatEnds(until.keys, until, channel, server, now) > now)
        assertEquals(Mutes.NOT_MUTED, Mutes.chatEnds(until.keys, until, "demo:Maya Chen", "", now))
    }

    @Test fun sampleCardsParseLikeRealOnes() {
        assertEquals(SampleInbox.LINK, previewableLink("Here’s the loop we talked about: " + SampleInbox.LINK))
        val poll = parsePoll(SampleInbox.pollText())
        assertNotNull(poll)
        assertEquals(SampleInbox.POLL, poll!!.id)
        assertEquals(3, poll.options.size)
        assertFalse(SampleInbox.LINK.contains(" "))
    }
}
