package org.fluidmessages

/**
 * Settings → Running now: what FluidMessages keeps going right now, in plain words. A status page rather than a
 * battery manager: it reads the same settings, jobs, and connections the rest of the app uses (RunningNowPage reads
 * them into a [Snapshot]) and never starts anything. The sorting and every word are here, kept pure for the tests.
 */
internal object BackgroundStatus {
    enum class Group(val title: String) {
        ALWAYS("Always running"),
        SCHEDULE("On a schedule"),
        OPEN("Only while FluidMessages is open"),
        OFF("Not running")
    }

    enum class Icon { CONNECTION, SCHEDULE, WEB, BACKUP, NEARBY, TOR, CALL, TIMER, OUTBOX }

    /** Where a row's tap goes: the setting that controls it (MainActivity.openRunning). */
    enum class Opens { ACCOUNTS, BATTERY, COMPANION, BACKUPS, NEARBY, TOR, CALLS, PRIVACY, ABOUT }

    data class Row(val id: String, val group: Group, val icon: Icon, val name: String, val state: String, val why: String,
                   val opens: Opens, val costly: Boolean = false)

    /** A chat service connected from this phone (DirectProtocols): its switches and its last saved status. */
    data class Connection(
        val label: String,
        /** Signed in and turned on (DirectProtocols.on and the service's switch). */
        val on: Boolean,
        val stayConnected: Boolean,
        /** A client is running in this process now. */
        val live: Boolean,
        /** DirectProtocols.status: connected, connecting, reconnecting, disconnected, off, or "". */
        val state: String = "",
        val manual: Boolean = false,
        val since: Long = 0,
        val retryAt: Long = 0,
        /** IRC that uses FluidMessages Web's connection with the same nick instead of its own. */
        val usesWeb: Boolean = false,
        /** Checked by the background job when it isn't kept connected (Matrix and Email). */
        val polled: Boolean = false
    )

    /** One backup schedule, with whether Android has it as a pending job. */
    data class Backup(val id: String, val name: String, val minutes: Int, val wifiOnly: Boolean, val last: Long, val scheduled: Boolean)

    data class Snapshot(
        val now: Long,
        val connections: List<Connection> = emptyList(),
        val nearbyOnly: Boolean = false,
        // Background check (SyncJob, SyncPolicy)
        val backgroundMinutes: Int = 15,
        val checkScheduled: Boolean = false,
        val lastCheck: Long = 0,
        val wifiOnly: Boolean = false,
        val pauseOnSaver: Boolean = true,
        val saverOn: Boolean = false,
        val foregroundSeconds: Int = 20,
        val disappearingTimers: Boolean = false,
        // FluidMessages Web
        val paired: Boolean = false,
        val syncTexts: Boolean = true,
        val lastWebSync: Long = 0,
        val webMatrixCalls: Boolean = false,
        // Backups
        val backups: List<Backup> = emptyList(),
        // Nearby
        val nearbyOn: Boolean = false,
        val discoverable: Boolean = false,
        val nearbyLive: Boolean = false,
        val helping: Boolean = true,
        val bluetooth: Boolean = false,
        // Tor
        val torOn: Boolean = false,
        /** TorRoute.words(status), e.g. "Connected through Tor"; "" while it hasn't been checked yet. */
        val torWords: String = "",
        /** Who runs Tor: "Orbot", or "built-in". */
        val torEngine: String = "Orbot",
        // Calls
        val sipReady: Boolean = false,
        /** SipAgent.mode: off, open, or always. */
        val sipMode: String = "open",
        val sipStatus: String = "",
        val callsHeldByTor: Boolean = false,
        // One-off jobs and alarms
        val disappearingAlarm: Long = 0,
        val outboxWaiting: Boolean = false,
        val syncSoonWaiting: Boolean = false,
        val updateChecks: Boolean = false,
        /** Pending Android job ids nothing above accounts for. */
        val otherJobs: List<Int> = emptyList()
    )

