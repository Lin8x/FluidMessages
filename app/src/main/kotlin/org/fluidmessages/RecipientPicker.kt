package org.fluidmessages

import android.content.SharedPreferences
import android.net.Uri
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Function

data class Recipient(val key: String, val name: String, val address: String, val recent: Boolean, val photo: Uri?)

/**
 * What a typed address can become. Only protocols that are turned on and have an account (on the companion
 * or this phone) are offered; a turned-on protocol without an account offers to add one instead.
 */
internal data class AddressOption(val protocol: MessageProtocol, val target: String) {
    /** Passed back to MainActivity, which links it to a conversation and opens the chat. */
    val encoded: String get() = protocol.name.lowercase() + ":" + target
}

/**
 * Each service a typed address could be on, from NewAddress.detect (the same rules as FluidMessages Web). An IRC
 * nickname typed as riley@irc.libera.chat keeps its network, so the chat goes through your account there; a bare
 * word is offered as an IRC nickname only alongside contacts it matches.
 */
internal fun addressOptions(query: String, available: Set<MessageProtocol>): List<AddressOption> =
    NewAddress.detect(query).mapNotNull { typed ->
        val protocol = NewAddress.protocol(typed.service) ?: return@mapNotNull null
        AddressOption(protocol, if (typed.network.isEmpty()) typed.target else typed.target + "@" + typed.network)
    }.filter { it.protocol in available }

internal class RecipientPicker(
    root: LinearLayout, prefs: SharedPreferences, allowContacts: Runnable, keyForNumber: Function<String, String>,
    proceed: Consumer<List<String>>,
    /** Protocols you can message now, and turned-on protocols that still need an account. */
    available: Set<MessageProtocol> = emptySet(), needsAccount: Set<MessageProtocol> = emptySet(),
    addAccount: Runnable? = null,
    /**
     * The Channels tab: every server's joined channels and rooms. Null hides the tabs (decoy mode, forwarding).
     * Picking a channel calls [openChannel] with its address and protocol; [serverAction] gets ("join" or "browse",
     * server id) or ("add", "").
     */
    private val servers: List<ServerChannels>? = null,
    private val openChannel: BiConsumer<String, String>? = null,
    private val serverAction: BiConsumer<String, String>? = null,
) {
    var recipients by mutableStateOf(emptyList<Recipient>())
    var contactsAllowed by mutableStateOf(false)
    var loading by mutableStateOf(true)

    init {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(Appearance.read(prefs)) {
                var query by remember { mutableStateOf("") }
                var selected by remember { mutableStateOf(emptyList<Recipient>()) }
                val search = remember(query) { MessageSearch(query) }
                val results = remember(search, recipients) {
                    recipients.filter { search.matches(it.name, it.address) }
                }
                val typedNumbers = remember(query, recipients) {
                    try {
                        SmsStore.recipients(query).map { address ->
                            val key = keyForNumber.apply(address)
                            recipients.firstOrNull { it.key == key } ?: Recipient(key, address, address, false, null)
                        }.distinctBy { it.key }
                    } catch (_: IllegalArgumentException) { emptyList() }
                }
                val typed = typedNumbers.singleOrNull()
                val addresses = remember(query) { addressOptions(query, available) }
                val missing = remember(query) { addressOptions(query, needsAccount) }
                fun toggle(recipient: Recipient) {
                    selected = if (selected.any { it.key == recipient.key }) selected.filterNot { it.key == recipient.key }
                        else selected + recipient
                    query = ""
                }
                fun addNumbers() {
                    selected = (selected + typedNumbers).distinctBy { it.key }
                    if (typedNumbers.isNotEmpty()) query = ""
                }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (servers != null) RecipientTabs(tab) { tab = it }
                    if (servers != null && tab == 1) ChannelList(servers, Modifier.weight(1f),
                        { address, protocol -> openChannel?.accept(address, protocol) },
                        { action, id -> serverAction?.accept(action, id) })
                    else {
                    OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(if (available.isEmpty()) "Name or phone number" else "Name, phone number, or address") },
                        supportingText = if (available.isEmpty()) null else ({
                            Text("You can also enter " + available.joinToString(", ") { protocol -> when (protocol) {
                                MessageProtocol.MATRIX -> "a Matrix ID"
                                MessageProtocol.XMPP -> "an XMPP address"
                                MessageProtocol.EMAIL -> "an email address"
                                else -> "an IRC #channel or irc:nick"
                            } } + ".")
                        }),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { addNumbers() }))
                    if (selected.isNotEmpty()) {
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(selected, key = { it.key }) { row ->
                                InputChip(selected = true, onClick = { toggle(row) }, label = { Text(row.name) },
                                    trailingIcon = { Text("×", modifier = Modifier.padding(end = 3.dp)) })
                            }
                        }
                    }
                    if (!contactsAllowed) TextButton(onClick = allowContacts::run) { Text("Allow contacts to search by name") }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (typedNumbers.size > 1) item {
                            TextButton(onClick = ::addNumbers) { Text("Add ${typedNumbers.size} numbers") }
                        }
                        if (typed != null && results.none { it.key == typed.key }) item {
                            TextButton(onClick = { toggle(typed) }) { Text("Use ${typed.address}") }
                        }
                        for (option in addresses) item {
                            TextButton(onClick = { toggle(Recipient(option.encoded, option.target, option.encoded, false, null)) }) {
                                Text("Message ${option.target} with ${option.protocol.label}")
                            }
                        }
                        for (option in missing) item {
                            TextButton(enabled = addAccount != null, onClick = { addAccount?.run() }) {
                                Text("Add a ${option.protocol.label} account to message ${option.target}")
                            }
                        }
                        if (results.isEmpty() && typedNumbers.isEmpty() && addresses.isEmpty() && missing.isEmpty()) item {
                            Text(if (loading) "Loading recipients…" else if (search.empty()) (if (available.isEmpty()) "Enter a phone number to start"
                                else "Enter a phone number or an address to start")
                                else "No matching recipients", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(results, key = { it.key }) { recipient ->
                            val checked = selected.any { it.key == recipient.key }
                            Surface(color = if (checked) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.fillMaxWidth().clickable { toggle(recipient) }.padding(horizontal = 11.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    val initials = recipient.name.split(' ').mapNotNull { it.firstOrNull()?.takeIf(Char::isLetter) }.take(2)
                                        .joinToString("").uppercase()
                                    Avatar(initials, recipient.name, 37, photoUri = recipient.photo)
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(recipient.name, style = MaterialTheme.typography.bodyLarge)
                                        val detail = if (recipient.name == recipient.address) "Recent" else recipient.address +
                                            if (recipient.recent) " · Recent" else ""
                                        if (detail != "Recent" || recipient.recent) Text(detail,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (checked) Text("✓", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 7.dp))
                                }
                            }
                        }
                    }
                    val recipientsToSend = selected.ifEmpty { typedNumbers }
                    AppButton(onClick = { proceed.accept(recipientsToSend.map { it.address }) },
                        enabled = recipientsToSend.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (recipientsToSend.size > 1) "Continue with ${recipientsToSend.size} people" else "Continue")
                    }
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

