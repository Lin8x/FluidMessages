package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages → Communities: grouping by server, the three sort orders, and the "Show community chats in Chats" rule. */
class CommunitiesTest {
    private val radio = ServerChip("irc:kestrel@irc.alpha.test", "Alpha Net", "irc", order = 0)
    private val garden = ServerChip("matrix:@kestrel:beta.test", "beta.test", "matrix", order = 1)

    private fun channel(name: String, server: ServerChip, last: Long, joined: Long, unread: Boolean = false) =
        ThreadRow(name, name, "", "", "", unread, false, server = server.id, lastAt = last, joinedAt = joined)

    private val rows = listOf(
        channel("#radio-club", radio, last = 500, joined = 1),
        channel("#antennas", radio, last = 900, joined = 3, unread = true),
        channel("Seed swap", garden, last = 700, joined = 5),
        channel("Allotments", garden, last = 100, joined = 4, unread = true),
    )

    private fun shape(groups: List<Communities.Group>) = groups.map { it.server.name to it.channels.map(ThreadRow::name) }

    @Test fun recentActivityPutsTheNewestMessageFirst() {
        assertEquals(listOf("Alpha Net" to listOf("#antennas", "#radio-club"), "beta.test" to listOf("Seed swap", "Allotments")),
            shape(Communities.group(listOf(radio, garden), rows, Communities.Sort.RECENT)))
    }

    @Test fun nameSortsServersAndChannelsAtoZIgnoringChannelMarks() {
        assertEquals(listOf("Alpha Net" to listOf("#antennas", "#radio-club"), "beta.test" to listOf("Allotments", "Seed swap")),
            shape(Communities.group(listOf(garden, radio), rows, Communities.Sort.NAME)))
    }

    @Test fun dateJoinedPutsTheNewestServerAndChannelFirst() {
        assertEquals(listOf("beta.test" to listOf("Seed swap", "Allotments"), "Alpha Net" to listOf("#antennas", "#radio-club")),
            shape(Communities.group(listOf(radio, garden), rows, Communities.Sort.JOINED)))
    }

    @Test fun serversWithoutChannelsStillShowAndCountUnread() {
        val empty = ServerChip("xmpp:kestrel@gamma.test", "gamma.test", "xmpp", order = 2)
        val groups = Communities.group(listOf(radio, garden, empty), rows, Communities.Sort.RECENT)
        assertEquals(listOf(1, 1, 0), groups.map { it.unread })
        assertEquals(emptyList<ThreadRow>(), groups.last().channels)
        assertEquals("gamma.test", groups.last().server.name)
    }

    @Test fun unknownSortFallsBackToRecentActivity() {
        assertEquals(Communities.Sort.RECENT, Communities.Sort.parse("newest"))
        assertEquals(Communities.Sort.JOINED, Communities.Sort.parse("joined"))
    }

    @Test fun communityChatsShowInChatsOnlyWhileTheSettingIsOn() {
        assertTrue(Communities.showsInChats(community = true, showCommunityChats = true))
        assertFalse(Communities.showsInChats(community = true, showCommunityChats = false))
        // Direct messages on those services, and every other chat, always show in Chats.
        assertTrue(Communities.showsInChats(community = false, showCommunityChats = false))
    }

    @Test fun hiddenCommunityChatsCountOnCommunitiesInsteadOfMessages() {
        val messages = listOf(
            BadgeMessage("a", "+15550101", unread = true),
            BadgeMessage("room", "room-1", unread = true, community = true),
            BadgeMessage("dm", "kestrel", unread = true),
        )
        assertEquals(listOf(3, 0), TabBadges.unreadSplit(messages, emptySet(), emptySet(), emptySet(), true).toList())
        assertEquals(listOf(2, 1), TabBadges.unreadSplit(messages, emptySet(), emptySet(), emptySet(), false).toList())
        // Muted rooms count nowhere.
        assertEquals(listOf(2, 0), TabBadges.unreadSplit(messages, setOf("room-1"), emptySet(), emptySet(), false).toList())
    }

    @Test fun serverInitials() {
        assertEquals("AN", Communities.initials("Alpha Net"))
        assertEquals("BT", Communities.initials("beta.test"))
        assertEquals("GA", Communities.initials("gamma"))
    }
}
