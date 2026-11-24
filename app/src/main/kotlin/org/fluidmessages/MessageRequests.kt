package org.fluidmessages

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.provider.BlockedNumberContract
import android.provider.Telephony
import org.json.JSONObject

/**
 * Message requests and automated senders, the same rules as FluidMessages Web (server/requests.js and
 * server/message_requests.py).
 *
 * A request is an incoming one-to-one chat from someone you don't know: a text from a number that isn't in your
 * contacts (only while "Filter unknown senders (texts)" is on, off by default like iOS, so delivery and sign-in codes
 * are never missed), or an internet chat from someone who isn't in your contacts or favorites and whom you've never
 * messaged (on by default): a Matrix or XMPP invitation, or an IRC, XMPP, or email chat that wrote first. Network
 * services (NickServ and the like, servers) and channels or rooms you joined are never requests. Requests stay out of
 * the inbox and the Messages count, notify quietly or not at all, and show under the Requests filter with Accept,
 * Delete, Block, and Block and report spam at the top of the chat. Replying accepts one too.
 *
 * Settings and decisions sync with FluidMessages Web: "requests:<setting>" and "request:<address>" merge items
 * (PhoneSyncItems, only once both sides say they understand them). Decoy and private-folder chats never leave the phone.
 */
internal object MessageRequests {
    const val TEXTS = "requestTexts"
    const val CHATS = "requestChats"
    const val NOTIFY = "requestNotify"
    const val AUTOMATED_OUT = "automatedOut"
    const val AUTOMATED_QUIET = "automatedQuiet"
    const val AUTOMATED_BLOCK = "automatedBlock"
    /** Preference prefix for what you decided about a chat, by address, like senderType.<address>. */
    const val DECISION = "request."
    const val ACCEPTED = "accepted"
    const val DELETED = "deleted:"
    private val deleted = Regex("deleted:([0-9]{1,15})")
    private val serviceNick = Regex("(?i)(nick|chan|memo|oper|host|bot|help|info|stat|sasl|alis|global|group|hist|op)serv|global|\\*.*")
    private val mailService = Regex("(?i)(mailer-daemon|postmaster|mail-daemon)@.*")

    data class Settings(
        val texts: Boolean = false,
        val chats: Boolean = true,
        /** Request notifications: quiet (true) or off (false). */
        val notifyQuiet: Boolean = true,
        val automatedOut: Boolean = false,
        val automatedQuiet: Boolean = false,
        val automatedBlock: Boolean = false,
    )

    /** What the rule reads about one chat. [service] is a protocol family: SMS, MATRIX, XMPP, IRC, or EMAIL. */
    data class Facts(
        val service: String = "SMS",
        val group: Boolean = false,
        /** A Matrix or XMPP invitation not answered yet. */
        val invite: Boolean = false,
        val contact: Boolean = false,
        val favorite: Boolean = false,
        /** You've sent something in this chat. */
        val sent: Boolean = false,
        val decision: String = "",
        val automated: Boolean = false,
        val networkService: Boolean = false,
        /** An internet chat that wrote first, rather than one you linked or joined. */
        val stranger: Boolean = false,
    )

    enum class Alert { LOUD, QUIET, NONE }

    // The rules, on plain values so they can be tested (MessageRequestsTest, and test-local with the same cases).

    @JvmStatic
    fun isRequest(facts: Facts, settings: Settings): Boolean {
        if (facts.decision == ACCEPTED || facts.contact || facts.favorite || facts.sent) return false
        if (facts.service == "SMS") return settings.texts && !facts.group && !facts.automated
        if (!settings.chats || facts.networkService) return false
        if (facts.invite) return true
        return !facts.group && facts.stranger
    }

