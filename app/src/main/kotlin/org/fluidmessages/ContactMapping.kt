package org.fluidmessages

import java.util.Locale

/** A chat address saved on a contact: service is matrix, xmpp, email, irc, or sip. IRC may carry a network (riley@irc.libera.chat). */
data class ChatAddress(val service: String, val address: String)

/**
 * A contact as the editor and FluidMessages Web know it: a name, phone numbers, and chat addresses. webId and
 * webUpdated say which web contact (main.py web_contacts) it came from, if any. The photo travels separately.
 */
data class ContactDraft(val name: String, val numbers: List<String>, val addresses: List<ChatAddress>,
                        val webId: String = "", val webUpdated: Long = 0)

/**
 * One data row on an Android contact, by its ContactsContract column names: mimetype, data1 (the value), data2 (the
 * type), and for chat accounts data5 (the protocol) and data6 (a custom protocol's name). id is the row's _ID when
 * it was read from the phone.
 */
data class ContactRow(val mime: String, val value: String, val type: Int = 0, val protocol: Int? = null,
                      val custom: String? = null, val id: Long = 0) {
    /** What makes two rows the same detail, whatever their label: kind, protocol, and value. */
    val key: String get() = mime + "\u001f" + (protocol ?: "") + "\u001f" + (custom?.lowercase(Locale.ROOT) ?: "") + "\u001f" +
        if (mime == ContactMapping.PHONE) value.filter { it.isDigit() || it == '+' }.let { if (it.length > 10) it.takeLast(10) else it }
        else value.trim().lowercase(Locale.ROOT)
}

/** What saving a draft changes on an existing contact: rows to delete, rows to add, and whether the name changed. */
data class ContactPlan(val delete: List<Long>, val insert: List<ContactRow>, val rename: Boolean)

/**
 * The mapping between contacts and Android's ContactsContract rows. Phone numbers are Phone rows; email addresses are
 * Email rows; SIP addresses are SipAddress rows; XMPP is an Im row with the Jabber protocol; Matrix and IRC are Im rows
 * with a custom protocol named "Matrix" or "IRC", which other contact apps show as is. Pure, so it's tested on the JVM
 * (ContactMappingTest.kt); ContactWriter applies it.
 */
object ContactMapping {
    // ContactsContract's values, spelled out so this stays plain Kotlin.
    const val NAME = "vnd.android.cursor.item/name"
    const val PHONE = "vnd.android.cursor.item/phone_v2"
    const val EMAIL = "vnd.android.cursor.item/email_v2"
    const val IM = "vnd.android.cursor.item/im"
    const val SIP = "vnd.android.cursor.item/sip_address"
    const val PHOTO = "vnd.android.cursor.item/photo"
    const val TYPE_MOBILE = 2
    const val TYPE_OTHER = 3
    const val PROTOCOL_CUSTOM = -1
    const val PROTOCOL_JABBER = 7
    /** Every kind of row the editor owns; anything else on a contact (birthdays, addresses, notes) is left alone. */
    val MANAGED = setOf(PHONE, EMAIL, IM, SIP)
    val SERVICES = listOf("matrix", "xmpp", "email", "irc", "sip")

    private val numberPattern = Regex("\\+?[0-9][0-9 ().-]{2,39}")

    /** A cleaned draft, the way the server cleans a web contact: trimmed, deduplicated, and only valid values. */
    fun clean(name: String, numbers: List<String>, addresses: List<ChatAddress>, webId: String = "", webUpdated: Long = 0): ContactDraft {
        val cleanNumbers = numbers.map { it.trim() }.filter { numberPattern.matches(it) && it.count(Char::isDigit) >= 3 }
            .distinctBy { number -> number.filter { it.isDigit() || it == '+' } }.take(10)
        val cleanAddresses = addresses.mapNotNull { item ->
            var value = item.address.trim()
            if (item.service !in SERVICES || value.isEmpty() || value.length > 320 || value.any { it < ' ' }) return@mapNotNull null
            if (item.service == "sip") value = value.removePrefix("sip:").removePrefix("SIP:")
            if (item.service == "xmpp" || item.service == "email") value = value.lowercase(Locale.ROOT)
            ChatAddress(item.service, value)
        }.distinct().take(20)
        return ContactDraft(name.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(100), cleanNumbers, cleanAddresses,
            webId, webUpdated)
    }

