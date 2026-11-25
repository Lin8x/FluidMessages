package org.fluidmessages

/**
 * The meeting services a meeting link can use. Zoom is first and the default; Zoom, Google Meet, Microsoft Teams, and
 * Jitsi Meet are all on at first. FluidMessages has no account with Zoom, Google, or Microsoft, so for those it sends
 * a link you saved yourself; only Jitsi Meet makes a new room on the spot. You can also add your own service: a name
 * and a saved https link ([custom]). The browser companion (server/meetings.js) has the same list, checks, and wording.
 */
class MeetingService internal constructor(
    val id: String, val label: String,
    /** Who runs it, for "Zoom can see who joins". Empty for Jitsi and for your own services. */
    val company: String,
    /** On unless turned off in Settings → Calls → Meeting links. */
    val onAtFirst: Boolean,
    /** The service's own page, where you find your link. Empty for your own services. */
    val home: String,
    /** What a link looks like, shown as the field's hint. */
    val example: String,
    /** Where to find your link, in three short steps. */
    val steps: List<String>,
    /** For a service you added: its saved link. Null for the built-in services. */
    val customLink: String? = null,
    /** For a service you added: the host its link must be on, such as video.example.org, or empty for any host. */
    val customHost: String = "",
) {
    /** A service you added yourself, with a name and a link you saved. */
    val custom: Boolean get() = customLink != null

    /** Jitsi makes a fresh room each time. The others send the link you saved. */
    val needsSavedLink: Boolean get() = this != JITSI

    /** The short name used inside a message: "Join my Teams call". */
    val shortLabel: String get() = if (this == TEAMS) "Teams" else label

    /** Who can see the call, said plainly. */
    val privacy: String get() = when {
        this == JITSI -> "The meeting server can access the call."
        custom -> "Whoever runs $label can see who joins and may record."
        else -> "$company can see who joins and may record."
    } + " For a private call, use an encrypted call instead."

    /** The one sentence at the top of the setup page. */
    val setupLine: String get() = if (custom) "FluidMessages sends your saved $label link each time you pick $label."
        else "FluidMessages can’t make $label meetings for you. Save your own $label link once, " +
            "and FluidMessages sends it each time you pick $label."

    override fun equals(other: Any?): Boolean = other is MeetingService && other.id == id && other.custom == custom
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = label

    companion object {
        @JvmField val ZOOM = MeetingService("zoom", "Zoom", "Zoom", true, "https://zoom.us/profile", "https://zoom.us/j/1234567890", listOf(
            "Open Zoom with the button below and sign in.",
            "On your Profile page, find Personal Meeting ID and copy the link under it.",
            "Come back here and paste the link."))
        @JvmField val MEET = MeetingService("meet", "Google Meet", "Google", true, "https://meet.google.com/", "https://meet.google.com/abc-defg-hij", listOf(
            "Open Google Meet with the button below and sign in.",
            "Tap New meeting, then Create a meeting for later, and copy the link.",
            "Come back here and paste the link."))
        @JvmField val TEAMS = MeetingService("teams", "Microsoft Teams", "Microsoft", true, "https://teams.microsoft.com/", "https://teams.microsoft.com/meet/1234567890", listOf(
            "Open Microsoft Teams with the button below and sign in.",
            "Tap Meet, then Create a meeting link, and copy the link.",
            "Come back here and paste the link."))
        @JvmField val JITSI = MeetingService("jitsi", "Jitsi Meet", "", true, "https://meet.jit.si/", "https://meet.jit.si/YourRoom", emptyList())

        /** The built-in services, Zoom first. */
        @JvmField val entries: List<MeetingService> = listOf(ZOOM, MEET, TEAMS, JITSI)

        const val DEFAULT_JITSI = "meet.jit.si"
        /** The longest name your own service can have. */
        const val NAME_MAX = 32
        /** How many services of your own you can add. */
        const val CUSTOM_MAX = 10

        /** A built-in service by its id, or null. Your own services are found with [MeetingSettings.service]. */
        @JvmStatic fun of(id: String?): MeetingService? = entries.firstOrNull { it.id.equals(id, true) }

        /** A service you added: a name such as "Work video", the link it sends, and the host it must be on ("" for any). */
        @JvmStatic @JvmOverloads
        fun custom(id: String, name: String, link: String, host: String = ""): MeetingService =
            MeetingService(id, name, "", true, "", "https://" + host.ifEmpty { "video.example.org" } + "/your-room", emptyList(), link, host)
    }
}

