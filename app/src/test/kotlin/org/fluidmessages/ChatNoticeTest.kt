package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat setting notices: the plain text sent when a chat's disappearing timer or encryption changes. */
class ChatNoticeTest {
    @Test fun everyTimerRoundTrips() {
        for (seconds in Disappearing.CHOICES) {
            val text = timerNoticeText(seconds)
            assertEquals(text, ChatNotice.Timer(seconds), parseChatNotice(text))
        }
        assertEquals("⏱️ Disappearing messages are on. New messages disappear after 1 day.", timerNoticeText(86400))
        assertEquals("⏱️ Disappearing messages are off.", timerNoticeText(0))
        assertEquals(ChatNotice.Timer(0), parseChatNotice(timerNoticeText(-1)))
    }

    @Test fun encryptionRoundTrips() {
        assertEquals("🔒 Messages in this chat are now encrypted with FluidMessages.", encryptionNoticeText(true))
        assertEquals(ChatNotice.Encryption(true), parseChatNotice(encryptionNoticeText(true)))
        assertEquals(ChatNotice.Encryption(false), parseChatNotice(encryptionNoticeText(false)))
    }

    @Test fun toleratesOtherAppsButNotLookalikes() {
        // Some apps drop the emoji variation selector or add a trailing newline.
        assertEquals(ChatNotice.Timer(3600), parseChatNotice("⏱ Disappearing messages are on. New messages disappear after 1 hour.\n"))
        assertNull(parseChatNotice("Disappearing messages are off."))
        assertNull(parseChatNotice("⏱️ Disappearing messages are on. New messages disappear after 0 days."))
        assertNull(parseChatNotice("⏱️ Disappearing messages are off. Just kidding"))
        assertNull(parseChatNotice("Reply to “x”\n\n🔒 Messages in this chat are now encrypted with FluidMessages."))
        assertTrue(isChatNotice(timerNoticeText(300)))
        assertFalse(isChatNotice("See you at the lake"))
        assertFalse(isChatNotice(null))
    }
}
