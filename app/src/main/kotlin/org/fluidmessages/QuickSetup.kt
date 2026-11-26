package org.fluidmessages

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences

/**
 * Quick setup (Settings → Privacy and security, Accounts, Chat services, and the setup's services step): a few named
 * choices for people who don't want to learn every switch. A choice only sets settings that already exist, plus the
 * one switch it needs, Only send encrypted messages ([ENCRYPTED_ONLY]).
 *
 * The choices are data ([CHOICES]): each is a set of setting values. The choice shown is worked out each time by
 * comparing the current settings with each choice in order ([current]), so changing any of those settings by hand
 * shows Custom. Names, who each is for, and what it doesn't protect are all here, so they're easy to change.
 *
 * A choice never turns on something that needs a step the person hasn't done (app lock without a screen lock, Tor
 * where it can't run, Nearby without a name). It says what's left instead ([Plan.steps]). It never turns texting off.
 *
 * FluidMessages Web has the same choices for the settings it has (server/quick-setup.js).
 */
internal object QuickSetup {
    /** Only send encrypted messages: off unless turned on. Sending where a chat can't be encrypted right now is off. */
    const val ENCRYPTED_ONLY = "encryptedOnly"
    const val CUSTOM = "custom"

    // The settings a choice can set, with the defaults the app reads them with.
    const val LOCK = "lock"
    const val LINK_PREVIEWS = "linkPreviews"
    const val LINK_PICTURES = "autoPictures"
    const val GIF_SEARCH = "gifSource"
    const val STRIP_DETAILS = "stripMetadata"
    const val NEARBY_ON = "protocolNEARBY"
    val SERVICES = listOf("protocolSMS", "protocolMMS", "protocolMATRIX", "protocolXMPP", "protocolEMAIL", "protocolIRC")
    val CALLS = listOf("call.MATRIX", "call.XMPP", "call.SIP", "call.MEETING")

    val DEFAULTS: Map<String, Any> = buildMap {
        put(ENCRYPTED_ONLY, false); put(LOCK, false); put(LINK_PREVIEWS, true); put(LINK_PICTURES, true)
        put(NotificationContent.PREF, true); put(PrivateKeyboard.PREF, true); put(STRIP_DETAILS, true); put(GIF_SEARCH, "none")
        put("tor", false); put("tor.callsDirect", false); put(NetworkGate.NEARBY_ONLY, false)
        put(NEARBY_ON, false); put("nearby.visibility", "everyone"); put("nearby.help", true)
        SERVICES.forEach { put(it, true) }
        CALLS.forEach { put(it, true) }
    }

    /** The three questions under the cards. */
    enum class Send(val title: String) { ANYTHING("Anything"), ENCRYPTED("Encrypted messages only"), NEARBY("Nearby only") }

    private val ANYTHING = mapOf(NetworkGate.NEARBY_ONLY to false, ENCRYPTED_ONLY to false)
    private val ENCRYPTED = mapOf(NetworkGate.NEARBY_ONLY to false, ENCRYPTED_ONLY to true)
    private val NEARBY = mapOf(NetworkGate.NEARBY_ONLY to true)
    private val NO_TOR = mapOf("tor" to false)
    private val TOR = mapOf("tor" to true, "tor.callsDirect" to false)
    /** Fewer leaks? No: today's defaults for these. App lock and GIF search stay as they are. */
    private val CONVENIENCE = mapOf(LINK_PREVIEWS to true, LINK_PICTURES to true, NotificationContent.PREF to true,
        PrivateKeyboard.PREF to true, STRIP_DETAILS to true)
    /** Fewer leaks? Yes. */
    private val FEWER_LEAKS = mapOf(LINK_PREVIEWS to false, LINK_PICTURES to false, NotificationContent.PREF to false,
        PrivateKeyboard.PREF to true, STRIP_DETAILS to true, GIF_SEARCH to "none", LOCK to true)
    private val EVERYTHING = SERVICES.associateWith { true } + CALLS.associateWith { true } +
        mapOf(NEARBY_ON to true, "nearby.visibility" to "everyone", "nearby.help" to true)

