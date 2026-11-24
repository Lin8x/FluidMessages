package org.fluidmessages

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message requests and automated senders: who counts as someone you don't know, the defaults per service, where
 * requests and automated chats show, how they alert, and the synced items. scripts/test-local runs the same cases
 * against FluidMessages Web's rules (server/requests.js).
 */
class MessageRequestsTest {
    private val defaults = MessageRequests.Settings()
    private val texts = defaults.copy(texts = true)
    private fun facts(service: String, vararg on: String, decision: String = "") = MessageRequests.Facts(service = service,
        group = "group" in on, invite = "invite" in on, contact = "contact" in on, favorite = "favorite" in on, sent = "sent" in on,
        decision = decision, automated = "automated" in on, networkService = "service" in on, stranger = "stranger" in on)

    @Test fun textsFromUnknownNumbersAreRequestsOnlyWhenFiltered() {
        // Off by default, like iOS, so delivery and sign-in codes are never missed.
        assertFalse(defaults.texts)
        assertFalse(MessageRequests.isRequest(facts("SMS"), defaults))
        assertTrue(MessageRequests.isRequest(facts("SMS"), texts))
        assertFalse("a contact", MessageRequests.isRequest(facts("SMS", "contact"), texts))
        assertFalse("a favorite", MessageRequests.isRequest(facts("SMS", "favorite"), texts))
        assertFalse("you replied", MessageRequests.isRequest(facts("SMS", "sent"), texts))
        assertFalse("a group text", MessageRequests.isRequest(facts("SMS", "group"), texts))
        assertFalse("automated senders go under Automated", MessageRequests.isRequest(facts("SMS", "automated"), texts))
        assertFalse("accepted", MessageRequests.isRequest(facts("SMS", decision = "accepted"), texts))
        // Deleted is not accepted: a new message makes it a request again.
        assertTrue(MessageRequests.isRequest(facts("SMS", decision = "deleted:1700000000000"), texts))
    }

    @Test fun internetStrangersAreRequestsByDefault() {
        assertTrue(defaults.chats)
        for (service in listOf("MATRIX", "XMPP", "IRC", "EMAIL")) {
            assertTrue(service, MessageRequests.isRequest(facts(service, "stranger"), defaults))
            assertFalse("$service off", MessageRequests.isRequest(facts(service, "stranger"), defaults.copy(chats = false)))
            assertFalse("$service you linked", MessageRequests.isRequest(facts(service), defaults))
            assertFalse("$service you messaged", MessageRequests.isRequest(facts(service, "stranger", "sent"), defaults))
            assertFalse("$service contact", MessageRequests.isRequest(facts(service, "stranger", "contact"), defaults))
            assertFalse("$service favorite", MessageRequests.isRequest(facts(service, "stranger", "favorite"), defaults))
            assertFalse("$service accepted", MessageRequests.isRequest(facts(service, "stranger", decision = "accepted"), defaults))
        }
        // The chat-services switch doesn't change texts, and the texts switch doesn't change chats.
        assertFalse(MessageRequests.isRequest(facts("SMS"), defaults.copy(chats = true)))
        assertTrue(MessageRequests.isRequest(facts("IRC", "stranger"), defaults.copy(texts = false)))
    }

    @Test fun invitesServicesAndJoinedChannels() {
        assertTrue("a Matrix DM invite", MessageRequests.isRequest(facts("MATRIX", "invite", "stranger"), defaults))
        assertTrue("a Matrix room invite", MessageRequests.isRequest(facts("MATRIX", "invite", "group", "stranger"), defaults))
        assertTrue("an XMPP subscription request", MessageRequests.isRequest(facts("XMPP", "invite", "stranger"), defaults))
        assertFalse("an accepted invite", MessageRequests.isRequest(facts("MATRIX", "invite", decision = "accepted"), defaults))
        assertFalse("NickServ", MessageRequests.isRequest(facts("IRC", "stranger", "service"), defaults))
        assertFalse("a channel you joined", MessageRequests.isRequest(facts("IRC", "group"), defaults))
        assertFalse("a room you joined", MessageRequests.isRequest(facts("MATRIX", "group"), defaults))
        assertFalse("a group chat that wrote first", MessageRequests.isRequest(facts("XMPP", "group", "stranger"), defaults))
    }

    @Test fun networkServicesAreNeverPeople() {
        for (nick in listOf("NickServ", "ChanServ", "MemoServ", "OperServ", "HostServ", "BotServ", "Global", "*status", "irc.example.net")) {
            assertTrue(nick, MessageRequests.networkService("IRC", nick))
        }
        assertFalse(MessageRequests.networkService("IRC", "kestrel"))
        assertFalse(MessageRequests.networkService("IRC", "servant"))
        assertTrue("an XMPP server", MessageRequests.networkService("XMPP", "example.org"))
        assertFalse(MessageRequests.networkService("XMPP", "amir@example.org"))
        assertTrue(MessageRequests.networkService("MATRIX", "@server:example.org"))
        assertFalse(MessageRequests.networkService("MATRIX", "@maya:example.org"))
        assertTrue(MessageRequests.networkService("EMAIL", "MAILER-DAEMON@example.org"))
        assertFalse(MessageRequests.networkService("EMAIL", "leah@example.org"))
        assertTrue("nobody", MessageRequests.networkService("IRC", ""))
    }

