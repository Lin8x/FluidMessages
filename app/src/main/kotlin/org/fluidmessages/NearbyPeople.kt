package org.fluidmessages

import java.util.Locale

/**
 * People nearby, as plain rules the JVM tests cover (NearbyPeopleTest): who can see this phone, trust on first use,
 * message requests from someone you haven't added, a person's key changing, and looking for phones on this Wi-Fi.
 *
 * Trust on first use: tapping someone under People nearby pins the key their phone proved in the Noise handshake. Every
 * later connection must prove the same key, so no one else can read or send in that chat. Until you compare the safety
 * code or scan their code, the chat says "Not verified yet" with the yellow lock, like OMEMO's blind trust. People
 * added in person (a scanned or typed code, or matching safety codes) are verified from the start.
 */
internal object NearbyPeople {
    const val VISIBILITY = "nearby.visibility"
    /** Set once Nearby turned on for the first time, so People nearby opens with its one-line hint. */
    const val FIRST_HINT = "nearby.firstHint"

    enum class Visibility(val key: String, val title: String, val line: String) {
        EVERYONE("everyone", "Everyone nearby", "FluidMessages phones close by list you under People nearby and can send you a message request."),
        ADDED("added", "Only people I’ve added", "Only people you’ve added see you. No one else can send you a request."),
        NOBODY("nobody", "No one", "You aren’t listed for anyone. People you’ve added can still message you when they’re close by.");

        companion object {
            /** Everyone nearby unless you chose otherwise. */
            @JvmStatic fun parse(value: String?) = values().firstOrNull { it.key == value } ?: EVERYONE
        }
    }

    /** What others see, in plain words, under Who can see me nearby. */
    const val WHAT_THEY_SEE = "Others see only the name you chose and a short tag made from your key, like “Rosa · 3F9A”. " +
        "Never your phone number, contacts, or messages."

    /** Hidden phones that help pass messages along: what that still needs, said honestly. */
    const val HIDDEN_RELAY = "With Help pass messages along on, your phone still connects to other FluidMessages phones close by " +
        "to swap the messages it carries. Their app can tell a phone is there, with your name and tag, even though you aren’t " +
        "listed. Turn off Help pass messages along (and leave channels) to stay out of sight completely."

    /** Whether this phone tells [peerAdded] (someone you've added, or not) that it can be listed. */
    fun shown(mine: Visibility, peerAdded: Boolean) = when (mine) {
        Visibility.EVERYONE -> true
        Visibility.ADDED -> peerAdded
        Visibility.NOBODY -> false
    }

    /** Whether to talk with a phone you haven't added: to be seen, or for channels and passing messages along. */
    fun welcomesStrangers(mine: Visibility, community: Boolean) = mine == Visibility.EVERYONE || community

    /** Whether a message from someone you haven't added can land as a request. Phones from before requests never send one. */
    fun takesRequests(mine: Visibility, theirsModern: Boolean) = mine == Visibility.EVERYONE && theirsModern

    /** Four characters from someone's key, so two people with the same name can be told apart. */
    fun tag(key: ByteArray): String = NearbySign.mark(key)

    fun label(name: String, key: ByteArray) = name + " · " + tag(key)

    /** How a person is close by. */
    fun way(kind: String) = if (kind == "Bluetooth") "Close by (Bluetooth)" else "On your Wi-Fi"

    // Trust

    /** One person's chat, as plain values from its link. [changedKey] is a new key waiting for your OK, or "". */
    data class Person(
        val key: String,
        val id: String = "",
        val tofu: Boolean = false,
        val verified: Boolean = false,
        val request: Boolean = false,
        val decision: String = "",
        val changedKey: String = "",
        val blocked: Boolean = false,
    )

    /** Verified: added in person, or checked later. People added before trust on first use were all added in person. */
    fun verified(person: Person) = !person.tofu || person.verified

    /** Added: you started the chat, or accepted their request (or replied). Blocked people never are. */
    fun added(person: Person) = !person.blocked && (!person.request || person.decision == MessageRequests.ACCEPTED)

    /** Messages go only while the key you pinned is the one in use. */
    fun canSend(person: Person) = !person.blocked && person.changedKey.isEmpty()

