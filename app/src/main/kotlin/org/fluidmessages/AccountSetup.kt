package org.fluidmessages

import java.text.Normalizer
import java.util.Locale

/**
 * Settings FluidMessages fills in for an email address. Security follows the port, as everywhere else (EmailPorts):
 * IMAP 993 and SMTP 465 use TLS from the start, SMTP 587 upgrades with STARTTLS, and nothing is sent in plain text.
 *
 * [note] is one plain sentence shown under "Set up for …", with [helpUrl] opening the provider's own page. [guess]
 * marks settings made up from the domain. [blocked] means the provider can't be signed in to directly (nothing is
 * filled in, and [note] says what to do instead).
 */
internal data class EmailSetup(val provider: String, val imapHost: String = "", val imapPort: Int = EmailPorts.IMAP,
                               val smtpHost: String = "", val smtpPort: Int = EmailPorts.SMTP, val note: String = "",
                               val helpUrl: String = "", val helpLabel: String = "", val appPassword: Boolean = false,
                               val guess: Boolean = false, val blocked: Boolean = false) {
    /** The account form's fields these settings fill in; none for a provider that can't be signed in to. */
    val fields: Map<String, String> get() = if (blocked) emptyMap() else mapOf("imap_host" to imapHost,
        EmailPorts.IMAP_KEY to imapPort.toString(), "smtp_host" to smtpHost, EmailPorts.SMTP_KEY to smtpPort.toString())

    val title: String get() = when {
        blocked -> "$provider can’t sign in here"
        guess -> "Our best guess for $provider"
        else -> "Set up for $provider"
    }

    /** One line with the servers, ports, and how each is secured, shown under "Show details". */
    val summary: String get() = if (blocked) "" else "Incoming: $imapHost, port $imapPort (${if (EmailPorts.imapStartTls(imapPort)) "STARTTLS" else "TLS"}). " +
        "Outgoing: $smtpHost, port $smtpPort (${if (EmailPorts.smtpStartTls(smtpPort)) "STARTTLS" else "TLS"})."
}

/**
 * Email providers FluidMessages knows the settings for, by the domain after the @. The same table as FluidMessages
 * Web's, so an address sets up the same way in both. Tested in AccountSetupTest.
 */
internal object EmailProviders {
    private val host = Regex("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")

    private fun appPassword(name: String, kind: String = "an app password") =
        "$name needs $kind instead of your normal password."

    private val known: List<Pair<List<String>, EmailSetup>> = listOf(
        listOf("gmail.com", "googlemail.com") to EmailSetup("Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465,
            "Gmail needs an app password instead of your normal password, and 2-Step Verification has to be on first.",
            "https://myaccount.google.com/apppasswords", "Get a Gmail app password", appPassword = true),
        listOf("outlook.com", "hotmail.com", "live.com", "msn.com") to EmailSetup("Outlook", "outlook.office365.com", 993,
            "smtp-mail.outlook.com", 587, "Outlook may not allow sign-in from other apps.",
            "https://support.microsoft.com/account-billing/5896ed9b-4263-e681-128a-a6f2979a7944", "See Microsoft’s help"),
        listOf("yahoo.com", "ymail.com", "rocketmail.com") to EmailSetup("Yahoo Mail", "imap.mail.yahoo.com", 993,
            "smtp.mail.yahoo.com", 465, appPassword("Yahoo Mail"), "https://login.yahoo.com/account/security",
            "Get a Yahoo app password", appPassword = true),
        listOf("aol.com") to EmailSetup("AOL Mail", "imap.aol.com", 993, "smtp.aol.com", 465, appPassword("AOL Mail"),
            "https://login.aol.com/account/security", "Get an AOL app password", appPassword = true),
        listOf("icloud.com", "me.com", "mac.com") to EmailSetup("iCloud Mail", "imap.mail.me.com", 993, "smtp.mail.me.com", 587,
            appPassword("iCloud Mail", "an app-specific password"), "https://account.apple.com/account/manage",
            "Get an app-specific password", appPassword = true),
        listOf("fastmail.com", "fastmail.fm") to EmailSetup("Fastmail", "imap.fastmail.com", 993, "smtp.fastmail.com", 465,
            appPassword("Fastmail"), "https://app.fastmail.com/settings/security/apps", "Get a Fastmail app password", appPassword = true),
        listOf("zoho.com", "zohomail.com") to EmailSetup("Zoho Mail", "imap.zoho.com", 993, "smtp.zoho.com", 465,
            appPassword("Zoho Mail"), "https://accounts.zoho.com/home#security/app_password", "Get a Zoho app password", appPassword = true),
        listOf("gmx.com", "gmx.us", "gmx.net", "gmx.de") to EmailSetup("GMX", "imap.gmx.com", 993, "mail.gmx.com", 587),
        listOf("mail.com") to EmailSetup("mail.com", "imap.mail.com", 993, "smtp.mail.com", 587),
        listOf("proton.me", "protonmail.com", "pm.me") to EmailSetup("Proton Mail", blocked = true,
            note = "Proton Mail needs its Bridge app on a computer, so set it up in FluidMessages Web on that computer instead."),
    )
    private val byDomain: Map<String, EmailSetup> = known.flatMap { (domains, setup) -> domains.map { it to setup } }.toMap()

