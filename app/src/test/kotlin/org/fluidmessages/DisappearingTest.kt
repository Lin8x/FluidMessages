package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When disappearing messages are due, when the wake-up alarm moves, and which remembered inbox rows still show. */
class DisappearingTest {
    private val minute = 60_000L

    @Test fun aChatTimerCountsFromEachMessageSentOrReceivedAfterItWasSet() {
        val since = 1_000_000L
        assertEquals(since + 5 * minute + 300_000L, Disappearing.expiryAt(0, 300, since + 5 * minute, since))
        // A message from the moment the timer was set counts too.
        assertEquals(since + 30_000L, Disappearing.expiryAt(0, 30, since, since))
        // Earlier messages stay.
        assertEquals(0L, Disappearing.expiryAt(0, 300, since - 1, since))
        // No timer, or no date, means it stays.
        assertEquals(0L, Disappearing.expiryAt(0, 0, since + minute, since))
        assertEquals(0L, Disappearing.expiryAt(0, 300, 0, 0))
        // A timer that was never turned on (since is "never") keeps everything.
        assertEquals(0L, Disappearing.expiryAt(0, 300, since, Long.MAX_VALUE))
    }

    @Test fun theTimerByAMessageSaysHowLongIsLeft() {
        assertEquals("Disappears in 45 s", Disappearing.timeLeft(44_200))
        assertEquals("Disappears in 5 min", Disappearing.timeLeft(4 * minute + 1))
        assertEquals("Disappears in 1 h", Disappearing.timeLeft(60 * minute))
        assertEquals("Disappears in 3 h", Disappearing.timeLeft(3 * 60 * minute - 5 * minute))
        assertEquals("Disappears in 7 d", Disappearing.timeLeft(7 * 24 * 60 * minute))
        assertEquals("Disappearing now", Disappearing.timeLeft(0))
    }

    @Test fun aTimerTheMessageCarriesWins() {
        // Encrypted texts, Matrix, and XMPP carry the sender's timer, even where this phone has none.
        assertEquals(42L, Disappearing.expiryAt(42, 0, 10, Long.MAX_VALUE))
        assertEquals(42L, Disappearing.expiryAt(42, 3600, 10, 0))
    }

    @Test fun earliestIgnoresMissingTimes() {
        assertEquals(0L, Disappearing.earliest(0, 0))
        assertEquals(5L, Disappearing.earliest(0, 5))
        assertEquals(5L, Disappearing.earliest(5, 0))
        assertEquals(3L, Disappearing.earliest(5, 3))
    }

    @Test fun theAlarmOnlyMovesWhenAMessageIsDueSooner() {
        val now = 10 * minute
        // Nothing set yet: set it.
        assertTrue(Disappearing.needsAlarm(0, now + minute, now))
        // Already due sooner: leave it.
        assertFalse(Disappearing.needsAlarm(now + minute, now + 2 * minute, now))
        assertFalse(Disappearing.needsAlarm(now + minute, now + minute, now))
        // Due later than this message: bring it forward.
        assertTrue(Disappearing.needsAlarm(now + 2 * minute, now + minute, now))
        // Long past, so it already fired or a restart dropped it: set it again.
        assertTrue(Disappearing.needsAlarm(now - 2 * minute, now + 5 * minute, now))
        // A message that never disappears needs nothing.
        assertFalse(Disappearing.needsAlarm(0, 0, now))
    }

    @Test fun rememberedInboxRowsFollowTodaysFoldersAndTimers() {
        val now = 10 * minute
        assertTrue(InboxSnapshot.shown("Inbox", false, false, false, 0, now))
        assertTrue(InboxSnapshot.shown("Inbox", false, false, false, now + minute, now))
        // Moved since: archived, blocked, or into the private folder.
        assertFalse(InboxSnapshot.shown("Inbox", true, false, false, 0, now))
        assertFalse(InboxSnapshot.shown("Inbox", false, true, false, 0, now))
        assertFalse(InboxSnapshot.shown("Inbox", false, false, true, 0, now))
        // Its latest message has disappeared since.
        assertFalse(InboxSnapshot.shown("Inbox", false, false, false, now, now))
        assertTrue(InboxSnapshot.shown("Archived", true, false, false, 0, now))
        assertFalse(InboxSnapshot.shown("Archived", true, true, false, 0, now))
        assertTrue(InboxSnapshot.shown("Blocked", false, true, false, 0, now))
        assertFalse(InboxSnapshot.shown("Blocked", false, false, false, 0, now))
        // The private folder is never remembered.
        assertFalse(InboxSnapshot.shown("Hidden chats", false, false, false, 0, now))
    }
}
