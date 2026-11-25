package org.fluidmessages

import org.json.JSONObject

/**
 * What a network's "you can't join this channel" reply means, and what FluidMessages does about it on its own, on the
 * phone's own connection (DirectIrc). FluidMessages Web has the same rules (server/irc_client.py join_need and
 * prerequisite), and scripts/test-local checks both with the same fictional server texts.
 *
 * - A channel for signed-in nicknames only (477, or a 473/474-style reply that says you must be identified or
 *   registered): with a password saved, joins wait until you're signed in, and the channel is tried once more after.
 * - A channel that names another one to join first ("You must join #lobby first"): that one is joined first, the
 *   channel is tried again, and the order is remembered so reconnecting joins them in the same order.
 */
internal object IrcJoins {
    /** Replies that mean a JOIN didn't work. 470 (sent somewhere else) is handled on its own and isn't here. */
    @JvmField val CODES = setOf("403", "405", "471", "473", "474", "475", "476", "477", "479", "489", "520")
    /** At most this many channels to join first for one channel, so a strange reply can't make a long chain. */
    const val MOST_FIRST = 3

    private const val NAME = "([#&][^\\s,:\\u0007()\"'`<>\\[\\]]+)"
    /** "You must join #help first", "you need to be in #lobby", "join #lobby before #chat", "join #rules first". */
    private val firstPatterns = listOf(
        Regex("\\b(?:must|need to|needs to|have to|has to|required to|please)\\s+(?:first\\s+)?(?:join|be in|be on|be a member of)\\s+(?:channel\\s+)?$NAME",
            RegexOption.IGNORE_CASE),
        Regex("\\bjoin\\s+(?:channel\\s+)?$NAME\\s+(?:first|before)\\b", RegexOption.IGNORE_CASE),
    )
    private val signInWords = Regex("identif|regist|logged in|log in|signed in|sign in|authenticat|services account|\\(\\+r\\)|\\+r\\b",
        RegexOption.IGNORE_CASE)
    private val mustWords = Regex("\\bneed|\\bmust\\b|\\bonly\\b|\\brequire|\\bhave to\\b|\\(\\+r\\)", RegexOption.IGNORE_CASE)
    /** 477 is also "this channel doesn't support modes" on some servers. */
    private val noModes = Regex("support|modes", RegexOption.IGNORE_CASE)

    private fun same(a: String, b: String) = AccountKeys.targetKey("irc", a) == AccountKeys.targetKey("irc", b)

    /** Whether a join reply means the channel only lets in people signed in to their nickname. */
    @JvmStatic
    fun needsSignIn(code: String, text: String): Boolean = when (code) {
        "477" -> !noModes.containsMatchIn(text) || signInWords.containsMatchIn(text)
        in CODES -> code != "475" && signInWords.containsMatchIn(text) && mustWords.containsMatchIn(text)
        else -> false
    }

    /**
     * The channel a join reply says to join first, such as #help in "Cannot join channel #chat (You must join #help
     * first)", or null. Never [channel] itself.
     */
    @JvmStatic
    fun prerequisite(code: String, text: String, channel: String): String? {
        if (code !in CODES) return null
        return firstPatterns.asSequence().flatMap { pattern -> pattern.findAll(text).map { it.groupValues[1].trimEnd('.', '!', '?', ';') } }
            .firstOrNull { IrcChannels.channelName(it) == it && !same(it, channel) }
    }

    /**
     * [channels] in the order to join them: each one's channels to join first ([first]) come before it, without
     * repeats, and a loop (two channels that each name the other) can't go on forever.
     */
    @JvmStatic
    fun order(channels: List<String>, first: (String) -> List<String>): List<String> {
        val done = LinkedHashMap<String, String>()
        val visiting = HashSet<String>()
        fun visit(channel: String, depth: Int) {
            val key = AccountKeys.targetKey("irc", channel)
            if (key in done || key in visiting || depth > MOST_FIRST) return
            visiting += key
            for (before in first(channel)) visit(before, depth + 1)
            visiting -= key
            done[key] = channel
        }
        for (channel in channels) visit(channel, 0)
        return done.values.toList()
    }

    // The remembered "join this first" list, one channel per line: the channel folded for IRC, then the channels to join
    // before it, separated by spaces ("#chat #lobby"). Channel names never hold spaces, so nothing needs escaping.

