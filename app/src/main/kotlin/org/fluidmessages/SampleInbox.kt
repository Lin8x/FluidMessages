package org.fluidmessages

/**
 * The sample inbox's made-up data (Settings → Help → Sample inbox): its chats, group members, community servers,
 * starred favorites, and the mutes and unread chats it starts with. Everything is fictional, nothing here is ever
 * sent, and none of it is saved with your real chats. MainActivity draws it with the same screens as live chats.
 */
internal object SampleInbox {
    /** Each chat: name, initials, their first message, your reply, day, and service ("Group" is an RCS or SMS group). */
    @JvmField val CHATS: Array<Array<String>> = arrayOf(
        arrayOf("Maya Chen", "MC", "Coffee at ten?", "See you there!", "9:41 AM", "SMS"),
        arrayOf("Jordan Ellis", "JE", "I sent the route for Saturday.", "That looks like a great walk.", "Yesterday", "RCS"),
        arrayOf("Avery Brooks", "AB", "Are we still meeting Sunday?", "Yes, let’s meet at the park.", "Tuesday", "SMS"),
        arrayOf("Sam Rivera", "SR", "I saved you a seat!", "On my way.", "Monday", "RCS"),
        arrayOf("Weekend Plans", "WP", "Picnic by the lake on Saturday?", "I’ll bring dessert.", "Today", "Group"),
        arrayOf("Trail Alerts", "TA", "Trail update: the south entrance is open. Reply STOP to stop receiving these messages.", "STOP", "Today", "SMS"),
        arrayOf("Nora Patel", "NP", "Did you want the window seat?", "That would be great.", "Yesterday", "SMS"),
        arrayOf("Cameron Reed", "CR", "The pottery studio finally fired our pieces.", "Did the blue glaze come out okay?", "Yesterday", "RCS"),
        arrayOf("Photo Walk", "PW", "Blue hour at the footbridge tomorrow?", "I just charged the camera batteries.", "Today", "Group"),
        arrayOf("Trail Crew", "TC", "North trail advisory: the footpath reopens at noon.", "Great, I’ll bring the spare water bottles.", "Today", "Group"),
        arrayOf("Garden Committee", "GC", "Here’s the sketch for the raised beds.", "Let’s keep the path four feet wide.", "Yesterday", "MMS"),
        arrayOf("Leah Morgan", "LM", "The glaze workshop moved to Thursday.", "I’ll update the signup form.", "Yesterday", "Email"),
        arrayOf("Design Studio", "DS", "I pinned the new type samples in the room.", "The narrower one fits the poster.", "Monday", "Matrix"),
        arrayOf("Amir Nasser", "AN", "I pushed the updated timetable to the shared drive.", "I’ll check the last train.", "Tuesday", "XMPP"),
        arrayOf("#radio-club", "RC", "The antenna tuning session starts at seven.", "I’ll bring the analyzer.", "Today", "IRC"),
        arrayOf("#trail-maps", "TM", "I traced the new switchbacks on the east ridge.", "Nice, the old map skipped those.", "Yesterday", "IRC"),
        arrayOf("Book Swap", "BS", "Three new paperbacks on the shelf by the window.", "I’ll trade my mystery for the sci-fi one.", "Today", "Matrix"),
        // A Nearby chat: someone on the same Wi-Fi, with no internet.
        arrayOf("Rosa Lindqvist", "RL", "The power’s out on our street too. Are you okay?", "We’re fine. Come by for tea, we have a gas stove.", "Today", "Nearby"),
        // A message request: a new number you've never answered, so it has no reply (MessageRequests).
        arrayOf(REQUEST, "#", "Hi! Is this the bike repair co-op? I found this number on the library flyer.", "", "Today", "SMS"),
    )

    /** The sample's message request, from a number that isn't a sample contact. Its Automated filter has Trail Alerts. */
    const val REQUEST = "(202) 555-0177"

    /** Members of each group chat, channel, and room, in the order they first speak. */
    @JvmField val GROUPS: Map<String, List<String>> = mapOf(
        "Weekend Plans" to listOf("Maya Chen", "Jordan Ellis", "Avery Brooks"),
        "Photo Walk" to listOf("Jordan Ellis", "Cameron Reed", "Sam Rivera"),
        "Trail Crew" to listOf("Trail Alerts", "Maya Chen", "Cameron Reed"),
        "Garden Committee" to listOf("Avery Brooks", "Leah Morgan", "Amir Nasser"),
        "Design Studio" to listOf("Nora Patel", "Maya Chen", "Amir Nasser"),
        "#radio-club" to listOf("mosaic", "kestrel", "orbital"),
        "#trail-maps" to listOf("switchback", "kestrel", "cairn"),
        "Book Swap" to listOf("Leah Morgan", "Cameron Reed", "Avery Brooks"),
    )