    data class Choice(
        val id: String,
        val title: String,
        /** Who it's for, in a few words. */
        val forWho: String,
        /** What it does, in one plain sentence. */
        val does: String,
        /** What this doesn't protect. */
        val doesnt: String,
        val values: Map<String, Any>,
    )

    /** In order: the first that matches the current settings is the one shown. */
    val CHOICES = listOf(
        Choice("everything", "Everything on", "You want to reach anyone, anywhere, with every feature.",
            "Turns on every chat service, Nearby, calls, and extras.",
            "Plain texts, IRC, and Email can be read by the companies that carry them. People nearby can see your name and tag.",
            ANYTHING + NO_TOR + CONVENIENCE + EVERYTHING),
        Choice("standard", "Standard", "Most people.",
            "The settings FluidMessages starts with. Your chat services, Nearby, and calls stay as you set them.",
            "Plain texts, IRC, and Email can be read by the companies that carry them.",
            ANYTHING + NO_TOR + CONVENIENCE),
        Choice("lock", "App lock + fewer leaks", "You want more privacy but still want everything to work.",
            "Locks the app with your phone's PIN, fingerprint, or face, and turns off extras that tell others about you.",
            "Doesn't encrypt anything; texts, IRC, and Email are still readable by those who carry them.",
            ANYTHING + NO_TOR + FEWER_LEAKS),
        Choice("encrypted", "Encrypted only", "You don't want to use Tor, but you only want to send messages nobody else can read.",
            "Everything in App lock + fewer leaks, and sending is off in chats that can't be encrypted right now.",
            "Servers can still see who you talk to and when.",
            ENCRYPTED + NO_TOR + FEWER_LEAKS),
        Choice("tor", "Encrypted + Tor", "Journalists, activists, or anyone who worries about being watched or tracked.",
            "Everything in Encrypted only, and your internet connections go through Tor.",
            "Your phone company still sees you use Tor; Tor is slower.",
            ENCRYPTED + TOR + FEWER_LEAKS),
        Choice("nearby", "Nearby only", "Blackouts or places with no internet.",
            "FluidMessages stops using the internet and only talks to phones close by. App lock turns on.",
            "People nearby can see your name and tag.",
            NEARBY + mapOf(LOCK to true)),
    )
    const val CUSTOM_TITLE = "Custom"
    const val CUSTOM_LINE = "You changed some of these settings yourself."

    fun choice(id: String): Choice? = CHOICES.firstOrNull { it.id == id }

    private fun value(values: Map<String, *>, key: String): Any? = values[key] ?: DEFAULTS[key]

    fun matches(choice: Choice, values: Map<String, *>): Boolean = choice.values.all { (key, want) -> value(values, key) == want }

    /** The card last used ("Use this setup"), so Standard and Everything on can share settings without Standard turning anything off. */
    const val CHOSEN = "quickSetup.chosen"

    /**
     * The choice the settings match, or [CUSTOM]. The card you last used wins while its settings still hold; once you
     * change one of them it's Custom. With no card used yet, the first choice that matches.
     */
    fun current(values: Map<String, *>): String {
        val chosen = (values[CHOSEN] as? String)?.let(::choice)
        if (chosen != null) return if (matches(chosen, values)) chosen.id else CUSTOM
        return CHOICES.firstOrNull { matches(it, values) }?.id ?: CUSTOM
    }

    fun title(id: String): String = choice(id)?.title ?: CUSTOM_TITLE

    /** "Quick setup: Standard", for the entry points. */
    @JvmStatic fun label(prefs: SharedPreferences): String = "Quick setup: " + title(current(prefs.all))

    // The questions

    data class Answers(val send: Send, val tor: Boolean, /** null when some extras are on and some off. */ val fewerLeaks: Boolean?)

