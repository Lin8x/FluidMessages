package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inbox filter menu and its rules, in the inbox, the open private folder, decoy mode, and the sample inbox. */
class InboxFiltersTest {
    private val Inbox = InboxFilters.Scope.INBOX
    private val group = InboxFilters.Chat(group = true, services = setOf("MATRIX"))
    private val direct = InboxFilters.Chat(group = false, favorite = true, services = setOf("SMS"), unread = true)

    @Test fun groupsDirectFavoritesAndServices() {
        assertTrue(InboxFilters.shows(Inbox, "groups", group))
        assertFalse(InboxFilters.shows(Inbox, "groups", direct))
        assertTrue(InboxFilters.shows(Inbox, "direct", direct))
        assertFalse(InboxFilters.shows(Inbox, "direct", group))
        assertTrue(InboxFilters.shows(Inbox, "favorites", direct))
        assertFalse(InboxFilters.shows(Inbox, "favorites", group))
        assertTrue(InboxFilters.shows(Inbox, "matrix", group))
        assertFalse(InboxFilters.shows(Inbox, "texts", group))
        assertTrue(InboxFilters.shows(Inbox, "texts", direct))
        assertTrue(InboxFilters.shows(Inbox, "unread", direct))
        assertFalse(InboxFilters.shows(Inbox, "unread", group))
        assertTrue(InboxFilters.keepsMessage("matrix", "MATRIX"))
        assertFalse(InboxFilters.keepsMessage("matrix", "SMS"))
        assertTrue(InboxFilters.keepsMessage("groups", "SMS"))
    }

    @Test fun archivedAndBlockedStayInTheirOwnFilters() {
        val archived = direct.copy(archived = true)
        val blocked = direct.copy(blocked = true)
        assertFalse(InboxFilters.shows(Inbox, "favorites", archived))
        assertTrue(InboxFilters.shows(Inbox, "archived", archived))
        assertFalse(InboxFilters.shows(Inbox, "inbox", blocked))
        assertFalse(InboxFilters.shows(Inbox, "archived", blocked.copy(archived = true)))
        assertTrue(InboxFilters.shows(Inbox, "blocked", blocked))
    }

    @Test fun thePrivateFolderNeverLeaksIntoOtherFilters() {
        val hidden = group.copy(hidden = true)
        for (filter in InboxFilters.ALL) assertFalse(filter.id, InboxFilters.shows(Inbox, filter.id, hidden.copy(archived = filter.id == "archived")))
        // Inside the open private folder, filters apply to its chats only.
        assertTrue(InboxFilters.shows(InboxFilters.Scope.PRIVATE, "groups", hidden))
        assertFalse(InboxFilters.shows(InboxFilters.Scope.PRIVATE, "direct", hidden))
        assertFalse(InboxFilters.shows(InboxFilters.Scope.PRIVATE, "groups", group))
    }

    @Test fun decoyModeShowsDecoyChatsOnlyAndNoFavorites() {
        val decoy = direct.copy(decoy = true)
        assertTrue(InboxFilters.shows(InboxFilters.Scope.DECOY, "direct", decoy))
        assertTrue(InboxFilters.shows(InboxFilters.Scope.DECOY, "texts", decoy))
        assertFalse(InboxFilters.shows(InboxFilters.Scope.DECOY, "favorites", decoy))
        assertFalse(InboxFilters.shows(InboxFilters.Scope.DECOY, "inbox", direct))
        assertFalse(InboxFilters.shows(InboxFilters.Scope.DECOY, "inbox", decoy.copy(hidden = true)))
    }

    @Test fun theSampleInboxUsesItsOwnChats() {
        val sample = InboxFilters.Chat(group = true, favorite = true, services = setOf("SMS"))
        assertTrue(InboxFilters.shows(InboxFilters.Scope.SAMPLE, "favorites", sample))
        assertTrue(InboxFilters.shows(InboxFilters.Scope.SAMPLE, "groups", sample))
        assertFalse(InboxFilters.shows(InboxFilters.Scope.SAMPLE, "groups", sample.copy(hidden = true)))
    }

    @Test fun storedOrderKeepsHiddenFiltersAndAddsNewOnes() {
        val order = InboxFilters.parse(listOf("groups", "-unread", "inbox", "-inbox", "nope", "groups"))
        assertEquals(listOf("groups", "unread", "inbox"), order.take(3).map { it.id })
        assertFalse(order[1].shown)
        assertTrue(order[2].shown)
        assertEquals(InboxFilters.ALL.size, order.size)
        assertEquals(listOf("groups", "-unread", "inbox"), InboxFilters.encode(order).take(3))
        assertEquals(InboxFilters.encode(order), InboxFilters.encode(InboxFilters.parse(InboxFilters.encode(order))))
    }

    @Test fun menuShowsServicesYouHaveInOrder() {
        val order = InboxFilters.parse(listOf("inbox", "irc", "groups", "-favorites", "matrix"))
        val menu = InboxFilters.menu(order, setOf("SMS", "IRC"))
        assertEquals(listOf("Inbox", "IRC", "Groups"), menu.take(3))
        assertFalse("Favorites" in menu)
        assertFalse("Matrix" in menu)
        assertTrue("Texts" in menu)
        assertEquals(listOf("Inbox", "Groups"), InboxFilters.menu(InboxFilters.move(order, 1, 1).let { InboxFilters.move(it, 2, 5) }, emptySet()).take(2))
    }

    @Test fun reorderAndShow() {
        val order = InboxFilters.parse(emptyList())
        assertEquals(listOf("unread", "inbox"), InboxFilters.move(order, 0, 1).take(2).map { it.id })
        assertEquals(order, InboxFilters.move(order, 0, -1))
        assertTrue(InboxFilters.show(order, "inbox", false).first().shown)
        assertFalse(InboxFilters.show(order, "groups", false).first { it.id == "groups" }.shown)
        assertEquals("Groups", InboxFilters.title("groups"))
        assertEquals("Messages", InboxFilters.title("inbox"))
    }
}