    private fun read(saved: String?): LinkedHashMap<String, List<String>> {
        val all = LinkedHashMap<String, List<String>>()
        for (line in saved.orEmpty().lines()) {
            val words = line.trim().split(' ').filter { it.isNotEmpty() }
            if (words.size >= 2) all[words[0]] = words.drop(1).mapNotNull { IrcChannels.channelName(it) }.take(MOST_FIRST)
        }
        return all
    }

    private fun write(all: Map<String, List<String>>): String =
        all.entries.filter { it.value.isNotEmpty() }.toList().takeLast(200).joinToString("\n") { (key, list) -> key + " " + list.joinToString(" ") }

    /** The channels to join before [channel], from the saved list. */
    @JvmStatic
    fun firstFor(saved: String?, channel: String): List<String> = read(saved)[AccountKeys.targetKey("irc", channel)].orEmpty()

    /** The saved list with [first] to join before [channel]. */
    @JvmStatic
    fun remember(saved: String?, channel: String, first: String): String {
        val all = read(saved)
        val now = firstFor(saved, channel)
        if (IrcChannels.channelName(first) != first || now.any { same(it, first) } || same(first, channel)) return write(all)
        all[AccountKeys.targetKey("irc", channel)] = now.plus(first).takeLast(MOST_FIRST)
        return write(all)
    }

    /** The saved list without [first] for [channel] (details → Remove). */
    @JvmStatic
    fun forget(saved: String?, channel: String, first: String): String {
        val all = read(saved)
        all[AccountKeys.targetKey("irc", channel)] = firstFor(saved, channel).filterNot { same(it, first) }
        return write(all)
    }

    /** The channels in [others] (channel → its channels to join first) that need [channel] joined first. */
    @JvmStatic
    fun neededBy(channel: String, others: Map<String, List<String>>): List<String> =
        others.filter { (name, firsts) -> !same(name, channel) && firsts.any { same(it, channel) } }.keys.toList()

    // Words people see

    /** "#lobby is needed to stay in #chat on this network." */
    @JvmStatic
    fun neededText(first: String, needs: List<String>): String {
        val list = if (needs.size < 3) needs.joinToString(" and ") else needs.dropLast(1).joinToString(", ") + ", and " + needs.last()
        return "$first is needed to stay in $list on this network."
    }

    /** The line in a channel's details for each channel joined before it. */
    @JvmStatic
    fun detailsLine(first: String): String = "Also joins $first first (this network needs it)"

    /** In the channel, when the network named another channel to join first. */
    @JvmStatic
    fun joiningFirst(first: String, channel: String, network: String): String =
        "$network only lets people into $channel after they join $first, so FluidMessages joined $first first. It does this every time " +
            "you connect. You can change it in this channel’s details."

    /** In the channel, while a saved password signs you in before trying again. */
    @JvmStatic
    fun waitingForSignIn(channel: String): String =
        "$channel only lets in people who are signed in to their nickname. FluidMessages is signing you in, then it tries again."

    /**
     * Why the channel didn't let you in, when it's about signing in. [saved]: a password is saved on the account, so
     * signing in was tried and didn't work.
     */
    @JvmStatic
    fun signInNeeded(channel: String, saved: Boolean): String = if (saved)
        "$channel only lets in people who are signed in to their nickname, and signing in didn’t work. Check the account name and password " +
            "in the IRC account settings, then try again."
    else "$channel only lets in people who are signed in to their nickname. Sign in with NickServ, then try again. " +
        "Type your password in the NickServ card, or save it as Password in the IRC account settings."
}

/**
 * The phone's side of channels joined first: the list for a channel's details (the phone's own connection keeps it
 * itself; FluidMessages Web sends "join_first" with each channel), Remove, and which joined channels need a channel,
 * so Communities won't remove it alone.
 */
internal object PhoneJoinFirst {
    @JvmStatic
    fun of(context: android.content.Context, link: JSONObject): List<String> {
        if (link.optBoolean("direct")) return DirectIrc.joinFirst(context, link.optString("target"))
        val list = link.optJSONArray("join_first") ?: return emptyList()
        return (0 until list.length()).mapNotNull { IrcChannels.channelName(list.optString(it)) }
    }

    /** Stops joining [first] before the channel. Runs off the main thread. */
    @JvmStatic
    fun forget(context: android.content.Context, link: JSONObject, first: String) {
        if (link.optBoolean("direct")) DirectIrc.forgetJoinFirst(context, link.optString("target"), first)
        else GatewayClient.forgetJoinFirst(DirectProtocols.prefs(context), link.optString("address"), first)
    }

