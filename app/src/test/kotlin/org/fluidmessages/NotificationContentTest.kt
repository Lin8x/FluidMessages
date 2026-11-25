package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** New-text notifications: what they say, and when they say nothing about the sender or the message. */
class NotificationContentTest {
    private val open = NotificationContent.Guard()

    @Test fun titleIsTheSavedNameOrTheNumber() {
        assertEquals("Sam Rivera", NotificationContent.title("Sam Rivera", "+1 555 0100"))
        assertEquals("+1 555 0100", NotificationContent.title(null, "+1 555 0100"))
        assertEquals("+1 555 0100", NotificationContent.title("  ", "+1 555 0100"))
    }

    @Test fun showsTheNameAndTheMessage() {
        val shown = NotificationContent.build(open, "Sam Rivera", "+1 555 0100", "See you at 7")
        assertTrue(shown.reveals)
        assertEquals("Sam Rivera", shown.title)
        assertEquals("See you at 7", shown.text)
    }

    @Test fun staysPlainWheneverSomethingAsksForPrivacy() {
        val guards = listOf(open.copy(showContent = false), open.copy(appLock = true), open.copy(hidden = true),
            open.copy(chatCode = true), open.copy(decoyHides = true))
        for (guard in guards) {
            assertFalse(guard.toString(), guard.reveals)
            val shown = NotificationContent.build(guard, "Sam Rivera", "+1 555 0100", "See you at 7")
            assertFalse(shown.reveals)
            assertEquals("New text message", shown.title)
            assertEquals("", shown.text)
        }
        assertTrue(open.reveals)
    }

    @Test fun reactionTextsReadNaturally() {
        assertEquals("Sam liked “See you at 7”", NotificationContent.text("Liked “See you at 7”", "Sam Rivera"))
        assertEquals("Sam laughed at “Bring snacks”", NotificationContent.text("Laughed at “Bring snacks”", "Sam"))
        assertEquals("Sam loved an image", NotificationContent.text("Loved an image", "Sam Rivera"))
        assertEquals("Sam reacted 😂 to “Bring snacks”", NotificationContent.text("Reacted 😂 to “Bring snacks”", "Sam Rivera"))
        assertEquals("Sam removed a like from “See you at 7”", NotificationContent.text("Removed a like from “See you at 7”", "Sam Rivera"))
        // Without a saved name the number is already the title, so the text keeps its own wording.
        assertEquals("Liked “See you at 7”", NotificationContent.text("Liked “See you at 7”", null))
    }

    @Test fun ordinaryTextsAreShownAsWritten() {
        assertEquals("Liked the movie a lot", NotificationContent.text("Liked the movie a lot", "Sam Rivera"))
        assertEquals("Running late", NotificationContent.text("  Running late\n", "Sam Rivera"))
        assertEquals(501, NotificationContent.text("a".repeat(900), null).length)
    }
}
