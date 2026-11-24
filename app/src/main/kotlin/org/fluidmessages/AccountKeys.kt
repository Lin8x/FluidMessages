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
    /** Services whose accounts are servers with channels or rooms, like Discord servers. */
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

    /** "via alice@example.org" when the service has more than one account; otherwise nothing. */
    fun via(accountId: String, name: String, accountsOnService: Int) =
        if (accountId.isEmpty() || accountsOnService < 2) "" else "via " + name.ifEmpty { address(accountId) }

    /** A server's short name: the IRC network, the Matrix homeserver, or the XMPP domain. */
    fun serverName(accountId: String): String {
        val address = address(accountId)
        val host = when (service(accountId)) {
            "matrix" -> address.substringAfter(':', address)
            else -> address.substringAfterLast('@', address)
        }
        return knownNetworks.firstOrNull { it.host == host }?.name ?: host
    }

    data class Network(val name: String, val host: String, val about: String)

    /** Public IRC networks offered when adding one. Picking one only fills in the form. */
    val knownNetworks = listOf(
        Network("Libera.Chat", "irc.libera.chat", "Free and open-source projects"),
        Network("OFTC", "irc.oftc.net", "Debian, Tor, and other projects"),
    )

    /** Channels and rooms first, then direct messages, keeping each group's order: (key, isGroup) pairs. */
    fun <T> serverOrder(rows: List<T>, isGroup: (T) -> Boolean): List<T> = rows.filter(isGroup) + rows.filterNot(isGroup)

    /** The heading over each part of a server's chat list. */
    fun section(protocol: String, group: Boolean) = when {
        !group -> "Direct messages"
        protocol == "irc" -> "Channels"
        protocol == "xmpp" -> "Group chats"
        else -> "Rooms"
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
        "irc" -> "Like a Discord server: a network such as Libera.Chat with many channels. Each network is its own account."
        "matrix" -> "Your Matrix account lives on a homeserver. Its rooms, and Spaces that group rooms like a Discord server, can be on any homeserver."
        "xmpp" -> "Your XMPP account lives on one server. Group chats live on group chat services such as conference.example.org."
        else -> "Each mailbox is its own account, such as a work and a personal address."
    }
}
