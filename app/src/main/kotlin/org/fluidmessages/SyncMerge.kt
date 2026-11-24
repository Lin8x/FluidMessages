package org.fluidmessages

import java.util.Locale

/**
 * Combining this phone's settings with FluidMessages Web's (server/sync_merge.py keeps the web's side).
 *
 * Each side lists what it keeps as items: a key like "folder:+15550100" or "look:theme" and a short text value. No
 * item means the default (in no folder, the Fluid theme, no timer). The last values both sides agreed on are the
 * baseline. For each key:
 * - Same on both: nothing to do.
 * - Changed on one side only since the baseline: that side's value goes to the other.
 * - No baseline yet (first pairing): an item on one side only is copied to the other, so pinned on one and pinned on
 *   both stays pinned, and a sticker on one shows up on both.
 * - Different on both: a conflict, kept for the person to choose. Nothing changes for that key until then.
 * Accounts are compared by their public address only and never copied: choosing a side decides who connects.
 * With several accounts per service, each has its own item (AccountKeys): only the same account connected on both
 * sides is a conflict, and the older one-per-service item is left out whenever the web lists per-account ones.
 */
internal data class SyncConflict(val key: String, val phone: String?, val web: String?, val phoneTime: Long = 0, val webTime: Long = 0)

internal data class SyncDecision(val phone: String?, val web: String?, val pick: String)

internal data class SyncMergeResult(
    /** Values to write on this phone; null means back to the default. */
    val applyPhone: Map<String, String?>,
    /** Values for FluidMessages Web. */
    val applyWeb: Map<String, String?>,
    /** What both sides agree on after this merge, the next baseline. */
    val baseline: Map<String, String>,
    val conflicts: List<SyncConflict>,
)

internal object SyncMerge {
    const val PHONE = "phone"
    const val WEB = "web"

    /** Groups that follow the "use the phone's settings" choice; with the web's own chosen, each side keeps its own. */
    val settingsGroups = setOf("look", "fun", "pref", "timer", "wallpaper")
    private val folders = listOf("pinned", "archived", "blocked")

    /**
     * Item groups newer than the merge, exchanged only when both sides say they understand them (server/sync_merge.py
     * OPTIONAL): timed mutes, inbox and messaging choices ("pref:"), favorites ("favorite:<address>" = "on"), and
     * message requests ("request:<address>" and "requests:<setting>", MessageRequests).
     */
    val requestGroups = listOf("request", "requests")
    val optionalGroups = listOf("muteuntil", "pref", "favorite") + requestGroups

    /**
     * The groups both sides understand, from FluidMessages Web's answer: its "items", or "mute_until" from before; and
     * the message request groups when it answers "requests": 1, as the first FluidMessages Web with requests did.
     */
    fun sharedGroups(items: List<String>?, muteUntil: Boolean, requests: Boolean = false): Set<String> =
        ((items ?: if (muteUntil) listOf("muteuntil") else emptyList()) + (if (requests) requestGroups else emptyList()))
            .filter { it in optionalGroups }.toSet()

    /** Whether [key] takes part in a merge where both sides understand [shared]. */
    fun understood(key: String, shared: Set<String>) = group(key) !in optionalGroups || group(key) in shared

    private val phoneNumber = Regex("\\+?[0-9(][0-9 ().-]{1,38}")

    /**
     * A favorite as an item name: a phone number as its digits (and a leading +), anything else as it is, so one
     * number is one item on both sides (server/sync_merge.py favorite_address).
     */
    fun favoriteAddress(text: String): String {
        val trimmed = text.trim()
        if (phoneNumber.matches(trimmed) && trimmed.count { it.isDigit() } >= 3) {
            return (if (trimmed.startsWith("+")) "+" else "") + trimmed.filter { it.isDigit() }
        }
        return trimmed
    }

    fun group(key: String) = key.substringBefore(':')
    fun name(key: String) = key.substringAfter(':')

