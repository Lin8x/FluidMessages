package org.fluidmessages

/**
 * Which page comes next in Add server: Add server → the Communities page (only while no IRC, Matrix, or XMPP server is
 * added yet) → Choose a service → that service's help page (only while it has no account yet) → the public servers →
 * the form. FluidMessages Web does the same (server/servers.js addServerSteps).
 *
 * [added] is the services that already have an account, as "irc", "matrix", or "xmpp" (FluidMessages Web's and this
 * phone's own).
 */
internal object AddServerFlow {
    /** The services with servers, channels, and rooms. */
    val services = setOf("irc", "matrix", "xmpp")

    /** Whether Add server starts with the Communities page: no community server is added yet. */
    @JvmStatic
    fun introDue(added: Collection<String>): Boolean = added.none { it.lowercase() in services }

    /** Whether choosing [protocol] shows its help page first: it has no account yet. */
    @JvmStatic
    fun guideDue(protocol: MessageProtocol, added: Collection<String>): Boolean =
        added.none { it.equals(protocol.name, ignoreCase = true) }

    /** Where Back from the server list goes: the help page when it showed, otherwise Choose a service. */
    @JvmStatic
    fun listBack(guided: Boolean): String = if (guided) "guide" else "services"
}
