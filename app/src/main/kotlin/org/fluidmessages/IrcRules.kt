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
