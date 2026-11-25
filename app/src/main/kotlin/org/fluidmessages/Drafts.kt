package org.fluidmessages

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.function.Consumer

/**
 * The message someone started typing and didn't send, kept per chat so it's still there when the chat reopens.
 *
 * Drafts stay on this phone, in their own private file, sealed with the same key as the app's other stored secrets
 * (Privacy.seal). Drafts of chats in the private folder are sealed to the folder's key instead and are only read or
 * written while the folder is open, so they never show in the normal inbox. A draft goes away when it's sent or
 * emptied. Drafts aren't backed up or synced, and they never appear in logs.
 */
internal object Drafts {
    private const val FILE = "drafts"
    private const val KEY = "drafts"
    private const val HIDDEN_KEY = "drafts.hidden"
    const val MAX_CHARS = 4000
    const val MAX_CHATS = 100
    private const val PREVIEW_CHARS = 120

    /** [book] with [address]'s draft set to [text]; blank text removes it. The newest draft is last; the oldest go past [MAX_CHATS]. */
    fun updated(book: Map<String, String>, address: String, text: String): Map<String, String> {
        val next = LinkedHashMap(book)
        next.remove(address)
        if (text.isNotBlank()) next[address] = messageExcerpt(text, MAX_CHARS)
        while (next.size > MAX_CHATS) next.remove(next.keys.first())
        return next
    }

    /** The inbox row's line for a draft: one line, shortened. */
    @JvmStatic
    fun preview(text: String): String = "Draft: " + messageExcerpt(text.trim().replace(Regex("\\s+"), " "), PREVIEW_CHARS)

    /** The drafts the normal inbox may show: never one for a chat that is, or has since become, hidden. */
    fun inboxVisible(book: Map<String, String>, hidden: (String) -> Boolean): Map<String, String> =
        book.filter { (address, text) -> text.isNotBlank() && !hidden(address) }

    private fun file(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun decode(json: String): Map<String, String> = runCatching {
        val stored = JSONObject(json)
        LinkedHashMap<String, String>().apply { for (key in stored.keys()) put(key, stored.optString(key)) }
    }.getOrDefault(emptyMap())

    private fun encode(book: Map<String, String>) = JSONObject().apply { for ((address, text) in book) put(address, text) }.toString()

    private fun readBook(context: Context, prefs: SharedPreferences, hidden: Boolean): Map<String, String>? = runCatching {
        val stored = file(context).getString(if (hidden) HIDDEN_KEY else KEY, "").orEmpty()
        when {
            stored.isEmpty() -> emptyMap()
            hidden -> decode(Privacy.vaultOpen(stored))
            else -> decode(Privacy.open(prefs, stored))
        }
    }.getOrNull()

    private fun writeBook(context: Context, prefs: SharedPreferences, hidden: Boolean, book: Map<String, String>) {
        val key = if (hidden) HIDDEN_KEY else KEY
        runCatching {
            if (book.isEmpty()) file(context).edit().remove(key).apply()
            else file(context).edit().putString(key, if (hidden) Privacy.vaultSeal(prefs, encode(book)) else Privacy.seal(prefs, encode(book))).apply()
        }
    }

    /** The saved draft for this chat, or "". A private-folder chat's draft reads only while the folder is open. */
    @JvmStatic
    fun read(context: Context, prefs: SharedPreferences, address: String, hidden: Boolean): String {
        if (hidden && !Privacy.vaultOpen()) return ""
        val own = readBook(context, prefs, hidden)?.get(address).orEmpty()
        if (!hidden) return own
        // A draft written before the chat moved into the private folder moves with it.
        val earlier = readBook(context, prefs, false)?.get(address).orEmpty()
        if (earlier.isEmpty()) return own
        save(context, prefs, address, "", false)
        if (own.isNotEmpty()) return own
        save(context, prefs, address, earlier, true)
        return earlier
    }

    /** Keeps [text] as this chat's draft; blank removes it. */
    @JvmStatic
    fun save(context: Context, prefs: SharedPreferences, address: String, text: String, hidden: Boolean) {
        if (hidden && !Privacy.vaultOpen()) return
        // A book that can't be read right now is left alone rather than written over.
        val book = readBook(context, prefs, hidden) ?: return
        val next = updated(book, address, text)
        if (next != book) writeBook(context, prefs, hidden, next)
    }

    /** Drafts by chat address for the normal inbox's rows. Private-folder chats are never in it. */
    @JvmStatic
    fun inbox(context: Context, prefs: SharedPreferences): Map<String, String> = runCatching {
        inboxVisible(readBook(context, prefs, false).orEmpty()) { HiddenChats.addressHidden(prefs, it) }
    }.getOrDefault(emptyMap())

    /** Restores this chat's draft into [screen] and keeps what's typed from then on. Made-up decoy chats keep none. */
    @JvmStatic
    fun attach(screen: ConversationScreen, context: Context, prefs: SharedPreferences, address: String, hidden: Boolean) {
        if (address.startsWith(Privacy.DECOY)) return
        val app = context.applicationContext
        var saved = read(app, prefs, address, hidden)
        if (screen.draft.isEmpty() && saved.isNotEmpty()) screen.draft = saved
        screen.saveDraft = Consumer { text ->
            if (text != saved) {
                saved = text
                save(app, prefs, address, text, hidden)
            }
        }
    }
}
