package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelDirectoryTest {
    private val libera = "irc:sam@irc.libera.chat"
    private val matrix = "matrix:@sam:example.org"

    @Test
    fun groupsJoinedChannelsUnderTheirServerSortedByName() {
        val links = listOf(
            ChannelLink("chat-1", "#zebra", "irc", libera, true),
            ChannelLink("chat-2", "#apples", "irc", libera, true),
            // A direct message on the same network isn't a channel.
            ChannelLink("chat-3", "riley", "irc", libera, false),
            ChannelLink("chat-4", "Book club", "matrix", matrix, true),
            // The same room linked twice only shows once.
            ChannelLink("chat-4", "Book club", "matrix", matrix, true),
        )
        val servers = ChannelDirectory.group(listOf(libera to "irc", matrix to "matrix"), links)
        assertEquals(listOf("Libera.Chat", "example.org"), servers.map { it.name })
        assertEquals(listOf("#apples", "#zebra"), servers[0].channels.map { it.title })
        assertEquals(listOf("chat-4"), servers[1].channels.map { it.address })
    }

    @Test
    fun serverWithNothingJoinedIsStillListedAndBlankTitlesFallBackToAddress() {
        val servers = ChannelDirectory.group(listOf(libera to "irc", matrix to "matrix"),
            listOf(ChannelLink("chat-9", " ", "matrix", matrix, true)))
        assertEquals(emptyList<ServerChannel>(), servers[0].channels)
        assertEquals("chat-9", servers[1].channels.single().title)
    }

    @Test
    fun noServersMeansAnEmptyList() {
        assertEquals(emptyList<ServerChannels>(), ChannelDirectory.group(emptyList(), emptyList()))
    }
}
