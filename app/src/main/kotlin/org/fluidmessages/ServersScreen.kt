package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A channel or room found on a server, or one of a Space's rooms. */
private data class FoundRoom(val target: String, val name: String, val detail: String, val joined: Boolean, val joinAs: String)

/**
 * Servers, like Discord's: an IRC network, a Matrix account (and its Spaces), or an XMPP account. These full pages
 * join a channel or room by name, browse the server's own directory (IRC LIST, the Matrix public room directory, XMPP
 * service discovery), and add another server. Everything goes through FluidMessages Web, which holds the connections.
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

    @JvmStatic
    fun browse(root: LinearLayout, prefs: SharedPreferences, account: JSONObject, opened: BiConsumer<String, String>) =
        mountView(root, prefs) { Browse(prefs, account, opened) }

    /** Add server: pick IRC, Matrix, or XMPP; [pick] opens the account form for it. */
    @JvmStatic
    fun add(root: LinearLayout, prefs: SharedPreferences, pick: Consumer<MessageProtocol>) = mountView(root, prefs) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            item {
                Text("Connect another IRC network, Matrix homeserver, or XMPP account. Each one shows up above your chats.",
                    Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                PreferencesCard("Add server") {
                    listOf(MessageProtocol.IRC to "IRC network", MessageProtocol.MATRIX to "Matrix homeserver", MessageProtocol.XMPP to "XMPP account")
                        .forEachIndexed { index, (protocol, title) ->
                            if (index > 0) HorizontalDivider(Modifier.padding(start = 15.dp))
                            PreferenceText(title, AccountKeys.help(protocol.name.lowercase()),
                                Modifier.fillMaxWidth().clickable { pick.accept(protocol) }.padding(15.dp))
                        }
                }
            }
        }
    }
}

private fun noun(service: String) = when (service) { "irc" -> "channel"; "matrix" -> "room"; else -> "group chat" }

@Composable
private fun Join(prefs: SharedPreferences, account: JSONObject, browse: Runnable, opened: BiConsumer<String, String>) {
    val id = account.optString("id")
    val service = AccountKeys.service(id)
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Join a ${noun(service)} on ${AccountKeys.serverName(id)}", style = MaterialTheme.typography.headlineSmall) }
        item {
            OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                label = { Text(if (service == "irc") "Channel" else "Address") },
                placeholder = { Text(when (service) { "irc" -> "#channel"; "matrix" -> "#room:" + AccountKeys.serverName(id); else -> "room@conference.example.org" }) })
        }
        item {
            Text(when (service) {
                "irc" -> "Channel names start with #. You can leave it out."
                "matrix" -> "A room address like #room:example.org, or a room ID. Rooms on other homeservers work too."
                else -> "A group chat address like room@conference.example.org."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = browse::run) { Text("Browse instead") }
                Spacer(Modifier.weight(1f))
                AppButton(enabled = !busy && target.isNotBlank(), onClick = {
                    busy = true
                    status = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { GatewayClient.join(prefs, id, target.trim(), "") } }
                            .onSuccess { route -> busy = false; route?.let { opened.accept(it.optString("address"), it.optString("protocol")) } }
                            .onFailure { status = it.message ?: "Couldn’t join."; busy = false }
                    }
                }) { Text(if (busy) "Joining…" else "Join") }
            }
        }
    }
}