/** Checking and reading meeting links. Plain text rules, the same as server/meetings.js and server/main.py. */
object MeetingLinks {
    private val zoom = Regex("""^https://(?:[a-z0-9-]+\.)*zoom\.us/(?:j/\d{9,11}|my/[A-Za-z0-9._-]{1,64})(?:[?#]\S*)?$""", RegexOption.IGNORE_CASE)
    private val meet = Regex("""^https://meet\.google\.com/[a-z]{3}-[a-z]{4}-[a-z]{3}(?:[?#]\S*)?$""", RegexOption.IGNORE_CASE)
    private val teams = Regex("""^https://teams\.(?:microsoft|live)\.com/(?:l/meetup-join/\S+|meet/[A-Za-z0-9]+(?:[?#]\S*)?)$""", RegexOption.IGNORE_CASE)
    private val server = Regex("""^[A-Za-z0-9.-]{3,253}(:[0-9]{1,5})?$""")
    private val anyLink = Regex("""https://[^\s<>"']+""")
    /** A link to a service you added: https, a real host name, then anything with no spaces. */
    private val customLink = Regex("""^https://([A-Za-z0-9-]{1,63}(?:\.[A-Za-z0-9-]{1,63})+)(?::\d{1,5})?(?:[/?#]\S*)?$""")
    /** The host a service you added must be on, such as video.example.org. */
    private val hostName = Regex("""^[a-z0-9-]{1,63}(?:\.[a-z0-9-]{1,63})+$""")
    /**
     * What FluidMessages sends: "📹 Join my Zoom video call: https://…", or "📹 Join my Work video call: https://…"
     * for a service you added. Jitsi has no service name, as before. Groups: the emoji, the name, "video ", the link.
     */
    private val invite = Regex("""^(📹|📞)?\s*Join my (?:(\S.{0,31}?) )??(video )?call: (https://\S+)\s*$""")
    /** The older shape, a room on any server, which is how a Jitsi room on your own server looks. */
    private val room = Regex("""^https://[A-Za-z0-9.-]+(?::\d+)?/[A-Za-z0-9_-]+$""")

    /** The names the built-in services use, which a service you add can't take. */
    private val builtInNames: Set<String> = MeetingService.entries.flatMap { listOf(it.label, it.shortLabel) }.map { it.lowercase() }.toSet()

    /** A character that can't be seen or that changes how the text around it shows. */
    private fun hidden(text: String): Boolean = text.codePoints().anyMatch { point ->
        Character.getType(point).let { it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() }
    }

    private fun spaced(text: String): String = text.trim().replace(Regex("""\s+"""), " ")

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

    /** The name for a service you add, tidied; null when it's empty, longer than [MeetingService.NAME_MAX], or a built-in one's. */
    @JvmStatic fun cleanName(text: String): String? =
        spaced(text).takeIf { it.isNotEmpty() && it.length <= MeetingService.NAME_MAX && !hidden(it) && it.lowercase() !in builtInNames }

    /** What's wrong with a name for a service you add, or null when it's fine. [others] are your other services' names. */
    @JvmStatic @JvmOverloads
    fun nameProblem(text: String, others: List<String> = emptyList()): String? {
        val name = spaced(text)
        return when {
            name.isEmpty() -> "Give your service a name."
            name.length > MeetingService.NAME_MAX -> "Use a shorter name, ${MeetingService.NAME_MAX} letters or fewer."
            hidden(name) -> "That name has a letter that can’t be shown. Type it again."
            name.lowercase() in builtInNames -> "$name is already on the list. Use another name."
            others.any { it.equals(name, true) } -> "You already have a service named $name."
            else -> null
        }
    }

