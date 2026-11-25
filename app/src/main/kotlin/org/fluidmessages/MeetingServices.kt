package org.fluidmessages

/**
 * The meeting services a meeting link can use. Zoom is first and the default. FluidMessages has no account with
 * Zoom, Google, or Microsoft, so for those it sends a link you saved yourself; only Jitsi Meet makes a new room on
 * the spot. The browser companion (server/meetings.js) has the same list, checks, and wording.
 */
enum class MeetingService(
    val id: String, val label: String,
    /** Who runs it, for "Zoom can see who joins". Empty for Jitsi, where it's whoever runs the server. */
    val company: String,
    /** On unless turned off in Settings → Calls → Meeting links. */
    val onAtFirst: Boolean,
    /** The service's own page, where you find your link. */
    val home: String,
    /** What a link looks like, shown as the field's hint. */
    val example: String,
    /** Where to find your link, in three short steps. */
    val steps: List<String>,
) {
    ZOOM("zoom", "Zoom", "Zoom", true, "https://zoom.us/profile", "https://zoom.us/j/1234567890", listOf(
        "Open Zoom with the button below and sign in.",
        "On your Profile page, find Personal Meeting ID and copy the link under it.",
        "Come back here and paste the link.")),
    MEET("meet", "Google Meet", "Google", false, "https://meet.google.com/", "https://meet.google.com/abc-defg-hij", listOf(
        "Open Google Meet with the button below and sign in.",
        "Tap New meeting, then Create a meeting for later, and copy the link.",
        "Come back here and paste the link.")),
    TEAMS("teams", "Microsoft Teams", "Microsoft", false, "https://teams.microsoft.com/", "https://teams.microsoft.com/meet/1234567890", listOf(
        "Open Microsoft Teams with the button below and sign in.",
        "Tap Meet, then Create a meeting link, and copy the link.",
        "Come back here and paste the link.")),
    JITSI("jitsi", "Jitsi Meet", "", true, "https://meet.jit.si/", "https://meet.jit.si/YourRoom", emptyList());

    /** Jitsi makes a fresh room each time. The others send the link you saved. */
    val needsSavedLink: Boolean get() = this != JITSI

    /** The short name used inside a message: "Join my Teams call". */
    val shortLabel: String get() = if (this == TEAMS) "Teams" else label

    /** Who can see the call, said plainly. */
    val privacy: String get() = (if (this == JITSI) "The meeting server can access the call."
        else "$company can see who joins and may record.") + " For a private call, use an encrypted call instead."

    /** The one sentence at the top of the setup page. */
    val setupLine: String get() = "FluidMessages can’t make $label meetings for you. Save your own $label link once, " +
        "and FluidMessages sends it each time you pick $label."

    companion object {
        const val DEFAULT_JITSI = "meet.jit.si"
        @JvmStatic fun of(id: String?): MeetingService? = entries.firstOrNull { it.id.equals(id, true) }
    }
}

/** Checking and reading meeting links. Plain text rules, the same as server/meetings.js and server/main.py. */
object MeetingLinks {
    private val zoom = Regex("""^https://(?:[a-z0-9-]+\.)*zoom\.us/(?:j/\d{9,11}|my/[A-Za-z0-9._-]{1,64})(?:[?#]\S*)?$""", RegexOption.IGNORE_CASE)
    private val meet = Regex("""^https://meet\.google\.com/[a-z]{3}-[a-z]{4}-[a-z]{3}(?:[?#]\S*)?$""", RegexOption.IGNORE_CASE)
    private val teams = Regex("""^https://teams\.(?:microsoft|live)\.com/(?:l/meetup-join/\S+|meet/[A-Za-z0-9]+(?:[?#]\S*)?)$""", RegexOption.IGNORE_CASE)
    private val server = Regex("""^[A-Za-z0-9.-]{3,253}(:[0-9]{1,5})?$""")
    private val anyLink = Regex("""https://[^\s<>"']+""")
    /** What FluidMessages sends: "📹 Join my Zoom video call: https://…". Jitsi has no service name, as before. */
    private val invite = Regex("""^(?:📹|📞)?\s*Join my (?:(?:Zoom|Google Meet|Teams) )?(video )?call: (https://\S+)\s*$""")
    /** The older shape, a room on any server, which is how a Jitsi room on your own server looks. */
    private val room = Regex("""^https://[A-Za-z0-9.-]+(?::\d+)?/[A-Za-z0-9_-]+$""")

