package org.fluidmessages

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * This phone's side of the merge (SyncMerge): what it keeps, as items, and writing merged values back.
 *
 * Left out on purpose: chats in the private folder and decoy chats, chat codes (their hashes stay where they were
 * made), account passwords and tokens, wallet addresses, custom colors, sounds and vibration, and wallpaper photos.
 * Sounds stay on each device on purpose: the phone plays Android tones, the web its own, with nothing to compare.
 * Contacts made on the web travel on their own endpoint (WebContacts), never as items.
 */
internal object PhoneSyncItems {
    private val themes = MessageStyle.Theme.values().associateBy { it.name.lowercase(Locale.ROOT) }
    private val internet = listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)
    private val funKeys = mapOf("effects" to "fun.effects", "reactions" to "fun.reactions", "games" to "fun.games",
        "random" to "fun.random", "gifs" to "gifs", "linkPreviews" to "linkPreviews")
    /** Appearance item → phone setting and its default. */
    private val ints = mapOf("radius" to ("radius" to 18), "size" to ("size" to 16), "padding" to ("padding" to 12),
        "chromeOpacity" to ("chromeOpacity" to 84), "speed" to ("speed" to 220))
    private val strings = mapOf("wallpaper" to ("wallpaper" to "none"), "searchPosition" to ("searchPosition" to "bottom"),
        "toolbarFinish" to ("toolbarFinish" to "plain"), "density" to ("inboxDensity" to "standard"))
    private val bools = mapOf("tails" to "bubbleTails", "exactTimes" to "exactTimes", "combineProtocols" to "combineProtocols")
    private val modes = mapOf("tagMode" to DisplayRules.TAGS, "encryptionMode" to DisplayRules.ENCRYPTION)
    private val layout = listOf("radius", "size", "padding", "wallpaper", "searchPosition", "toolbarFinish", "chromeOpacity",
        "inboxDensity", "bubbleTails")
    private val stickerId = Regex("[A-Za-z0-9_-]{1,80}")
    /** Messaging item → phone setting, each on by default. */
    private val switches = mapOf("tabCounts" to TabBadges.PREF, "showCommunityChats" to Communities.SHOW_PREF,
        "announceSettings" to MainActivity.ANNOUNCE_SETTINGS)
    private val defaultFilters = InboxFilters.encode(InboxFilters.parse(emptyList()))

    /** Chats that never leave this phone: the private folder and decoys. */
    fun privateChat(prefs: SharedPreferences, address: String) =
        address.startsWith(Privacy.DECOY) || HiddenChats.addressHidden(prefs, address)

    fun stickers(context: Context): Map<String, File> =
        File(context.filesDir, "stickers").listFiles()?.filter { it.isFile && stickerId.matches(it.nameWithoutExtension) }
            ?.associateBy { it.nameWithoutExtension }.orEmpty()

    fun read(context: Context, prefs: SharedPreferences): Map<String, String> {
        val items = sortedMapOf<String, String>()
        fun chat(kind: String, address: String, value: String) { if (!privateChat(prefs, address)) items["$kind:$address"] = value }
        // Appearance
        val theme = MessageStyle.theme(prefs)
        if (theme != MessageStyle.Theme.FLUID) items["look:theme"] = theme.name.lowercase(Locale.ROOT)
        if (prefs.contains("dark")) items["look:mode"] = if (prefs.getBoolean("dark", false)) "dark" else "light"
        for ((item, setting) in ints) {
            val value = prefs.getInt(setting.first, setting.second)
            if (value != setting.second) items["look:$item"] = value.toString()
        }
        for ((item, setting) in strings) {
            var value = prefs.getString(setting.first, setting.second) ?: setting.second
            if (item == "wallpaper" && WallpaperPhotos.isPhoto(value)) value = "photo"
            if (value != setting.second) items["look:$item"] = value
        }
        for ((item, setting) in bools) if (prefs.getBoolean(setting, false)) items["look:$item"] = "true"
        for ((item, setting) in modes) DisplayRules.mode(prefs, setting).takeIf { it != "auto" }?.let { items["look:$item"] = it }
        runCatching { JSONObject(prefs.getString("chatProfiles", "{}") ?: "{}") }.getOrNull()?.let { profiles ->
            for (address in profiles.keys()) {
                val wallpaper = profiles.optJSONObject(address)?.optString("wallpaper").orEmpty()
                if (wallpaper.isNotEmpty() && wallpaper != "none") chat("wallpaper", address, if (WallpaperPhotos.isPhoto(wallpaper)) "photo" else wallpaper)
            }
        }
        // Fun
        for ((item, setting) in funKeys) if (!prefs.getBoolean(setting, true)) items["fun:$item"] = "off"
        // Messaging: counts on tabs, community chats and their order, the inbox filter menu, announcing changes
        for ((item, setting) in switches) if (!prefs.getBoolean(setting, true)) items["pref:$item"] = "off"
        Communities.sort(prefs).takeIf { it != Communities.Sort.RECENT }?.let { items["pref:communitySort"] = it.id }
        InboxFilters.encode(InboxFilters.read(prefs)).takeIf { it != defaultFilters }?.let { items["pref:inboxFilters"] = it.joinToString(",") }
        // Favorites, by number or chat address (FavoriteItems)
        for (address in FavoriteItems.read(context, prefs).orEmpty()) chat("favorite", address, "on")
        // Chats
        val places = linkedMapOf<String, MutableSet<String>>()
        for (folder in listOf("pinned", "archived", "blocked")) {
            for (address in MainActivity.addressList(prefs, folder)) places.getOrPut(address) { mutableSetOf() } += folder
        }
        for ((address, set) in places) SyncMerge.folderValue(set)?.let { chat("folder", address, it) }
        // Muted stays "on" for every version, timed or not; how long is its own item (SyncMergeRun.TIMED), only for a
        // FluidMessages Web that understands it. Mutes whose time is up are no item at all, so both sides unmute.
        for ((key, end) in Mutes.active(prefs)) {
            chat("muted", key, "on")
            if (end > 0) chat(SyncMergeRun.TIMED, key, end.toString())
        }
        for ((key, value) in prefs.all) {
            when {
                key.startsWith("senderType.") && (value == "automated" || value == "person") -> chat("sender", key.removePrefix("senderType."), value as String)
                key.startsWith("disappear.") && value is Int && value >= 0 -> chat("timer", key.removePrefix("disappear."), value.toString())
                key.startsWith("gifSite.off.") && value == true -> items["gifoff:" + key.removePrefix("gifSite.off.")] = "off"
            }
        }
        Disappearing.globalSeconds(prefs).takeIf { it > 0 }?.let { items["timer:*"] = it.toString() }
        // Requests and spam, and what you decided about each request (MessageRequests; SyncMergeRun.REQUESTS).
        items.putAll(MessageRequests.syncItems(prefs) { privateChat(prefs, it) })
        // GIF sites and payments you added
        for (site in GifSites.custom(prefs)) if (site.name.isNotBlank()) items["gif:" + site.name] = site.search
        runCatching { JSONArray(prefs.getString("money.custom", "[]")) }.getOrNull()?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("name").isNotBlank()) items["pay:" + item.optString("name")] = item.optString("template")
            }
        }
        prefs.getString("money.default", "").orEmpty().takeIf { it.isNotEmpty() }?.let { items["pay:*"] = it }
        for (id in stickers(context).keys) items["sticker:$id"] = "1"
        // Accounts connected from this phone, by their address only
        for (protocol in internet) {
            if (!DirectProtocols.on(prefs, protocol)) continue
            val account = DirectProtocols.account(context, protocol) ?: continue
            identity(protocol, account).takeIf { it.isNotEmpty() }?.let {
                val service = protocol.name.lowercase(Locale.ROOT)
                items["account:$service"] = it
                // The same account, by its own key: a conflict only if FluidMessages Web connects it too.
                items[AccountKeys.mergeKey(service, it)] = AccountKeys.PHONE
            }
        }
        for (protocol in internet) if (!protocol.enabled(prefs)) items["protocol:" + protocol.name.lowercase(Locale.ROOT)] = "off"
        return items
    }

    fun identity(protocol: MessageProtocol, account: JSONObject): String = when (protocol) {
        MessageProtocol.MATRIX -> account.optString("user_id")
        MessageProtocol.XMPP -> account.optString("jid")
        MessageProtocol.EMAIL -> account.optString("address")
        MessageProtocol.IRC -> account.optString("nick").takeIf { it.isNotEmpty() }?.let { it + "@" + account.optString("server") }.orEmpty()
        else -> ""
    }

    /** Writes merged values. Stickers to add come from [fetchSticker]; everything else is a setting. */
    fun apply(context: Context, prefs: SharedPreferences, changes: Map<String, String?>, fetchSticker: (String) -> Unit = {}) {
        if (changes.isEmpty()) return
        val edit = prefs.edit()
        // A new theme brings its colors; the layout choices that merged on their own stay.
        if (changes.containsKey("look:theme")) {
            val kept = prefs.all.filterKeys { it in layout }
            val theme = themes[changes["look:theme"]] ?: MessageStyle.Theme.FLUID
            edit.selectBuiltInTheme(theme.name)
            for ((key, value) in kept) when (value) {
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
            }
        }
        val folders = listOf("pinned", "archived", "blocked", "muted").associateWith { MainActivity.addressList(prefs, it).toMutableSet() }
        val muteEnds = Mutes.until(prefs).toMutableMap()
        var foldersChanged = false
        var timers = false
        val favorites = linkedMapOf<String, Boolean>()
        val afterwards = mutableListOf<() -> Unit>()
        for ((key, value) in changes) {
            val name = SyncMerge.name(key)
            when (SyncMerge.group(key)) {
                "look" -> when {
                    name == "mode" -> if (value == null) edit.remove("dark") else edit.putBoolean("dark", value == "dark")
                    name in ints -> ints.getValue(name).let { (setting, _) ->
                        val number = value?.toIntOrNull()
                        if (number == null) edit.remove(setting) else edit.putInt(setting, number)
                    }
                    name in strings -> strings.getValue(name).let { (setting, _) ->
                        if (value == null) edit.remove(setting) else if (value != "photo") edit.putString(setting, value)
                    }
                    name in bools -> edit.putBoolean(bools.getValue(name), value == "true")
                    name in modes -> if (value == null) edit.putString(modes.getValue(name), "auto") else edit.putString(modes.getValue(name), value)
                }
                "wallpaper" -> if (!privateChat(prefs, name) && value != "photo") afterwards += {
                    val profile = ChatProfile.read(prefs, name)
                    ChatProfile(profile.alias(), profile.theme(), if (value == null || value == "none") "" else value,
                        profile.background(), profile.outgoing(), profile.incoming()).save(prefs, name)
                }
                "fun" -> funKeys[name]?.let { edit.putBoolean(it, value != "off") }
                "pref" -> when (name) {
                    in switches -> edit.putBoolean(switches.getValue(name), value != "off")
                    "communitySort" -> edit.putString(Communities.SORT_PREF, Communities.Sort.parse(value).id)
                    // A filter this phone doesn't know is dropped and the rest keep their order (InboxFilters.parse).
                    "inboxFilters" -> if (value == null) edit.remove(InboxFilters.PREF)
                        else edit.putString(InboxFilters.PREF, JSONArray(InboxFilters.encode(InboxFilters.parse(value.split(',')))).toString())
                }
                "favorite" -> if (!privateChat(prefs, name)) favorites[name] = value == "on"
                "folder" -> if (!privateChat(prefs, name)) {
                    val wanted = SyncMerge.folderSet(value)
                    for (folder in listOf("pinned", "archived", "blocked")) {
                        if (folder in wanted) folders.getValue(folder).add(name) else folders.getValue(folder).remove(name)
                    }
                    foldersChanged = true
                }
                "muted" -> if (!privateChat(prefs, name)) {
                    if (value == "on") folders.getValue("muted").add(name) else { folders.getValue("muted").remove(name); muteEnds.remove(name) }
                    foldersChanged = true
                }
                // When a timed mute ends; no value is forever, for as long as it stays muted.
                SyncMergeRun.TIMED -> if (!privateChat(prefs, name)) {
                    val end = value?.toLongOrNull()?.takeIf { it > 0 }
                    if (end == null) muteEnds.remove(name) else muteEnds[name] = end
                    foldersChanged = true
                }
                "requests" -> MessageRequests.applySync(edit, "requests", name, value)
                "request" -> if (!privateChat(prefs, name)) MessageRequests.applySync(edit, "request", name, value)
                "sender" -> if (!privateChat(prefs, name)) {
                    if (value == "automated" || value == "person") edit.putString("senderType.$name", value) else edit.remove("senderType.$name")
                }
                "timer" -> {
                    timers = true
                    val seconds = value?.toIntOrNull()
                    afterwards += {
                        if (name == "*") Disappearing.setGlobal(prefs, seconds ?: 0)
                        else if (!privateChat(prefs, name)) Disappearing.set(prefs, name, seconds ?: -1)
                    }
                }
                "gifoff" -> edit.putBoolean("gifSite.off.$name", value == "off")
                "gif" -> afterwards += {
                    GifSites.remove(prefs, name)
                    if (value != null) GifSites.add(prefs, name, value)
                }
                "pay" -> if (name == "*") {
                    if (value == null) edit.remove("money.default") else edit.putString("money.default", value)
                } else afterwards += {
                    CustomMoney.remove(prefs, name)
                    if (value != null && CustomMoney.validTemplate(value)) CustomMoney.add(prefs, name, value)
                }
                "sticker" -> if (value == null) stickers(context)[name]?.delete() else afterwards += { runCatching { fetchSticker(name) } }
                "account" -> {
                    // The web was chosen for this account: stop connecting from this phone. The account stays saved here,
                    // so turning "Connect from this phone" back on needs no password.
                    val protocol = MessageProtocol.parse(name.substringBefore(':'))
                    // Per account: only when the web was chosen for the very account this phone connects.
                    val mine = AccountKeys.perAccount(key)?.let { (_, address) ->
                        value == AccountKeys.WEB && DirectProtocols.account(context, protocol)?.let { identity(protocol, it) } == address
                    } ?: (value != null)
                    if (mine && internet.contains(protocol) && DirectProtocols.on(prefs, protocol)) {
                        edit.putBoolean(DirectProtocols.key(protocol), false)
                        afterwards += { DirectProtocols.restart(context) }
                    }
                }
                "protocol" -> internet.firstOrNull { it.name.lowercase(Locale.ROOT) == name }?.let { edit.putBoolean(it.key(), value != "off") }
            }
        }
        if (foldersChanged) {
            for ((folder, values) in folders) if (folder != "muted") edit.putString(folder, JSONArray(values).toString())
            Mutes.write(edit, folders.getValue("muted") to muteEnds.filterKeys { it in folders.getValue("muted") })
        }
        edit.commit()
        if (favorites.isNotEmpty()) FavoriteItems.apply(context, prefs, favorites)
        for (run in afterwards) run()
        if (timers) runCatching { SyncJob.reschedule(context) }
    }
}