    /** When a request was deleted, or 0. Messages up to then stay gone; a newer one makes it a request again. */
    @JvmStatic
    fun deletedAt(decision: String?): Long = decision?.let { deleted.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L

    @JvmStatic
    fun validDecision(value: String?) = value == ACCEPTED || value != null && deleted.matches(value)

    /** NickServ and friends, bouncer nicks (*status), servers, and mail systems: never a request. */
    @JvmStatic
    fun networkService(service: String, name: String?): Boolean {
        val value = name.orEmpty()
        if (value.isEmpty()) return true
        return when (service) {
            "IRC" -> serviceNick.matches(value) || '.' in value
            "XMPP" -> '@' !in value
            "MATRIX" -> ':' !in value || value.removePrefix("@").substringBefore(':').lowercase() in setOf("server", "notices", "server-notices", "_server")
            "EMAIL" -> mailService.matches(value)
            else -> false
        }
    }

    /** How a new message alerts. Deleted requests with nothing new and blocked automated senders never do. */
    @JvmStatic
    fun alert(request: Boolean, automated: Boolean, settings: Settings): Alert = when {
        automated && (settings.automatedBlock || settings.automatedQuiet) -> Alert.NONE
        request -> if (settings.notifyQuiet) Alert.QUIET else Alert.NONE
        else -> Alert.LOUD
    }

    /** A sample chat's facts: a text from a number that isn't a contact, answered or decided on this screen only. */
    @JvmStatic
    fun sampleFacts(sent: Boolean, decision: String) = Facts(service = "SMS", sent = sent, decision = decision)

    // This phone's settings

    @JvmStatic
    fun settings(prefs: SharedPreferences) = Settings(
        texts = prefs.getBoolean(TEXTS, false), chats = prefs.getBoolean(CHATS, true),
        notifyQuiet = prefs.getString(NOTIFY, "quiet") != "off", automatedOut = prefs.getBoolean(AUTOMATED_OUT, false),
        automatedQuiet = prefs.getBoolean(AUTOMATED_QUIET, false), automatedBlock = prefs.getBoolean(AUTOMATED_BLOCK, false))

    /** The sample inbox shows a request from a new number, whatever the real setting. */
    @JvmStatic
    fun sampleSettings(prefs: SharedPreferences) = settings(prefs).copy(texts = true)

    @JvmStatic
    fun decision(prefs: SharedPreferences, address: String): String = prefs.getString(DECISION + address, "").orEmpty()

    @JvmStatic
    fun decide(prefs: SharedPreferences, address: String, value: String?) {
        if (value == null || !validDecision(value)) prefs.edit().remove(DECISION + address).apply()
        else prefs.edit().putString(DECISION + address, value).apply()
    }

    @JvmStatic
    fun decisions(prefs: SharedPreferences): Map<String, String> = prefs.all.entries
        .filter { it.key.startsWith(DECISION) && (it.value as? String).let(::validDecision) }
        .associate { it.key.removePrefix(DECISION) to it.value as String }

    // Merge items (PhoneSyncItems), the same names as server/message_requests.py ITEMS.

    private val boolItems = mapOf("texts" to (TEXTS to false), "chats" to (CHATS to true), "automatedOut" to (AUTOMATED_OUT to false),
        "automatedQuiet" to (AUTOMATED_QUIET to false), "automatedBlock" to (AUTOMATED_BLOCK to false))

    /** The settings that differ from their default, as "requests:<name>" items, and each decision as "request:<address>". */
    fun syncItems(prefs: SharedPreferences, private: (String) -> Boolean): Map<String, String> {
        val items = sortedMapOf<String, String>()
        for ((name, setting) in boolItems) {
            val value = prefs.getBoolean(setting.first, setting.second)
            if (value != setting.second) items["requests:$name"] = if (value) "on" else "off"
        }
        if (prefs.getString(NOTIFY, "quiet") == "off") items["requests:notify"] = "off"
        for ((address, value) in decisions(prefs)) if (!private(address)) items["request:$address"] = value
        return items
    }

    /** Writes one merged item. [kind] is "requests" or "request"; a null value is the default. */
    fun applySync(edit: SharedPreferences.Editor, kind: String, name: String, value: String?) {
        if (kind == "requests") {
            if (name == "notify") { if (value == "off") edit.putString(NOTIFY, "off") else edit.remove(NOTIFY); return }
            val setting = boolItems[name] ?: return
            if (value == null) edit.remove(setting.first) else edit.putBoolean(setting.first, value == "on")
        } else if (kind == "request") {
            if (validDecision(value)) edit.putString(DECISION + name, value) else edit.remove(DECISION + name)
        }
    }

    // Live chats

    /** The route a chat came through FluidMessages Web or this phone by, if any. */
    @JvmStatic
    fun route(prefs: SharedPreferences, address: String): JSONObject? =
        runCatching { GatewayClient.links(prefs, address).firstOrNull() }.getOrNull()

    /** The facts for one live chat, read straight from Android's stores (for notifications, outside the inbox). */
    @JvmStatic
    fun factsFor(context: Context, prefs: SharedPreferences, address: String): Facts {
        val route = route(prefs, address)
        val phone = route == null && (SmsStore.validRecipient(address) || address.startsWith("mms:"))
        val service = if (phone) "SMS" else MessageProtocol.parse(route?.optString("protocol").orEmpty()).family().name
        val contacts = ContactDirectory.shared(context)
        val favorites = favoriteAddresses(context, favoriteKeys(prefs, false))
        return Facts(
            service = service,
            group = address.startsWith("mms:") || route?.optBoolean("group") == true,
            invite = route?.optBoolean("invite") == true,
            // Without the contacts permission nobody can be told apart, so no text counts as unknown.
            contact = phone && (!contacts.allowed() || contacts.find(address) != null),
            favorite = favoriteChat(address, favorites),
            sent = sentTo(context, address, phone),
            decision = decision(prefs, address),
            automated = phone && !address.startsWith("mms:") && SenderStyle.forAddress(prefs, address) == SenderStyle.AUTOMATED,
            networkService = !phone && networkService(service, route?.optString("inviter")?.takeIf { it.isNotEmpty() } ?: route?.optString("target")),
            stranger = route?.optBoolean("request") == true || route?.optBoolean("invite") == true,
        )
    }

    private fun sentTo(context: Context, address: String, phone: Boolean): Boolean = runCatching {
        if (phone) context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), "address = ? AND type != 1",
            arrayOf(address), null)?.use { it.moveToFirst() } == true
        else ProtocolStore.get(context).read().any { it.optString("address") == address && it.optInt("type", 1) != 1 && !it.optBoolean("event") }
    }.getOrDefault(true)

    /** How a new message in [address] alerts (SmsDelivery.notify). */
    @JvmStatic
    fun alertFor(context: Context, prefs: SharedPreferences, address: String): Alert = runCatching {
        if (address.startsWith(Privacy.DECOY)) return Alert.LOUD
        val facts = factsFor(context, prefs, address)
        alert(isRequest(facts, settings(prefs)), facts.automated, settings(prefs))
    }.getOrDefault(Alert.LOUD)

    /** Replying accepts a request: remembered per address, and synced. */
    @JvmStatic
    fun acceptOnReply(context: Context, prefs: SharedPreferences, address: String) {
        if (decision(prefs, address) == ACCEPTED || address.startsWith(Privacy.DECOY)) return
        Thread({
            val facts = runCatching { factsFor(context, prefs, address) }.getOrNull() ?: return@Thread
            if (!isRequest(facts, settings(prefs))) return@Thread
            decide(prefs, address, ACCEPTED)
            if (facts.service != "SMS") runCatching { GatewayClient.post(prefs, "requests/accept", JSONObject().put("address", address)) }
            SyncJob.syncSoon(context)
        }, "fluid-request-reply").start()
    }

    /**
     * Blocks a number with Android's own blocked numbers (BlockedNumberContract), which carriers and other apps share.
     * Only works while FluidMessages is the default SMS app; false when it can't, and the chat is still blocked here.
     */
    @JvmStatic
    fun systemBlock(context: Context, number: String): Boolean = runCatching {
        if (!SmsStore.validRecipient(number) || !BlockedNumberContract.canCurrentUserBlockNumbers(context)) return false
        val values = ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
        context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values) != null
    }.getOrDefault(false)
}

