package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typed addresses, read the same way as FluidMessages Web's detectAddress (scripts/test-local runs these cases there). */
class NewAddressTest {
    private val cases = listOf(
        "+1 (202) 555-0143" to listOf("sms" to "+1 (202) 555-0143"),
        "2025550143" to listOf("sms" to "2025550143"),
        "12" to emptyList(),
        "@maya:example.org" to listOf("matrix" to "@maya:example.org"),
        "!garden:example.org" to listOf("matrix" to "!garden:example.org"),
        "#book-club:example.org" to listOf("matrix" to "#book-club:example.org"),
        "maya@example.org" to listOf("email" to "maya@example.org", "xmpp" to "maya@example.org"),
        "Maya@Example.org" to listOf("email" to "maya@example.org", "xmpp" to "maya@example.org"),
        "garden@conference.example.org" to listOf("xmpp" to "garden@conference.example.org", "email" to "garden@conference.example.org"),
        "amir@localhost" to listOf("xmpp" to "amir@localhost"),
        "riley@irc.libera.chat" to listOf("irc" to "riley"),
        "#radio" to listOf("irc" to "#radio"),
        "irc:riley" to listOf("irc" to "riley"),
        "sip:sam@sip.example.org" to listOf("sip" to "sip:sam@sip.example.org"),
        "riley" to listOf("irc" to "riley"),
        "Maya Chen" to emptyList(),
        "" to emptyList(),
    )

    @Test
    fun readsEachFormatLikeTheWeb() {
        for ((text, expected) in cases) {
            assertEquals(text, expected, NewAddress.detect(text).map { it.service to it.target })
        }
    }

    @Test
    fun flagsRoomsNetworksAndGuesses() {
        assertEquals("irc.libera.chat", NewAddress.detect("riley@irc.libera.chat").single().network)
        assertTrue(NewAddress.detect("#radio").single().group)
        assertTrue(NewAddress.detect("!garden:example.org").single().group)
        assertTrue(NewAddress.detect("garden@conference.example.org").first().group)
        assertTrue(NewAddress.detect("riley").single().guess)
        assertEquals(listOf("irc" to "#radio"), NewAddress.detect("irc://irc.libera.chat/#radio").map { it.service to it.target })
        assertEquals("irc.libera.chat", NewAddress.detect("irc://irc.libera.chat/#radio").single().network)
        // Control characters and very long text are nothing.
        assertEquals(emptyList<TypedAddress>(), NewAddress.detect("@maya:exam\nple.org"))
        assertEquals(emptyList<TypedAddress>(), NewAddress.detect("@" + "m".repeat(400) + ":example.org"))
    }

    @Test
    fun newMessageOffersOnlyServicesYouCanUse() {
        val all = setOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)
        assertEquals(listOf("email:maya@example.org", "xmpp:maya@example.org"), addressOptions("maya@example.org", all).map { it.encoded })
        assertEquals(listOf("xmpp:maya@example.org"), addressOptions("maya@example.org", setOf(MessageProtocol.XMPP)).map { it.encoded })
        // The network stays with the nickname, so the chat goes through your account there.
        assertEquals(listOf("irc:riley@irc.libera.chat"), addressOptions("riley@irc.libera.chat", all).map { it.encoded })
        assertEquals(listOf("matrix:@maya:example.org"), addressOptions("@maya:example.org", all).map { it.encoded })
        // Numbers and SIP aren't internet chats.
        assertEquals(emptyList<AddressOption>(), addressOptions("+12025550143", all))
        assertEquals(emptyList<AddressOption>(), addressOptions("sip:sam@sip.example.org", all))
        assertEquals(emptyList<AddressOption>(), addressOptions("@maya:example.org", setOf(MessageProtocol.EMAIL)))
    }
}