    @Test fun deletedRequestsRememberWhen() {
        assertEquals(1700000000000L, MessageRequests.deletedAt("deleted:1700000000000"))
        assertEquals(0L, MessageRequests.deletedAt("accepted"))
        assertEquals(0L, MessageRequests.deletedAt("deleted:soon"))
        assertTrue(MessageRequests.validDecision("accepted"))
        assertFalse(MessageRequests.validDecision("blocked"))
    }

    @Test fun requestsAndAutomatedChatsShowInTheirOwnFilters() {
        val request = InboxFilters.Chat(group = false, unread = true, request = true)
        val scope = InboxFilters.Scope.INBOX
        assertTrue(InboxFilters.shows(scope, "requests", request))
        for (id in listOf("inbox", "unread", "texts", "direct")) assertFalse(id, InboxFilters.shows(scope, id, request))
        assertFalse("a blocked request", InboxFilters.shows(scope, "requests", request.copy(blocked = true)))
        assertTrue(InboxFilters.shows(scope, "blocked", request.copy(blocked = true)))
        val automated = InboxFilters.Chat(group = false, automated = true)
        assertTrue(InboxFilters.shows(scope, "automated", automated))
        assertTrue("in the inbox by default", InboxFilters.shows(scope, "inbox", automated))
        assertFalse(InboxFilters.shows(scope, "automated", InboxFilters.Chat(group = false)))
        val out = automated.copy(automatedOut = true)
        assertTrue(InboxFilters.shows(scope, "automated", out))
        for (id in listOf("inbox", "unread", "texts")) assertFalse(id, InboxFilters.shows(scope, id, out))
        // Decoy mode and the private folder: requests keep to their scope like every other filter.
        assertFalse(InboxFilters.shows(InboxFilters.Scope.DECOY, "requests", request))
        assertTrue(InboxFilters.shows(InboxFilters.Scope.DECOY, "requests", request.copy(decoy = true)))
        assertFalse(InboxFilters.shows(scope, "requests", request.copy(hidden = true)))
        assertTrue(InboxFilters.shows(InboxFilters.Scope.PRIVATE, "requests", request.copy(hidden = true)))
        assertTrue(InboxFilters.of("requests") != null && InboxFilters.of("automated") != null)
    }

    @Test fun automatedSettingsAndRequestAlerts() {
        assertEquals(MessageRequests.Alert.LOUD, MessageRequests.alert(false, false, defaults))
        assertEquals(MessageRequests.Alert.QUIET, MessageRequests.alert(true, false, defaults))
        assertEquals(MessageRequests.Alert.NONE, MessageRequests.alert(true, false, defaults.copy(notifyQuiet = false)))
        // Automated senders notify as usual until you say otherwise; all three switches start off.
        assertFalse(defaults.automatedOut || defaults.automatedQuiet || defaults.automatedBlock)
        assertEquals(MessageRequests.Alert.LOUD, MessageRequests.alert(false, true, defaults))
        assertEquals(MessageRequests.Alert.NONE, MessageRequests.alert(false, true, defaults.copy(automatedQuiet = true)))
        assertEquals(MessageRequests.Alert.NONE, MessageRequests.alert(false, true, defaults.copy(automatedBlock = true)))
        assertEquals(MessageRequests.Alert.LOUD, MessageRequests.alert(false, false, defaults.copy(automatedQuiet = true)))
    }

    @Test fun settingsAndDecisionsSyncAsItemsAndBack() {
        val prefs = MemoryPrefs()
        assertEquals(emptyMap<String, String>(), MessageRequests.syncItems(prefs) { false })
        prefs.edit().putBoolean(MessageRequests.TEXTS, true).putBoolean(MessageRequests.CHATS, false).putString(MessageRequests.NOTIFY, "off")
            .putBoolean(MessageRequests.AUTOMATED_BLOCK, true).putString("request.+12025550177", "accepted")
            .putString("request.+12025550180", "deleted:1700000000000").putString("request.+12025550199", "nonsense")
            .putString("request.decoy:1", "accepted").apply()
        val items = MessageRequests.syncItems(prefs) { it.startsWith("decoy:") }
        assertEquals(mapOf("requests:texts" to "on", "requests:chats" to "off", "requests:notify" to "off", "requests:automatedBlock" to "on",
            "request:+12025550177" to "accepted", "request:+12025550180" to "deleted:1700000000000"), items)
        val other = MemoryPrefs()
        val edit = other.edit()
        for ((key, value) in items) MessageRequests.applySync(edit, SyncMerge.group(key), SyncMerge.name(key), value)
        edit.apply()
        assertEquals(items, MessageRequests.syncItems(other) { false })
        // Back to the defaults: no items at all.
        val reset = other.edit()
        for (key in items.keys) MessageRequests.applySync(reset, SyncMerge.group(key), SyncMerge.name(key), null)
        reset.apply()
        assertEquals(emptyMap<String, String>(), MessageRequests.syncItems(other) { false })
        assertEquals(MessageRequests.Settings(), MessageRequests.settings(other))
    }
}

/** SharedPreferences in memory, for the settings tests. */
private class MemoryPrefs : SharedPreferences {
    val values = HashMap<String, Any?>()
    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val changes = HashMap<String, Any?>()
        val removed = HashSet<String>()
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removed += key; changes.remove(key) }
        override fun clear() = apply { removed += values.keys }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { for (key in removed) values.remove(key); values.putAll(changes) }
    }
}
