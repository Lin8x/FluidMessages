package org.fluidmessages

import java.security.MessageDigest
import java.util.Locale

/**
 * Keys for several accounts per chat service, matching FluidMessages Web (server/accounts.py and protocol_gateway.py).
 *
 * - An account's id is its service and public address, such as "matrix:@alice:example.org". Never a secret.
 * - The service's first account keeps every chat key from before multiple accounts. The same contact reached through
 *   another account is a chat of its own, whose address carries that account's short tag: "maya~1a2b3c4d".
 * - Phone and web merge items: "account:matrix" names the first account's address, as older versions compare it;
 *   "account:matrix:@alice:example.org" says where that one account connects ("web" or "phone").
 */
internal object AccountKeys {
    val services = listOf("matrix", "xmpp", "email", "irc")
    /** Services whose accounts are servers with channels or rooms. */
    val serverServices = listOf("irc", "matrix", "xmpp")
    const val WEB = "web"
    const val PHONE = "phone"

    /** The public address: Matrix ID, XMPP address, email address, or nick@network. */
    fun identity(protocol: String, fields: Map<String, String?>): String {
        fun pick(name: String) = fields[name].orEmpty()
        return when (protocol) {
            "matrix" -> pick("user_id")
            "xmpp" -> pick("jid").lowercase(Locale.ROOT)
            "email" -> pick("address").lowercase(Locale.ROOT)
            "irc" -> if (pick("nick").isEmpty()) "" else pick("nick") + "@" + pick("server").lowercase(Locale.ROOT)
            else -> ""
        }
    }

    fun id(protocol: String, fields: Map<String, String?>) = protocol + ":" + identity(protocol, fields)

    fun service(accountId: String) = accountId.substringBefore(':')

    fun address(accountId: String) = accountId.substringAfter(':', "")

    /** Eight hex digits of SHA-256, the same as accounts.tag on the server. */
    fun tag(accountId: String): String = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(8)

    /** A chat's address through [accountId] when the contact already has a chat through another account. */
    fun threadAddress(base: String, accountId: String) = baseAddress(base) + "~" + tag(accountId)

    /** The contact's own address, without an account tag. */
    fun baseAddress(address: String) = address.substringBefore('~')

    fun tagged(address: String) = Regex("~[0-9a-f]{8}").find(address)?.range?.last == address.length - 1

    /**
     * An address compared the way its service does (protocol_gateway.target_key), so one person, group, channel, or
     * room is one chat per account. Email, XMPP, Matrix user IDs and aliases ignore case; a Matrix room ID keeps its
     * case but not its server's; IRC folds case by RFC 1459, where []\~ are the capitals of {}|^. A Matrix room
     * reached through XMPP or IRC is a bridge, so it's compared as Matrix.
     */
    @JvmStatic
    fun targetKey(service: String, target: String): String {
        val trimmed = target.trim()
        val via = if (trimmed.startsWith("!")) "matrix" else service.lowercase(Locale.ROOT)
        return when {
            via == "irc" -> trimmed.lowercase(Locale.ROOT).map { when (it) { '[' -> '{'; ']' -> '}'; '\\' -> '|'; '~' -> '^'; else -> it } }
                .joinToString("")
            via == "matrix" && trimmed.startsWith("!") -> trimmed.substringBefore(':') + ":" + trimmed.substringAfter(':', "").lowercase(Locale.ROOT)
            else -> trimmed.lowercase(Locale.ROOT)
        }
    }

    /** Whether two links are the same chat: the same address on the same service through the same account. */
    @JvmStatic
    fun sameChat(service: String, target: String, account: String, otherService: String, otherTarget: String, otherAccount: String) =
        service.equals(otherService, ignoreCase = true) && account == otherAccount && targetKey(service, target) == targetKey(otherService, otherTarget)

    /** "from alice@example.org" for a chat's header when the service has more than one account; otherwise nothing. */
    fun via(accountId: String, name: String, accountsOnService: Int) =
        if (accountId.isEmpty() || accountsOnService < 2) "" else "from " + name.ifEmpty { address(accountId) }

