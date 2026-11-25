package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unsent drafts, kept per chat. */
class DraftsTest {
    @Test fun aDraftIsKeptPerChatAndClearedWhenEmptied() {
        var book = Drafts.updated(emptyMap(), "+1 555 0100", "On my way")
        book = Drafts.updated(book, "+1 555 0101", "Lunch on Friday?")
        assertEquals("On my way", book["+1 555 0100"])
        assertEquals("Lunch on Friday?", book["+1 555 0101"])
        // Sending or deleting the text leaves nothing behind.
        book = Drafts.updated(book, "+1 555 0100", "")
        assertFalse(book.containsKey("+1 555 0100"))
        book = Drafts.updated(book, "+1 555 0101", "   \n")
        assertTrue(book.isEmpty())
    }

    @Test fun onlySoManyDraftsAndCharactersAreKept() {
        var book = emptyMap<String, String>()
        for (index in 0 until Drafts.MAX_CHATS + 5) book = Drafts.updated(book, "chat-$index", "hello")
        assertEquals(Drafts.MAX_CHATS, book.size)
        assertFalse(book.containsKey("chat-0"))
        assertTrue(book.containsKey("chat-${Drafts.MAX_CHATS + 4}"))
        assertEquals(Drafts.MAX_CHARS + 1, Drafts.updated(emptyMap(), "a", "x".repeat(Drafts.MAX_CHARS * 2))["a"]!!.length)
    }

    @Test fun privateFolderDraftsNeverReachTheInbox() {
        val book = mapOf("+1 555 0100" to "On my way", "+1 555 0199" to "Secret plan")
        val shown = Drafts.inboxVisible(book) { it == "+1 555 0199" }
        assertEquals(mapOf("+1 555 0100" to "On my way"), shown)
    }

    @Test fun theInboxLineIsOneShortLine() {
        assertEquals("Draft: On my way, see you soon", Drafts.preview("  On my way,\nsee you   soon "))
        assertTrue(Drafts.preview("x".repeat(500)).length < 140)
    }
}