    /** Every domain in the table, for tests. */
    val domains: Set<String> get() = byDomain.keys

    /** The lowercase domain of a typed address, or null until it looks like a whole one (name@host.tld). */
    fun domain(address: String): String? {
        val value = address.trim()
        val at = value.lastIndexOf('@')
        if (at < 1 || value.any { it.isWhitespace() }) return null
        return value.substring(at + 1).lowercase(Locale.ROOT).trimEnd('.').takeIf { host.matches(it) }
    }

    /**
     * Settings for [address]: a known provider's, or a guess (imap.domain on 993, smtp.domain on 465) for any other
     * domain. Null until the address has a whole domain.
     */
    fun detect(address: String): EmailSetup? {
        val domain = domain(address) ?: return null
        return byDomain[domain] ?: EmailSetup(domain, "imap.$domain", EmailPorts.IMAP, "smtp.$domain", EmailPorts.SMTP,
            "This is only a guess from your address. If it doesn’t connect, ask your email provider for its IMAP and SMTP settings.",
            guess = true)
    }
}

/** Small helpers that fill in or tidy the account forms, so there's less to type and fewer ways to get it wrong. */
internal object AccountSetup {
    private val matrixId = Regex("@?([^@:\\s]+):([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::\\d{1,5})?)")
    private val spaceless = setOf("address", "jid", "user_id", "server", "host", "imap_host", "smtp_host", "homeserver", "nick",
        "imap_user", "sasl_user")
    private val hosts = setOf("server", "host", "imap_host", "smtp_host")

    /** "@sam:example.org" from what was typed, adding a forgotten @; null when it isn't a whole Matrix ID. */
    fun matrixId(typed: String): String? = matrixId.matchEntire(typed.trim())?.let { match ->
        val domain = match.groupValues[2]
        if ('.' in domain || domain.startsWith("localhost")) "@" + match.groupValues[1] + ":" + domain.lowercase(Locale.ROOT) else null
    }

    /**
     * The homeserver for a Matrix ID: a known public one's real address (matrix.org signs in at
     * matrix-client.matrix.org), else https:// and the ID's own domain. Null until the ID is whole.
     */
    fun matrixHomeserver(userId: String): String? {
        val domain = matrixId(userId)?.substringAfter(':') ?: return null
        return ServerDirectory.matrix.firstOrNull { it.name == domain }?.address ?: "https://$domain"
    }

    /** A homeserver address as the sign-in needs it: https:// added when left out, and no trailing slash. */
    fun homeserverUrl(typed: String): String {
        val value = typed.trim().trimEnd('/')
        return if (value.isEmpty() || value.contains("://")) value else "https://$value"
    }