    /** A typed or pasted link, tidied: spaces trimmed and https:// added when it's missing. */
    @JvmStatic fun tidy(text: String): String {
        val value = text.trim()
        return when {
            value.startsWith("https://", true) -> "https://" + value.substring(8)
            value.startsWith("http://", true) -> "https://" + value.substring(7)
            value.isEmpty() || "://" in value -> value
            else -> "https://$value"
        }
    }

    /** A Jitsi server name such as meet.jit.si, or null when it isn't one. */
    @JvmStatic fun cleanServer(text: String): String? =
        text.trim().replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "").trimEnd('/').takeIf { server.matches(it) }

    /** The link, tidied, when it really is a [service] meeting link; null when it isn't. */
    @JvmStatic @JvmOverloads
    fun valid(service: MeetingService, text: String, jitsiServer: String = MeetingService.DEFAULT_JITSI): String? {
        val link = tidy(text)
        if (link.length > 2000 || link.any { it.isWhitespace() }) return null
        val ok = when (service) {
            MeetingService.ZOOM -> zoom.matches(link)
            MeetingService.MEET -> meet.matches(link)
            MeetingService.TEAMS -> teams.matches(link)
            MeetingService.JITSI -> room.matches(link) && host(link).let { it.equals(jitsiServer, true) || it.equals(MeetingService.DEFAULT_JITSI, true) }
        }
        return if (ok) link else null
    }

    /** Which service a link belongs to, or null for any other link. */
    @JvmStatic @JvmOverloads
    fun serviceOf(link: String, jitsiServer: String = MeetingService.DEFAULT_JITSI): MeetingService? =
        MeetingService.entries.firstOrNull { valid(it, link, jitsiServer) == link }

    /** What to tell someone whose pasted link isn't right. */
    @JvmStatic fun problem(service: MeetingService, text: String): String =
        if (text.isBlank()) "Paste your ${service.label} link first."
        else "That doesn’t look like a ${service.label} link. It should look like ${service.example}"

    internal fun host(link: String): String = link.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')

    /** The message that carries a meeting link. Every app can open it; FluidMessages shows it as a call card. */
    @JvmStatic fun text(service: MeetingService, video: Boolean, link: String): String {
        val name = if (service == MeetingService.JITSI) "" else service.shortLabel + " "
        return if (video) "📹 Join my ${name}video call: $link" else "📞 Join my ${name}call: $link"
    }

    /** A call card's parts, or null for any other message. */
    internal fun parse(text: String, jitsiServer: String = MeetingService.DEFAULT_JITSI): MeetingInvite? {
        val match = invite.find(text.trim()) ?: return null
        val link = match.groupValues[2]
        val service = serviceOf(link, jitsiServer)
        if (service == null && !room.matches(link)) return null
        return MeetingInvite(match.groupValues[1].isNotEmpty(), link, service)
    }

    /** The first meeting link in an everyday message, for the "Join meeting" button under it. */
    internal fun find(text: String, jitsiServer: String = MeetingService.DEFAULT_JITSI): Pair<MeetingService, String>? {
        for (match in anyLink.findAll(text)) {
            val link = match.value.trimEnd('.', ',', ')', '!', ';', ':', '’', '”')
            serviceOf(link, jitsiServer)?.let { return it to link }
        }
        return null
    }

    /** Where Join goes. A Jitsi voice call starts with the camera off; other services decide for themselves. */
    @JvmStatic fun joinLink(service: MeetingService?, video: Boolean, link: String): String =
        if (!video && (service == null || service == MeetingService.JITSI)) "$link#config.startWithVideoMuted=true" else link

    @JvmStatic fun newJitsiRoom(jitsiServer: String, randomHex: String): String = "https://$jitsiServer/FluidMessages-$randomHex"
}