    /** Every row, in group order and then in the order they're built. [clock] formats a time of day, e.g. "9:41". */
    fun rows(s: Snapshot, clock: (Long) -> String): List<Row> {
        val rows = mutableListOf<Row>()
        val internet = !s.nearbyOnly

        // Chat services connected from this phone
        for (c in s.connections) {
            if (!c.on) continue
            val id = "direct-" + c.label.lowercase()
            when {
                !internet -> rows += Row(id, Group.OFF, Icon.CONNECTION, c.label, "Paused · Nearby only is on",
                    "Nearby only keeps FluidMessages off the internet.", Opens.NEARBY)
                c.usesWeb -> rows += Row(id, Group.OFF, Icon.CONNECTION, c.label, "Uses FluidMessages Web’s connection",
                    "FluidMessages Web is already connected as you, so this phone doesn’t open a second connection.", Opens.ACCOUNTS)
                c.stayConnected -> rows += Row(id, Group.ALWAYS, Icon.CONNECTION, c.label, connectionWords(c, clock),
                    "Stay connected is on, so messages arrive right away, even with FluidMessages closed. "
                        + "Android shows a “Staying connected” notification.", Opens.ACCOUNTS, costly = true)
                else -> rows += Row(id, Group.OPEN, Icon.CONNECTION, c.label,
                    if (c.live) connectionWords(c, clock) else "Connects when you open FluidMessages",
                    if (c.polled) "Closed when you leave FluidMessages. The background check looks for new messages in between."
                    else "Closed when you leave FluidMessages. Turn on Stay connected to get messages right away.", Opens.ACCOUNTS)
            }
        }

        // Background check (SyncJob)
        val minutes = maxOf(15, s.backgroundMinutes)
        val polled = s.connections.filter { it.on && it.polled && !it.stayConnected && !it.usesWeb && internet }.map { it.label }
        if (s.backgroundMinutes == 0) {
            rows += Row("check", Group.OFF, Icon.SCHEDULE, "Background check", "Off",
                "New messages from FluidMessages Web, Matrix, and Email wait until you open FluidMessages.", Opens.BATTERY)
        } else if (s.checkScheduled) {
            val parts = mutableListOf("Checks " + every(minutes).replaceFirstChar { it.lowercase() })
            if (s.pauseOnSaver && s.saverOn) parts += "paused for Battery Saver"
            else nextAt(s.lastCheck, minutes, s.now, clock)?.let { parts += it }
            if (s.wifiOnly) parts += "Wi-Fi only"
            val jobs = mutableListOf<String>()
            if (s.paired) jobs += "syncs with FluidMessages Web"
            if (polled.isNotEmpty()) jobs += "checks " + list(polled)
            if (s.disappearingTimers) jobs += "removes disappearing messages"
            rows += Row("check", Group.SCHEDULE, Icon.SCHEDULE, "Background check", parts.joinToString(" · "),
                if (jobs.isEmpty()) "Wakes FluidMessages now and then. Android groups it with other apps’ work."
                else "Wakes FluidMessages now and then: " + list(jobs) + ".", Opens.BATTERY)
        }

        // FluidMessages Web
        if (s.paired) {
            val parts = mutableListOf<String>()
            parts += if (s.lastWebSync > 0) "Last synced " + when {
                s.now - s.lastWebSync < 60_000 -> "just now"
                recent(s.lastWebSync, s.now) -> "at " + clock(s.lastWebSync)
                else -> "more than a day ago"
            } else "Not synced yet"
            val background = s.backgroundMinutes > 0 && s.checkScheduled && internet
            if (background && !(s.pauseOnSaver && s.saverOn)) nextAt(s.lastCheck, minutes, s.now, clock)?.let { parts += it }
            rows += Row("web", if (!internet) Group.OFF else if (background) Group.SCHEDULE else Group.OPEN, Icon.WEB,
                "FluidMessages Web sync", if (!internet) "Paused · Nearby only is on" else parts.joinToString(" · "),
                (if (s.syncTexts) "Sends your texts to FluidMessages Web and picks up the ones you wrote there. "
                else "Picks up settings and chats from FluidMessages Web. Texts aren’t synced. ")
                    + if (background) "Runs with the background check." else "Runs only while FluidMessages is open.",
                Opens.COMPANION)
            if (internet && s.foregroundSeconds > 0) rows += Row("web-open", Group.OPEN, Icon.WEB, "Checking while open",
                "Every " + seconds(s.foregroundSeconds), "Keeps your chats up to date while you’re looking at them.", Opens.BATTERY)
            if (internet && s.webMatrixCalls) rows += Row("web-calls", Group.OPEN, Icon.CALL, "Matrix calls through FluidMessages Web",
                "Checks every 3 seconds", "So a Matrix call can ring here while FluidMessages is open.", Opens.CALLS)
        }

        // Backups
        for (b in s.backups) {
            if (b.minutes <= 0) continue
            val parts = mutableListOf(every(b.minutes))
            if (!b.scheduled) parts += "Android doesn’t have it scheduled · open Backups to save it again"
            else {
                if (b.last > 0) parts += "last " + (if (recent(b.last, s.now)) "at " + clock(b.last) else "more than a day ago")
                nextAt(b.last, b.minutes, s.now, clock)?.let { parts += it }
            }
            if (b.wifiOnly) parts += "Wi-Fi only"
            rows += Row("backup-" + b.id, Group.SCHEDULE, Icon.BACKUP, b.name, parts.joinToString(" · "),
                "Saves a backup by itself. Android may run it a little later to save battery.", Opens.BACKUPS)
        }

        // Nearby
        if (s.nearbyOn) {
            val parts = mutableListOf<String>()
            parts += if (s.discoverable) "Discoverable" else "While FluidMessages is open"
            if (s.nearbyLive) parts += "looking now"
            if (s.helping) parts += "passing messages along"
            if (s.bluetooth) parts += "Bluetooth scanning"
            if (s.nearbyOnly) parts += "Nearby only"
            rows += Row("nearby", if (s.discoverable) Group.ALWAYS else Group.OPEN, Icon.NEARBY, "Nearby", parts.joinToString(" · "),
                (if (s.discoverable) "Stay discoverable is on, so friends close by can reach you with FluidMessages closed. "
                else "Looks for your people only while FluidMessages is open. ")
                    + (if (s.helping) "Help pass messages along carries other people’s sealed messages on. " else "")
                    + if (s.bluetooth) "Bluetooth looks for phones every few minutes." else "Wi-Fi only, no Bluetooth.",
                Opens.NEARBY, costly = s.discoverable)
        } else rows += Row("nearby", Group.OFF, Icon.NEARBY, "Nearby", "Off", "Chat with friends close by, no internet needed.", Opens.NEARBY)

        // Tor
        if (s.torOn) {
            val words = s.torWords.ifEmpty { "Checking…" }
            rows += Row("tor", Group.ALWAYS, Icon.TOR, "Tor", "$words · ${if (s.torEngine == "built-in") "built in" else "through ${s.torEngine}"}",
                "Chat services, FluidMessages Web, and previews connect through Tor. "
                    + if (s.torEngine == "built-in") "FluidMessages keeps Tor running." else "${s.torEngine} keeps Tor running.",
                Opens.TOR, costly = true)
        } else rows += Row("tor", Group.OFF, Icon.TOR, "Tor", "Off", "Connections go directly, not through Tor.", Opens.TOR)

        // Internet calls (SipAgent)
        if (s.sipReady) {
            val held = s.callsHeldByTor || !internet
            val heldWords = if (!internet) "Paused · Nearby only is on" else "Paused · calls can’t go through Tor"
            val status = s.sipStatus.ifEmpty { "Offline" }
            when (s.sipMode) {
                "always" -> rows += Row("sip", if (held) Group.OFF else Group.ALWAYS, Icon.CALL, "Internet calls (SIP)",
                    if (held) heldWords else status, "Stays signed in so calls ring even with FluidMessages closed.", Opens.CALLS, costly = !held)
                "open" -> rows += Row("sip", if (held) Group.OFF else Group.OPEN, Icon.CALL, "Internet calls (SIP)",
                    if (held) heldWords else status, "Calls ring only while FluidMessages is open.", Opens.CALLS)
                else -> rows += Row("sip", Group.OFF, Icon.CALL, "Internet calls (SIP)", "Doesn’t receive calls",
                    "You can still place calls.", Opens.CALLS)
            }
        }

        // One-off jobs and alarms
        if (s.disappearingAlarm > s.now - 60_000) rows += Row("disappearing", Group.SCHEDULE, Icon.TIMER, "Disappearing messages",
            "Next one goes " + (if (recent(s.disappearingAlarm, s.now)) "at " + clock(s.disappearingAlarm) else "in more than a day"),
            "A one-time alarm wakes FluidMessages to delete it on time, then sets the next.", Opens.PRIVACY)
        if (s.outboxWaiting) rows += Row("outbox", Group.SCHEDULE, Icon.OUTBOX, "Messages waiting to send",
            "Waiting for a connection", "Sends what you wrote while offline once you’re back online.", Opens.ACCOUNTS)
        if (s.syncSoonWaiting) rows += Row("sync-soon", Group.SCHEDULE, Icon.WEB, "One sync soon", "Waiting for a network",
            "Tells FluidMessages Web about messages removed with the app closed.", Opens.COMPANION)
        if (s.updateChecks) rows += Row("updates", Group.OPEN, Icon.SCHEDULE, "Update check", "Once a day",
            "Asks GitHub for a newer FluidMessages.", Opens.ABOUT)
        for (job in s.otherJobs) rows += Row("job-$job", Group.SCHEDULE, Icon.SCHEDULE, "Other scheduled job", "Android job $job",
            "Scheduled by FluidMessages. Android runs it when its conditions are met.", Opens.BATTERY)

        return Group.values().flatMap { group -> rows.filter { it.group == group } }
    }

