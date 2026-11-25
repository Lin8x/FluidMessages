package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A channel or room found on a server, or one of a Space's rooms. */
internal data class FoundRoom(val target: String, val name: String, val detail: String, val joined: Boolean, val joinAs: String,
                              val alias: String = "", val topic: String = "")

/** Filtering found rooms as you type: every word in the name, address, alias, or topic. */
internal object RoomFilter {
    fun filter(rooms: List<FoundRoom>, query: String): List<FoundRoom> =
        rooms.filter { ServerDirectory.matches(query, it.name, it.target, it.alias, it.topic) }
}

/**
 * Servers: an IRC network, a Matrix account (and its Spaces), or an XMPP account. These full pages add a server (pick a
 * service, then a well-known public server or Other server…), join a channel or room by name, browse the server's own
 * directory (IRC LIST, the Matrix public room directory, XMPP service discovery), and confirm leaving one.
 * Servers on FluidMessages Web go through it, since it holds those connections. An IRC network this phone connects
 * itself joins and browses on the phone's own connection (DirectIrc), with nothing else needed.
 */
internal object ServersScreen {
    private fun mountView(root: LinearLayout, prefs: SharedPreferences, content: @Composable () -> Unit) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { AppTheme(Appearance.read(prefs)) { content() } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** Join a channel or room by name. [opened] gets the joined chat's address. */
    @JvmStatic
    fun join(root: LinearLayout, prefs: SharedPreferences, account: JSONObject, browse: Runnable, opened: BiConsumer<String, String>) =
        mountView(root, prefs) { Join(prefs, account, browse, opened) }

    /** The server's directory. [opened] gets a chat's address and protocol, after joining it or for Open on a joined one. */
    @JvmStatic
    fun browse(root: LinearLayout, prefs: SharedPreferences, account: JSONObject, opened: BiConsumer<String, String>) =
        mountView(root, prefs) { if (onPhone(account)) PhoneIrcBrowse(prefs, account, opened) else Browse(prefs, account, opened) }

    /** The account for a server this phone connects itself, for [join] and [browse]. Only IRC can do both on the phone. */
    @JvmStatic
    fun directAccount(id: String): JSONObject = JSONObject().put("id", id).put("protocol", AccountKeys.service(id)).put("direct", true)

    /** Why Join and Browse aren't offered for a Matrix or XMPP account this phone connects itself, in one sentence. */
    @JvmStatic
    fun phoneOnly(id: String): String {
        val label = MessageProtocol.parse(AccountKeys.service(id)).label
        return "This phone can’t look up $label ${noun(AccountKeys.service(id))}s on its own connection yet, so finding and joining them needs FluidMessages Web."
    }

    /** Add server, step 1: pick IRC, Matrix, or XMPP; [pick] opens that service's list of public servers. */
    @JvmStatic
    fun add(root: LinearLayout, prefs: SharedPreferences, pick: Consumer<MessageProtocol>) = mountView(root, prefs) { Services(pick) }

    /**
     * Add server, step 2: well-known public servers for [protocol], searchable, and Other server…. [pick] gets the
     * server's name (empty for Other server…) and the sign-in fields it fills in.
     */
    @JvmStatic
    fun directory(root: LinearLayout, prefs: SharedPreferences, protocol: MessageProtocol, pick: BiConsumer<String, Map<String, String>>) =
        mountView(root, prefs) { Directory(protocol.name.lowercase(), pick) }

    /** Leaving a channel or room, confirmed on its own page. */
    @JvmStatic
    fun leave(root: LinearLayout, prefs: SharedPreferences, noun: String, name: String, server: String, leave: Runnable, cancel: Runnable) =
        mountView(root, prefs) { Leave(noun, name, server, leave, cancel) }

    fun serviceTitle(service: String) = when (service) { "irc" -> "IRC network"; "matrix" -> "Matrix homeserver"; else -> "XMPP server" }
}

/** An account this phone connects itself (ServersScreen.directAccount), not one on FluidMessages Web. */
private fun onPhone(account: JSONObject) = account.optBoolean("direct") && AccountKeys.service(account.optString("id")) == "irc"

private fun noun(service: String) = when (service) { "irc" -> "channel"; "matrix" -> "room"; else -> "group chat" }
private fun plural(service: String) = when (service) { "irc" -> "networks"; "matrix" -> "homeservers"; else -> "servers" }

@Composable
private fun ServiceBadge(service: String, text: String = MessageProtocol.parse(service).label.take(1)) {
    // The theme's own protocol color, shaded so white text stays readable (the bubble contrast rule).
    val appearance = LocalAppearance.current
    val protocol = MessageProtocol.parse(service)
    val tint = appearance?.let { protocol.tint(it) } ?: Color(MessageStyle.filledControl(LocalControlTint.current))
    val fill = accessibleBubbleFill(tint)
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(fill), contentAlignment = Alignment.Center) {
        Text(text, color = bubbleTextColor(fill), fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun Services(pick: Consumer<MessageProtocol>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Choose a service", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text("Connect another IRC network, Matrix homeserver, or XMPP server. Each one shows up above your chats.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(listOf(MessageProtocol.IRC, MessageProtocol.MATRIX, MessageProtocol.XMPP), key = { it.name }) { protocol ->
            val service = protocol.name.lowercase()
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).clickable(onClickLabel = "Choose ${protocol.label}") { pick.accept(protocol) }
                    .padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ServiceBadge(service)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(protocol.label, style = MaterialTheme.typography.titleMedium)
                        Text(AccountKeys.help(service), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 26.sp)
                }
            }
        }
    }
}