/** A meeting-link call in a message. [service] is null for a room on a server FluidMessages doesn't know. */
internal data class MeetingInvite(val video: Boolean, val link: String, val service: MeetingService? = null) {
    val server: String get() = MeetingLinks.host(link)
    /** "Zoom", or the server's name for a Jitsi room. */
    val place: String get() = if (service == null || service == MeetingService.JITSI) server else service.label
}

/**
 * Your meeting-link choices: which services are on, your saved links, the one you used last, and the Jitsi server.
 * Read from the app's settings as plain values, so it can be checked without a phone.
 *
 * Settings from before there was a choice of service keep working: "meetingServer" is still the Jitsi server, and
 * someone who set their own Jitsi server keeps Jitsi as their first pick until they choose something else.
 */
data class MeetingSettings(val on: Map<MeetingService, Boolean>, val links: Map<MeetingService, String>,
                           val last: MeetingService?, val jitsiServer: String) {
    fun isOn(service: MeetingService): Boolean = on[service] ?: service.onAtFirst

    /** The services you can pick when sending, Zoom first. Never empty: with all turned off, Zoom and Jitsi show. */
    val enabled: List<MeetingService> get() = MeetingService.entries.filter { isOn(it) }
        .ifEmpty { MeetingService.entries.filter { it.onAtFirst } }

    /** The last one you used when it's still on; a Jitsi server of your own means Jitsi; otherwise the first, Zoom. */
    val preselected: MeetingService get() {
        val choices = enabled
        if (last != null && last in choices) return last
        if (last == null && jitsiServer != MeetingService.DEFAULT_JITSI && MeetingService.JITSI in choices) return MeetingService.JITSI
        return choices.first()
    }

    /** A saved link that still passes the check; a broken one counts as not saved. */
    fun link(service: MeetingService): String? = links[service]?.let { MeetingLinks.valid(service, it, jitsiServer) }

    /** Ready to send right away: Jitsi always, the others once a link is saved. */
    fun ready(service: MeetingService): Boolean = !service.needsSavedLink || link(service) != null

    /** Whether [service] may be turned off: one always stays on, so the picker is never empty. */
    fun canTurnOff(service: MeetingService): Boolean = MeetingService.entries.any { it != service && isOn(it) }

    /** The line under a service in the picker and in settings. */
    fun detail(service: MeetingService): String = when {
        !service.needsSavedLink -> "Makes a new room each time on $jitsiServer. No account needed."
        link(service) != null -> "Sends your saved link"
        else -> "Save your own ${service.label} link first"
    }

    companion object {
        const val SERVER = "meetingServer"
        const val LAST = "meeting.last"
        @JvmStatic fun onKey(service: MeetingService) = "meeting.on." + service.id
        @JvmStatic fun linkKey(service: MeetingService) = "meeting.link." + service.id

        /** From stored settings (SharedPreferences.getAll); anything missing or of the wrong kind uses the default. */
        @JvmStatic fun from(stored: Map<String, *>): MeetingSettings {
            val server = (stored[SERVER] as? String)?.let(MeetingLinks::cleanServer) ?: MeetingService.DEFAULT_JITSI
            val on = MeetingService.entries.mapNotNull { service -> (stored[onKey(service)] as? Boolean)?.let { service to it } }.toMap()
            val links = MeetingService.entries.filter { it.needsSavedLink }
                .mapNotNull { service -> (stored[linkKey(service)] as? String)?.takeIf { it.isNotBlank() }?.let { service to it } }.toMap()
            return MeetingSettings(on, links, MeetingService.of(stored[LAST] as? String), server)
        }
    }
}
