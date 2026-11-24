package org.fluidmessages

import android.provider.CallLog
import org.junit.Assert.assertEquals
import org.junit.Test

class TabBadgesTest {
    private fun message(name: String, unread: Boolean, hidden: Boolean = false, protocol: String = "SMS") =
        BadgeMessage("demo:$name\u001f$protocol", "demo:$name", unread, hidden)

    @Test
    fun countsConversationsWhoseNewestMessageIsUnread() {
        val rows = listOf(
            message("Maya Chen", unread = true),
            message("Maya Chen", unread = true),
            message("Jordan Ellis", unread = false),
            // An older unread message doesn't count once a newer one in the same chat was read.
            message("Jordan Ellis", unread = true),
            message("Avery Brooks", unread = true),
        )
        assertEquals(2, TabBadges.unreadConversations(rows, emptySet(), emptySet(), emptySet()))
    }

    @Test
    fun separateProtocolThreadsCountSeparately() {
        val rows = listOf(message("Maya Chen", true, protocol = "SMS"), message("Maya Chen", true, protocol = "MATRIX"))
        assertEquals(2, TabBadges.unreadConversations(rows, emptySet(), emptySet(), emptySet()))
    }

    @Test
    fun leavesOutMutedArchivedBlockedPrivateAndDecoyChats() {
        val rows = listOf(
            message("Maya Chen", true),
            message("Sam Rivera", true),
            message("Avery Brooks", true),
            message("Nora Patel", true),
            message("Cameron Reed", true, hidden = true),
            BadgeMessage(Privacy.DECOY + "Leah Morgan\u001fSMS", Privacy.DECOY + "Leah Morgan", true),
        )
        val count = TabBadges.unreadConversations(rows, muted = setOf("demo:Sam Rivera"),
            archived = setOf("demo:Avery Brooks"), blocked = setOf("demo:Nora Patel"))
        assertEquals(1, count)
    }

    @Test
    fun countsMissedCallsAfterThePhoneTabWasOpened() {
        val calls = listOf(
            BadgeCall(CallLog.Calls.MISSED_TYPE, 2_000, "demo:Maya Chen"),
            BadgeCall(CallLog.Calls.MISSED_TYPE, 3_000, "demo:Jordan Ellis"),
            BadgeCall(CallLog.Calls.MISSED_TYPE, 1_000, "demo:Sam Rivera"),
            BadgeCall(CallLog.Calls.INCOMING_TYPE, 4_000, "demo:Mom"),
            BadgeCall(CallLog.Calls.REJECTED_TYPE, 4_000, "demo:Nora Patel"),
            BadgeCall(CallLog.Calls.OUTGOING_TYPE, 4_000, "demo:Avery Brooks"),
        )
        assertEquals(2, TabBadges.missedCalls(calls, since = 1_000))
        assertEquals(1, TabBadges.missedCalls(calls, since = 1_000) { it == "demo:Maya Chen" })
        assertEquals(0, TabBadges.missedCalls(calls, since = 3_000))
    }

    @Test
    fun capsTheLabelAtNinetyNine() {
        assertEquals("", TabBadges.label(0))
        assertEquals("", TabBadges.label(-2))
        assertEquals("1", TabBadges.label(1))
        assertEquals("99", TabBadges.label(99))
        assertEquals("99+", TabBadges.label(100))
        assertEquals("99+", TabBadges.label(4_200))
    }

    @Test
    fun describesTheTabForTalkBack() {
        assertEquals("Messages", TabBadges.description(false, 0))
        assertEquals("Messages, 3 unread", TabBadges.description(false, 3))
        assertEquals("Phone, 1 missed call", TabBadges.description(true, 1))
        assertEquals("Phone, 120 missed calls", TabBadges.description(true, 120))
    }
}

class UnreadFolderTest {
    @Test
    fun unreadFolderUsesTheBadgeRule() {
        assertEquals(true, TabBadges.unreadThread(true, "demo:Maya Chen", emptySet()))
        assertEquals(false, TabBadges.unreadThread(false, "demo:Maya Chen", emptySet()))
        // Muted chats aren't counted on the badge, so they aren't in Unread either.
        assertEquals(false, TabBadges.unreadThread(true, "demo:Maya Chen", setOf("demo:Maya Chen")))
    }
}