    fun answers(values: Map<String, *>): Answers {
        val send = when {
            value(values, NetworkGate.NEARBY_ONLY) == true -> Send.NEARBY
            value(values, ENCRYPTED_ONLY) == true -> Send.ENCRYPTED
            else -> Send.ANYTHING
        }
        val fewer = when {
            FEWER_LEAKS.all { (key, want) -> value(values, key) == want } -> true
            CONVENIENCE.all { (key, want) -> value(values, key) == want } -> false
            else -> null
        }
        return Answers(send, value(values, "tor") == true, fewer)
    }

    fun sendValues(send: Send): Map<String, Any> = when (send) { Send.ANYTHING -> ANYTHING; Send.ENCRYPTED -> ENCRYPTED; Send.NEARBY -> NEARBY }
    fun torValues(on: Boolean): Map<String, Any> = if (on) TOR else NO_TOR
    fun leakValues(fewer: Boolean): Map<String, Any> = if (fewer) FEWER_LEAKS else CONVENIENCE

    // What a choice changes

    /** What this phone can do now, for the steps a choice needs. */
    data class Ready(
        val screenLock: Boolean = true,
        val tor: Boolean = true,
        val nearbyName: Boolean = true,
        val bluetooth: Boolean = true,
    )

    data class Plan(
        /** Only the settings that change. */
        val set: Map<String, Any>,
        /** What's left for the person to do, in plain words. */
        val steps: List<String>,
        /** Other things worth knowing, like what happens to texts. */
        val notes: List<String>,
    )

    const val NEEDS_SCREEN_LOCK = "App lock needs a screen lock on this phone. Set a PIN, pattern, or password in Android Settings, then choose this again."
    const val NEEDS_TOR = "This phone can't run Tor inside FluidMessages. Install Orbot, then choose this again."
    const val NEEDS_NEARBY_NAME = "Nearby needs a name first. Set it up in Settings → Chat services → Nearby, then choose this again."
    const val NEEDS_BLUETOOTH = "Nearby can also use Bluetooth. Allow it on the Nearby page."
    const val GIF_KEY = "GIF search needs your own free key. Add one in Settings → Media, GIFs, and fun."
    const val TOR_NOTE = "Tor makes connections slower, and some servers block it. Internet calls are off while Tor is on. Texts and phone calls never use Tor."
    const val TEXTS_ON = "Texts still work: they use your phone plan, not the internet. You can turn them off on the Nearby page."
    const val TEXTS_OFF = "Texts are off while Nearby only is on, as you chose on the Nearby page. You can turn them back on there."

    fun plan(choice: Choice, values: Map<String, *>, ready: Ready): Plan {
        val wanted = choice.values.toMutableMap()
        val steps = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (wanted[LOCK] == true && value(values, LOCK) != true && !ready.screenLock) { wanted.remove(LOCK); steps += NEEDS_SCREEN_LOCK }
        if (wanted["tor"] == true && value(values, "tor") != true && !ready.tor) { wanted.remove("tor"); wanted.remove("tor.callsDirect"); steps += NEEDS_TOR }
        if (wanted[NEARBY_ON] == true && value(values, NEARBY_ON) != true && !ready.nearbyName) {
            listOf(NEARBY_ON, "nearby.visibility", "nearby.help").forEach(wanted::remove)
            steps += NEEDS_NEARBY_NAME
        }
        if (wanted[NEARBY_ON] == true && ready.nearbyName && !ready.bluetooth) notes += NEEDS_BLUETOOTH
        if (choice.id == "everything" && value(values, GIF_SEARCH) == "none") notes += GIF_KEY
        if (wanted["tor"] == true) notes += TOR_NOTE
        // Nearby only keeps texts as its own switch says, and says so; a choice never turns texting off.
        if (wanted[NetworkGate.NEARBY_ONLY] == true) notes += if (values[NetworkGate.TEXTS] == false) TEXTS_OFF else TEXTS_ON
        val set = wanted.filter { (key, want) -> value(values, key) != want }
        return Plan(set, steps, notes)
    }

