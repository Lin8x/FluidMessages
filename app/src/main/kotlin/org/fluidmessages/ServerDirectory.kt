package org.fluidmessages

import java.text.Normalizer
import java.util.Locale

/**
 * Well-known public servers offered in Add server, one list per service. Keep it in step with FluidMessages Web's
 * server/server-directory.js. Only add servers that are public and long-running. Picking one only fills in the
 * sign-in form; nothing connects until Save. IRC entries are TLS hosts; FluidMessages never connects to IRC without TLS.
 */
internal object ServerDirectory {
    /** A preset key: the XMPP address field shows "@domain" after what you type, and adds it on Save. */
    const val XMPP_DOMAIN = "jid_domain"

    /** The XMPP address for [typed] on [domain]: unchanged if it already has an @. */
    fun xmppAddress(typed: String, domain: String?): String =
        if (domain.isNullOrBlank() || typed.isBlank() || typed.contains('@')) typed.trim() else typed.trim() + "@" + domain

    /** [address] is the IRC host, the Matrix homeserver URL, or the XMPP domain. [tls] is false only for IRC networks without it. */
    data class Entry(val service: String, val name: String, val address: String, val about: String, val port: Int = 0,
                     val tls: Boolean = true) {
        /** The sign-in form's fields this entry fills in. */
        val preset: Map<String, String> get() = when (service) {
            "irc" -> mapOf("server" to address, "port" to port.toString())
            "matrix" -> mapOf("homeserver" to address)
            else -> mapOf(XMPP_DOMAIN to address)
        }
        val shownAddress: String get() = if (port > 0) "$address:$port" else address
    }

    private fun irc(name: String, host: String, about: String, port: Int = 6697, tls: Boolean = true) = Entry("irc", name, host, about, port, tls)
    private fun matrix(name: String, homeserver: String, about: String) = Entry("matrix", name, homeserver, about)
    private fun xmpp(domain: String, about: String) = Entry("xmpp", domain, domain, about)

    val irc = listOf(
        irc("Libera.Chat", "irc.libera.chat", "Free and open-source software projects and their communities"),
        irc("OFTC", "irc.oftc.net", "Debian, Tor, and other open-source projects"),
        irc("EFnet", "irc.efnet.org", "One of the oldest IRC networks, with general chat of every kind"),
        irc("IRCnet", "ssl.ircnet.ovh", "A long-running network, popular in Europe"),
        irc("Undernet", "irc.undernet.org", "A large general-purpose network with help and social channels"),
        irc("DALnet", "irc.dal.net", "Social chat, games, and help channels"),
        irc("QuakeNet", "irc.quakenet.org", "Gaming communities and esports", port = 6667, tls = false),
        irc("Rizon", "irc.rizon.net", "Anime, gaming, and file-sharing communities"),
        irc("hackint", "irc.hackint.org", "Hacker spaces and security communities"),
        irc("Snoonet", "irc.snoonet.org", "Chat rooms for online communities and forums"),
        irc("GIMPNet", "irc.gimp.org", "GIMP, GNOME, and related desktop projects"),
        irc("EsperNet", "irc.esper.net", "Minecraft and other gaming and open-source projects"),
        irc("tilde.chat", "irc.tilde.chat", "Small shared-computer (tilde) communities"),
    )

    val matrix = listOf(
        matrix("matrix.org", "https://matrix-client.matrix.org", "The largest public homeserver, run by the Matrix.org Foundation"),
        matrix("envs.net", "https://matrix.envs.net", "A community homeserver for the envs.net shared-computer community"),
        matrix("tchncs.de", "https://matrix.tchncs.de", "A long-running free homeserver from Germany, open to everyone"),
        matrix("mozilla.org", "https://mozilla.modular.im", "Mozilla’s community homeserver. Sign in with a Mozilla account; its public rooms are open to everyone"),
        matrix("catgirl.cloud", "https://matrix.catgirl.cloud", "A free community homeserver with open sign-up"),
        matrix("g24.at", "https://matrix.g24.at", "A long-running free homeserver from Austria"),
        matrix("nope.chat", "https://nope.chat", "A free homeserver with open sign-up"),
        matrix("gnulinux.club", "https://matrix.gnulinux.club", "A free homeserver for Linux and free-software users"),
    )

    val xmpp = listOf(
        xmpp("jabber.org", "The original XMPP service, running since 1999. Free sign-up"),
        xmpp("conversations.im", "From the makers of the Conversations app. Account creation is paid after a free trial"),
        xmpp("yax.im", "A free service you can sign up for from any XMPP app"),
        xmpp("xmpp.jp", "A free service from Japan, open to everyone"),
        xmpp("jabber.fr", "A free French-language community service"),
        xmpp("chapril.org", "A free service run by the April free-software association"),
        xmpp("nixnet.services", "A free privacy-focused service"),
        xmpp("magicbroccoli.de", "A free community service from Germany"),
    )

    fun of(service: String): List<Entry> = when (service) {
        "irc" -> irc
        "matrix" -> matrix
        "xmpp" -> xmpp
        else -> emptyList()
    }

    /** The IRC network's name for [host], if it's a known one. */
    fun ircName(host: String): String? = irc.firstOrNull { it.address.equals(host, ignoreCase = true) }?.name

    /** Lowercase, without accents, for matching. */
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    /** Whether every word of [query] appears in one of [fields]. A blank query matches everything. */
    fun matches(query: String, vararg fields: String): Boolean {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = fold(fields.joinToString(" "))
        return words.all { text.contains(it) }
    }

    /** Entries whose name, address, or description contain every word of [query]. */
    fun search(service: String, query: String): List<Entry> = of(service).filter { matches(query, it.name, it.address, it.about) }
}
