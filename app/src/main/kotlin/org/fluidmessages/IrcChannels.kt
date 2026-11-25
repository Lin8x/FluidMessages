package org.fluidmessages

import java.util.Locale
import java.util.PriorityQueue

/** One channel from a network's public list (IRC LIST): its name, how many people are in it, and its topic. */
internal data class IrcChannelRow(val name: String, val people: Int, val topic: String)

/**
 * Finding and joining IRC channels on the phone's own connection (DirectIrc), the same way FluidMessages Web does it
 * (server/irc_client.py list_channels): ask the network for its public list, keep only what matches, and cap it.
 * Everything here is plain logic, so it's tested on the JVM (IrcChannelsTest).
 */
internal object IrcChannels {
    /** How many channels a search shows, and the most a caller may ask for. */
    const val LIMIT = 100
    const val MAX_LIMIT = 200
    /** Big networks send thousands of rows, so stop waiting after this long and show what arrived. */
    const val TIMEOUT_MILLIS = 20_000L
    /** At least this long between two LIST requests, so searching never floods the network. */
    const val GAP_MILLIS = 3_000L
    /** With no search, only channels with more people than this are asked for where the network can filter (ELIST=U). */
    const val BUSY_ABOVE = 9
    const val TOPIC_LENGTH = 300

    private val valid = Regex("[#&][^\\s,\\u0007]{1,49}")
    private val formatting = Regex("\\u0003(?:\\d{1,2}(?:,\\d{1,2})?)?|[\\u0000-\\u001f\\u007f]")
    private val modes = Regex("^\\[\\+[A-Za-z]*(?: [^\\]]*)?] ?")

    /** Real, long-lived public channels on well-known networks, shown as "Popular here" before the live list loads. */
    private val popular = mapOf(
        "irc.libera.chat" to listOf("#libera", "#linux", "#python", "#ubuntu", "#archlinux"),
        "irc.oftc.net" to listOf("#oftc", "#debian", "#tor"),
    )

    @JvmStatic
    fun popular(host: String): List<String> = popular[host.trim().lowercase(Locale.ROOT)].orEmpty()

    /**
     * The channel a person meant: "linux", " #linux ", and "#linux," are all #linux. Null when it can't be a channel
     * name (empty, too long, or with characters IRC doesn't allow).
     */
    @JvmStatic
    fun channelName(typed: String): String? {
        val word = typed.trim().split(Regex("[\\s,]+")).firstOrNull { it.isNotEmpty() } ?: return null
        val name = if (word.startsWith("#") || word.startsWith("&")) word else "#$word"
        return name.takeIf { valid.matches(it) }
    }

    /** Whether what was typed is already a whole channel name, such as a pasted "#linux". */
    fun looksLikeChannel(typed: String): Boolean = typed.trim().let { it.length > 1 && it.startsWith("#") && channelName(it) == it }

    /** What the network can filter itself, from its "ELIST=…" feature line (005); "" when it doesn't say. */
    @JvmStatic
    fun elist(features: List<String>): String =
        features.firstOrNull { it.startsWith("ELIST=", ignoreCase = true) }?.substring(6)?.uppercase(Locale.ROOT).orEmpty()

    /** The words of a search, lowercased, without anything that could change the LIST line. */
    private fun words(search: String): List<String> = ServerDirectory.fold(search).split(Regex("\\s+"))
        .map { word -> word.filter { it > ' ' && it != ',' && it != '\u007f' }.take(50) }.filter { it.isNotEmpty() }

    /**
     * The line that asks for the list. A network that can match names (ELIST has M) searches for the first word
     * itself; one that can filter by size (U) skips its tiny channels when there's no search. Any other network sends
     * everything, and [IrcChannelList] filters and caps it here.
     */
    @JvmStatic
    fun command(search: String, elist: String): String {
        val words = words(search)
        val word = words.firstOrNull().orEmpty().trim('#', '&')
        return when {
            word.isNotEmpty() && 'M' in elist -> "LIST " + if ('*' in word || '?' in word) word else "*$word*"
            words.isEmpty() && 'U' in elist -> "LIST >$BUSY_ABOVE"
            else -> "LIST"
        }
    }