    /** What a choice sets, in plain words, one line per group of settings. */
    fun lines(choice: Choice): List<String> = describe(choice.values)

    fun describe(values: Map<String, Any>): List<String> = buildList {
        fun on(key: String) = values[key]
        val names = mapOf("protocolSMS" to "texts", "protocolMMS" to "MMS", "protocolMATRIX" to "Matrix", "protocolXMPP" to "XMPP",
            "protocolEMAIL" to "Email", "protocolIRC" to "IRC", "call.MATRIX" to "Matrix", "call.XMPP" to "XMPP", "call.SIP" to "SIP",
            "call.MEETING" to "meeting links")
        val services = SERVICES.filter { values[it] == true }
        if (services.size == SERVICES.size) add("Every chat service on: texts, Matrix, XMPP, Email, and IRC. Each one still needs an account to connect.")
        else if (services.isNotEmpty()) add("Turns on " + services.joinToString(", ") { names.getValue(it) } + ". Each one still needs an account to connect.")
        if (values[NEARBY_ON] == true || "nearby.visibility" in values || "nearby.help" in values)
            add("Nearby on: everyone close by can see you, and your phone helps pass messages along.")
        else if (values[NEARBY_ON] == false) add("Nearby off.")
        val calls = CALLS.filter { values[it] == true }
        if (calls.size == CALLS.size) add("Calls on every service: Matrix, XMPP, SIP, and meeting links.")
        else if (calls.isNotEmpty()) add("Calls on: " + calls.joinToString(", ") { names.getValue(it) } + ".")
        when (on(NetworkGate.NEARBY_ONLY)) {
            true -> add("Nearby only on: FluidMessages doesn't use the internet. It only talks to phones close by.")
            false -> add("Nearby only off.")
        }
        when (on(ENCRYPTED_ONLY)) {
            true -> add("Only send encrypted messages on: sending is off in chats that can't be encrypted right now. You can still read everything.")
            false -> add("Messages send whether or not they're encrypted.")
        }
        when (on("tor")) {
            true -> add("Connect through Tor on: chat servers don't see your internet address. Internet calls are off while Tor is on.")
            false -> add("Connect through Tor off.")
            else -> if (on("tor.callsDirect") == false) add("Internet calls stay off while Tor is on.")
        }
        if (on(LOCK) == true) add("App lock on: your phone's PIN, fingerprint, or face opens FluidMessages.")
        when (on(LINK_PREVIEWS)) {
            true -> add("Link previews on. Links from people you don't know still wait for a tap.")
            false -> add("Link previews off.")
        }
        when (on(LINK_PICTURES)) {
            true -> add("Pictures and GIFs from links show by themselves.")
            false -> add("Pictures and GIFs from links off: you see the link instead.")
        }
        when (on(NotificationContent.PREF)) {
            true -> add("Notifications show names and message text.")
            false -> add("Notifications say only “New text message”.")
        }
        if (on(GIF_SEARCH) == "none") add("GIF search off, so nothing you type goes to Tenor or GIPHY.")
        if (on(PrivateKeyboard.PREF) == true) add("Private keyboard on: your keyboard is asked not to learn what you type.")
        if (on(STRIP_DETAILS) == true) add("Location and camera details are removed from photos you send.")
    }

    // Only send encrypted messages

    @JvmStatic fun encryptedOnly(prefs: SharedPreferences): Boolean = prefs.getBoolean(ENCRYPTED_ONLY, false)

    /** Whether a chat's route is end-to-end encrypted right now, from the same state as its colored lock (SecurityUi). */
    @JvmStatic fun encrypted(encryption: Encryption): Boolean = when (encryption) {
        Encryption.END_TO_END, Encryption.COMPANION_E2EE, Encryption.FLUIDMESSAGES, Encryption.UNVERIFIED -> true
        Encryption.NONE, Encryption.IN_TRANSIT, Encryption.PREVIEW -> false
    }

    private const val ON = "Only send encrypted messages is on. "
    private const val FLUID_FIX = " To send here, turn on FluidMessages encryption in this chat's details, with a key you both type."