    /** The second and third group messages, after the first one in [CHATS]. */
    @JvmStatic fun groupReplies(name: String): Pair<String, String> = when (name) {
        "Weekend Plans" -> "I’ll grab a picnic blanket and some lemonade." to "I booked the tables by the willow tree."
        "Photo Walk" -> "The fog should lift around seven. Bring the small tripod." to "I’ll take the bus to the east entrance."
        "#radio-club" -> "Bring a spare coax cable if you have one." to "I printed the band plan for the table."
        "#trail-maps" -> "The GPS track is in the pinned message." to "I marked the washout near mile three."
        "Book Swap" -> "Is anyone reading the lighthouse one?" to "I left a bookmark with my notes in it."
        "Design Studio" -> "The poster proofs come back Friday." to "I added the palette to the shared board."
        else -> "I can meet at the trailhead with the work gloves." to "I updated the volunteer sign-in sheet."
    }

    /** A community server in the sample: an IRC network or a Matrix homeserver, with the channels or rooms joined there. */
    data class Server(val id: String, val name: String, val service: String, val account: String, val channels: List<String>)

    @JvmField val SERVERS: List<Server> = listOf(
        Server("sample:irc", "Harbor Net", "irc", "you@irc.harbor.example", listOf("#radio-club", "#trail-maps")),
        Server("sample:matrix", "Lakeside Commons", "matrix", "@you:lakeside.example", listOf("Design Studio", "Book Swap")),
    )

    /** Pretend channels a sample server's Browse shows. Nothing is joined. */
    @JvmStatic fun directory(id: String): List<String> = when (id) {
        "sample:irc" -> listOf("#bike-repair · 18 people", "#night-sky · 42 people", "#seed-library · 9 people")
        else -> listOf("Board Games · 31 people", "Community Garden · 57 people", "Running Club · 24 people")
    }

    /** The server a sample chat is a channel or room on, or "" for any other chat. */
    @JvmStatic fun serverOf(name: String): String = SERVERS.firstOrNull { name in it.channels }?.id.orEmpty()

    @JvmStatic fun server(id: String): Server? = SERVERS.firstOrNull { it.id == id }

    /**
     * The connection mark beside each IRC, Matrix, and XMPP sample chat's tag: Lakeside Commons' rooms show a server
     * that isn't connected and Amir Nasser's XMPP account one that's connecting, so every look is on screen. "" for texts and email.
     */
    @JvmStatic fun connection(name: String): String {
        val service = CHATS.firstOrNull { it[0] == name }?.get(5) ?: return ""
        return when {
            serverOf(name) == "sample:matrix" -> "disconnected"
            name == SECOND_ACCOUNT_CHAT -> "connecting"
            service == "IRC" || service == "Matrix" || service == "XMPP" -> "connected"
            else -> ""
        }
    }

    /** Sample contacts that start starred in the sample Phone tab, so the Favorites filter has chats to show. */
    @JvmField val FAVORITES: List<String> = listOf("demo:Maya Chen", "demo:Jordan Ellis", "demo:Nora Patel")

    /** Chats that start unread until they're opened or answered. */
    @JvmField val UNREAD: Set<String> = setOf("Maya Chen", "Jordan Ellis", "Weekend Plans", "Photo Walk", "Trail Crew",
        "#radio-club", "Book Swap", REQUEST)

    @JvmField val ARCHIVED: Set<String> = setOf("Avery Brooks")
    @JvmField val BLOCKED: Set<String> = setOf("Sam Rivera")

    @JvmStatic fun folderOf(name: String) = when (name) { in ARCHIVED -> "Archived"; in BLOCKED -> "Blocked"; else -> "Inbox" }

    /**
     * The mutes the sample starts with, as key → how long from now (0 is forever): one chat for a few hours, so it
     * shows "Muted until …", and one whole server.
     */
    @JvmField val MUTES: Map<String, Long> = mapOf(
        "demo:Trail Crew" to 3 * Mutes.HOUR,
        Mutes.serverKey("sample:matrix") to 8 * Mutes.HOUR,
    )

    /** The chat that shows which of your accounts it's on, for people with more than one account on a service. */
    const val SECOND_ACCOUNT_CHAT = "Amir Nasser"
    const val SECOND_ACCOUNT = "you@work.example"

    /** The sample link and its preview card, made up and kept on the phone: no page is fetched. */
    const val LINK = "https://trails.example/ridge-loop"
    const val LINK_SITE = "trails.example"
    const val LINK_TITLE = "Ridge Loop · 6.2 miles"
    const val LINK_ABOUT = "A shady loop past two lookouts and the old fire tower. Best before ten in the morning."

    /** The sample poll, with a fixed id so its votes add up. The same text pollText() sends. */
    const val POLL = "picnic01"

    @JvmStatic fun pollText(): String = "📊 Poll: What should we grill?\n1. Veggie skewers\n2. Sweet corn\n3. Burgers\n" +
        "Reply with a number to vote · poll $POLL"

    fun names(): List<String> = CHATS.map { it[0] }
}