    /** The host a link must be on, tidied (video.example.org); "" when left empty; null when it isn't a host name. */
    @JvmStatic fun cleanHost(text: String): String? {
        val value = text.trim().replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "").trimEnd('/').lowercase()
        return if (value.isEmpty()) "" else value.takeIf { it.length <= 253 && hostName.matches(it) }
    }

    /** The link, tidied, when it really is a [service] meeting link; null when it isn't. */
    @JvmStatic @JvmOverloads
    fun valid(service: MeetingService, text: String, jitsiServer: String = MeetingService.DEFAULT_JITSI): String? {
        val link = tidy(text)
        if (link.length > 2000 || link.any { it.isWhitespace() }) return null
        val ok = when {
            // Your own service: any https link, on its host (or under it) when you gave one.
            service.custom -> customLink.matchEntire(link)?.groupValues?.get(1)?.lowercase()
                ?.let { host -> service.customHost.isEmpty() || host == service.customHost || host.endsWith("." + service.customHost) } == true
            service == MeetingService.ZOOM -> zoom.matches(link)
            service == MeetingService.MEET -> meet.matches(link)
            service == MeetingService.TEAMS -> teams.matches(link)
            service == MeetingService.JITSI -> room.matches(link) && host(link).let { it.equals(jitsiServer, true) || it.equals(MeetingService.DEFAULT_JITSI, true) }
            else -> false
        }
        return if (ok) link else null
    }

    /** A link for a service you add, tidied, when it's an https link (on [host] when one is given); null when it isn't. */
    @JvmStatic @JvmOverloads
    fun validCustom(text: String, host: String = ""): String? = valid(MeetingService.custom("", "", "", host), text)

    /** Which built-in service a link belongs to, or null for any other link. */
    @JvmStatic @JvmOverloads
    fun serviceOf(link: String, jitsiServer: String = MeetingService.DEFAULT_JITSI): MeetingService? =
        MeetingService.entries.firstOrNull { valid(it, link, jitsiServer) == link }

    /** What to tell someone whose pasted link isn't right. */
    @JvmStatic fun problem(service: MeetingService, text: String): String = when {
        text.isBlank() -> "Paste your ${service.label} link first."
        service.custom -> linkProblem(text, service.customHost) ?: ""
        else -> "That doesn’t look like a ${service.label} link. It should look like ${service.example}"
    }

    /** What's wrong with a link for a service you add, or null when it's fine. */
    @JvmStatic fun linkProblem(text: String, host: String): String? = when {
        text.isBlank() -> "Paste your link first."
        validCustom(text, host) != null -> null
        host.isNotEmpty() && validCustom(text) != null -> "That link isn’t on $host. Check it for a typo."
        else -> "That doesn’t look like a web link. It should start with https:// and have no spaces."
    }

    internal fun host(link: String): String = link.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')

    /** The message that carries a meeting link. Every app can open it; FluidMessages shows it as a call card. */
    @JvmStatic fun text(service: MeetingService, video: Boolean, link: String): String {
        val name = if (service == MeetingService.JITSI) "" else service.shortLabel + " "
        // A name that already ends in "video" ("Work video") isn't followed by a second "video".
        val videoWord = if (Regex("""(?i)(^|\s)video $""").containsMatchIn(name)) "" else "video "
        return if (video) "📹 Join my ${name}${videoWord}call: $link" else "📞 Join my ${name}call: $link"
    }

    /**
     * A call card's parts, or null for any other message. No name or a built-in name needs that service's link (or a
     * Jitsi room); any other name is a service the sender added, and needs an https link.
     */
    internal fun parse(text: String, jitsiServer: String = MeetingService.DEFAULT_JITSI): MeetingInvite? {
        val match = invite.find(text.trim()) ?: return null
        val (emoji, named, videoWord, link) = match.destructured
        var name = named
        var video = videoWord.isNotEmpty()
        // "📞 Join my Work video call:" is a voice call on a service named Work video.
        if (emoji == "📞" && video) { name = if (name.isEmpty()) "video" else "$name video"; video = false }
        // A name such as "Video" leaves no second "video" word, so the 📹 says it's a video call.
        if (emoji == "📹") video = true
        if (name.isEmpty() || name.lowercase() in builtInNames) {
            val service = serviceOf(link, jitsiServer)
            if (service == null && !room.matches(link)) return null
            return MeetingInvite(video, link, service)
        }
        if (hidden(name) || validCustom(link) != link) return null
        return MeetingInvite(video, link, MeetingService.custom("", name, link))
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
    /** "Zoom", the server's name for a Jitsi room, or the sender's own name with the link's server ("Work video · video.example.org"). */
    val place: String get() = when {
        service == null || service == MeetingService.JITSI -> server
        service.custom -> service.label + " · " + server
        else -> service.label
    }
}