    /** Why sending is off in this chat, or "" when it may send. Receiving and reading are never blocked. */
    @JvmStatic fun sendRefused(on: Boolean, protocol: MessageProtocol, encryption: Encryption): String {
        if (!on || encrypted(encryption)) return ""
        return ON + when (protocol) {
            MessageProtocol.SMS, MessageProtocol.MMS, MessageProtocol.RCS -> "This text isn't encrypted, so sending here is off.$FLUID_FIX"
            MessageProtocol.IRC -> "IRC can't encrypt messages, so sending here is off.$FLUID_FIX"
            MessageProtocol.EMAIL -> "Email isn't encrypted end to end, so sending here is off.$FLUID_FIX"
            MessageProtocol.MATRIX -> "This Matrix room isn't encrypted, so sending here is off. A room admin can turn on encryption.$FLUID_FIX"
            MessageProtocol.XMPP -> "This XMPP chat isn't encrypted with OMEMO right now, so sending here is off. Check OMEMO in this chat's details."
            MessageProtocol.NEARBY -> "This Nearby channel is open, so anyone close by can read it. Sending here is off."
        }
    }

    /** The mark beside a service in the account and service lists while Only send encrypted messages is on, or "". */
    @JvmStatic fun serviceMark(on: Boolean, protocol: MessageProtocol): String = if (!on) "" else when (protocol) {
        MessageProtocol.IRC, MessageProtocol.EMAIL, MessageProtocol.SMS, MessageProtocol.MMS, MessageProtocol.RCS ->
            "Can't encrypt by itself · sends only with FluidMessages encryption"
        MessageProtocol.MATRIX -> "Sends only in encrypted rooms"
        MessageProtocol.XMPP -> "Sends only when OMEMO is on"
        MessageProtocol.NEARBY -> "Sends only in people chats and rooms with a passphrase"
    }

    /** The service's line in a list, with [serviceMark] after it. */
    @JvmStatic fun serviceLine(prefs: SharedPreferences, protocol: MessageProtocol, line: String): String =
        serviceMark(encryptedOnly(prefs), protocol).let { if (it.isEmpty()) line else "$line · $it" }

    /** A service's name with a short mark, for the Accounts cards: "IRC · Can't encrypt". */
    @JvmStatic fun serviceTitle(prefs: SharedPreferences, protocol: MessageProtocol): String = if (!encryptedOnly(prefs)) protocol.label
        else protocol.label + " · " + when (protocol) {
            MessageProtocol.MATRIX -> "Encrypted rooms only"
            MessageProtocol.XMPP -> "OMEMO chats only"
            MessageProtocol.NEARBY -> "Encrypted chats only"
            else -> "Can't encrypt"
        }

    // On the phone

    @JvmStatic fun ready(context: Context): Ready = Ready(
        screenLock = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true,
        tor = BuiltInTor.supported() || TorRoute.orbotInstalled(context),
        nearbyName = DirectNearby.identity(context) != null,
        bluetooth = DirectNearby.bluetoothAllowed(context),
    )

    /** Saves [plan] and restarts what has to notice: Tor, connections, and Nearby. */
    fun apply(context: Context, prefs: SharedPreferences, plan: Plan, chosen: String?) {
        if (chosen != null) prefs.edit().putString(CHOSEN, chosen).apply()
        if (plan.set.isEmpty()) return
        save(prefs, plan.set)
        if ("tor" in plan.set) TorRoute.set(prefs, plan.set["tor"] == true)
        if (plan.set["tor"] == true) TorRoute.start(context)
        Thread({ runCatching { DirectProtocols.restart(context) }; runCatching { SipAgent.sync(context) } }, "fluid-quick-setup").start()
    }

    fun save(prefs: SharedPreferences, values: Map<String, Any>) {
        val edit = prefs.edit()
        for ((key, value) in values) when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is String -> edit.putString(key, value)
            is Int -> edit.putInt(key, value)
        }
        edit.apply()
    }
}