/**
 * The Phone tab's favorites as merge items. The phone stars people by key ("contact:<id>", "web:<id>", "chat:<address>",
 * "number:<number>"); FluidMessages Web stars a number or chat address. Contact ids mean nothing on the web, so each
 * starred person becomes the numbers and addresses it reaches, and a favorite from the web stars the contact with
 * that number, or else the address itself.
 */
internal object FavoriteMerge {
    /** What each starred key reaches, as item names. */
    fun items(reach: Map<String, List<String>>): Set<String> =
        reach.values.flatten().map(SyncMerge::favoriteAddress).filter { it.isNotEmpty() }.toSortedSet()

    /**
     * The starred keys after [changes] (item name → starred or not). Unstarring takes every key that reaches the
     * address; starring adds [find]'s key for it unless one already reaches it.
     */
    fun apply(reach: Map<String, List<String>>, changes: Map<String, Boolean>, find: (String) -> String): List<String> {
        val now = LinkedHashMap(reach.mapValues { (_, addresses) -> addresses.map(SyncMerge::favoriteAddress) })
        for ((address, on) in changes) {
            if (on) {
                if (now.values.none { address in it }) now[find(address)] = listOf(address)
            } else {
                now.entries.removeAll { address in it.value }
            }
        }
        return now.keys.toList()
    }
}

