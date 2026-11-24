package org.fluidmessages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.telephony.PhoneNumberUtils

/** One saved detail on a contact: a phone number, email, SIP address, website, address, or birthday. */
data class ContactField(val kind: Kind, val label: String, val value: String) {
    enum class Kind { PHONE, EMAIL, SIP, WEBSITE, ADDRESS, BIRTHDAY }
}

/** Every detail saved on an Android contact, with Android's own labels (mobile, work, home…). */
object ContactFields {
    private val kinds = mapOf(
        Phone.CONTENT_ITEM_TYPE to ContactField.Kind.PHONE, Email.CONTENT_ITEM_TYPE to ContactField.Kind.EMAIL,
        SipAddress.CONTENT_ITEM_TYPE to ContactField.Kind.SIP, Website.CONTENT_ITEM_TYPE to ContactField.Kind.WEBSITE,
        StructuredPostal.CONTENT_ITEM_TYPE to ContactField.Kind.ADDRESS, Event.CONTENT_ITEM_TYPE to ContactField.Kind.BIRTHDAY,
    )

    /** Details for everyone at once, by contact id, in one query. */
    fun all(context: Context): Map<Long, List<ContactField>> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        return query(context, null)
    }

    /** Details for the contact with this phone number, or none. */
    @JvmStatic fun forNumber(context: Context, number: String): List<ContactField> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val id = runCatching {
            context.contentResolver.query(Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(android.provider.ContactsContract.PhoneLookup._ID), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull() ?: return emptyList()
        return query(context, id)[id].orEmpty()
    }

    private fun query(context: Context, contact: Long?): Map<Long, List<ContactField>> {
        val result = linkedMapOf<Long, MutableList<ContactField>>()
        val types = kinds.keys.toList()
        val selection = "${Data.MIMETYPE} IN (${types.joinToString(",") { "?" }})" + (if (contact != null) " AND ${Data.CONTACT_ID} = ?" else "")
        val args = (types + listOfNotNull(contact?.toString())).toTypedArray()
        try {
            context.contentResolver.query(Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.IS_PRIMARY),
                selection, args, "${Data.IS_SUPER_PRIMARY} DESC, ${Data.IS_PRIMARY} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val kind = kinds[cursor.getString(1)] ?: continue
                    val value = cursor.getString(2)?.trim().orEmpty()
                    if (value.isEmpty()) continue
                    val type = cursor.getInt(3)
                    val custom = cursor.getString(4)
                    val resources = context.resources
                    val label = when (kind) {
                        ContactField.Kind.PHONE -> Phone.getTypeLabel(resources, type, custom).toString()
                        ContactField.Kind.EMAIL -> Email.getTypeLabel(resources, type, custom).toString() + " email"
                        ContactField.Kind.SIP -> "SIP"
                        ContactField.Kind.WEBSITE -> "Website"
                        ContactField.Kind.ADDRESS -> StructuredPostal.getTypeLabel(resources, type, custom).toString() + " address"
                        ContactField.Kind.BIRTHDAY -> if (type == Event.TYPE_BIRTHDAY) "Birthday" else Event.getTypeLabel(resources, type, custom).toString()
                    }.replaceFirstChar { it.uppercase() }
                    val list = result.getOrPut(cursor.getLong(0)) { mutableListOf() }
                    val duplicate = list.any { it.kind == kind && (it.value.equals(value, true) ||
                        kind == ContactField.Kind.PHONE && PhoneNumberUtils.compare(it.value, value)) }
                    if (!duplicate) list += ContactField(kind, label, value)
                }
            }
        } catch (_: RuntimeException) {}
        // Phones first, then email, then the rest, the way contact cards usually read.
        return result.mapValues { (_, fields) -> fields.sortedBy { it.kind.ordinal } }
    }

}