    /**
     * A phone showing the random ID of someone you've added with a different key: their key changed, or someone is
     * pretending. Like OMEMO's rule, messages wait for your OK.
     */
    fun keyChanged(person: Person, helloId: String, key: String) =
        person.id.isNotEmpty() && person.id == helloId && !person.key.equals(key, ignoreCase = true)

    enum class Intake { STORE, REQUEST, REFUSE }

    /**
     * What happens to a one-to-one message from [person] (null: no chat with that key yet). [impersonates] is true
     * when it claims the ID of someone you've added with another key. Refused messages aren't confirmed, so the
     * sender's phone keeps them and tries again later.
     */
    fun intake(person: Person?, impersonates: Boolean, mine: Visibility, theirsModern: Boolean): Intake = when {
        impersonates -> Intake.REFUSE
        person == null -> if (takesRequests(mine, theirsModern)) Intake.REQUEST else Intake.REFUSE
        person.blocked -> Intake.REFUSE
        else -> Intake.STORE
    }

    /** The lock word for a one-to-one chat. */
    fun lockWords(person: Person, name: String): String = when {
        person.changedKey.isNotEmpty() -> "$name’s key changed. Messages are on hold until you check it."
        verified(person) -> "Verified. Only you and $name can read these messages."
        else -> "Not verified yet. Locked to the key $name’s phone showed the first time you met. Verify $name to be sure."
    }

    // Looking for phones on this Wi-Fi when the network blocks finding them

    /** At most this many connection attempts a second, each given up after [TIMEOUT_MS]. */
    const val MAX_PER_SECOND = 50
    const val TIMEOUT_MS = 400
    const val SPACING_MS = 1000L / MAX_PER_SECOND
    /** The widest network it looks through: a /24, 254 addresses. */
    const val WIDEST_PREFIX = 24

    /** Private IPv4 ranges only (RFC 1918): 10/8, 172.16/12, 192.168/16. */
    fun isPrivate(address: ByteArray): Boolean {
        if (address.size != 4) return false
        val a = address[0].toInt() and 0xff
        val b = address[1].toInt() and 0xff
        return a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168
    }

    /** Looking is allowed only on Wi-Fi, never on mobile data or a VPN. */
    fun mayLook(wifi: Boolean, cellular: Boolean, vpn: Boolean) = wifi && !cellular && !vpn

    /**
     * The addresses to try: this phone's own network, at most a /24 (a wider one is cut to the /24 this phone is in),
     * without the network and broadcast addresses or this phone. Empty for anything that isn't a private IPv4 address.
     */
    fun addressesToTry(own: ByteArray, prefix: Int): List<ByteArray> {
        if (own.size != 4 || !isPrivate(own) || prefix !in 1..32) return emptyList()
        val bits = maxOf(prefix, WIDEST_PREFIX)
        if (bits >= 31) return emptyList()
        val value = toInt(own)
        val mask = (-1L shl (32 - bits)).toInt()
        val network = value and mask
        val broadcast = network or mask.inv()
        val result = ArrayList<ByteArray>()
        var host = network + 1
        while (host < broadcast) {
            if (host != value) result += fromInt(host).takeIf(::isPrivate) ?: break
            host++
        }
        return result
    }

    fun show(address: ByteArray) = address.joinToString(".") { (it.toInt() and 0xff).toString() }

    private fun toInt(address: ByteArray) = address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }

    private fun fromInt(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    /** The line while looking, and after. */
    fun lookingLine(done: Boolean, found: Int): String = when {
        !done && found == 0 -> "Looking for FluidMessages on this Wi-Fi…"
        !done -> "Looking for FluidMessages on this Wi-Fi… found $found"
        found == 0 -> "No FluidMessages phones answered on this Wi-Fi."
        found == 1 -> "Found 1 phone with FluidMessages on this Wi-Fi."
        else -> "Found $found phones with FluidMessages on this Wi-Fi."
    }

    /** A first name for Nearby from the phone owner's name, or "" (cleaned like any Nearby name). */
    fun firstName(owner: String?): String {
        val first = owner?.trim()?.split(Regex("\\s+"))?.firstOrNull().orEmpty()
        return NearbyWire.cleanName(first) ?: ""
    }

    fun sameName(a: String, b: String) = a.trim().lowercase(Locale.ROOT) == b.trim().lowercase(Locale.ROOT)
}