    /** A LIST reply row (322: you, channel, people, topic). Null for rows that aren't a channel, such as hidden ones. */
    @JvmStatic
    fun row(params: List<String>): IrcChannelRow? {
        if (params.size < 3) return null
        val name = params[1]
        if (!valid.matches(name)) return null
        return IrcChannelRow(name, params[2].toIntOrNull()?.coerceAtLeast(0) ?: 0, topic(params.getOrElse(3) { "" }))
    }

    /** A topic without the "[+nt]" mode prefix some networks add, colors, or control characters, cut to a sane length. */
    fun topic(raw: String): String = raw.replace(modes, "").replace(formatting, "").trim().take(TOPIC_LENGTH)

    /** "1 person", "42 people", and the topic, for a channel's row. */
    fun detail(row: IrcChannelRow): String =
        listOf(if (row.people > 0) "${row.people} ${if (row.people == 1) "person" else "people"}" else "", row.topic)
            .filter { it.isNotEmpty() }.joinToString(" · ")

    /**
     * Why a channel didn't let you in, in plain words with what to do, from the network's reply number and its words
     * ([said]). [saved]: a NickServ password is saved on the account. FluidMessages Web says the same (irc_client.join_error).
     */
    @JvmStatic
    @JvmOverloads
    fun joinError(code: String, channel: String, network: String, said: String, saved: Boolean = false): String = when {
        IrcJoins.needsSignIn(code, said) -> IrcJoins.signInNeeded(channel, saved)
        code == "403" || code == "476" || code == "479" -> "$network has no channel called $channel. Check the spelling, or browse the channel list."
        code == "405" -> "You’re in as many channels as $network allows. Leave one, then try again."
        code == "471" -> "$channel is full right now. Try again later."
        code == "473" -> "$channel is invite-only: only people someone invited can join. Ask someone in it to invite you. " +
            "The invitation shows up in Invitations."
        code == "474" -> "You can’t join $channel because its moderators banned you."
        code == "475" -> "$channel is locked with a password. Ask someone in it for the password, then join with it."
        code == "520" -> "$channel is only for the people who run $network."
        else -> "Couldn’t join $channel" + if (said.isBlank()) "." else ". $network said: “${said.trim().take(200)}”"
    }

    /** What the Join and Browse pages say while the phone's connection isn't ready, and the button that helps ("" for none). */
    fun waiting(state: String, network: String, enabled: Boolean): Pair<String, String> = when {
        !enabled -> "IRC is turned off in Settings → Chat services. Turn it on to connect to $network." to ""
        state == "connecting" -> "Connecting to $network…" to ""
        state == "reconnecting" -> "The connection to $network dropped. It will try again soon." to "Connect now"
        state == "offline" -> "Your phone is offline. $network connects when you’re back online." to "Try now"
        state == "disconnected" -> "$network kept dropping the connection, so FluidMessages stopped trying." to "Connect"
        else -> "You’re not connected to $network yet." to "Connect"
    }
}

/**
 * Collects a LIST reply as it streams in: keeps only channels matching every word of the search (in the name or
 * topic), and of those only the [limit] busiest, so thousands of rows never pile up in memory.
 */
internal class IrcChannelList(search: String, limit: Int = IrcChannels.LIMIT) {
    private val words = ServerDirectory.fold(search).split(Regex("\\s+")).filter { it.isNotEmpty() }
    private val limit = limit.coerceIn(1, IrcChannels.MAX_LIMIT)
    private val kept = PriorityQueue<IrcChannelRow>(compareBy<IrcChannelRow> { it.people }.thenByDescending { it.name })
    private val names = HashSet<String>()
    private var matched = 0

    /** Adds one 322 row's parameters. */
    @Synchronized
    fun add(params: List<String>) {
        val row = IrcChannels.row(params) ?: return
        if (words.isNotEmpty()) {
            val text = ServerDirectory.fold(row.name + " " + row.topic)
            if (!words.all { it in text }) return
        }
        if (!names.add(AccountKeys.targetKey("irc", row.name))) return
        matched++
        kept.add(row)
        if (kept.size > limit) kept.poll()
    }

    /** How many channels matched, including those past the cap. */
    val total: Int @Synchronized get() = matched

    /** The kept channels, busiest first. */
    @Synchronized
    fun result(): List<IrcChannelRow> = kept.sortedWith(compareByDescending<IrcChannelRow> { it.people }.thenBy { it.name.lowercase(Locale.ROOT) })
}