/** A joined channel, room, or group chat, as the Channels tab lists it. */
data class ServerChannel(val address: String, val title: String, val protocol: String)

/** One server (IRC network, Matrix or XMPP account on FluidMessages Web) and its joined channels. */
data class ServerChannels(val id: String, val name: String, val service: String, val channels: List<ServerChannel>)

/** A link from FluidMessages Web: a conversation, the protocol, the account it goes through, and whether it's a group. */
internal data class ChannelLink(val address: String, val title: String, val protocol: String, val account: String, val group: Boolean)

internal object ChannelDirectory {
    /**
     * Each server's joined channels and rooms, in the servers' own order, each sorted by name. Direct messages and links
     * through other accounts are left out; a server with nothing joined yet is still listed, so it can offer Join.
     */
    @JvmStatic
    fun group(servers: List<Pair<String, String>>, links: List<ChannelLink>): List<ServerChannels> = servers.map { (id, service) ->
        val joined = links.filter { it.group && it.account == id }.distinctBy { it.address }
            .map { ServerChannel(it.address, it.title.ifBlank { it.address }, it.protocol) }
            .sortedBy { it.title.trimStart('#', '&', '!').lowercase() }
        ServerChannels(id, AccountKeys.serverName(id), service, joined)
    }

    /**
     * The servers whose channels match every word of [query] (by name or the server's name), each with only its
     * matching channels. A blank query keeps everything, including servers with nothing joined yet.
     */
    @JvmStatic
    fun filter(servers: List<ServerChannels>, query: String): List<ServerChannels> {
        if (query.isBlank()) return servers
        return servers.map { server ->
            server.copy(channels = server.channels.filter { ServerDirectory.matches(query, it.title, server.name) })
        }.filter { it.channels.isNotEmpty() }
    }
}

/** "People" and "Channels" on the new-message screen. */
@Composable
private fun RecipientTabs(selected: Int, pick: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = Color(MessageStyle.filledControl(LocalControlTint.current))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHigh).padding(3.dp)) {
        listOf("People", "Channels").forEachIndexed { index, label ->
            val active = index == selected
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                .background(if (active) tint else Color.Transparent)
                .clickable(role = Role.Tab, onClickLabel = "Show $label") { pick(index) },
                contentAlignment = Alignment.Center) {
                Text(label, color = if (active) Color(MessageStyle.filledControlInk(LocalControlTint.current)) else scheme.onSurface,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ChannelList(servers: List<ServerChannels>, modifier: Modifier, open: (String, String) -> Unit, action: (String, String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(servers, query) { ChannelDirectory.filter(servers, query) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (servers.isNotEmpty()) item(key = "search") {
            OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Search channels and rooms") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        }
        if (servers.isNotEmpty() && shown.isEmpty()) item(key = "nothing") {
            Text("No joined channels or rooms match “${query.trim()}”. Browse a server to find more.",
                Modifier.padding(12.dp), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (servers.isEmpty()) item {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No servers yet", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text("Servers are IRC networks, Matrix homeservers, and XMPP accounts on FluidMessages Web. Their channels " +
                    "and rooms show up in your chat list, and here, so you can write in one.",
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        for (server in shown) {
            item(key = "server:" + server.id) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name, Modifier.weight(1f), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(MessageProtocol.parse(server.service).label, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (server.channels.isEmpty()) item(key = "empty:" + server.id) {
                Text("Nothing joined yet", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            items(server.channels, key = { server.id + "\u001f" + it.address }) { channel ->
                Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${channel.title}") { open(channel.address, channel.protocol) }
                        .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(channel.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item(key = "join:" + server.id) {
                // The Join page joins by name and links to the server's directory.
                TextButton(onClick = { action("join", server.id) }) {
                    Text("Join or browse…")
                }
            }
        }
        item(key = "add") {
            Column {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TextButton(onClick = { action("add", "") }) { Text("Add server") }
            }
        }
    }
}