    fun merge(
        phone: Map<String, String>,
        web: Map<String, String>,
        baseline: Map<String, String>?,
        decisions: Map<String, SyncDecision> = emptyMap(),
        inScope: (String) -> Boolean = { true },
    ): SyncMergeResult {
        val applyPhone = linkedMapOf<String, String?>()
        val applyWeb = linkedMapOf<String, String?>()
        val conflicts = mutableListOf<SyncConflict>()
        // Keys left out of this merge keep their baseline.
        val next = (baseline ?: emptyMap()).toMutableMap()
        fun agree(key: String, value: String?) { if (value == null) next.remove(key) else next[key] = value }
        val keys = (phone.keys + web.keys + baseline.orEmpty().keys).toSortedSet()
        val perAccount = AccountKeys.perAccountServices(web.keys)
        for (key in keys) {
            if (!inScope(key)) continue
            val p = phone[key]
            val w = web[key]
            if (group(key) == "account") {
                next.remove(key)
                // A newer web lists each account; different accounts on each side then simply both connect.
                if (AccountKeys.perAccount(key) == null && name(key) in perAccount) continue
                val decided = decisions[key]
                if (p != null && w != null && p != w && (decided == null || decided.phone != p || decided.web != w)) {
                    conflicts += SyncConflict(key, p, w)
                }
                continue
            }
            if (p == w) { agree(key, p); continue }
            if (baseline != null) {
                val b = baseline[key]
                when {
                    p == b -> { applyPhone[key] = w; agree(key, w) }
                    w == b -> { applyWeb[key] = p; agree(key, p) }
                    else -> conflicts += SyncConflict(key, p, w)
                }
                continue
            }
            val wider = if (group(key) == "folder") widerFolder(p, w) else null
            when {
                p == null -> { applyPhone[key] = w; agree(key, w) }
                w == null -> { applyWeb[key] = p; agree(key, p) }
                // Pinned on one side, pinned and archived on the other: both get pinned and archived.
                wider == w -> { applyPhone[key] = w; agree(key, w) }
                wider == p -> { applyWeb[key] = p; agree(key, p) }
                else -> conflicts += SyncConflict(key, p, w)
            }
        }
        return SyncMergeResult(applyPhone, applyWeb, next, conflicts)
    }

    /** The folder set that contains the other, if one does. */
    private fun widerFolder(a: String?, b: String?): String? {
        val left = folderSet(a)
        val right = folderSet(b)
        return when {
            left.containsAll(right) -> a
            right.containsAll(left) -> b
            else -> null
        }
    }

    fun folderSet(value: String?) = value.orEmpty().split('+').filter { it in folders }.toSet()

    fun folderValue(set: Collection<String>): String? = folders.filter { it in set }.sorted().joinToString("+").ifEmpty { null }

    /** Phone for every conflict, or web for every one. */
    fun all(conflicts: List<SyncConflict>, side: String) = conflicts.associate { it.key to side }

    /** The chosen value of each conflict, by key, filling in the phone for any not chosen. */
    fun chosen(conflicts: List<SyncConflict>, choices: Map<String, String>): Map<String, String?> =
        conflicts.associate { it.key to if (choices[it.key] == WEB) it.web else it.phone }

    /** What this phone has to change once the choices are in: only the conflicts settled for the web. */
    fun phoneChanges(conflicts: List<SyncConflict>, choices: Map<String, String>): Map<String, String?> =
        conflicts.filter { choices[it.key] == WEB }.associate { it.key to it.web }

    // Words for the conflict screen

    private val lookNames = mapOf(
        "theme" to "Color theme", "mode" to "Light or dark", "radius" to "Bubble corners", "size" to "Text size",
        "padding" to "Bubble padding", "wallpaper" to "Wallpaper", "searchPosition" to "Search position",
        "toolbarFinish" to "Toolbar finish", "density" to "Inbox density", "tails" to "Bubble tails",
        "chromeOpacity" to "Toolbar opacity", "speed" to "Motion", "exactTimes" to "Exact times",
        "combineProtocols" to "Combine linked conversations", "tagMode" to "Inbox protocol tags", "encryptionMode" to "Encryption labels")
    private val prefNames = mapOf("tabCounts" to "Counts on tabs", "showCommunityChats" to "Show community chats",
        "communitySort" to "Communities order", "inboxFilters" to "Inbox filters", "announceSettings" to "Announce setting changes")
    private val funNames = mapOf("effects" to "Message effects", "reactions" to "Reaction effects", "games" to "Games",
        "random" to "Random picks", "gifs" to "Pictures and GIFs from links", "linkPreviews" to "Link previews")

    private val requestNames = mapOf("texts" to "Filter unknown senders (texts)", "chats" to "Filter unknown senders (chat services)",
        "notify" to "Request notifications", "automatedOut" to "Keep automated messages out of the inbox",
        "automatedQuiet" to "Don't notify for automated messages", "automatedBlock" to "Block automated senders")