    /** The IRC channel link for a conversation, or null when it isn't one (a direct chat, the server chat…). */
    @JvmStatic
    fun channelLink(prefs: android.content.SharedPreferences, conversation: String): JSONObject? {
        val link = DirectProtocols.route(prefs, conversation, MessageProtocol.IRC) ?: GatewayClient.routeOf(prefs, conversation, MessageProtocol.IRC)
        val target = link?.optString("target").orEmpty()
        return link?.takeIf { it.optBoolean("group") && !it.optBoolean("server_chat") && (target.startsWith("#") || target.startsWith("&")) }
    }

    /** The other joined channels on [link]'s network that need it joined first, as their links. */
    @JvmStatic
    fun neededBy(context: android.content.Context, link: JSONObject): List<JSONObject> {
        val direct = link.optBoolean("direct")
        val mates = GatewayClient.allRoutes(DirectProtocols.prefs(context)).filter {
            it.optString("protocol") == "irc" && it.optBoolean("group") && it.optBoolean("direct") == direct &&
                (direct || it.optString("account") == link.optString("account")) && it.optString("address") != link.optString("address")
        }
        val names = IrcJoins.neededBy(link.optString("target"), mates.associate { it.optString("target") to of(context, it) })
        return mates.filter { it.optString("target") in names }
    }
}

/** A channel that didn't let you in: its plain-words reason, and the network's reply number ([code], such as 475). */
internal class IrcJoinRefused(message: String, val code: String) : IllegalStateException(message)

/**
 * The IRC account form's words for a nickname and a registered account, the same on FluidMessages Web
 * (companion.js and account-setup.js). The stored keys stay sasl_user, sasl_password, and nickserv_password.
 */
internal object IrcWords {
    const val NICK = "Nickname: the name people see."
    const val ACCOUNT = "Account name: the name you registered with the network’s NickServ. Usually the same as your nickname. " +
        "Leave blank to use your nickname."
    const val PASSWORD = "Password: your NickServ password. FluidMessages signs you in with it each time it connects."
    const val NICKSERV = "Leave this blank unless signing in with Password doesn’t work on this network. It’s sent to NickServ after connecting."
    const val WHATS_THIS_TITLE = "What’s this?"
    const val WHATS_THIS = "Anyone can pick a free nickname. Some people also register theirs with the network’s NickServ, so no one else can " +
        "use it. If you registered, fill in the account name and password. If you didn’t, leave them blank."
}

/**
 * Short explainers in IRC chats, so the parts of IRC that surprise people are said once, in plain words: a channel is
 * a public room (shown the first time you open any IRC channel), and the network's server chat is the network talking
 * to you (always at its top). FluidMessages Web shows the same words (chat-commands.js).
 */
internal object IrcIntro {
    const val SEEN = "ircChannelIntroSeen"
    const val SERVER_SUFFIX = " · network messages"

    @JvmStatic
    fun channel(channel: String): String =
        "IRC channels are public rooms. Anyone in $channel can read what you write. Moderators can remove people."

    const val SERVER = "This is the network talking to you: welcome notes, sign-in answers, and problems joining channels. " +
        "You can type commands here, like /join #channel or /msg NickServ help."

    /** The server chat's name: "Libera.Chat · network messages" for a network stored as its host (irc.libera.chat). */
    @JvmStatic
    fun serverChatTitle(stored: String): String {
        if (stored.endsWith(SERVER_SUFFIX)) return stored
        val name = ServerDirectory.ircName(stored.trim()) ?: stored.trim().ifEmpty { "IRC" }
        return name + SERVER_SUFFIX
    }

    /** What to show at the top of an IRC chat with this [link], or null. [seen]: the channel explainer was dismissed. */
    @JvmStatic
    fun forLink(link: JSONObject?, seen: Boolean): String? = when {
        link == null -> null
        link.optBoolean("server_chat") -> SERVER
        link.optBoolean("group") && link.optString("target").let { it.startsWith("#") || it.startsWith("&") } && !seen ->
            channel(link.optString("target"))
        else -> null
    }

    @JvmStatic
    fun forChat(prefs: android.content.SharedPreferences, conversation: String): String? = forLink(
        DirectProtocols.route(prefs, conversation, MessageProtocol.IRC) ?: GatewayClient.routeOf(prefs, conversation, MessageProtocol.IRC),
        prefs.getBoolean(SEEN, false))
}