internal object FavoriteItems {
    private const val KEY = "phoneFavorites"

    private fun keys(prefs: SharedPreferences): List<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map(array::getString)
    }.getOrDefault(emptyList())

    private fun contactsAllowed(context: Context) =
        context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Each starred key and what it reaches, or null when a starred contact can't be read (contacts not allowed). */
    private fun reach(context: Context, prefs: SharedPreferences): Map<String, List<String>>? {
        val keys = keys(prefs)
        val ids = keys.mapNotNull { key -> key.removePrefix("contact:").takeIf { key.startsWith("contact:") }?.toLongOrNull() }
        val numbers = mutableMapOf<Long, MutableList<String>>()
        if (ids.isNotEmpty()) {
            if (!contactsAllowed(context)) return null
            val phone = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            try {
                context.contentResolver.query(phone, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " IN (" + ids.joinToString(",") + ")", null, null)?.use { cursor ->
                    while (cursor.moveToNext()) cursor.getString(1)?.let { numbers.getOrPut(cursor.getLong(0)) { mutableListOf() } += it }
                }
            } catch (_: RuntimeException) { return null }
        }
        val web = WebContacts.list(prefs).associateBy { it.id }
        return keys.associateWith { key ->
            when {
                key.startsWith("contact:") -> key.removePrefix("contact:").toLongOrNull()?.let { numbers[it] }.orEmpty()
                key.startsWith("web:") -> web[key.removePrefix("web:")]?.draft?.let { it.numbers + it.addresses.map { chat -> chat.address } }.orEmpty()
                key.startsWith("chat:") -> listOf(key.removePrefix("chat:"))
                key.startsWith("number:") -> listOf(key.removePrefix("number:"))
                else -> emptyList() // The sample's and decoy people never leave this phone.
            }.filter { !it.startsWith(Privacy.DECOY) }
        }
    }

    /** Favorites as item names, or null when they can't all be read: then they stay out of this merge. */
    fun read(context: Context, prefs: SharedPreferences): Set<String>? = reach(context, prefs)?.let(FavoriteMerge::items)

    fun apply(context: Context, prefs: SharedPreferences, changes: Map<String, Boolean>) {
        val reach = reach(context, prefs) ?: return
        val web = WebContacts.list(prefs)
        val keys = FavoriteMerge.apply(reach, changes) { address ->
            contactFor(context, address)?.let { "contact:$it" }
                ?: web.firstOrNull { entry -> (entry.draft.numbers + entry.draft.addresses.map { it.address }).any { SyncMerge.favoriteAddress(it) == address } }
                    ?.let { "web:" + it.id }
                ?: "chat:$address"
        }
        prefs.edit().putString(KEY, JSONArray(keys).toString()).apply()
    }

    private fun contactFor(context: Context, address: String): Long? {
        if (!SmsStore.validRecipient(address) || !contactsAllowed(context)) return null
        val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(address))
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup._ID), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (_: RuntimeException) { null }
    }
}