    /** A server's short name: the IRC network, the Matrix homeserver, or the XMPP domain. */
    fun serverName(accountId: String): String {
        val address = address(accountId)
        val host = when (service(accountId)) {
            "matrix" -> address.substringAfter(':', address)
            else -> address.substringAfterLast('@', address)
        }
        return (if (service(accountId) == "irc") ServerDirectory.ircName(host) else null) ?: host
    }

    // Phone and web merge

    fun mergeKey(protocol: String, identity: String) = "account:$protocol:$identity"

    /** A per-account merge key's (service, address), or null for the older one-per-service key. */
    fun perAccount(key: String): Pair<String, String>? {
        val name = key.substringAfter(':')
        if (!key.startsWith("account:") || !name.contains(':')) return null
        return name.substringBefore(':') to name.substringAfter(':')
    }

    /** Services the web lists per-account items for. Their older "account:<service>" item is left out of the merge. */
    fun perAccountServices(items: Collection<String>) = items.mapNotNull { perAccount(it)?.first }.toSet()

    /** Plain-language help for adding another account or server. */
    fun help(protocol: String) = when (protocol) {
        "irc" -> "An IRC network such as Libera.Chat hosts many public chat rooms called channels. Each network is its own account."
        "matrix" -> "Your Matrix account lives on a homeserver. You can join rooms on any homeserver, and Spaces collect related rooms together."
        "xmpp" -> "Your XMPP account lives on one server. Group chats live on group chat services such as conference.example.org."
        else -> "Each mailbox is its own account, such as a work and a personal address."
    }
}

/** An account you can send from, as FluidMessages Web lists it: its id, public address, and whether it was used last. */
internal data class SendAccount(val id: String, val name: String, val recent: Boolean = false) {
    val shown: String get() = name.ifEmpty { AccountKeys.address(id) }
}

/**
 * Which account a message goes out from, the way mail apps show a From field and chat apps a workspace. Pure, so the
 * rules are tested on the JVM (FromAccountTest); FluidMessages Web follows the same ones (servers.js).
 *
 * - Nothing shows while a service has one account.
 * - An existing chat always replies from the account it belongs to. A link from before multiple accounts names none
 *   and belongs to the service's first account.
 * - New message preselects the account used most recently, else the newest one, like the companion does.
 */
internal object FromAccount {
    fun shows(accounts: List<SendAccount>) = accounts.size > 1

    /** The account a chat sends from: its link's, or the service's first account for older links. */
    fun chatAccount(linked: String, accounts: List<SendAccount>): SendAccount? =
        if (!shows(accounts)) null else accounts.firstOrNull { it.id == linked } ?: accounts.first().takeIf { linked.isEmpty() }

    /** "From: alice@example.org" above the composer, or "" with one account. */
    fun line(linked: String, accounts: List<SendAccount>) = chatAccount(linked, accounts)?.let { "From: " + it.shown }.orEmpty()

    fun explain(name: String) = "Replies in this chat come from $name. To write from another account, start a new message."

    const val NEW_MESSAGE = "New message from another account"

    /** For New message: an IRC network typed with the nick, else the account used last, else the newest one. */
    fun preselect(accounts: List<SendAccount>, network: String = ""): String {
        if (accounts.isEmpty()) return ""
        if (network.isNotEmpty()) accounts.firstOrNull { it.name.lowercase().endsWith("@" + network.lowercase()) }?.let { return it.id }
        return (accounts.firstOrNull { it.recent } ?: accounts.last()).id
    }

    /** The picker's row for an account: its address and the service, such as "alice@example.org · Email". */
    fun choiceLabel(account: SendAccount, service: String) = account.shown + " · " + service

    /** The inbox row's small hint: only for a chat on another account than the service's first, with more than one. */
    fun inboxHint(linked: String, accounts: List<SendAccount>): String {
        val account = chatAccount(linked, accounts) ?: return ""
        return if (account.id == accounts.first().id) "" else account.shown
    }
}
