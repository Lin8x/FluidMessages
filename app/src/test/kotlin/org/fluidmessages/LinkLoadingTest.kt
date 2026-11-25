package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link previews and linked pictures only load by themselves for your own messages and saved contacts. */
class LinkLoadingTest {
    @Test fun strangersLinksWaitForATap() {
        assertFalse(LinkLoading.auto(outgoing = false, senderSaved = false, sample = false))
    }

    @Test fun yourOwnMessagesAndSavedContactsLoadByThemselves() {
        assertTrue(LinkLoading.auto(outgoing = true, senderSaved = false, sample = false))
        assertTrue(LinkLoading.auto(outgoing = false, senderSaved = true, sample = false))
        // Sample chats are made up on the phone and fetch nothing.
        assertTrue(LinkLoading.auto(outgoing = false, senderSaved = false, sample = true))
    }

    @Test fun inAGroupTextOnlySavedPeopleCount() {
        val members = listOf(GroupMember("Sam Rivera", "+1 555 0100"), GroupMember("+1 555 0101", "+1 555 0101"), GroupMember("Avery Chen"))
        assertEquals(setOf("Sam Rivera"), LinkLoading.savedNames(members) { it == "+1 555 0100" })
    }
}
