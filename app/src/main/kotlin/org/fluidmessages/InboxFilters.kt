package org.fluidmessages

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import org.json.JSONArray

/**
 * The inbox filter menu at the upper trailing edge, the same as FluidMessages Web (server/inbox-filters.js).
 * Settings → Messaging → Inbox filters picks which filters the menu shows and in what order, stored as an ordered list
 * of ids where a hidden one has a "-" in front. Inbox is always shown. Mirrored to FluidMessages Web as
 * appearance.inboxFilters.
 *
 * Filters apply within a scope: the inbox (never the private folder's chats), the open private folder (only its
 * chats), a decoy inbox (only the decoy chats, with no favorites), or the sample inbox (only the sample chats).
 */
internal object InboxFilters {
    const val PREF = "inboxFilters"

    data class Filter(val id: String, val label: String, val about: String, val service: String = "")

    val ALL = listOf(
        Filter("inbox", "Inbox", "Every chat that isn't archived or blocked"),
        Filter("unread", "Unread", "Chats with messages you haven't read"),
        Filter("favorites", "Favorites", "Chats with people you starred as favorites"),
        Filter("groups", "Groups", "Group texts, channels, and rooms"),
        Filter("direct", "Direct messages", "One-to-one chats"),
        Filter("texts", "Texts", "SMS, MMS, and RCS", "SMS"),
        Filter("matrix", "Matrix", "Matrix chats and rooms", "MATRIX"),
        Filter("xmpp", "XMPP", "XMPP chats and group chats", "XMPP"),
        Filter("email", "Email", "Email conversations", "EMAIL"),
        Filter("irc", "IRC", "IRC channels and direct messages", "IRC"),
        Filter("archived", "Archived", "Chats you archived"),
        Filter("blocked", "Blocked", "Chats you blocked"),
    )

    data class Entry(val id: String, val shown: Boolean)

    /** Where filters apply. */
    enum class Scope { INBOX, PRIVATE, DECOY, SAMPLE }

    /** What a filter reads about one chat. [services] are protocol families ("SMS", "MATRIX", …). */
    data class Chat(
        val group: Boolean,
        val favorite: Boolean = false,
        val services: Set<String> = setOf("SMS"),
        val unread: Boolean = false,
        val archived: Boolean = false,
        val blocked: Boolean = false,
        /** In the private folder. */
        val hidden: Boolean = false,
        /** A decoy chat, or one allowed in decoy mode. */
        val decoy: Boolean = false,
    )

    fun of(id: String): Filter? = ALL.firstOrNull { it.id == id }
    fun byLabel(label: String): Filter? = ALL.firstOrNull { it.label == label }
    /** The filter a menu label picks; anything else (Inbox, Private folder) is Inbox. */
    fun idOf(label: String): String = byLabel(label)?.id ?: "inbox"

    /** Every known filter once, in the stored order; unknown ids dropped, missing ones appended shown. */
    fun parse(stored: List<String>): List<Entry> {
        val seen = HashSet<String>()
        val order = ArrayList<Entry>()
        for (item in stored) {
            val id = item.removePrefix("-")
            if (of(id) == null || !seen.add(id)) continue
            order += Entry(id, id == "inbox" || !item.startsWith("-"))
        }
        for (filter in ALL) if (seen.add(filter.id)) order += Entry(filter.id, true)
        return order
    }

    fun encode(order: List<Entry>): List<String> = order.map { (if (it.shown || it.id == "inbox") "" else "-") + it.id }

    @JvmStatic
    fun read(prefs: SharedPreferences): List<Entry> = parse(try {
        val array = JSONArray(prefs.getString(PREF, "[]"))
        (0 until array.length()).map(array::getString)
    } catch (error: Exception) { emptyList() })

    fun save(prefs: SharedPreferences, order: List<Entry>) {
        prefs.edit().putString(PREF, JSONArray(encode(order)).toString()).apply()
    }

    @JvmStatic
    fun stored(prefs: SharedPreferences): JSONArray = JSONArray(encode(read(prefs)))

