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
import javax.net.ssl.HttpsURLConnection

/**
 * This phone's side of the merge (SyncMerge): what it keeps, as items, and writing merged values back.
 *
 * Left out on purpose: chats in the private folder and decoy chats, chat codes (their hashes stay where they were
 * made), account passwords and tokens, wallet addresses, custom colors, sounds and vibration, and wallpaper photos.
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
        // Chats
        val places = linkedMapOf<String, MutableSet<String>>()
        for (folder in listOf("pinned", "archived", "blocked")) {
            for (address in MainActivity.addressList(prefs, folder)) places.getOrPut(address) { mutableSetOf() } += folder
        }
        for ((address, set) in places) SyncMerge.folderValue(set)?.let { chat("folder", address, it) }
        for (address in MainActivity.addressList(prefs, "muted")) chat("muted", address, "on")
        for ((key, value) in prefs.all) {
            when {
                key.startsWith("senderType.") && (value == "automated" || value == "person") -> chat("sender", key.removePrefix("senderType."), value as String)
                key.startsWith("disappear.") && value is Int && value >= 0 -> chat("timer", key.removePrefix("disappear."), value.toString())
                key.startsWith("gifSite.off.") && value == true -> items["gifoff:" + key.removePrefix("gifSite.off.")] = "off"
            }
        }
        Disappearing.globalSeconds(prefs).takeIf { it > 0 }?.let { items["timer:*"] = it.toString() }
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
            identity(protocol, account).takeIf { it.isNotEmpty() }?.let { items["account:" + protocol.name.lowercase(Locale.ROOT)] = it }
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
        var foldersChanged = false
        var timers = false
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
                "folder" -> if (!privateChat(prefs, name)) {
                    val wanted = SyncMerge.folderSet(value)
                    for (folder in listOf("pinned", "archived", "blocked")) {
                        if (folder in wanted) folders.getValue(folder).add(name) else folders.getValue(folder).remove(name)
                    }
                    foldersChanged = true
                }
                "muted" -> if (!privateChat(prefs, name)) {
                    if (value == "on") folders.getValue("muted").add(name) else folders.getValue("muted").remove(name)
                    foldersChanged = true
                }
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
                    val protocol = MessageProtocol.parse(name)
                    if (value != null && internet.contains(protocol) && DirectProtocols.on(prefs, protocol)) {
                        edit.putBoolean(DirectProtocols.key(protocol), false)
                        afterwards += { DirectProtocols.restart(context) }
                    }
                }
                "protocol" -> internet.firstOrNull { it.name.lowercase(Locale.ROOT) == name }?.let { edit.putBoolean(it.key(), value != "off") }
            }
        }
        if (foldersChanged) for ((folder, values) in folders) edit.putString(folder, JSONArray(values).toString())
        edit.commit()
        for (run in afterwards) run()
        if (timers) runCatching { SyncJob.reschedule(context) }
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
        val phone = PhoneSyncItems.read(context, prefs)
        val stamps = stamps(prefs, phone)
        val result = SyncMerge.merge(phone, state.optJSONObject("web")?.strings().orEmpty(), baseline, decisions) { key ->
            (follow || SyncMerge.group(key) !in SyncMerge.settingsGroups) && !chatKeyPrivate(prefs, key)
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
        for ((key, value) in agreed) baselineJson.put(key, value)
        val answer = try {
            request(prefs, "POST", "merge", JSONObject().put("revision", state.optString("revision"))
                .put("apply_web", applyWeb).put("baseline", baselineJson).put("conflicts", conflicts))
        } catch (error: HttpProblem) { if (error.code == 409) throw Stale() else throw error }
        prefs.edit().putString(PAIR, answer.optString("pair")).apply()
        SyncPending.save(prefs, answer.optJSONObject("pending"))
        return SyncPending.count(prefs)
    }

    private fun chatKeyPrivate(prefs: SharedPreferences, key: String) =
        SyncMerge.group(key) in setOf("folder", "muted", "sender", "timer", "wallpaper") && SyncMerge.name(key) != "*" &&
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

    private fun open(prefs: SharedPreferences, endpoint: String): HttpsURLConnection {
        val origin = prefs.getString("server", "").orEmpty()
        val token = Privacy.token(prefs)
        if (!CloudBackup.paired(origin, token)) throw IOException("Pair FluidMessages Web first.")
        val connection = URL("$origin/api/$endpoint").openConnection() as HttpsURLConnection
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