/**
 * Your meeting-link choices: which services are on, your saved links, the services you added, the one you used last,
 * and the Jitsi server. Read from the app's settings as plain values, so it can be checked without a phone.
 *
 * Settings from before there was a choice of service keep working: "meetingServer" is still the Jitsi server, and
 * someone who set their own Jitsi server keeps Jitsi as their first pick until they choose something else. Only a
 * switch you flipped is stored, so Google Meet and Teams (off at first in older versions) now show for anyone who never
 * touched them, and a choice you made stays.
 *
 * Your own services are kept as "meeting.custom" (their ids, in order), with "meeting.name.<id>", "meeting.link.<id>",
 * "meeting.host.<id>" (when you gave one), and "meeting.on.<id>" (once you switch it off) for each.
 */
data class MeetingSettings @JvmOverloads constructor(
    val on: Map<MeetingService, Boolean>, val links: Map<MeetingService, String>,
    val last: MeetingService?, val jitsiServer: String,
    /** The services you added, in the order you added them. */
    val custom: List<MeetingService> = emptyList(),
) {
    /** Every service: the built-in ones, Zoom first, then the ones you added. */
    val all: List<MeetingService> get() = MeetingService.entries + custom

    fun isOn(service: MeetingService): Boolean = on[service] ?: service.onAtFirst

    /** The services you can pick when sending, Zoom first. Never empty: with all turned off, the built-in ones show. */
    val enabled: List<MeetingService> get() = all.filter { isOn(it) }
        .ifEmpty { MeetingService.entries.filter { it.onAtFirst } }

    /** A service by its id, built-in or one you added. */
    fun service(id: String?): MeetingService? = all.firstOrNull { it.id == id }

    /** Whether you can add another service of your own. */
    val canAddCustom: Boolean get() = custom.size < MeetingService.CUSTOM_MAX

    /**
     * What's wrong with your own service before it's saved, or null when it can be saved. [id] is the service being
     * changed, or null for a new one.
     */
    fun customProblem(id: String?, name: String, link: String, host: String): String? {
        if (id == null && !canAddCustom) return "You can add up to ${MeetingService.CUSTOM_MAX} services of your own."
        MeetingLinks.nameProblem(name, custom.filter { it.id != id }.map { it.label })?.let { return it }
        val cleanHost = MeetingLinks.cleanHost(host) ?: return "Enter just the web address, like video.example.org."
        return MeetingLinks.linkProblem(link, cleanHost)
    }

    /**
     * The settings to write to save your own service, once [customProblem] found nothing wrong: each key's new value,
     * or null to remove it. A new service ([id] null) gets [newId] and starts on.
     */
    fun customWrites(id: String?, newId: String, name: String, link: String, host: String): Map<String, Any?> {
        val key = id ?: newId
        val cleanHost = MeetingLinks.cleanHost(host).orEmpty()
        val writes = linkedMapOf<String, Any?>(nameKey(key) to MeetingLinks.cleanName(name),
            "meeting.link.$key" to MeetingLinks.validCustom(link, cleanHost), hostKey(key) to cleanHost.ifEmpty { null })
        if (id == null) {
            writes[CUSTOM] = (custom.map { it.id } + key).joinToString(",")
            writes["meeting.on.$key"] = null
        }
        return writes
    }

    /** The settings to write to remove your own service [id]: everything about it goes. */
    fun customRemoval(id: String): Map<String, Any?> {
        val writes = linkedMapOf<String, Any?>(CUSTOM to custom.map { it.id }.filter { it != id }.joinToString(",").ifEmpty { null },
            nameKey(id) to null, "meeting.link.$id" to null, hostKey(id) to null, "meeting.on.$id" to null)
        if (last?.id == id) writes[LAST] = null
        return writes
    }

    /** The last one you used when it's still on; a Jitsi server of your own means Jitsi; otherwise the first, Zoom. */
    val preselected: MeetingService get() {
        val choices = enabled
        if (last != null && last in choices) return last
        if (last == null && jitsiServer != MeetingService.DEFAULT_JITSI && MeetingService.JITSI in choices) return MeetingService.JITSI
        return choices.first()
    }

    /** A saved link that still passes the check; a broken one counts as not saved. */
    fun link(service: MeetingService): String? =
        if (service.custom) MeetingLinks.valid(service, service.customLink.orEmpty())
        else links[service]?.let { MeetingLinks.valid(service, it, jitsiServer) }

    /** Ready to send right away: Jitsi always, the others once a link is saved. */
    fun ready(service: MeetingService): Boolean = !service.needsSavedLink || link(service) != null

    /** Whether [service] may be turned off: one always stays on, so the picker is never empty. */
    fun canTurnOff(service: MeetingService): Boolean = all.any { it != service && isOn(it) }

    /** The line under a service in the picker and in settings. */
    fun detail(service: MeetingService): String = when {
        !service.needsSavedLink -> "Makes a new room each time on $jitsiServer. No account needed."
        service.custom && link(service) != null -> "Your own service · sends your saved link"
        service.custom -> "Your link needs a fix first"
        link(service) != null -> "Sends your saved link"
        else -> "Save your own ${service.label} link first"
    }

    companion object {
        const val SERVER = "meetingServer"
        const val LAST = "meeting.last"
        /** The ids of the services you added, in order, joined with commas. */
        const val CUSTOM = "meeting.custom"
        private val customId = Regex("^c[0-9a-f]{8}$")
        @JvmStatic fun onKey(service: MeetingService) = "meeting.on." + service.id
        @JvmStatic fun linkKey(service: MeetingService) = "meeting.link." + service.id
        @JvmStatic fun nameKey(id: String) = "meeting.name.$id"
        @JvmStatic fun hostKey(id: String) = "meeting.host.$id"

        /** A new id for a service you add, from random hex digits. */
        @JvmStatic fun newCustomId(randomHex: String): String = "c" + randomHex.lowercase().take(8)

        /** From stored settings (SharedPreferences.getAll); anything missing or of the wrong kind uses the default. */
        @JvmStatic fun from(stored: Map<String, *>): MeetingSettings {
            val server = (stored[SERVER] as? String)?.let(MeetingLinks::cleanServer) ?: MeetingService.DEFAULT_JITSI
            val custom = (stored[CUSTOM] as? String).orEmpty().split(',').filter { customId.matches(it) }.distinct()
                .take(MeetingService.CUSTOM_MAX).mapNotNull { id ->
                    val name = (stored[nameKey(id)] as? String)?.let(MeetingLinks::cleanName) ?: return@mapNotNull null
                    val link = (stored["meeting.link.$id"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MeetingService.custom(id, name, link, (stored[hostKey(id)] as? String)?.let(MeetingLinks::cleanHost).orEmpty())
                }
            val all = MeetingService.entries + custom
            val on = all.mapNotNull { service -> (stored[onKey(service)] as? Boolean)?.let { service to it } }.toMap()
            val links = MeetingService.entries.filter { it.needsSavedLink }
                .mapNotNull { service -> (stored[linkKey(service)] as? String)?.takeIf { it.isNotBlank() }?.let { service to it } }.toMap()
            val last = (stored[LAST] as? String)?.let { id -> all.firstOrNull { it.id.equals(id, true) } }
            return MeetingSettings(on, links, last, server, custom)
        }
    }
}
