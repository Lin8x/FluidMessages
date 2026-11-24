package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contacts as Android rows: what New contact, Edit, and contacts from FluidMessages Web save. Fictional people only. */
class ContactMappingTest {
    private val rowan = ContactDraft("Rowan Hale", listOf("+1 202 555 0177"), listOf(
        ChatAddress("matrix", "@rowan:example.org"), ChatAddress("xmpp", "rowan@example.org"), ChatAddress("email", "rowan@example.org"),
        ChatAddress("irc", "rowan@irc.libera.chat"), ChatAddress("sip", "rowan@sip.example.org")))

    @Test
    fun eachDetailBecomesTheRightRow() {
        val rows = ContactMapping.rows(rowan)
        assertEquals(ContactRow(ContactMapping.PHONE, "+1 202 555 0177", ContactMapping.TYPE_MOBILE), rows[0])
        assertEquals(ContactRow(ContactMapping.IM, "@rowan:example.org", ContactMapping.TYPE_OTHER, ContactMapping.PROTOCOL_CUSTOM, "Matrix"), rows[1])
        assertEquals(ContactRow(ContactMapping.IM, "rowan@example.org", ContactMapping.TYPE_OTHER, ContactMapping.PROTOCOL_JABBER), rows[2])
        assertEquals(ContactRow(ContactMapping.EMAIL, "rowan@example.org", ContactMapping.TYPE_OTHER), rows[3])
        assertEquals(ContactRow(ContactMapping.IM, "rowan@irc.libera.chat", ContactMapping.TYPE_OTHER, ContactMapping.PROTOCOL_CUSTOM, "IRC"), rows[4])
        assertEquals(ContactRow(ContactMapping.SIP, "rowan@sip.example.org", ContactMapping.TYPE_OTHER), rows[5])
        // These are ContactsContract's own values.
        assertEquals("vnd.android.cursor.item/phone_v2", ContactMapping.PHONE)
        assertEquals("vnd.android.cursor.item/im", ContactMapping.IM)
        assertEquals("vnd.android.cursor.item/sip_address", ContactMapping.SIP)
    }

    @Test
    fun rowsReadBackAsTheSameContact() {
        val read = ContactMapping.draft("Rowan Hale", ContactMapping.rows(rowan).mapIndexed { index, row -> row.copy(id = index + 1L) } +
            // Rows FluidMessages doesn't chat on, and another app's Matrix label, read sensibly.
            ContactRow(ContactMapping.IM, "rowan.aim", 3, 0) + ContactRow(ContactMapping.IM, "@rh:example.net", 3, -1, "Element"))
        assertEquals(rowan.numbers, read.numbers)
        assertEquals(rowan.addresses + ChatAddress("matrix", "@rh:example.net"), read.addresses)
        assertNull(ContactMapping.address(ContactRow(ContactMapping.PHONE, "+12025550177")))
        assertEquals(ChatAddress("sip", "sam@sip.example.org"), ContactMapping.address(ContactRow(ContactMapping.SIP, "sip:sam@sip.example.org")))
    }

    @Test
    fun cleaningMatchesTheServer() {
        val clean = ContactMapping.clean("  Rowan   Hale ", listOf(" +1 202 555 0177", "+1 (202) 555-0177", "call me", "12", ""),
            listOf(ChatAddress("xmpp", "Rowan@Example.org"), ChatAddress("xmpp", "rowan@example.org"), ChatAddress("sip", "sip:sam@sip.example.org"),
                ChatAddress("fax", "x"), ChatAddress("matrix", " "), ChatAddress("matrix", "@a:\nb")), "wc-0123456789abcdef", 5)
        assertEquals("Rowan Hale", clean.name)
        assertEquals(listOf("+1 202 555 0177"), clean.numbers)
        assertEquals(listOf(ChatAddress("xmpp", "rowan@example.org"), ChatAddress("sip", "sam@sip.example.org")), clean.addresses)
        assertEquals("wc-0123456789abcdef", clean.webId)
        assertEquals(5L, clean.webUpdated)
    }

    @Test
    fun editingKeepsUntouchedRowsAndTheirLabels() {
        val existing = listOf(
            ContactRow(ContactMapping.PHONE, "(202) 555-0177", 3 /* work */, id = 10),
            ContactRow(ContactMapping.PHONE, "+1 202 555 0100", 2, id = 11),
            ContactRow(ContactMapping.IM, "@rowan:example.org", 3, -1, "matrix", id = 12),
            ContactRow("vnd.android.cursor.item/contact_event", "2001-04-05", 3, id = 13))
        val plan = ContactMapping.plan("Rowan Hale", existing, ContactDraft("Rowan Hale", listOf("+1 202 555 0177"),
            listOf(ChatAddress("matrix", "@rowan:example.org"), ChatAddress("xmpp", "rowan@example.org"))))
        // The work number is the same phone, so it stays with its label; the other number goes; the birthday is never touched.
        assertEquals(listOf(11L), plan.delete)
        assertEquals(listOf(ContactRow(ContactMapping.IM, "rowan@example.org", 3, ContactMapping.PROTOCOL_JABBER)), plan.insert)
        assertFalse(plan.rename)
        assertTrue(ContactMapping.plan("Rowan Hale", existing, rowan.copy(name = "Rowan Hale-Ortiz")).rename)
    }

    @Test
    fun aWebContactOnlyRemovesWhatItSavedBefore() {
        val before = ContactDraft("Rowan Hale", listOf("+1 202 555 0177"), listOf(ChatAddress("matrix", "@rowan:example.org")))
        val keys = ContactMapping.keys(before)
        // On the phone you added a home number; on the web the Matrix address was removed and XMPP added.
        val existing = ContactMapping.rows(before).mapIndexed { index, row -> row.copy(id = index + 1L) } +
            ContactRow(ContactMapping.PHONE, "+1 202 555 0199", 1, id = 9)
        val after = before.copy(addresses = listOf(ChatAddress("xmpp", "rowan@example.org")))
        val plan = ContactMapping.plan("Rowan Hale", existing, after) { it.key in keys }
        assertEquals(listOf(2L), plan.delete)
        assertEquals(listOf(ContactRow(ContactMapping.IM, "rowan@example.org", 3, ContactMapping.PROTOCOL_JABBER)), plan.insert)
        // Joining someone you already had removes nothing.
        assertEquals(emptyList<Long>(), ContactMapping.plan("Rowan", existing, after) { false }.delete)
    }
}