/** Keeps the differences waiting for a choice, as FluidMessages Web last reported them. */
internal object SyncPending {
    private const val KEY = "merge.pending"

    fun id(prefs: SharedPreferences): String = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}").optString("id") }.getOrDefault("")

    fun conflicts(prefs: SharedPreferences): List<SyncConflict> = runCatching { parse(JSONObject(prefs.getString(KEY, "{}") ?: "{}")) }
        .getOrDefault(emptyList())

    @JvmStatic fun count(prefs: SharedPreferences) = conflicts(prefs).size

    fun parse(pending: JSONObject?): List<SyncConflict> {
        val list = pending?.optJSONArray("conflicts") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            SyncConflict(item.optString("key"), item.text("phone"), item.text("web"), item.optLong("phone_t"), item.optLong("web_t"))
        }.filter { it.key.isNotEmpty() }
    }

    fun save(prefs: SharedPreferences, pending: JSONObject?) {
        if (pending == null || parse(pending).isEmpty()) prefs.edit().remove(KEY).apply()
        else prefs.edit().putString(KEY, pending.toString()).apply()
    }
}

private fun JSONObject.text(name: String): String? = if (isNull(name) || !has(name)) null else optString(name)

/**
 * Runs the merge with FluidMessages Web: on each sync, and right after the step-by-step pairing. An older
 * FluidMessages Web without the merge answers 404, and the earlier one-way sync carries on.
 */
