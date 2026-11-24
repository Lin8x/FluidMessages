package org.fluidmessages

import java.util.Locale

/**
 * What a typed number or address is, for New message, the keypad, and the contact editor. FluidMessages Web reads
 * addresses with the same rules (people.js detectAddress), and both are tested with the same cases
 * (NewAddressTest.kt and scripts/test-local).
 *
 * service is sms, matrix, xmpp, email, irc, or sip. More than one result means it could be either (name@example.org is
 * email or XMPP), best guess first. group marks a room or channel, network an IRC network typed as nick@irc.network,
 * and guess a bare word that might be an IRC nickname or just part of a name.
 */
internal data class TypedAddress(val service: String, val target: String, val group: Boolean = false,
                                 val network: String = "", val guess: Boolean = false)

internal object NewAddress {
    private val number = Regex("\\+?[\\d ().-]+")
    private val sip = Regex("sip:([^@\\s]{1,64}@[A-Za-z0-9.:-]{1,253})", RegexOption.IGNORE_CASE)
    private val matrixUser = Regex("@[a-z0-9._=\\-/+]{1,255}:[A-Za-z0-9.:-]{1,255}")
    private val matrixRoom = Regex("[!#][^\\s:]{1,250}:[A-Za-z0-9.:-]{1,255}")
    private val channel = Regex("[#&][^\\s,:\\u0007]{1,49}")
    private val ircLink = Regex("irc:(?://([A-Za-z0-9.-]+)/)?(\\S+)", RegexOption.IGNORE_CASE)
    private val nick = Regex("[A-Za-z\\[\\]\\\\`_^{|}][A-Za-z0-9\\[\\]\\\\`_^{|}-]{0,31}")
    private val userAtHost = Regex("([^@\\s<>\"']{1,64})@([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?)")
    private val groupHost = Regex("(conference|muc|chat|rooms?)\\..*", RegexOption.IGNORE_CASE)

    fun detect(text: String): List<TypedAddress> {
        val value = text.trim()
        if (value.isEmpty() || value.length > 320 || value.any { it < ' ' }) return emptyList()
        if (number.matches(value)) return if (value.count(Char::isDigit) >= 3) listOf(TypedAddress("sms", value)) else emptyList()
        sip.matchEntire(value)?.let { return listOf(TypedAddress("sip", "sip:" + it.groupValues[1])) }
        if (matrixUser.matches(value)) return listOf(TypedAddress("matrix", value))
        if (matrixRoom.matches(value)) return listOf(TypedAddress("matrix", value, group = true))
        if (channel.matches(value)) return listOf(TypedAddress("irc", value, group = true))
        ircLink.matchEntire(value)?.let { match ->
            val target = match.groupValues[2]
            if (nick.matches(target) || channel.matches(target) && ':' !in target) {
                return listOf(TypedAddress("irc", target, group = target[0] == '#' || target[0] == '&',
                    network = match.groupValues[1].lowercase(Locale.ROOT)))
            }
        }
        userAtHost.matchEntire(value)?.let { match ->
            val (user, host) = match.destructured
            val lower = value.lowercase(Locale.ROOT)
            // riley@irc.libera.chat: a nickname on that IRC network.
            if (host.startsWith("irc.", ignoreCase = true) && nick.matches(user)) {
                return listOf(TypedAddress("irc", user, network = host.lowercase(Locale.ROOT)))
            }
            if ('.' !in host) return listOf(TypedAddress("xmpp", lower))
            // Group chats live on conference servers; everything else could be email or XMPP.
            if (groupHost.matches(host)) return listOf(TypedAddress("xmpp", lower, group = true), TypedAddress("email", lower))
            return listOf(TypedAddress("email", lower), TypedAddress("xmpp", lower))
        }
        if (nick.matches(value)) return listOf(TypedAddress("irc", value, guess = true))
        return emptyList()
    }

    /** The protocol behind a service name, or null for sms and sip, which aren't internet chats. */
    fun protocol(service: String): MessageProtocol? = when (service) {
        "matrix" -> MessageProtocol.MATRIX
        "xmpp" -> MessageProtocol.XMPP
        "email" -> MessageProtocol.EMAIL
        "irc" -> MessageProtocol.IRC
        else -> null
    }

    fun label(service: String) = when (service) {
        "sms" -> "SMS"; "matrix" -> "Matrix"; "xmpp" -> "XMPP"; "email" -> "Email"; "irc" -> "IRC"; "sip" -> "SIP"; else -> service
    }
}
