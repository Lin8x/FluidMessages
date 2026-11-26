package org.fluidmessages

import java.net.URI

/**
 * The FluidMessages Web address the phone connects to (Settings → FluidMessages Web and the step-by-step guide).
 * https:// is preferred, and an address typed without one gets it. http:// works too, for testing at home, with a
 * warning: anyone on the same Wi-Fi could read the messages and the link code.
 */
object CompanionAddress {
    /** An address that passed the check: [origin] to save (the scheme the person typed, and host:port), and [plain] for http://. */
    data class Checked(val origin: String, val plain: Boolean)

    const val PLAIN_WARNING = "This address starts with http://, so anyone on the same Wi-Fi could read your messages and " +
        "the link code. That’s fine for testing at home. For everyday use, use an https:// address."

    /** The address to save, or null when it isn't a plain web address (no user name, path, ?query, or #part). */
    @JvmStatic
    fun check(address: String): Checked? {
        val typed = address.trim()
        if (typed.isEmpty() || typed.any { it.isWhitespace() }) return null
        val full = if (typed.contains("://")) typed else "https://$typed"
        val endpoint = try { URI(full) } catch (error: Exception) { return null }
        val scheme = endpoint.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val authority = endpoint.rawAuthority
        if (endpoint.host.isNullOrEmpty() || authority.isNullOrEmpty() || endpoint.rawUserInfo != null) return null
        if (endpoint.rawQuery != null || endpoint.rawFragment != null) return null
        if (!endpoint.rawPath.isNullOrEmpty() && endpoint.rawPath != "/") return null
        if (endpoint.port != -1 && endpoint.port !in 1..65535) return null
        return Checked("$scheme://$authority", scheme == "http")
    }

    /** Whether the address being typed would connect without encryption, for the inline warning. */
    @JvmStatic
    fun plain(address: String): Boolean = check(address)?.plain == true

    /** A saved address the companion connections may use: http:// or https:// (MainActivity.saveCompanion saves only those). */
    @JvmStatic
    fun usable(origin: String?): Boolean =
        origin != null && (origin.regionMatches(0, "https://", 0, 8, ignoreCase = true)
            || origin.regionMatches(0, "http://", 0, 7, ignoreCase = true))
}
