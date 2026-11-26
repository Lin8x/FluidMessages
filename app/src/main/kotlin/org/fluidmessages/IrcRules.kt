package org.fluidmessages

/**
 * IRC rules the phone's direct connection (DirectIrc) shares with the companion (server/irc_client.py): which nicks
 * are network services, nick_ fallback when a nick is taken, mentions of your nick, how much text fits in one line,
 * and pacing so the network doesn't disconnect you for flooding.
 */
internal object IrcRules {
    private val services = setOf("nickserv", "chanserv", "memoserv", "operserv", "hostserv", "botserv", "saslserv", "helpserv",
        "infoserv", "statserv", "global", "alis", "q", "x", "authserv", "groupserv")
    private const val NICK_EDGE = "[\\w\\[\\]\\\\`^{|}-]"
    /** What the server puts before a relayed line besides your nick: ":", "!user@host", and a space. */
    private const val PREFIX_ROOM = 76
    const val MAX_PAYLOAD = 400

    /** Network services and common bots, labeled as automated chats. */
    @JvmStatic
    fun isService(nick: String): Boolean {
        val lowered = nick.lowercase()
        return lowered in services || lowered.endsWith("serv") || lowered.endsWith("bot")
    }

    /** nick_, nick__, then nick1…nick9 within 30 characters, when the nickname is taken while connecting. */
    @JvmStatic
    fun fallbackNick(nick: String, tries: Int): String {
        val base = nick.trimEnd('_', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9').ifEmpty { nick }
        return if (tries <= 2) base.take(30 - tries) + "_".repeat(tries) else base.take(28) + (tries - 2)
    }

    /** Your nick as a word: "kit: hi" and "hi kit!" count, "kitten" doesn't. */
    @JvmStatic
    fun mentions(text: String, vararg nicks: String): Boolean {
        val names = nicks.filter { it.isNotEmpty() }.distinct().joinToString("|") { Regex.escape(it) }
        if (names.isEmpty()) return false
        return Regex("(?<!$NICK_EDGE)(?:$names)(?!$NICK_EDGE)", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    /** Bytes of text that fit in one line to [target] once the server adds its prefix. */
    @JvmStatic
    fun limit(command: String, target: String, nick: String): Int =
        minOf(MAX_PAYLOAD, 510 - "$command $target :".toByteArray().size - nick.toByteArray().size - PREFIX_ROOM)

    /** Network service and bot direct chats, and notices, are automated. */
    @JvmStatic
    fun automated(nick: String, notice: Boolean) = notice || isService(nick)

    /** The reconnect schedule: 15 s, 30 s, 1 min, 2 min, 5 min, then 10 min, the same as FluidMessages Web's. */
    val backoffSeconds = listOf(15L, 30L, 60L, 120L, 300L, 600L)
    const val RECONNECTS_PER_HOUR = 5

    /** Milliseconds before automatic reconnect number [attempt] (0 is the first after a drop), ±20% by [random] in 0..1. */
    @JvmStatic
    fun backoffMillis(attempt: Int, random: Double): Long {
        val base = backoffSeconds[attempt.coerceIn(0, backoffSeconds.size - 1)] * 1000
        return (base * (0.8 + 0.4 * random.coerceIn(0.0, 1.0))).toLong()
    }

    /** Channels rejoined right after connecting: those with Keep joined on (the default). The rest join when opened. */
    @JvmStatic
    fun keepJoined(channels: List<String>, keep: (String) -> Boolean): List<String> = channels.filter(keep)

    /** The nicknames IRC networks accept: a letter or one of []\`_^{|} first, then those, digits, or -. */
    private val nickPattern = Regex("[A-Za-z\\[\\]\\\\`_^{|}][A-Za-z0-9\\[\\]\\\\`_^{|}-]{0,31}")

    @JvmStatic
    fun validNick(nick: String) = nickPattern.matches(nick)

    /** What the account form says about a nickname a network won't take, or null when it's fine. */
    @JvmStatic
    fun nickProblem(nick: String): String? = when {
        nick.isBlank() -> "Pick a nickname."
        validNick(nick) -> null
        nick.first().isDigit() || nick.first() == '-' -> "A nickname can’t start with a number or a dash. Start it with a letter."
        nick.length > 32 -> "That nickname is too long. Use 16 letters or fewer."
        else -> "A nickname can only use letters, numbers, and - _ [ ] { } \\ | ^ `. No spaces or dots."
    }

    /** A connection problem in plain words. [fatal]: trying again won't help until you change something or tap Reconnect. */
    data class Problem(val text: String, val fatal: Boolean)

    private val blocked = Regex("k-?lined|g-?lined|z-?lined|d-?lined|banned|not welcome|you are not allowed", RegexOption.IGNORE_CASE)

    private val torMention = Regex("\\btor\\b|onion|exit node", RegexOption.IGNORE_CASE)

    /** A network that won't take this connection through Tor. FluidMessages Web says the same (irc_client.explain). */
    private fun torRefusal(network: String, sasl: Boolean, quote: String) = Problem("$network doesn’t let you in through Tor this way. "
        + "Many IRC networks, like Libera.Chat, only allow Tor for a registered nickname signed in with SASL, and some block Tor. "
        + (if (sasl) "Check the account name and password in the IRC account settings, or turn off Connect through Tor."
        else "Register your nickname without Tor first, then type its password in Password in the IRC account settings. "
            + "Or turn off Connect through Tor.") + quote, true)

    /** Whether a server notice or closing reason says this connection must sign in with SASL first. */
    @JvmStatic
    fun needsSasl(text: String) = Regex("\\bsasl\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        && Regex("requir|need|must|only|identify|authenticat", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /**
     * Why [network] refused or closed the connection, in plain words with what to do, from the reply [command]
     * ("ERROR", "465", "432", "904"…) and its [text], which is quoted as the network said it. [sasl]: a SASL password
     * is set. Null for replies that aren't a problem with connecting.
     */
    @JvmStatic @JvmOverloads
    fun explain(command: String, text: String, network: String, nick: String, sasl: Boolean, tor: Boolean = false): Problem? {
        val said = text.trim().take(200)
        val quote = if (said.isEmpty()) "" else " It said: “$said”"
        // Through Tor (TorRoute), a refusal is almost always about Tor: Libera.Chat and others only take Tor with SASL, or not at all.
        if (tor && (command == "ERROR" || command == "465" || command == "463") &&
            (needsSasl(said) || blocked.containsMatchIn(said) || torMention.containsMatchIn(said))) return torRefusal(network, sasl, quote)
        return when (command) {
            "ERROR" -> when {
                needsSasl(said) -> Problem("$network only lets people signed in to a registered nickname connect from your internet connection. "
                    + "This often happens on a VPN, a work or school network, or a cloud server. " + (if (sasl)
                    "Check the account name and password in the IRC account settings." else
                    "Register your nickname from another connection, then type its password in Password in the IRC account settings.") + quote, true)
                blocked.containsMatchIn(said) -> Problem("$network blocked this connection.$quote Try another network, or another internet connection.", true)
                Regex("ping timeout|connection reset|broken pipe", RegexOption.IGNORE_CASE).containsMatchIn(said) -> Problem("The connection to $network dropped.", false)
                said.contains("Client Quit", ignoreCase = true) -> null
                else -> Problem("$network closed the connection.$quote", false)
            }
            "465", "463", "464" -> Problem("$network won’t let you connect.$quote " + if (command == "464")
                "Check the password in the account settings." else "Try another network, or another internet connection.", true)
            "432" -> Problem("$network doesn’t allow the nickname “$nick”. Pick another one in the account settings: start it with a letter, "
                + "and use only letters, numbers, - or _.", true)
            "433", "436", "437" -> Problem("Every nickname we tried on $network is taken. Pick another nickname in the IRC account settings.", true)
            "904", "905" -> if (sasl) Problem("Signing in to your nickname on $network didn’t work. Check the account name (the name you registered) "
                + "and password in the IRC account settings.$quote", false) else null
            "906", "907" -> null
            else -> null
        }
    }

    /**
     * An error reply to something you did (a command, a message), in plain words with what to do. [about] is the
     * nickname or channel it's about, [said] the network's own words, used when the reply is one this doesn't know.
     * FluidMessages Web says the same (irc_client.error_text).
     */
    @JvmStatic
    fun errorText(code: String, about: String, said: String): String {
        val it = about.ifEmpty { "that" }
        return when (code) {
            "401", "406" -> "$it isn’t on the network right now. Check the spelling, or try again later."
            "402" -> "That server isn’t part of this network."
            "403" -> "There’s no channel called $it. Check the spelling, or browse the channel list."
            "404" -> "You can’t write in $it right now. You may need to join it first, or its moderators only let some people talk."
            "405" -> "You’re in as many channels as this network allows. Leave one, then try again."
            "411", "412" -> "There was nothing to send. Type a message first."
            "421" -> "The network doesn’t know the command “$it”. Type /help to see the commands you can use."
            "432" -> "The network doesn’t allow the nickname “$it”. Start it with a letter, and use only letters, numbers, - or _."
            "433", "436" -> "The nickname $it is already taken. Pick another one."
            "437" -> "$it can’t be used right now. Try again in a few minutes, or pick another one."
            "441" -> "$it isn’t in that channel."
            "442" -> "You’re not in $it. Join it first."
            "443" -> "$it is already in that channel."
            "461" -> "That command is missing something. Type /help to see how to use it."
            "472", "501" -> "The network doesn’t know that setting."
            "478" -> "That list in $it is full. A moderator has to remove an entry first."
            "481" -> "Only the people who run the network can do that."
            "482" -> "Only moderators of $it can do that. Moderators show with @ before their name."
            "484" -> "The network limits what this connection can do, so it can’t do that."
            "485" -> "Only the channel’s owner can do that."
            "489" -> "$it only lets in people on a secure connection."
            "491" -> "This network doesn’t let you become one of its staff from here."
            "502" -> "You can only change your own settings."
            else -> said.trim().ifEmpty { "The network said no, without a reason." }
        }
    }

    /**
     * The inbox mark's state, from a connection's state: "connected", "connecting" (also while waiting to try again),
     * "off" (the service is turned off, or you turned the connection off: [manual]), or "disconnected". Nothing known
     * yet counts as not connected, so a server chat never shows no mark. The same rule as connections.js.
     */
    @JvmStatic @JvmOverloads
    fun connectionState(state: String, manual: Boolean = false): String = when {
        state == "connected" -> "connected"
        state == "connecting" || state == "reconnecting" -> "connecting"
        state == "off" || manual -> "off"
        else -> "disconnected"
    }

    /** The words read out for the inbox's connection mark. */
    @JvmStatic
    fun connectionLabel(state: String): String = when (state) {
        "connected" -> "Connected"
        "connecting" -> "Connecting"
        "disconnected" -> "Not connected"
        "off" -> "Turned off"
        else -> ""
    }

    /**
     * The first line of a Connection section: "Connected to Libera.Chat as kit", "Connecting to Libera.Chat…",
     * "Not connected to Libera.Chat · <why>", or "Turned off · …". [serviceOff]: the service itself is off.
     */
    @JvmStatic
    fun connectionSummary(state: String, network: String, nick: String, error: String, serviceOff: Boolean): String {
        val why = error.trim().let { if (it.isEmpty()) "" else " · $it" }
        return when (state) {
            "connected" -> "Connected to $network" + if (nick.isEmpty()) "" else " as $nick"
            "connecting" -> "Connecting to $network…"
            "off" -> if (serviceOff) "Turned off · Turn this service on in Settings → Chat services"
                else "Turned off · Tap Connect to connect to $network$why"
            else -> "Not connected to $network$why"
        }
    }
}

/**
 * At most [limit] automatic reconnects in any hour per network. Once used up, the phone stops and shows
 * "Disconnected · Reconnect"; reconnecting by hand doesn't count.
 */
internal class ReconnectBudget(private val limit: Int = IrcRules.RECONNECTS_PER_HOUR, private val windowMillis: Long = 3_600_000) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryUse(now: Long): Boolean {
        while (times.isNotEmpty() && now - times.first() >= windowMillis) times.removeFirst()
        if (times.size >= limit) return false
        times.addLast(now)
        return true
    }

    @Synchronized
    fun reset() = times.clear()
}

/**
 * A burst of [burst] lines, then one every [everyMillis], like most IRC clients. [wait] returns how long to sleep
 * before the next line may go (0 when it may go now) and takes that line's place.
 */
internal class IrcFlood(private val burst: Int = 5, private val everyMillis: Long = 2000, private val clock: () -> Long = System::currentTimeMillis) {
    private var tokens = burst.toDouble()
    private var at = clock()

    @Synchronized
    fun wait(): Long {
        val now = clock()
        tokens = minOf(burst.toDouble(), tokens + (now - at).toDouble() / everyMillis)
        at = now
        if (tokens >= 1) {
            tokens -= 1
            return 0
        }
        val pause = ((1 - tokens) * everyMillis).toLong()
        tokens = 0.0
        at = now + pause
        return pause
    }
}