object SyncMergeRun {
    private const val PAIR = "merge.pair"
    private const val APPLIED = "merge.applied"
    private const val SEEN = "merge.seen"
    private const val LIMIT = 8 * 1024 * 1024
    /**
     * Timed mutes: "muteuntil:<key>" = when the mute ends, beside "muted:<key>" = "on" that every version compares.
     * Older FluidMessages Web refuses groups it doesn't know, and an older phone would take a missing item as a change,
     * so these are only exchanged when both sides say so: this phone asks with [ITEMS_HEADER] and posts "mute_until": 1,
     * and FluidMessages Web answers "mute_until": 1 (server/sync_merge.py TIMED).
     */
    const val TIMED = "muteuntil"
    private const val ITEMS_HEADER = "X-Fluid-Merge-Items"
    /**
     * Every newer group this phone understands (SyncMerge.optionalGroups, message requests included). [ITEMS_HEADER]
     * stays exactly "muteuntil", which servers from before compare whole; FluidMessages Web answers the groups it knows
     * as "items".
     */
    private const val GROUPS_HEADER = "X-Fluid-Merge-Groups"
    /**
     * Message requests: "requests:<setting>" and "request:<address>" (MessageRequests) are in [GROUPS_HEADER] too. The
     * first FluidMessages Web with requests only knew a header of its own, so this phone also asks with
     * [REQUESTS_HEADER] and posts "requests": 1; that server answers "requests": 1 rather than listing them in "items".
     */
    val REQUESTS = SyncMerge.requestGroups.toSet()
    private const val REQUESTS_HEADER = "X-Fluid-Merge-Requests"
    @Volatile private var nextRun = 0L

