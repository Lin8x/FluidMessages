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
    val settingsGroups = setOf("look", "fun", "timer", "wallpaper")
    private val folders = listOf("pinned", "archived", "blocked")

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
        for (key in keys) {
            if (!inScope(key)) continue
            val p = phone[key]
            val w = web[key]
            if (group(key) == "account") {
                next.remove(key)
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
    private val funNames = mapOf("effects" to "Message effects", "reactions" to "Reaction effects", "games" to "Games",
        "random" to "Random picks", "gifs" to "Pictures and GIFs from links", "linkPreviews" to "Link previews")

    /** The heading for a key's group, and the item's own title. [person] names a chat by its address. */
    fun describe(key: String, person: (String) -> String): Pair<String, String> {
        val name = name(key)
        return when (group(key)) {
            "look" -> "Appearance" to (lookNames[name] ?: name)
            "fun" -> "Fun and effects" to (funNames[name] ?: name)
            "folder" -> "Chat folders" to person(name)
            "muted" -> "Muted chats" to person(name)
            "sender" -> "Automated senders" to person(name)
            "timer" -> "Disappearing messages" to if (name == "*") "Default timer" else person(name)
            "wallpaper" -> "Chat wallpaper" to person(name)
            "gif" -> "GIF sites" to name
            "gifoff" -> "GIF sites" to name.replaceFirstChar { it.titlecase(Locale.ROOT) }
            "pay" -> "Payments" to if (name == "*") "Default way to pay" else name
            "sticker" -> "Stickers" to "Sticker"
            "account" -> "Chat accounts" to name.replaceFirstChar { it.titlecase(Locale.ROOT) }
            "protocol" -> "Ways to message" to name.replaceFirstChar { it.titlecase(Locale.ROOT) }
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
            "sender" -> when (value) { "automated" -> "Automated"; "person" -> "A person"; else -> "Automatic" }
            "timer" -> value?.toIntOrNull()?.let { Disappearing.label(it) } ?: if (name == "*") "Off" else "Follows the default"
            "wallpaper" -> when (value) { null, "none" -> "Solid"; "photo" -> "A photo"; else -> value.replaceFirstChar { it.titlecase(Locale.ROOT) } }
            "gif", "pay" -> value ?: "Not added"
            "sticker" -> if (value == null) "Deleted" else "Kept"
            "account" -> value ?: "None"
            else -> value ?: "Default"
        }
    }
}