/**
 * The request rule for one inbox load: each chat's facts worked out once, from rows already read (newest first).
 * [identity] gives the chat a row belongs to, as the inbox groups them.
 */
internal class InboxRequests(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val contacts: ContactDirectory,
    rows: List<JSONObject>,
    identity: java.util.function.Function<JSONObject, String>,
) {
    val settings = MessageRequests.settings(prefs)
    private val sent = HashSet<String>()
    private val favorites by lazy { favoriteAddresses(context, favoriteKeys(prefs, false)) }
    private val cache = HashMap<String, MessageRequests.Facts>()

    init {
        for (row in rows) if (row.optInt("type", 1) != 1 && !row.optBoolean("event")) sent += identity.apply(row)
    }

    /** [family] is the row's protocol family name (MessageProtocol.family()). */
    fun facts(number: String, family: String): MessageRequests.Facts = cache.getOrPut(number + "\u001f" + family) {
        // Decoy chats are made up and never requests; decoy mode shows only decoy data.
        if (number.startsWith(Privacy.DECOY)) return@getOrPut MessageRequests.Facts(service = family, contact = true)
        val route = MessageRequests.route(prefs, number)
        val phone = family == "SMS"
        val group = number.startsWith("mms:") || route?.optBoolean("group") == true
        MessageRequests.Facts(
            service = family,
            group = group,
            invite = route?.optBoolean("invite") == true,
            contact = phone && (!contacts.allowed() || SmsStore.validRecipient(number) && contacts.find(number) != null),
            favorite = !group && favoriteChat(number, favorites),
            sent = number in sent,
            decision = MessageRequests.decision(prefs, number),
            automated = phone && !group && SenderStyle.forAddress(prefs, number) == SenderStyle.AUTOMATED,
            networkService = !phone && MessageRequests.networkService(family,
                route?.optString("inviter")?.takeIf { it.isNotEmpty() } ?: route?.optString("target")),
            stranger = route?.optBoolean("request") == true || route?.optBoolean("invite") == true,
        )
    }

    fun request(number: String, family: String) = MessageRequests.isRequest(facts(number, family), settings)
    fun automated(number: String, family: String) = facts(number, family).automated
    fun automatedOut(number: String, family: String) = settings.automatedOut && automated(number, family)
    fun automatedBlocked(number: String, family: String) = settings.automatedBlock && automated(number, family)

    /** A message from before the chat was deleted as a request: it stays gone. */
    fun gone(number: String, family: String, date: Long) = date <= MessageRequests.deletedAt(facts(number, family).decision)

    /** Kept out of the Messages count: requests, and automated chats kept out of the inbox or blocked. */
    fun quiet(number: String, family: String) = request(number, family) || automatedOut(number, family) || automatedBlocked(number, family)
}
