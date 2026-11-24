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
        assertEquals("⏱️ Disappearing messages are on. New messages disappear after 1 day.", chatNoticeLabel(ChatNotice.Timer(86400)))
        assertTrue(timerNoticeText(86400).startsWith("⏱️ Disappearing messages are on. New messages disappear after 1 day.\n\nSent by FluidMessages."))
        assertTrue(timerNoticeText(0).startsWith("⏱️ Disappearing messages are off.\n\n"))
        assertEquals(ChatNotice.Timer(0), parseChatNotice(timerNoticeText(-1)))
    }

    @Test fun encryptionRoundTrips() {
        assertEquals("🔒 Messages in this chat are now encrypted with FluidMessages.\n\n" +
            "You'll need FluidMessages to read encrypted messages: https://github.com/Lin8x/FluidMessages", encryptionNoticeText(true))
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

    @Test fun everySentNoticeTellsOtherAppsAndKeepsTheNoteShort() {
        val notices = Disappearing.CHOICES.map { ChatNotice.Timer(it) } + ChatNotice.Timer(0) +
            ChatNotice.Encryption(true) + ChatNotice.Encryption(false)
        for (notice in notices) {
            val sent = chatNoticeText(notice)
            assertTrue(sent, sent.contains("FluidMessages", ignoreCase = false) && sent.lines().size == 3)
            assertFalse(chatNoticeLabel(notice).contains('\n'))
            assertFalse(chatNoticeLabel(notice).contains(FLUID_LINK))
            assertEquals(notice, parseChatNotice(sent))
        }
        assertTrue(encryptionNoticeText(true).contains("You'll need FluidMessages to read encrypted messages"))
    }

    @Test fun oldAndNewFormatsReadTheSame() {
        // Before the line for other apps, notices were the sentence alone.
        assertEquals(ChatNotice.Timer(86400), parseChatNotice("⏱️ Disappearing messages are on. New messages disappear after 1 day."))
        assertEquals(ChatNotice.Encryption(true), parseChatNotice("🔒 Messages in this chat are now encrypted with FluidMessages."))
        assertEquals(ChatNotice.Encryption(false), parseChatNotice("🔓 Messages in this chat are no longer encrypted with FluidMessages."))
        assertEquals(ChatNotice.Timer(0), parseChatNotice("⏱️ Disappearing messages are off."))
        // Carriers and other apps can turn the line breaks into CRLF.
        assertEquals(ChatNotice.Encryption(true), parseChatNotice(encryptionNoticeText(true).replace("\n", "\r\n")))
        // Another notice's line, or anything else after the note, isn't a notice.
        assertNull(parseChatNotice(chatNoticeLabel(ChatNotice.Encryption(true)) + "\n\n" + chatNoticeFooter(ChatNotice.Timer(0))))
        assertNull(parseChatNotice("🔒 Messages in this chat are now encrypted with FluidMessages.\n\nlol ok"))
    }
}