@Composable
private fun Directory(service: String, pick: BiConsumer<String, Map<String, String>>) {
    var query by remember { mutableStateOf("") }
    val found = remember(query) { ServerDirectory.search(service, query) }
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Public " + plural(service), style = MaterialTheme.typography.headlineSmall) }
        item {
            Text("Well-known public ${plural(service)}. Picking one fills in the sign-in form. Nothing connects until you save.",
                color = scheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(query, { query = it.take(60) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), singleLine = true,
                label = { Text("Search " + plural(service)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        }
        if (found.isEmpty()) item {
            Text("No public ${plural(service)} match “${query.trim()}”. Choose Other server… below.", color = scheme.onSurfaceVariant)
        }
        items(found, key = { it.name }) { entry ->
            Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().clickable(enabled = entry.tls, onClickLabel = "Use ${entry.name}") { pick.accept(entry.name, entry.preset) }
                    .padding(horizontal = 15.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, color = if (entry.tls) scheme.onSurface else scheme.onSurfaceVariant)
                    Text(if (entry.tls) entry.about else entry.about + ". It doesn’t offer TLS, so FluidMessages can’t connect to it.",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Text(entry.shownAddress, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        item {
            // A themed row like the servers above it, with a dashed-looking outline: Other server… for any host.
            Surface(color = scheme.surfaceContainerLow, contentColor = scheme.onSurface, shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, scheme.outline), modifier = Modifier.padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Enter another server") { pick.accept("", emptyMap()) }
                    .padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(scheme.primary.copy(alpha = .16f)),
                        contentAlignment = Alignment.Center) {
                        Text("+", color = scheme.primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Other server…", color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                        Text(when (service) {
                            "irc" -> "Enter any network’s host and port"
                            "matrix" -> "Enter any homeserver’s address"
                            else -> "Enter your address on any XMPP server"
                        }, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Leave(noun: String, name: String, server: String, leave: Runnable, cancel: Runnable) {
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Remove $name?", style = MaterialTheme.typography.headlineSmall)
        Text(server, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("FluidMessages leaves this $noun and takes it out of your list. You stop getting its messages.")
        Text("Earlier messages stay in Archived. You can join again any time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel::run, enabled = !busy) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Button(enabled = !busy, onClick = { busy = true; leave.run() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                Text(if (busy) "Removing…" else "Remove $noun")
            }
        }
    }
}

@Composable
private fun Join(prefs: SharedPreferences, account: JSONObject, browse: Runnable, opened: BiConsumer<String, String>) {
    val id = account.optString("id")
    val service = AccountKeys.service(id)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val phone = onPhone(account)
    val link = if (phone) phoneIrcLink(prefs) else null
    var target by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var needsKey by remember(target) { mutableStateOf(false) }
    var key by remember(target) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Join a ${noun(service)} on ${AccountKeys.serverName(id)}", style = MaterialTheme.typography.headlineSmall) }
        if (link != null && !link.ready) item { PhoneIrcWaiting(link, AccountKeys.serverName(id)) }
        item {
            OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                label = { Text(if (service == "irc") "Channel" else "Address") },
                placeholder = { Text(when (service) { "irc" -> "#channel"; "matrix" -> "#room:" + AccountKeys.serverName(id); else -> "room@conference.example.org" }) })
        }
        item {
            Text(when (service) {
                "irc" -> "Type the channel’s name, like linux or #linux. The # is optional."
                "matrix" -> "A room address like #room:example.org, or a room ID. Rooms on other homeservers work too."
                else -> "A group chat address like room@conference.example.org."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        // A channel locked with a password (475): its password, right here, then Join again.
        if (needsKey) item {
            OutlinedTextField(key, { key = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                label = { Text("Channel password") }, visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Ask someone in the channel for it. It isn’t saved, so you may need it again after reconnecting.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = browse::run) { Text(if (service == "irc") "Browse channels instead" else "Browse instead") }
                Spacer(Modifier.weight(1f))
                AppButton(enabled = !busy && target.isNotBlank() && link?.ready != false && (!needsKey || key.isNotBlank()), onClick = {
                    busy = true
                    status = null
                    val password = if (needsKey) key.trim() else ""
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            if (phone) DirectProtocols.ircJoin(context, target, password) else GatewayClient.join(prefs, id, target.trim(), "", password)
                        } }
                            .onSuccess { route -> busy = false; route?.let { opened.accept(it.optString("address"), it.optString("protocol")) } }
                            .onFailure {
                                status = it.message ?: "Couldn’t join."
                                if (it is IrcJoinRefused && it.code == "475") needsKey = true
                                busy = false
                            }
                    }
                }) { Text(if (busy) "Joining…" else if (needsKey) "Join with password" else "Join") }
            }
        }
    }
}

/** The joined chat's (address, protocol) for [target] on account [id], if it's joined. */
private fun joinedChat(prefs: SharedPreferences, id: String, vararg targets: String): Pair<String, String>? {
    val wanted = targets.filter { it.isNotEmpty() }.map { it.lowercase() }
    return GatewayClient.allRoutes(prefs).firstOrNull { route ->
        route.optString("account") == id && route.optBoolean("group") && route.optString("target").lowercase() in wanted
    }?.let { it.optString("address") to it.optString("protocol") }
}

@Composable
private fun Browse(prefs: SharedPreferences, account: JSONObject, opened: BiConsumer<String, String>) {
    val id = account.optString("id")
    val service = AccountKeys.service(id)
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var mucService by remember { mutableStateOf("") }
    var services by remember { mutableStateOf<List<String>?>(null) }
    var rooms by remember { mutableStateOf<List<FoundRoom>>(emptyList()) }
    var asked by remember { mutableStateOf<String?>(null) }
    var spaces by remember { mutableStateOf<List<Pair<String, List<FoundRoom>>>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Bumped by each request, so an older, slower answer never replaces a newer one.
    var request by remember { mutableIntStateOf(0) }
    fun run(term: String) = scope.launch {
        val mine = ++request
        busy = true
        status = "Searching…"
        runCatching { withContext(Dispatchers.IO) { GatewayClient.browse(prefs, id, term, mucService) } }
            .onSuccess { found ->
                if (mine != request) return@onSuccess
                asked = term
                val list = found.optJSONArray("services")
                services = list?.let { (0 until it.length()).map { index -> it.optString(index) } }
                val items = found.optJSONArray("rooms")
                rooms = if (items == null) emptyList() else (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { room ->
                    val members = room.optInt("members")
                    FoundRoom(room.optString("target"), room.optString("name").ifEmpty { room.optString("target") },
                        listOf(room.optString("alias"), if (members > 0) "$members ${if (members == 1) "person" else "people"}" else "", room.optString("topic"))
                            .filter { it.isNotEmpty() }.joinToString(" · "), room.optBoolean("joined"), room.optString("alias").ifEmpty { room.optString("target") },
                        room.optString("alias"), room.optString("topic"))
                }
                status = null
            }
            .onFailure { if (mine == request) status = it.message ?: "Couldn’t reach the server." }
        if (mine == request) busy = false
    }
    // The list filters as you type; the server is asked again shortly after you stop, where it can search
    // (IRC LIST with a mask, the Matrix directory's filter). XMPP's list is fetched once and filtered here.
    LaunchedEffect(mucService) { asked = null; services = null; rooms = emptyList(); run("") }
    LaunchedEffect(search, asked) {
        val term = search.trim()
        if (service == "xmpp" || asked == null || term == asked) return@LaunchedEffect
        delay(450)
        run(term)
    }
    fun join(room: FoundRoom) = scope.launch {
        status = "Joining…"
        runCatching { withContext(Dispatchers.IO) { GatewayClient.join(prefs, id, room.joinAs, room.name) } }
            .onSuccess { route -> route?.let { opened.accept(it.optString("address"), it.optString("protocol")) } }
            .onFailure { status = it.message ?: "Couldn’t join." }
    }
    fun open(room: FoundRoom) {
        val chat = joinedChat(prefs, id, room.target, room.alias)
        if (chat != null) opened.accept(chat.first, chat.second) else join(room)
    }
    LaunchedEffect(id) {
        if (service == "matrix") runCatching { withContext(Dispatchers.IO) { GatewayClient.spaces(prefs, id) } }.onSuccess { list ->
            val joined = GatewayClient.allRoutes(prefs).filter { it.optString("account") == id }.map { it.optString("target") }.toSet()
            spaces = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }.map { space ->
                val children = space.optJSONArray("rooms")
                space.optString("name") to (0 until (children?.length() ?: 0)).mapNotNull { children?.optJSONObject(it) }.map { room ->
                    FoundRoom(room.optString("target"), room.optString("name"), room.optString("topic"), room.optString("target") in joined,
                        room.optString("target"), topic = room.optString("topic"))
                }
            }
        }
    }
    val shownServices = services?.filter { ServerDirectory.matches(search, it) }
    val shownRooms = remember(rooms, search) { RoomFilter.filter(rooms, search) }
    val joinedTargets = remember(rooms) {
        GatewayClient.allRoutes(prefs).filter { it.optString("account") == id && it.optBoolean("group") }.map { it.optString("target").lowercase() }.toSet()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Browse " + if (service == "xmpp") mucService.ifEmpty { "group chat services" } else AccountKeys.serverName(id),
                style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Text(when {
                service == "irc" -> "The network’s public channels, busiest first. Big networks have thousands, so the network searches them for you and the first 100 matches show."
                service == "matrix" -> "Public rooms in this homeserver’s directory."
                mucService.isEmpty() -> "Group chat services on your XMPP server. Pick one to see its group chats."
                else -> "Public group chats on this service."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(search, { search = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(when { service == "irc" -> "Search channels"; service == "matrix" -> "Search rooms"
                    mucService.isEmpty() -> "Search services"; else -> "Search group chats" }) },
                trailingIcon = if (busy) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (service != "xmpp") run(search.trim()) }))
        }
        if (mucService.isNotEmpty()) item { TextButton(onClick = { mucService = ""; search = "" }) { Text("‹ All group chat services") } }
        val line = status ?: when {
            asked == null -> null
            shownServices != null && services.orEmpty().isEmpty() -> "Your server didn’t list a group chat service. Join one by address instead."
            shownServices != null -> if (shownServices.isEmpty()) "Nothing matches “${search.trim()}”." else null
            shownRooms.isEmpty() -> if (search.isBlank()) "Nothing listed." else "Nothing matches “${search.trim()}”."
            else -> "${shownRooms.size} found"
        }
        line?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(shownServices.orEmpty(), key = { "service:$it" }) { jid ->
            PreferenceText(jid, "Group chat service", Modifier.fillMaxWidth().clickable { mucService = jid; search = "" }.padding(vertical = 8.dp))
        }
        items(shownRooms, key = { "room:" + it.target }) { room ->
            RoomRow(room, room.target.lowercase() in joinedTargets || room.alias.lowercase() in joinedTargets, { join(room) }) { open(room) }
        }
        if (service == "matrix") {
            item { Text("Your Spaces", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp)) }
            if (spaces.isEmpty()) item { Text("No Spaces on this account. A Space collects related rooms under one name.",
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            for ((name, children) in spaces) {
                item { Text(name, style = MaterialTheme.typography.labelLarge) }
                items(children, key = { "space:$name:" + it.target }) { room -> RoomRow(room, room.joined, { join(room) }) { open(room) } }
            }
        }
    }
}

@Composable
private fun RoomRow(room: FoundRoom, joinedHere: Boolean, join: () -> Unit, open: () -> Unit) {
    val joined = room.joined || joinedHere
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(room.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (room.detail.isNotEmpty()) Text(room.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (joined) {
            Text("Joined", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = open) { Text("Open") }
        } else AppButton(onClick = join) { Text("Join") }
    }
}

/** The phone's own IRC connection, checked about once a second while a Join or Browse page is open. */
private data class PhoneIrcLink(val ready: Boolean, val state: String, val enabled: Boolean)

@Composable
private fun phoneIrcLink(prefs: SharedPreferences): PhoneIrcLink {
    fun read() = PhoneIrcLink(DirectProtocols.ircReady(), DirectProtocols.status(prefs, MessageProtocol.IRC).optString("state"),
        MessageProtocol.IRC.enabled(prefs))
    var link by remember { mutableStateOf(read()) }
    LaunchedEffect(Unit) { while (true) { delay(700); link = read() } }
    return link
}

/** Shown while the phone isn't connected to the network: what's going on, in plain words, and a way to connect. */
@Composable
private fun PhoneIrcWaiting(link: PhoneIrcLink, network: String) {
    val context = LocalContext.current.applicationContext
    var asked by remember { mutableStateOf(false) }
    // A press shows "Connecting…" until the connection reports back.
    LaunchedEffect(asked) { if (asked) { delay(8000); asked = false } }
    val (line, button) = IrcChannels.waiting(if (asked) "connecting" else link.state, network, link.enabled)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(line, Modifier.weight(1f))
            if (button.isNotEmpty()) AppButton(onClick = { asked = true; DirectProtocols.reconnect(context, MessageProtocol.IRC) }) { Text(button) }
            else if (link.enabled) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * Browse for an IRC network this phone connects itself: the network's public channels (LIST) with people counts and
 * topics, searched as you type, and a few long-lived channels as a starting point until the live list arrives.
 */
@Composable
private fun PhoneIrcBrowse(prefs: SharedPreferences, account: JSONObject, opened: BiConsumer<String, String>) {
    val id = account.optString("id")
    val network = AccountKeys.serverName(id)
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val link = phoneIrcLink(prefs)
    var search by remember { mutableStateOf("") }
    // Null until the network's own list has arrived once.
    var rows by remember { mutableStateOf<List<IrcChannelRow>?>(null) }
    var asked by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf("") }
    var request by remember { mutableIntStateOf(0) }
    fun run(term: String) = scope.launch {
        val mine = ++request
        busy = true
        status = null
        runCatching { withContext(Dispatchers.IO) { DirectProtocols.ircList(context, term, IrcChannels.LIMIT) } }
            .onSuccess { if (mine == request) { rows = it; asked = term } }
            .onFailure { if (mine == request) status = it.message ?: "Couldn’t get the channel list." }
        if (mine == request) busy = false
    }
    // Asks once connected, then again shortly after you stop typing. The connection itself spaces requests out.
    LaunchedEffect(link.ready, search) {
        val term = search.trim()
        if (!link.ready || term == asked) return@LaunchedEffect
        if (asked != null) delay(900)
        run(term)
    }
    fun open(name: String): Boolean {
        val chat = DirectProtocols.routeForTarget(context, MessageProtocol.IRC, name) ?: return false
        opened.accept(chat.optString("address"), "irc")
        return true
    }
    fun join(name: String) = scope.launch {
        joining = name
        status = null
        runCatching { withContext(Dispatchers.IO) { DirectProtocols.ircJoin(context, name) } }
            .onSuccess { opened.accept(it.optString("address"), "irc") }
            .onFailure { status = it.message ?: "Couldn’t join $name." }
        joining = ""
    }
    val joined = remember(rows, joining) { DirectProtocols.targets(context, MessageProtocol.IRC, true).map { AccountKeys.targetKey("irc", it) }.toSet() }
    fun found(name: String, detail: String, topic: String = "") = FoundRoom(name, name, detail, AccountKeys.targetKey("irc", name) in joined, name, topic = topic)
    val live = rows
    val popular = remember(id) { IrcChannels.popular(AccountKeys.address(id).substringAfterLast('@')) }
    // The list filters as you type, before the network answers the new search.
    val shown = remember(live, search, joined) { RoomFilter.filter(live.orEmpty().map { found(it.name, IrcChannels.detail(it), it.topic) }, search) }
    val typed = search.trim().takeIf { IrcChannels.looksLikeChannel(it) && shown.none { room -> room.name.equals(it, ignoreCase = true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Browse $network", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text("Public channels on $network, busiest first. Type a word to search. Up to ${IrcChannels.LIMIT} show at a time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!link.ready) item { PhoneIrcWaiting(link, network) }
        item {
            OutlinedTextField(search, { search = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Search channels") },
                trailingIcon = if (busy) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (link.ready) run(search.trim()) }))
        }
        status?.let { problem ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(problem, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    if (link.ready && !busy && joining.isEmpty()) TextButton(onClick = { run(search.trim()) }) { Text("Try again") }
                }
            }
        }
        // A pasted or typed "#channel" can be joined straight away, listed or not.
        typed?.let { name -> item("typed") { RoomRow(found(name, "Join this channel by name"), false, { join(name) }) { if (!open(name)) join(name) } } }
        if (live == null && search.isBlank()) {
            if (popular.isNotEmpty()) item { Text("Popular here", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
            items(popular, key = { "popular:$it" }) { name -> RoomRow(found(name, "A long-running channel on $network"), false, { join(name) }) { if (!open(name)) join(name) } }
            if (link.ready && busy) item { Text("Getting the full list from $network…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            val line = when {
                live == null -> if (busy) "Searching $network…" else null
                shown.isEmpty() && busy -> "Searching $network…"
                shown.isEmpty() -> if (search.isBlank()) "$network didn’t list any public channels." else "No channel matches “${search.trim()}”."
                else -> "${shown.size} ${if (shown.size == 1) "channel" else "channels"}"
            }
            line?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(shown, key = { "room:" + AccountKeys.targetKey("irc", it.target) }) { room ->
                RoomRow(room, false, { join(room.name) }) { if (!open(room.name)) join(room.name) }
            }
        }
    }
}