    private class Unsupported : IOException("FluidMessages Web doesn't merge settings yet")
    private class Stale : IOException("FluidMessages Web changed while merging")

    /** Merges now, or at most once a minute unless [now]. Returns how many differences wait for a choice, or -1. */
    @JvmStatic
    @Synchronized
    fun run(context: Context, now: Boolean): Int {
        val prefs = SyncPolicy.prefs(context)
        if (!CloudBackup.paired(prefs.getString("server", ""), Privacy.token(prefs))) return -1
        val clock = android.os.SystemClock.elapsedRealtime()
        if (!now && clock < nextRun) return SyncPending.count(prefs)
        nextRun = clock + 60_000
        repeat(3) {
            try { return once(context, prefs) }
            catch (_: Stale) {}
            catch (_: Unsupported) { nextRun = clock + 30 * 60_000; return -1 }
        }
        return SyncPending.count(prefs)
    }

    private fun once(context: Context, prefs: SharedPreferences): Int {
        val state = request(prefs, "GET", "merge", null)
        // Choices made in a browser since the last look: write the web's picks here first.
        state.optJSONObject("resolved")?.let { resolved ->
            val id = resolved.optString("id")
            if (id.isNotEmpty() && id != prefs.getString(APPLIED, "")) {
                val values = resolved.optJSONObject("values") ?: JSONObject()
                val picks = resolved.optJSONObject("picks") ?: JSONObject()
                val changes = linkedMapOf<String, String?>()
                for (key in picks.keys()) if (picks.optString(key) == SyncMerge.WEB) changes[key] = values.text(key)
                PhoneSyncItems.apply(context, prefs, changes) { download(context, prefs, it) }
                prefs.edit().putString(APPLIED, id).apply()
            }
        }
        val pair = state.text("pair")
        val follow = state.optBoolean("follow_phone", true)
        val baseline = if (pair != null && pair == prefs.getString(PAIR, null)) state.optJSONObject("baseline")?.strings() else null
        val decisions = state.optJSONObject("decisions")?.let { all ->
            all.keys().asSequence().mapNotNull { key ->
                all.optJSONObject(key)?.let { key to SyncDecision(it.text("phone"), it.text("web"), it.optString("pick")) }
            }.toMap()
        }.orEmpty()
        // Newer item groups travel only when FluidMessages Web understands them; otherwise a timed mute is just "muted",
        // and inbox choices, favorites, and message requests stay on each side.
        val shared = SyncMerge.sharedGroups(state.optJSONArray("items")?.let { list -> (0 until list.length()).map(list::optString) },
            state.optInt("mute_until") == 1, state.optInt("requests") == 1)
        // Favorites of contacts that can't be read now (contacts not allowed) sit this merge out, rather than look deleted.
        val favorites = "favorite" in shared && FavoriteItems.read(context, prefs) != null
        val phone = PhoneSyncItems.read(context, prefs).filterKeys { SyncMerge.understood(it, shared) }
        val stamps = stamps(prefs, phone)
        val result = SyncMerge.merge(phone, state.optJSONObject("web")?.strings().orEmpty(), baseline, decisions) { key ->
            (follow || SyncMerge.group(key) !in SyncMerge.settingsGroups) && !chatKeyPrivate(prefs, key) && SyncMerge.understood(key, shared)
                && (favorites || SyncMerge.group(key) != "favorite")
        }
        // This phone's share goes in first: if posting fails, the next run sees both sides already agree.
        PhoneSyncItems.apply(context, prefs, result.applyPhone) { download(context, prefs, it) }
        val stickers = PhoneSyncItems.stickers(context)
        val agreed = result.baseline.toMutableMap()
        // A sticker that didn't arrive isn't agreed on yet, so the next merge tries again instead of deleting it.
        for ((key, value) in result.applyPhone) {
            if (SyncMerge.group(key) == "sticker" && value != null && SyncMerge.name(key) !in stickers) agreed.remove(key)
        }
        val applyWeb = JSONObject()
        for ((key, value) in result.applyWeb) {
            if (SyncMerge.group(key) == "sticker" && value != null) {
                val file = stickers[SyncMerge.name(key)]
                val sent = file != null && try { upload(prefs, SyncMerge.name(key), file); true } catch (_: Exception) { false }
                if (!sent) { agreed.remove(key); continue }
            }
            applyWeb.put(key, value ?: JSONObject.NULL)
        }
        val conflicts = JSONArray()
        for (conflict in result.conflicts) {
            conflicts.put(JSONObject().put("key", conflict.key).put("phone", conflict.phone ?: JSONObject.NULL)
                .put("phone_t", stamps[conflict.key] ?: 0L))
        }
        val baselineJson = JSONObject()
        // Only groups this FluidMessages Web knows: an older one refuses the whole merge for any other.
        for ((key, value) in agreed) if (SyncMerge.understood(key, shared)) baselineJson.put(key, value)
        val answer = try {
            request(prefs, "POST", "merge", JSONObject().put("revision", state.optString("revision"))
                .put("apply_web", applyWeb).put("baseline", baselineJson).put("conflicts", conflicts)
                .put("items", JSONArray(SyncMerge.optionalGroups.filter { it in shared }))
                .apply { if (TIMED in shared) put("mute_until", 1); if (shared.containsAll(REQUESTS)) put("requests", 1) })
        } catch (error: HttpProblem) { if (error.code == 409) throw Stale() else throw error }
        prefs.edit().putString(PAIR, answer.optString("pair")).apply()
        SyncPending.save(prefs, answer.optJSONObject("pending"))
        return SyncPending.count(prefs)
    }