    /** The heading for a key's group, and the item's own title. [person] names a chat by its address. */
    fun describe(key: String, person: (String) -> String): Pair<String, String> {
        val name = name(key)
        return when (group(key)) {
            "look" -> "Appearance" to (lookNames[name] ?: name)
            "fun" -> "Fun and effects" to (funNames[name] ?: name)
            "folder" -> "Chat folders" to person(name)
            "muted" -> "Muted chats" to person(name)
            "muteuntil" -> "How long chats are muted" to person(name)
            "favorite" -> "Favorites" to person(name)
            "pref" -> "Messaging" to (prefNames[name] ?: name)
            "sender" -> "Automated senders" to person(name)
            "timer" -> "Disappearing messages" to if (name == "*") "Default timer" else person(name)
            "wallpaper" -> "Chat wallpaper" to person(name)
            "gif" -> "GIF sites" to name
            "gifoff" -> "GIF sites" to name.replaceFirstChar { it.titlecase(Locale.ROOT) }
            "pay" -> "Payments" to if (name == "*") "Default way to pay" else name
            "sticker" -> "Stickers" to "Sticker"
            "account" -> "Chat accounts" to (AccountKeys.perAccount(key)?.let { (service, address) ->
                service.replaceFirstChar { it.titlecase(Locale.ROOT) } + " · " + address } ?: name.replaceFirstChar { it.titlecase(Locale.ROOT) })
            "protocol" -> "Chat services" to name.replaceFirstChar { it.titlecase(Locale.ROOT) }
            "request" -> "Message requests" to person(name)
            "requests" -> "Requests and spam" to (requestNames[name] ?: name)
            else -> "Other" to key
        }
    }

    /** A value in words. No value is each setting's default. */
    fun valueLabel(key: String, value: String?): String {
        val name = name(key)
        return when (group(key)) {
            "look" -> when {
                value == null -> when (name) { "theme" -> "Fluid"; "mode" -> "Automatic"; else -> "Default" }
                name == "theme" -> value.replaceFirstChar { it.titlecase(Locale.ROOT) }
                value == "true" -> "On"
                value == "false" -> "Off"
                else -> value.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            "fun", "protocol" -> if (value == "off") "Off" else "On"
            "gifoff" -> if (value == "off") "Hidden" else "Shown"
            "folder" -> folderSet(value).let { set ->
                if (set.isEmpty()) "Inbox" else folders.filter { it in set }.joinToString(", ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
            }
            "muted" -> if (value == "on") "Muted" else "Alerts on"
            "favorite" -> if (value == "on") "Favorite" else "Not a favorite"
            "pref" -> when (name) {
                "communitySort" -> Communities.Sort.parse(value).label
                "inboxFilters" -> value?.split(',')?.filter { it.isNotEmpty() && !it.startsWith("-") }
                    ?.joinToString(", ") { InboxFilters.of(it)?.label ?: it } ?: "Every filter"
                else -> if (value == "off") "Off" else "On"
            }
            "muteuntil" -> value?.toLongOrNull()?.let { "Until " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT).format(java.util.Date(it)) } ?: "Until you unmute"
            "sender" -> when (value) { "automated" -> "Automated"; "person" -> "A person"; else -> "Automatic" }
            "request" -> when { value == MessageRequests.ACCEPTED -> "Accepted"; value != null -> "Deleted"; else -> "Waiting in Requests" }
            "requests" -> when (value) {
                null -> if (name == "texts" || name.startsWith("automated")) "Off" else if (name == "notify") "Quiet" else "On"
                "on" -> "On"; "off" -> "Off"; else -> value.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
            "timer" -> value?.toIntOrNull()?.let { Disappearing.label(it) } ?: if (name == "*") "Off" else "Follows the default"
            "wallpaper" -> when (value) { null, "none" -> "Solid"; "photo" -> "A photo"; else -> value.replaceFirstChar { it.titlecase(Locale.ROOT) } }
            "gif", "pay" -> value ?: "Not added"
            "sticker" -> if (value == null) "Deleted" else "Kept"
            "account" -> when (value) {
                AccountKeys.WEB -> "Connects through FluidMessages Web"
                AccountKeys.PHONE -> "Connects from this phone"
                null -> "None"
                else -> value
            }
            else -> value ?: "Default"
        }
    }
}