    /** The data rows for a draft's numbers and addresses. */
    fun rows(draft: ContactDraft): List<ContactRow> =
        draft.numbers.map { ContactRow(PHONE, it, TYPE_MOBILE) } + draft.addresses.map(::row)

    fun row(address: ChatAddress): ContactRow = when (address.service) {
        "email" -> ContactRow(EMAIL, address.address, TYPE_OTHER)
        "sip" -> ContactRow(SIP, address.address, TYPE_OTHER)
        "xmpp" -> ContactRow(IM, address.address, TYPE_OTHER, PROTOCOL_JABBER)
        "matrix" -> ContactRow(IM, address.address, TYPE_OTHER, PROTOCOL_CUSTOM, "Matrix")
        else -> ContactRow(IM, address.address, TYPE_OTHER, PROTOCOL_CUSTOM, "IRC")
    }

    /** The chat address a saved row stands for, or null for numbers and rows FluidMessages doesn't chat on. */
    fun address(row: ContactRow): ChatAddress? = when {
        row.value.isBlank() -> null
        row.mime == EMAIL -> ChatAddress("email", row.value.trim())
        row.mime == SIP -> ChatAddress("sip", row.value.trim().removePrefix("sip:"))
        row.mime != IM -> null
        row.protocol == PROTOCOL_JABBER -> ChatAddress("xmpp", row.value.trim())
        row.protocol == PROTOCOL_CUSTOM && row.custom.equals("Matrix", true) -> ChatAddress("matrix", row.value.trim())
        row.protocol == PROTOCOL_CUSTOM && row.custom.equals("IRC", true) -> ChatAddress("irc", row.value.trim())
        // A Matrix ID saved under another app's own label still reads as Matrix.
        row.protocol == PROTOCOL_CUSTOM && NewAddress.detect(row.value).firstOrNull()?.service == "matrix" -> ChatAddress("matrix", row.value.trim())
        else -> null
    }

    /** A draft from a contact's name and saved rows, for Edit. */
    fun draft(name: String, rows: List<ContactRow>): ContactDraft = ContactDraft(name,
        rows.filter { it.mime == PHONE && it.value.isNotBlank() }.map { it.value.trim() }
            .distinctBy { row -> ContactRow(PHONE, row).key },
        rows.mapNotNull(::address).distinct())

    /**
     * What saving [draft] over a contact's [existing] rows changes. Rows already there stay untouched, so their labels
     * (work, home…) stay too; new ones are added; rows the draft no longer has are deleted only when [removable]
     * says so. The editor may remove anything it showed; a web contact only removes what it saved before; merging
     * into a contact you already had removes nothing.
     */
    fun plan(currentName: String, existing: List<ContactRow>, draft: ContactDraft, removable: (ContactRow) -> Boolean = { true }): ContactPlan {
        val wanted = rows(draft)
        val wantedKeys = wanted.map { it.key }.toSet()
        val haveKeys = existing.filter { it.mime in MANAGED }.map { it.key }.toSet()
        val delete = existing.filter { it.mime in MANAGED && it.key !in wantedKeys && removable(it) }.map { it.id }
        val insert = wanted.filter { it.key !in haveKeys }.distinctBy { it.key }
        return ContactPlan(delete, insert, draft.name.isNotBlank() && draft.name != currentName)
    }

    /** The keys of the rows a draft saves, kept to know what a web contact may remove next time. */
    fun keys(draft: ContactDraft): Set<String> = rows(draft).map { it.key }.toSet()
}
