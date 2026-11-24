package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "From: alice@example.org": when the composer, header, New message, and inbox show which account sends, and which. */
class FromAccountTest {
    private val alice = SendAccount("email:alice@example.test", "alice@example.test")
    private val work = SendAccount("email:alice@work.example.test", "alice@work.example.test", recent = true)
    private val side = SendAccount("email:side@example.test", "")

    @Test fun nothingShowsWithOneAccount() {
        assertFalse(FromAccount.shows(listOf(alice)))
        assertEquals("", FromAccount.line(alice.id, listOf(alice)))
        assertEquals("", FromAccount.line("", listOf(alice)))
        assertEquals("", FromAccount.inboxHint(alice.id, listOf(alice)))
        assertEquals("", AccountKeys.via(alice.id, alice.name, 1))
        assertNull(FromAccount.chatAccount(alice.id, emptyList()))
    }

    @Test fun aChatRepliesFromItsOwnAccount() {
        val both = listOf(alice, work)
        assertTrue(FromAccount.shows(both))
        assertEquals("From: alice@work.example.test", FromAccount.line(work.id, both))
        assertEquals("From: alice@example.test", FromAccount.line(alice.id, both))
        // A link from before multiple accounts names none: it belongs to the first account.
        assertEquals(alice, FromAccount.chatAccount("", both))
        // An account that was removed isn't guessed at.
        assertNull(FromAccount.chatAccount("email:gone@example.test", both))
        // Without a saved name, the address comes from the id.
        assertEquals("From: side@example.test", FromAccount.line(side.id, listOf(alice, side)))
        assertEquals("from alice@work.example.test", AccountKeys.via(work.id, work.name, 2))
        assertTrue(FromAccount.explain("alice@work.example.test").startsWith("Replies in this chat come from alice@work.example.test."))
    }

    @Test fun newMessagePreselectsTheMostRecentAccount() {
        assertEquals(work.id, FromAccount.preselect(listOf(alice, work, side)))
        // Nothing used yet: the newest account, as the companion picks.
        assertEquals(side.id, FromAccount.preselect(listOf(alice, side)))
        assertEquals("", FromAccount.preselect(emptyList()))
        // An IRC nick typed with its network goes through your account there.
        val libera = SendAccount("irc:riley@irc.libera.chat", "riley@irc.libera.chat")
        val oftc = SendAccount("irc:riley@irc.oftc.net", "riley@irc.oftc.net", recent = true)
        assertEquals(libera.id, FromAccount.preselect(listOf(libera, oftc), "irc.libera.chat"))
        assertEquals(oftc.id, FromAccount.preselect(listOf(libera, oftc), "irc.example.test"))
        assertEquals("alice@example.test · Email", FromAccount.choiceLabel(alice, "Email"))
    }

    @Test fun inboxHintsOnlyForAnotherAccount() {
        val both = listOf(alice, work)
        assertEquals("", FromAccount.inboxHint(alice.id, both))
        assertEquals("", FromAccount.inboxHint("", both))
        assertEquals("alice@work.example.test", FromAccount.inboxHint(work.id, both))
    }
}