    /** The background ones: always running or on a schedule. */
    fun running(rows: List<Row>) = rows.count { it.group == Group.ALWAYS || it.group == Group.SCHEDULE }

    const val NOTHING = "Nothing is running in the background"

    /** The Settings row: "3 things running", or [NOTHING]. */
    fun summary(rows: List<Row>): String = when (val count = running(rows)) {
        0 -> NOTHING
        1 -> "1 thing running"
        else -> "$count things running"
    }

    const val COSTLY = "Uses more battery"

    fun connectionWords(c: Connection, clock: (Long) -> String): String = when (c.state) {
        "connected" -> if (c.since > 0) "Connected · since " + clock(c.since) else "Connected"
        "connecting" -> "Connecting…"
        "reconnecting" -> if (c.retryAt > 0) "Reconnecting · next try at " + clock(c.retryAt) else "Reconnecting…"
        "disconnected" -> if (c.manual) "Disconnected · tap Reconnect in its account" else "Disconnected"
        else -> if (c.live) "Starting…" else "Not connected"
    }

    fun every(minutes: Int): String = when {
        minutes < 60 -> "Every $minutes minutes"
        minutes == 60 -> "Every hour"
        minutes % 1440 == 0 -> if (minutes == 1440) "Every day" else "Every ${minutes / 1440} days"
        minutes % 60 == 0 -> "Every ${minutes / 60} hours"
        else -> "Every $minutes minutes"
    }

    private fun seconds(value: Int) = if (value < 60) "$value seconds" else if (value == 60) "minute" else "${value / 60} minutes"

    /** "next at about 10:00" (or "next in about 22 hours") from the last run; null with no last run to count from. */
    fun nextAt(last: Long, minutes: Int, now: Long, clock: (Long) -> String): String? {
        if (last <= 0) return null
        val period = minutes * 60_000L
        var next = last + period
        // Android may have skipped a window (Doze, no network): the next one counts on from the last.
        if (next < now) next += ((now - next) / period + 1) * period
        val wait = next - now
        // Far off, a time of day could be today or tomorrow, so it says how long instead.
        return when {
            wait > 7 * 86_400_000L -> null
            wait > 36 * 3_600_000L -> "next in about ${(wait + 43_200_000L) / 86_400_000L} days"
            wait > 12 * 3_600_000L -> "next in about ${(wait + 1_800_000L) / 3_600_000L} hours"
            else -> "next at about " + clock(next)
        }
    }

    /** Close enough to now that a time of day says when. */
    private fun recent(time: Long, now: Long) = kotlin.math.abs(now - time) < 20 * 3_600_000L

    private fun list(items: List<String>) = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
