package org.fluidmessages

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Pairing with FluidMessages Web: the QR code it shows (Settings → Phone and other devices) and the certificate
 * fingerprint, scanned or typed. Pure, so it's unit tested (CompanionPairingTest).
 *
 * The QR code holds `fluidmessages://pair?a=…&a=…&o=…&t=…&f=…` (companion.js pairPayload):
 * - `a`: https:// addresses served with FluidMessages Web's own certificate, trusted only by its fingerprint
 * - `o`: other https:// addresses (your own domain behind a proxy), trusted the normal way
 * - `t`: the link code, and `f`: the certificate's SHA-256 fingerprint
 */
object CompanionPairing {
    /** What a QR code carries. [pinned] are tried first, with [fingerprint]; then [others], with normal trust. */
    data class Payload(val pinned: List<String>, val others: List<String>, val token: String, val fingerprint: String?)

    /** One address to try, in order: the origin to save, and the fingerprint to trust it by (null: normal trust). */
    data class Attempt(val origin: String, val fingerprint: String?)

    const val FINGERPRINT_HELP = "Optional. Shown under the link code in FluidMessages Web. With it, this phone trusts only that computer."

    /** The pairing QR code's contents, or null when it isn't one (or has nothing usable). */
    @JvmStatic
    fun parse(text: String): Payload? {
        val trimmed = text.trim()
        if (trimmed.length > 4096) return null
        val link = try { URI(trimmed) } catch (error: Exception) { return null }
        if (!link.scheme.equals("fluidmessages", ignoreCase = true) || !link.host.equals("pair", ignoreCase = true)) return null
        val pinned = mutableListOf<String>()
        val others = mutableListOf<String>()
        var token = ""
        var fingerprint: String? = null
        for (part in link.rawQuery.orEmpty().split('&')) {
            if (part.isEmpty()) continue
            val key = part.substringBefore('=')
            val value = try { URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8) } catch (error: Exception) { return null }
            when (key) {
                "a", "o" -> {
                    val checked = CompanionAddress.check(value) ?: continue
                    // Only https: the fingerprint means nothing over http://.
                    if (checked.plain) continue
                    val list = if (key == "a") pinned else others
                    if (checked.origin !in list && list.size < 8) list += checked.origin
                }
                "t" -> token = value.trim()
                // Base64url in the QR code (shorter), or hex digits.
                "f" -> fingerprint = normalize(value) ?: fromBase64(value) ?: return null
            }
        }
        if (token.length < 32 || token.length > 512 || token.any { it.isWhitespace() }) return null
        if (fingerprint == null) pinned.clear()
        others.removeAll(pinned)
        if (pinned.isEmpty() && others.isEmpty()) return null
        return Payload(pinned, others, token, fingerprint)
    }

    /** Where to try, in order: this computer's own addresses with the fingerprint, then the others. */
    @JvmStatic
    fun attempts(payload: Payload): List<Attempt> =
        payload.pinned.map { Attempt(it, payload.fingerprint) } + payload.others.map { Attempt(it, null) }

    /**
     * A typed or scanned SHA-256 fingerprint as 64 lowercase hex digits, or null when it isn't one. Colons, spaces,
     * and dashes between the digits are fine, and so are capital letters.
     */
    @JvmStatic
    fun normalize(typed: String): String? {
        val digits = typed.trim().removePrefix("sha256:").removePrefix("SHA256:").filterNot { it == ':' || it == ' ' || it == '-' }
        if (digits.length != 64 || digits.any { Character.digit(it, 16) < 0 }) return null
        return digits.lowercase()
    }

    /** A SHA-256 fingerprint written as 43 base64url characters (the QR code's `f`), as hex; null when it isn't one. */
    @JvmStatic
    fun fromBase64(value: String): String? {
        val text = value.trim().trimEnd('=')
        if (text.length != 43 || text.any { !(it.isLetterOrDigit() && it.code < 128) && it != '-' && it != '_' }) return null
        val bytes = try { java.util.Base64.getUrlDecoder().decode(text) } catch (error: IllegalArgumentException) { return null }
        return if (bytes.size == 32) bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } else null
    }

    /** The fingerprint the way FluidMessages Web shows it: AB:CD:… */
    @JvmStatic
    fun shown(fingerprint: String): String = fingerprint.uppercase().chunked(2).joinToString(":")

    /** A certificate's SHA-256 fingerprint, as 64 lowercase hex digits. */
    @JvmStatic
    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Whether a certificate (its DER bytes) is exactly the pinned one. */
    @JvmStatic
    fun pinMatches(der: ByteArray?, pin: String?): Boolean {
        val expected = pin?.let { normalize(it) } ?: return false
        if (der == null || der.isEmpty()) return false
        return MessageDigest.isEqual(fingerprint(der).toByteArray(), expected.toByteArray())
    }

    /** Whether [hostname] is the saved address's host: the pinned certificate is accepted only there. */
    @JvmStatic
    fun sameHost(origin: String?, hostname: String?): Boolean {
        val host = try { URI(origin ?: return false).host } catch (error: Exception) { null } ?: return false
        val asked = hostname?.removePrefix("[")?.removeSuffix("]") ?: return false
        return host.removePrefix("[").removeSuffix("]").equals(asked, ignoreCase = true)
    }
}