    /** The server of an XMPP address (sam@example.org is on example.org), or null until there is one. */
    fun xmppServer(jid: String): String? {
        val value = jid.trim().substringBefore('/')
        val at = value.indexOf('@')
        if (at < 1 || at != value.lastIndexOf('@')) return null
        return value.substring(at + 1).lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) && '.' in it }
    }

    /** An address with its domain in lowercase, as mail and XMPP servers treat it; the name before the @ is kept. */
    fun lowerDomain(address: String): String {
        val value = address.trim()
        val at = value.lastIndexOf('@')
        return if (at < 0) value else value.substring(0, at) + value.substring(at).lowercase(Locale.ROOT)
    }

    /**
     * An IRC nickname made from a name or address: "Sam Rivera" is SamRivera, "sam.rivera@example.org" is samrivera.
     * Only characters every network allows, starting with a letter, at most 16. "" when nothing usable is left.
     */
    fun nick(name: String): String {
        val plain = Normalizer.normalize(name.trim().removePrefix("@").substringBefore('@').substringBefore(':'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return plain.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
            .trimStart { it !in 'A'..'Z' && it !in 'a'..'z' }.take(16)
    }

    /** A known network typed by name ("Libera.Chat", "libera.chat") as its host; anything else as typed, in lowercase. */
    fun ircServer(typed: String): String {
        val value = typed.trim().lowercase(Locale.ROOT)
        return ServerDirectory.irc.firstOrNull { it.tls && it.name.lowercase(Locale.ROOT) == value }?.address ?: value
    }

    /** The channels typed in the IRC form: split on commas or spaces, each given its #, without repeats. */
    fun channels(typed: String): List<String> = typed.split(Regex("[,\\s]+")).mapNotNull { IrcChannels.channelName(it) }
        .distinctBy { AccountKeys.targetKey("irc", it) }

    /** Tidies a field as it's typed: addresses, hosts, and nicknames never hold spaces (a pasted one often does). */
    fun tidy(key: String, value: String): String = if (key in spaceless) value.filter { !it.isWhitespace() } else value

    /** Tidies a field as it's saved: hosts in lowercase, and addresses with a lowercase domain. */
    fun saved(key: String, value: String): String = when (key) {
        "server" -> ircServer(value)
        in hosts -> value.trim().lowercase(Locale.ROOT)
        "address" -> lowerDomain(value)
        "homeserver" -> homeserverUrl(value)
        "user_id" -> matrixId(value) ?: value.trim()
        else -> value.trim()
    }
}

/** What the full-page guide says before the form to add an account on a service. Plain words, for a first-timer. */
internal data class ServiceGuide(val title: String, val about: String, val exampleTitle: String, val exampleLine: String,
                                 val examplePreset: Map<String, String>, val exampleUrl: String, val exampleUrlLabel: String,
                                 val steps: List<String>,
                                 /** When the network asks you to sign in: a heading, then short steps. Empty for none. */
                                 val signIn: List<String> = emptyList())

internal object ServiceGuides {
    /** The one-line descriptions, the same on the phone and in FluidMessages Web. */
    const val MATRIX = "Modern group chat that no single company owns. Used by communities, schools, and workplaces."
    const val XMPP = "Simple, private one-to-one and group chat. It works like email: pick any provider and talk to people on all the others."
    const val EMAIL = "Your normal email, shown as chats. Only the conversations you choose show up."
    const val IRC = "Big public chat rooms for hobbies and open-source projects. No sign-up needed: just pick a nickname."

    /** What to do when an IRC network wants a registered nickname, the same words as FluidMessages Web's guide. */
    @JvmField val IRC_SIGN_IN = listOf(
        "If the network asks you to sign in",
        "Some networks, like Libera.Chat, only let registered nicknames connect from a VPN, a work or school network, or a cloud server. FluidMessages tells you when this happens.",
        "To register, connect once from home Wi-Fi or mobile data. In the network’s chat, type /msg NickServ REGISTER, a new password, and your email.",
        "Open the email the network sends you and follow its steps.",
        "Then edit the account: put your nickname in SASL account and that password in SASL password, and save.",
    )

    /** The public XMPP server offered as the ready-made example; it's in ServerDirectory's list. */
    const val XMPP_EXAMPLE = "jabber.org"

    fun of(protocol: MessageProtocol): ServiceGuide = when (protocol) {
        MessageProtocol.MATRIX -> ServiceGuide("What is Matrix?", MATRIX, "matrix.org",
            "The biggest free place to get a Matrix account. Make one on its website, then come back and sign in here.",
            ServerDirectory.matrix.first { it.name == "matrix.org" }.preset, "https://matrix.org", "Open matrix.org",
            listOf("Type your Matrix name. It looks like @sam:matrix.org.",
                "Type your password. We fill in the rest.",
                "Join rooms or message people. Your chats show up in your inbox."))
        MessageProtocol.XMPP -> ServiceGuide("What is XMPP?", XMPP, XMPP_EXAMPLE,
            "A free provider that has been running since 1999. Make an account on its website, then come back and sign in here.",
            ServerDirectory.xmpp.first { it.name == XMPP_EXAMPLE }.preset, "https://www.jabber.org", "Open jabber.org",
            listOf("Type your address. It looks like an email address: sam@jabber.org.",
                "Type your password. We find the server for you.",
                "Message people on any provider, or join a group chat."))
        MessageProtocol.IRC -> ServiceGuide("What is IRC?", IRC, "Libera.Chat",
            "The biggest network for open-source projects. You don’t need an account. Just pick a nickname.",
            ServerDirectory.irc.first { it.name == "Libera.Chat" }.preset, "", "",
            listOf("Pick a network. Libera.Chat is a good first one.",
                "Pick a nickname. That’s the name other people see.",
                "Browse the channels and join the ones you like."), IRC_SIGN_IN)
        else -> ServiceGuide("Email as chats", EMAIL, "Use the email you already have",
            "Gmail, Outlook, Yahoo, iCloud, and most others work. You don’t need a new address.", emptyMap(), "", "",
            listOf("Type your email address. For well-known providers, we fill in the settings.",
                "Type your password. Some providers want a special app password. We’ll tell you if yours does.",
                "Pick which people show up as chats. Other mail stays in your mail app."))
    }
}
