package org.fluidmessages

import java.net.URLDecoder

/**
 * OMEMO rules without cryptography, the same as FluidMessages Web's (server/omemo_trust.py): trust, fingerprints,
 * xmpp: links, and the lock color. Trust is "blind trust before verification" (BTBV), as Conversations does:
 * while you haven't verified any device of a contact, their new devices are trusted on sight; once you verify one,
 * later new devices wait for your OK before messages are locked for them.
 */
object OmemoRules {
    const val UNDECIDED = "undecided"
    const val BLIND = "blind"
    const val VERIFIED = "verified"
    const val UNCONFIRMED = "unconfirmed"
    const val DISTRUSTED = "distrusted"

    /** The trust a newly seen device gets, from the trust of that contact's other devices. */
    @JvmStatic fun firstUse(others: Collection<String>): String = if (VERIFIED in others) UNCONFIRMED else BLIND

    /** Messages are locked for these devices. */
    @JvmStatic fun trusted(name: String): Boolean = name == BLIND || name == VERIFIED

    /** A 32-byte key as 8 groups of 8 hex characters, the way other OMEMO apps show it. */
    @JvmStatic fun fingerprintGroups(hex: String): List<String> {
        var text = hex.lowercase().filter { it in "0123456789abcdef" }
        if (text.length == 66 && text.startsWith("05")) text = text.substring(2)
        return text.chunked(8)
    }

    @JvmStatic fun fingerprintText(hex: String): String = fingerprintGroups(hex).joinToString(" ")

    @JvmStatic fun sameFingerprint(a: String, b: String): Boolean {
        val left = fingerprintGroups(a).joinToString("")
        return left.isNotEmpty() && left == fingerprintGroups(b).joinToString("")
    }

    /** xmpp:user@host?omemo-sid-<id>=<fingerprint>;… — the link Conversations shows as a QR code. */
    @JvmStatic fun xmppUri(jid: String, devices: List<Pair<Int, String>>): String {
        val parts = devices.map { (id, fingerprint) -> "omemo-sid-$id=" + fingerprintGroups(fingerprint).joinToString("") }
        return "xmpp:$jid" + if (parts.isEmpty()) "" else "?" + parts.joinToString(";")
    }

    /** (bare JID, device id → fingerprint) from an xmpp: link, or null when it isn't one. */
    @JvmStatic fun parseXmppUri(uri: String?): Pair<String, Map<Int, String>>? {
        val text = uri?.trim() ?: return null
        if (!text.lowercase().startsWith("xmpp:")) return null
        val rest = text.substring(5).trimStart('/')
        val jid = runCatching { URLDecoder.decode(rest.substringBefore("?"), "UTF-8") }.getOrNull()
            ?.substringBefore("/")?.trim()?.lowercase() ?: return null
        if ("@" !in jid || " " in jid) return null
        val devices = linkedMapOf<Int, String>()
        for (part in rest.substringAfter("?", "").replace("&", ";").split(";")) {
            val key = part.substringBefore("=")
            if (!key.lowercase().startsWith("omemo-sid-")) continue
            val id = key.substring(10).toIntOrNull() ?: continue
            val fingerprint = fingerprintGroups(part.substringAfter("=", "")).joinToString("")
            if (id >= 1 && fingerprint.length == 64) devices[id] = fingerprint
        }
        return jid to devices
    }

    /** Device ids to mark verified after scanning [uri] for [jid], and those whose fingerprints don't match. */
    @JvmStatic fun verifyFromUri(uri: String, jid: String, known: Map<Int, String>): Pair<List<Int>, List<Int>> {
        val parsed = parseXmppUri(uri)
        if (parsed == null || parsed.first != jid.lowercase()) return emptyList<Int>() to emptyList()
        val matches = mutableListOf<Int>()
        val mismatches = mutableListOf<Int>()
        for ((id, fingerprint) in parsed.second) {
            val mine = known[id] ?: continue
            if (sameFingerprint(mine, fingerprint)) matches += id else mismatches += id
        }
        return matches.sorted() to mismatches.sorted()
    }

    /** What decides a chat's lock. */
    data class LockState(
        val available: Boolean = false, val devices: Int = 0, val trusted: Int = 0, val failed: Int = 0,
        val plain: Boolean = false, val group: Boolean = false, val groupOk: Boolean = false,
    )

    /** (color, words): green only when messages are locked with OMEMO for all trusted devices. */
    @JvmStatic fun lock(state: LockState): Pair<String, String> = when {
        !state.available -> "yellow" to "Encrypted in transit. OMEMO isn't set up here, so your XMPP server can read it."
        state.group && !state.groupOk -> "yellow" to "Encrypted in transit. This group isn't locked: OMEMO needs a members-only group where members can see each other's addresses."
        state.devices == 0 -> "yellow" to "Encrypted in transit. They don't use OMEMO yet, so your XMPP server can read it."
        state.plain -> "yellow" to "Your last message wasn't locked with OMEMO."
        state.trusted == 0 -> "yellow" to "Encrypted in transit. None of their devices is trusted yet; check them in Security."
        state.failed > 0 -> "yellow" to "Locked with OMEMO, but it couldn't reach every trusted device."
        else -> "green" to "Locked with OMEMO: only you and the people you're talking to can read it."
    }

    @JvmStatic fun trustLabel(name: String): String = when (name) {
        VERIFIED -> "Verified by you"
        BLIND -> "Trusted, not checked yet"
        UNCONFIRMED -> "New: waiting for your OK"
        DISTRUSTED -> "Off: messages aren't locked for it"
        else -> "Not checked yet"
    }
}
