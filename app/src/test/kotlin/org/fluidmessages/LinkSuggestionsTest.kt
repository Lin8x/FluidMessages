package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suggestions while linking an address: people from your own chats, matched on this phone (LinkSuggestions). */
class LinkSuggestionsTest {
    private val known = listOf(
        LinkSuggestion("Maya Chen", "@maya:example.org", "matrix"),
        LinkSuggestion("Maya Chen", "maya@example.org", "xmpp"),
        LinkSuggestion("Rosa Amaya", "@rosa:example.org", "matrix"),
        LinkSuggestion("Leo Park", "leo.park@example.org", "email"),
        LinkSuggestion("kestrel", "kestrel", "irc"),
        LinkSuggestion("Maya Chen", "@MAYA:example.org", "matrix"),
    )

    @Test fun nothingUntilYouType() {
        assertEquals(emptyList<LinkSuggestion>(), LinkSuggestions.match(known, "", "matrix"))
        assertEquals(emptyList<LinkSuggestion>(), LinkSuggestions.match(known, "   ", null))
    }

    @Test fun matchesNamesAndAddressesOnThatServiceOnly() {
        val matrix = LinkSuggestions.match(known, "maya", "matrix")
        // Same address written twice is one suggestion; XMPP's Maya isn't a Matrix address.
        assertEquals(listOf("@maya:example.org", "@rosa:example.org"), matrix.map { it.address })
        assertTrue(matrix.all { it.service == "matrix" })
        assertEquals(listOf("leo.park@example.org"), LinkSuggestions.match(known, "PARK", "email").map { it.address })
        assertEquals(listOf("kestrel"), LinkSuggestions.match(known, "kes", "irc").map { it.address })
    }

    @Test fun startsWithComesFirstAndAnyServiceWhenNotChosen() {
        val any = LinkSuggestions.match(known, "ma", null)
        assertEquals("Maya Chen", any.first().name)
        assertTrue(any.any { it.service == "xmpp" } && any.any { it.service == "matrix" })
        // Rosa Amaya only contains it, so she comes after the Mayas.
        assertEquals("Rosa Amaya", any.last().name)
    }

    @Test fun aNewAddressIsNotRepeatedBack() {
        // Typing exactly an address doesn't suggest it again; typing a new one finds nothing and still works.
        assertEquals(emptyList<LinkSuggestion>(), LinkSuggestions.match(known, "kestrel", "irc"))
        assertEquals(emptyList<LinkSuggestion>(), LinkSuggestions.match(known, "@new.person:example.org", "matrix"))
        assertEquals(2, LinkSuggestions.match(known, "example", null, limit = 2).size)
    }
}