    /** [order] with the entry at [index] moved by [delta], or unchanged at either end. */
    fun move(order: List<Entry>, index: Int, delta: Int): List<Entry> {
        val target = index + delta
        if (index !in order.indices || target !in order.indices) return order
        return order.toMutableList().also { val item = it.removeAt(index); it.add(target, item) }
    }

    fun show(order: List<Entry>, id: String, shown: Boolean) = order.map { if (it.id == id) it.copy(shown = shown || id == "inbox") else it }

    /** The menu's labels: shown filters in order; a service's only when you have chats or an account there. */
    @JvmStatic
    fun menu(order: List<Entry>, services: Set<String>): List<String> = order
        .filter { entry -> entry.shown && of(entry.id)!!.service.let { it.isEmpty() || it in services } }
        .map { of(it.id)!!.label }

    /** Archived and blocked chats show only under their own filter. */
    fun place(id: String) = if (id == "archived" || id == "blocked") id else "inbox"

    /** Whether [chat] shows under filter [id] in [scope]. */
    @JvmStatic
    fun shows(scope: Scope, id: String, chat: Chat): Boolean {
        when (scope) {
            Scope.PRIVATE -> if (!chat.hidden) return false
            Scope.DECOY -> if (!chat.decoy || chat.hidden) return false
            else -> if (chat.hidden) return false
        }
        val placed = when (place(id)) {
            "blocked" -> chat.blocked
            "archived" -> chat.archived && !chat.blocked
            else -> !chat.archived && !chat.blocked
        }
        return placed && matches(scope, id, chat)
    }

    /** The filter's own rule, without the folder. Favorites never match in a decoy inbox. */
    fun matches(scope: Scope, id: String, chat: Chat): Boolean {
        val filter = of(id) ?: return true
        if (filter.service.isNotEmpty()) return filter.service in chat.services
        return when (id) {
            "unread" -> chat.unread
            "favorites" -> scope != Scope.DECOY && chat.favorite
            "groups" -> chat.group
            "direct" -> !chat.group
            else -> true
        }
    }

    /** Whether a message of [family] keeps its chat under filter [id]: the service filters pick their own messages. */
    @JvmStatic
    fun keepsMessage(id: String, family: String): Boolean = of(id)?.service?.let { it.isEmpty() || it == family } ?: true

    /** The inbox's title for filter [id]: Messages for Inbox, otherwise the filter's name. */
    @JvmStatic
    fun title(id: String) = if (id == "inbox") "Messages" else of(id)?.label ?: "Messages"
}

/** The Phone tab's favorites (PhoneTab), as their keys: "contact:<id>", "chat:<address>", or "demo:<name>" in the sample. */
internal fun favoriteKeys(prefs: SharedPreferences, sample: Boolean): Set<String> = try {
    val array = JSONArray(prefs.getString(if (sample) "samplePhoneFavorites" else "phoneFavorites", "[]"))
    (0 until array.length()).map(array::getString).toSet()
} catch (error: Exception) { emptySet() }

/** The chats favorites reach: each starred contact's phone numbers, and each starred chat's address. */
internal fun favoriteAddresses(context: Context, keys: Set<String>): List<String> {
    val result = keys.filter { it.startsWith("chat:") }.map { it.removePrefix("chat:") }.toMutableList()
    val ids = keys.mapNotNull { it.removePrefix("contact:").takeIf { _ -> it.startsWith("contact:") }?.toLongOrNull() }
    if (ids.isEmpty() || context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return result
    val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    try {
        context.contentResolver.query(phone, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " IN (" + ids.joinToString(",") + ")", null, null)?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(result::add)
        }
    } catch (error: RuntimeException) { /* contacts unavailable */ }
    return result
}

/** Whether a chat's address is one of [favorites]: the same address, or the same phone number. */
internal fun favoriteChat(address: String, favorites: List<String>): Boolean = favorites.any { favorite ->
    favorite == address || SmsStore.validRecipient(address) && SmsStore.validRecipient(favorite) && PhoneNumberUtils.compare(favorite, address)
}
