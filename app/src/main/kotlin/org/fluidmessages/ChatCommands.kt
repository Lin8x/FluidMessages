package org.fluidmessages

/**
 * Slash commands typed in an IRC, Matrix, or XMPP chat: /me, /msg, /join, /part, /nick, /topic, and the rest. The
 * table and the errors match the companion's (server/chat_commands.py) and the browser's (chat-commands.js), so a
 * command works the same wherever you type it. Commands never reach SMS or email as text: a line there that starts
 * with a known command is refused with a hint, and "//" sends a line that starts with "/" as written.
 */
internal data class ChatCommand(val name: String, val args: String, val argv: List<String>)

internal class ChatCommandException(message: String) : IllegalArgumentException(message)

/** What a typed line is: text to send (literal when it began with //), a command to run, or a refusal. */
internal sealed class TypedLine {
    data class Text(val text: String, val literal: Boolean = false) : TypedLine()
    data class Command(val family: String, val command: ChatCommand) : TypedLine()
    data class Refused(val reason: String) : TypedLine()
}

internal data class CommandInfo(val name: String, val usage: String, val description: String, val protocols: Set<String>)

internal object ChatCommands {
    private val all = setOf("irc", "matrix", "xmpp")
    private val ircOnly = setOf("irc")

    val commands: List<CommandInfo> = listOf(
        CommandInfo("me", "/me waves", "Send an action, shown as “* you waves”", all),
        CommandInfo("msg", "/msg nick message", "Open a direct chat and send a message", all),
        CommandInfo("query", "/query nick", "Open a direct chat", all),
        CommandInfo("join", "/join #channel", "Join a channel or room", all),
        CommandInfo("part", "/part [reason]", "Leave this channel or room", all),
        CommandInfo("nick", "/nick newname", "Change your nickname (display name on Matrix)", all),
        CommandInfo("topic", "/topic [new topic]", "Show or change the topic", all),
        CommandInfo("invite", "/invite nick [#channel]", "Invite someone here", all),
        CommandInfo("whois", "/whois nick", "Look someone up", ircOnly),
        CommandInfo("away", "/away [message]", "Set or clear your away message", ircOnly),
        CommandInfo("kick", "/kick nick [reason]", "Remove someone (needs operator rights)", ircOnly),
        CommandInfo("mode", "/mode [target] +o nick", "Change channel or user modes", ircOnly),
        CommandInfo("notice", "/notice target message", "Send a notice", ircOnly),
        CommandInfo("quote", "/quote RAW LINE", "Send a raw IRC line (advanced)", ircOnly),
        CommandInfo("help", "/help", "List the commands", all),
    )
    private val byName = commands.associateBy { it.name }
    private val aliases = mapOf("leave" to "part", "raw" to "quote", "j" to "join", "q" to "query", "back" to "away", "action" to "me")
    private val needs = mapOf("me" to 1, "msg" to 2, "query" to 1, "join" to 1, "nick" to 1, "invite" to 1, "whois" to 1,
        "kick" to 1, "mode" to 1, "notice" to 2, "quote" to 1)
    private val names = mapOf("irc" to "IRC", "matrix" to "Matrix", "xmpp" to "XMPP")

    /** The command set a chat uses: Matrix for Matrix rooms, including IRC and XMPP rooms bridged through Matrix. */
    @JvmStatic
    fun family(protocol: MessageProtocol, target: String?): String {
        val name = protocol.name.lowercase()
        return if ((name == "irc" || name == "xmpp") && target?.startsWith("!") == true) "matrix" else name
    }

    @JvmStatic fun isChat(family: String) = family in all

    @JvmStatic
    fun looksLikeCommand(text: String): Boolean =
        text.length > 1 && text[0] == '/' && text[1] != '/' && !text[1].isWhitespace()

    fun available(family: String): List<CommandInfo> = commands.filter { family in it.protocols }

    @JvmStatic
    fun helpText(family: String): String =
        "Commands: " + available(family).joinToString("; ") { it.usage + " — " + it.description } +
            ". Start with // to send a line that begins with /."

    /** Like chat_commands.parse: the command, null for ordinary text, or a [ChatCommandException] with a friendly reason. */
    @JvmStatic
    fun parse(family: String, text: String): ChatCommand? {
        if (!looksLikeCommand(text)) return null
        val space = text.indexOf(' ')
        val word = if (space < 0) text.substring(1) else text.substring(1, space)
        val rest = if (space < 0) "" else text.substring(space + 1).trim()
        val lowered = word.lowercase()
        val name = (aliases[lowered] ?: lowered).takeIf { it in byName }
        if (family !in all) {
            if (name == null) return null // "/s" or "/shrug" in a text is just text.
            throw ChatCommandException("Commands work in IRC, Matrix, and XMPP chats. Start with // to send “${text.take(40)}” as text.")
        }
        if (name == null) throw ChatCommandException("Unknown command /${word.take(30)}. Type /help for the list, or start with // to send it as text.")
        val info = byName.getValue(name)
        if (family !in info.protocols) throw ChatCommandException("/$name isn’t available on ${names[family]}.")
        if ('\n' in rest || '\r' in rest) throw ChatCommandException("Commands fit on one line")
        val argv = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (argv.size < (needs[name] ?: 0)) throw ChatCommandException("Usage: ${info.usage}")
        return if (lowered == "back") ChatCommand(name, "", emptyList()) else ChatCommand(name, rest, argv)
    }

    /**
     * Reads what was typed in a chat before it's sent. /me stays text (it is a message, with replies and effects), and so
     * does any line in a chat that has a reply or attachment waiting; // sends the rest as written.
     */
    @JvmStatic
    fun read(protocol: MessageProtocol, target: String?, text: String): TypedLine {
        if (text.startsWith("//")) return TypedLine.Text(text.substring(1), literal = true)
        val family = family(protocol, target)
        return try {
            val command = parse(family, text.trim()) ?: return TypedLine.Text(text)
            if (command.name == "me") TypedLine.Text(text) else TypedLine.Command(family, command)
        } catch (error: ChatCommandException) {
            TypedLine.Refused(error.message ?: "That command can’t be used here")
        }
    }

    /** The hint list above the message box while a chat's draft starts with "/": the commands that match so far. */
    @JvmStatic
    fun hints(family: String, draft: String): List<CommandInfo> {
        if (family !in all || !draft.startsWith("/") || draft.startsWith("//") || draft.any { it.isWhitespace() }) return emptyList()
        val typed = draft.substring(1).lowercase()
        return available(family).filter { it.name.startsWith(typed) }.take(8)
    }

    /** "/me waves" as "* maya waves"; null for any other line. */
    @JvmStatic
    fun actionText(text: String, author: String?, outgoing: Boolean): String? =
        if (text.startsWith("/me ")) "* " + (if (outgoing) "you" else author ?: "someone") + " " + text.removePrefix("/me ") else null

    /** Joins, leaves, and quits, which can be hidden; other event lines always show. */
    @JvmStatic
    fun hideable(kind: String?): Boolean = kind == "join" || kind == "part" || kind == "quit"
}