@Composable
private fun Browse(prefs: SharedPreferences, account: JSONObject, opened: BiConsumer<String, String>) {
    val id = account.optString("id")
    val service = AccountKeys.service(id)
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var mucService by remember { mutableStateOf("") }
    var services by remember { mutableStateOf<List<String>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<FoundRoom>>(emptyList()) }
    var spaces by remember { mutableStateOf<List<Pair<String, List<FoundRoom>>>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run() = scope.launch {
        busy = true
        status = "Looking…"
        runCatching { withContext(Dispatchers.IO) { GatewayClient.browse(prefs, id, search.trim(), mucService) } }
            .onSuccess { found ->
                val list = found.optJSONArray("services")
                services = if (list == null) emptyList() else (0 until list.length()).map { list.optString(it) }
                val items = found.optJSONArray("rooms")
                rooms = if (items == null) emptyList() else (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { room ->
                    FoundRoom(room.optString("target"), room.optString("name").ifEmpty { room.optString("target") },
                        listOf(room.optString("alias"), if (room.optInt("members") > 0) "${room.optInt("members")} people" else "", room.optString("topic"))
                            .filter { it.isNotEmpty() }.joinToString(" · "), room.optBoolean("joined"), room.optString("alias").ifEmpty { room.optString("target") })
                }
                status = when {
                    list != null && services.isEmpty() -> "Your server didn’t list a group chat service. Join one by address instead."
                    list != null -> null
                    rooms.isEmpty() -> "Nothing matches."
                    else -> "${rooms.size} found"
                }
            }
            .onFailure { status = it.message ?: "Couldn’t reach the server." }
        busy = false
    }
    fun join(room: FoundRoom) = scope.launch {
        status = "Joining…"
        runCatching { withContext(Dispatchers.IO) { GatewayClient.join(prefs, id, room.joinAs, room.name) } }
            .onSuccess { route -> route?.let { opened.accept(it.optString("address"), it.optString("protocol")) } }
            .onFailure { status = it.message ?: "Couldn’t join." }
    }
    LaunchedEffect(mucService) { if (service != "irc") run() }
    LaunchedEffect(id) {
        if (service == "matrix") runCatching { withContext(Dispatchers.IO) { GatewayClient.spaces(prefs, id) } }.onSuccess { list ->
            val joined = GatewayClient.allRoutes(prefs).filter { it.optString("account") == id }.map { it.optString("target") }.toSet()
            spaces = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }.map { space ->
                val children = space.optJSONArray("rooms")
                space.optString("name") to (0 until (children?.length() ?: 0)).mapNotNull { children?.optJSONObject(it) }.map { room ->
                    FoundRoom(room.optString("target"), room.optString("name"), room.optString("topic"), room.optString("target") in joined, room.optString("target"))
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Browse " + if (service == "xmpp") mucService.ifEmpty { "group chat services" } else AccountKeys.serverName(id),
                style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Text(when {
                service == "irc" -> "The network’s public channels, busiest first. Big networks have thousands, so only the first 100 matches show."
                service == "matrix" -> "Public rooms in this homeserver’s directory."
                mucService.isEmpty() -> "Group chat services on your XMPP server. Pick one to see its group chats."
                else -> "Public group chats on this service."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, Modifier.weight(1f), singleLine = true, label = { Text("Search") })
                AppButton(enabled = !busy, onClick = { run() }) { Text("Search") }
            }
        }
        if (mucService.isNotEmpty()) item { TextButton(onClick = { mucService = ""; rooms = emptyList() }) { Text("‹ All group chat services") } }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        items(services, key = { "service:$it" }) { jid ->
            PreferenceText(jid, "Group chat service", Modifier.fillMaxWidth().clickable { mucService = jid }.padding(vertical = 8.dp))
        }
        items(rooms, key = { "room:" + it.target }) { room -> RoomRow(room) { join(room) } }
        if (service == "matrix") {
            item { Text("Your Spaces", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp)) }
            if (spaces.isEmpty()) item { Text("No Spaces on this account. Spaces group rooms the way a Discord server does.",
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            for ((name, children) in spaces) {
                item { Text(name, style = MaterialTheme.typography.labelLarge) }
                items(children, key = { "space:$name:" + it.target }) { room -> RoomRow(room) { join(room) } }
            }
        }
    }
}

@Composable
private fun RoomRow(room: FoundRoom, join: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(room.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (room.detail.isNotEmpty()) Text(room.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(enabled = !room.joined, onClick = join) { Text(if (room.joined) "Joined" else "Join") }
    }
}