    private fun chatKeyPrivate(prefs: SharedPreferences, key: String) =
        SyncMerge.group(key) in setOf("folder", "muted", TIMED, "favorite", "sender", "timer", "wallpaper", "request") && SyncMerge.name(key) != "*" &&
            PhoneSyncItems.privateChat(prefs, SyncMerge.name(key))

    /** When each item last changed on this phone, as far as merges noticed. */
    private fun stamps(prefs: SharedPreferences, items: Map<String, String>): Map<String, Long> {
        val seen = runCatching { JSONObject(prefs.getString(SEEN, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, Long>()
        val next = JSONObject()
        for (key in items.keys + seen.keys().asSequence().toList()) {
            val before = seen.optJSONArray(key)
            val value = items[key]
            val same = before != null && (if (before.isNull(0)) value == null else before.optString(0) == value)
            val stamp = if (same) before!!.optLong(1) else now
            result[key] = stamp
            if (value != null || now - stamp < 90L * 86_400_000) next.put(key, JSONArray().put(value ?: JSONObject.NULL).put(stamp))
        }
        prefs.edit().putString(SEEN, next.toString()).apply()
        return result
    }

    /**
     * Settles the differences from this phone: sends the choices, then writes the web's picks here. Returns null
     * when it worked, or what went wrong.
     */
    @JvmStatic
    fun resolve(context: Context, id: String, choices: Map<String, String>): String? {
        val prefs = SyncPolicy.prefs(context)
        return try {
            val conflicts = SyncPending.conflicts(prefs)
            request(prefs, "POST", "merge/resolve", JSONObject().put("id", id).put("choices", JSONObject(choices)))
            PhoneSyncItems.apply(context, prefs, SyncMerge.phoneChanges(conflicts, choices)) { download(context, prefs, it) }
            prefs.edit().putString(APPLIED, id).remove("merge.pending").apply()
            null
        } catch (error: HttpProblem) {
            if (error.code == 409) { runCatching { run(context, true) }; "These changed on FluidMessages Web. Look them over again." }
            else error.message
        } catch (_: Exception) {
            "Couldn't reach FluidMessages Web. Your choices weren't saved yet."
        }
    }

    private fun JSONObject.strings(): Map<String, String> = keys().asSequence().filter { !isNull(it) }.associateWith { optString(it) }

    class HttpProblem(val code: Int, message: String) : IOException(message)

    private fun open(prefs: SharedPreferences, endpoint: String): java.net.HttpURLConnection {
        NetworkGate.requireInternet()
        val origin = prefs.getString("server", "").orEmpty()
        val token = Privacy.token(prefs)
        if (!CloudBackup.paired(origin, token)) throw IOException("Pair FluidMessages Web first.")
        // Pinned to FluidMessages Web's certificate when one was saved (CompanionTls).
        val connection = CompanionTls.open(prefs, "$origin/api/$endpoint")
        connection.connectTimeout = 8000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        return connection
    }

    private fun request(prefs: SharedPreferences, method: String, endpoint: String, payload: JSONObject?): JSONObject {
        val connection = open(prefs, endpoint)
        try {
            connection.requestMethod = method
            if (endpoint == "merge" && method == "GET") {
                connection.setRequestProperty(ITEMS_HEADER, TIMED)
                connection.setRequestProperty(GROUPS_HEADER, SyncMerge.optionalGroups.joinToString(","))
                connection.setRequestProperty(REQUESTS_HEADER, "1")
            }
            if (payload != null) {
                val data = payload.toString().toByteArray(StandardCharsets.UTF_8)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(data.size)
                connection.doOutput = true
                connection.outputStream.use { it.write(data) }
            }
            val code = connection.responseCode
            if (code == 404 && endpoint == "merge" && method == "GET") throw Unsupported()
            val bytes = (if (code == 200) connection.inputStream else connection.errorStream)?.use { it.readNBytes(LIMIT + 1) } ?: ByteArray(0)
            if (bytes.size > LIMIT) throw IOException("The merge is too large.")
            val reply = runCatching { JSONObject(String(bytes, StandardCharsets.UTF_8)) }.getOrDefault(JSONObject())
            if (code != 200) throw HttpProblem(code, reply.optString("error", "FluidMessages Web returned HTTP $code"))
            return reply
        } finally { connection.disconnect() }
    }

    private fun upload(prefs: SharedPreferences, id: String, file: File) {
        val mime = when (file.extension.lowercase(Locale.ROOT)) { "png" -> "image/png"; "gif" -> "image/gif"; else -> "image/webp" }
        val connection = open(prefs, "stickers/upload")
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", mime)
            connection.setRequestProperty("X-Fluid-Sticker", id)
            connection.setFixedLengthStreamingMode(file.length())
            connection.doOutput = true
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output) } }
            if (connection.responseCode != 200) throw IOException("The sticker wasn't accepted")
        } finally { connection.disconnect() }
    }

    private fun download(context: Context, prefs: SharedPreferences, id: String) {
        if (!Regex("[A-Za-z0-9_-]{1,80}").matches(id)) return
        val connection = open(prefs, "stickers/file?id=$id")
        try {
            if (connection.responseCode != 200) return
            val extension = when (connection.getHeaderField("X-Fluid-Type")) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> return }
            val bytes = connection.inputStream.use { it.readNBytes(2_000_001) }
            if (bytes.size > 2_000_000) return
            val folder = File(context.filesDir, "stickers").apply { mkdirs() }
            val target = File(folder, "$id.$extension")
            val temporary = File(folder, "$id.part")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) temporary.delete()
        } finally { connection.disconnect() }
    }
}
