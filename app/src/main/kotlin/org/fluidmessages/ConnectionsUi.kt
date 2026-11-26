package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connection controls for IRC networks and Matrix and XMPP accounts: the Connection section in a chat's and a server's
 * details (the status in words with its plug, Reconnect or Connect, Disconnect, and Reconnect automatically), and the
 * Connections page from the inbox's filter menu, which lists every server with a switch. Disconnect turns Reconnect
 * automatically off (PhoneChat.disconnect), so nothing connects again until Connect, also when the app opens next.
 * Chats stay in the list. Full pages and inline cards only, never pop-ups.
 */
object ConnectionsScreen {
    /** The Connections page. [sample]: the sample inbox's pretend servers, which change on this screen only. */
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, sample: Boolean, openChatServices: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            AppTheme(appearance) { ConnectionsPage(sample, openChatServices) }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** A server's details: a live Connection card above its other sections (SettingsPages). */
    @JvmStatic
    fun mountServer(root: LinearLayout, prefs: SharedPreferences, account: String, sections: List<SettingsSection>) =
        SettingsPages.mountWithHeader(root, prefs, sections) { ConnectionCard(account, emptyList()) }
}

/** One row of the Connections page. */
internal data class ConnectionRow(val id: String, val name: String, val service: String, val now: ConnectionNow)

/** The sample's pretend connections: Harbor Net connected and Lakeside Commons not connected, the same as their chats' marks. */
internal object SampleConnections {
    fun now(id: String, on: Boolean): ConnectionNow = if (on) SampleInbox.server(id)?.name.orEmpty().let { name ->
        val state = if (id == "sample:matrix") "disconnected" else "connected"
        ConnectionNow(state, "", IrcRules.connectionSummary(state, name, "you",
            if (state == "disconnected") "The connection to $name dropped." else "", false), false, false) }
    else ConnectionNow("off", "", IrcRules.connectionSummary("off", SampleInbox.server(id)?.name.orEmpty(), "", "", false), true, false)
}

@Composable
private fun ConnectionsPage(sample: Boolean, openChatServices: Runnable) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val sampleOn = remember { mutableStateMapOf<String, Boolean>().apply { SampleInbox.SERVERS.forEachIndexed { i, s -> put(s.id, true) } } }
    fun load(): List<ConnectionRow> = if (sample) SampleInbox.SERVERS.map {
        ConnectionRow(it.id, it.name, it.service, SampleConnections.now(it.id, sampleOn[it.id] == true))
    } else PhoneChat.connectionServers(context).map {
        ConnectionRow(it, AccountKeys.serverName(it), AccountKeys.service(it), ConnectionNow.read(context, it))
    }
    var rows by remember { mutableStateOf(emptyList<ConnectionRow>()) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            rows = withContext(Dispatchers.IO) { load() }
            delay(3000)
        }
    }
    fun act(id: String, run: () -> Unit, said: String) {
        note = said
        scope.launch(Dispatchers.IO) {
            val failed = runCatching(run).exceptionOrNull()
            delay(800)
            val next = load()
            withContext(Dispatchers.Main) {
                rows = next
                if (failed != null) note = failed.message ?: "That didn’t work. Try again."
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Turn a server off to stop connecting to it, also when FluidMessages opens next. Its chats stay in your list. "
                + "Turn it back on to connect." + if (sample) " These are sample servers: nothing connects." else "",
                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (note.isNotEmpty()) item {
            Text(note, Modifier.semantics { contentDescription = note }, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        if (rows.isEmpty()) item {
            Text("No servers yet. Add an IRC network, or a Matrix or XMPP account, under Communities or in Settings → Accounts.",
                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            if (rows.isNotEmpty()) PreferencesCard("Servers") {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.outlineVariant)
                    ServerConnectionRow(row, scheme.surfaceContainer.luminance() < .4f, sample,
                        connect = { on ->
                            if (sample) { sampleOn[row.id] = on; rows = load() }
                            else if (row.now.serviceOff) openChatServices.run()
                            else act(row.id, { if (on) PhoneChat.connect(context, row.id) else PhoneChat.disconnect(context, row.id) },
                                (if (on) "Connecting to " else "Disconnected from ") + row.name)
                        },
                        reconnect = {
                            if (sample) note = "Sample: " + row.name + " is connected"
                            else act(row.id, { PhoneChat.reconnect(context, row.id) }, "Reconnecting to " + row.name + "…")
                        })
                }
            }
        }
    }
}

@Composable
private fun ServerConnectionRow(row: ConnectionRow, dark: Boolean, sample: Boolean, connect: (Boolean) -> Unit, reconnect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val on = !row.now.off
    Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 6.dp, top = 6.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectionGlyph(row.now.state, dark, scheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(row.name, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(ServersScreen.serviceTitle(row.service) + " · " + row.now.summary, color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        // The switch: on is connected (or trying to), off is turned off by you. Reconnect tries again right now.
        if (!row.now.serviceOff) SettingsSwitch(if (on) "Connected" else "Turned off",
            if (on) "Turn off to disconnect and stay disconnected" else "Turn on to connect", on) { connect(it) }
        else TextButton(onClick = { connect(true) }) { Text("Turn on in Settings → Chat services") }
        if (on && !row.now.serviceOff) TextButton(onClick = reconnect, enabled = !sample || row.now.state != "connected") {
            Text("Reconnect", fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The Connection section: what's happening in words beside its plug ("Connected to Libera.Chat as kit", "Not
 * connected to Libera.Chat · <why>", "Connecting…", "Turned off"), Reconnect or Connect, Disconnect, Reconnect
 * automatically, and any [extras] (Rejoin automatically for a channel). It checks every few seconds.
 */
@Composable
internal fun ConnectionCard(account: String, extras: List<SettingsAction>, tor: TorChat.Ref? = null) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val now = rememberConnectionNow(account)
    // A sample chat's buttons change it on this screen only.
    val sample = account.startsWith(ConnectionNow.SAMPLE_CHAT)
    var sampleState by remember(account) { mutableStateOf<String?>(null) }
    val shown = sampleState?.let { ConnectionNow.sample(account.removePrefix(ConnectionNow.SAMPLE_CHAT), it) } ?: now.value
    var auto by remember(account) { mutableStateOf(sample || PhoneChat.autoReconnect(context, account)) }
    var busy by remember { mutableStateOf("") }
    fun act(said: String, run: () -> Unit) {
        if (sample) {
            sampleState = if (said.startsWith("Disconnected")) "off" else "connected"
            auto = sampleState == "connected"
            busy = "Sample: nothing really connects."
            return
        }
        busy = said
        scope.launch(Dispatchers.IO) {
            val failed = runCatching(run).exceptionOrNull()
            delay(800)
            val next = ConnectionNow.read(context, account)
            val nextAuto = PhoneChat.autoReconnect(context, account)
            withContext(Dispatchers.Main) {
                now.value = next
                auto = nextAuto
                busy = failed?.message.orEmpty()
            }
        }
    }
    PreferencesCard("Connection") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectionGlyph(shown.state, scheme.surfaceContainer.luminance() < .4f, scheme.onSurfaceVariant)
            Text(shown.summary.ifEmpty { "Not connected" }, Modifier.weight(1f), color = scheme.onSurface,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        if (busy.isNotEmpty()) Text(busy, Modifier.padding(horizontal = 15.dp), color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        // A server's own page while Connect through Tor is on: Tor's state, checked every few seconds. A chat's
        // Connection section has its Tor rows instead (below).
        if (!sample && tor == null && TorRoute.on() && PhoneChat.onPhone(context, account)) {
            var torWords by remember(account) { mutableStateOf("") }
            LaunchedEffect(account) {
                while (true) { torWords = withContext(Dispatchers.IO) { TorRoute.status(context).words }; delay(5000) }
            }
            if (torWords.isNotEmpty()) Text(torWords, Modifier.padding(horizontal = 15.dp), color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        if (!shown.serviceOff) Row(Modifier.padding(start = 15.dp, end = 15.dp, bottom = 12.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (shown.off) AppButton(onClick = { act("Connecting…") { PhoneChat.connect(context, account) } }) { Text("Connect") }
            else {
                if (shown.state == "disconnected") AppButton(onClick = { act("Reconnecting…") { PhoneChat.reconnect(context, account) } }) {
                    Text("Reconnect")
                } else OutlinedButton(onClick = { act("Reconnecting…") { PhoneChat.reconnect(context, account) } }) { Text("Reconnect") }
                OutlinedButton(onClick = { act("Disconnected. It stays off until you tap Connect.") { PhoneChat.disconnect(context, account) } }) {
                    Text("Disconnect")
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.outlineVariant)
        SettingsSwitch("Reconnect automatically", if (auto) "Connects when FluidMessages opens and after the connection drops"
            else "Off: connects only when you tap Connect or Reconnect. Disconnect turns this off. Chats stay in your list.", auto) { on ->
            auto = on
            if (!sample) act("") { PhoneChat.setAutoReconnect(context, account, on) }
        }
        for (extra in extras) {
            HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.outlineVariant)
            val toggle = extra.toggle
            if (toggle != null) SettingsSwitch(extra.label, extra.detail, toggle) { extra.action.run() }
            else TextButton(onClick = extra.action::run, Modifier.padding(horizontal = 6.dp)) { Text(extra.label) }
        }
        // Through Tor, Waiting for Tor, or Not through Tor, and this connection's Connect through Tor switch (TorChat).
        if (tor != null) {
            HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.outlineVariant)
            TorRows(tor)
        }
    }
}

/** The plug at a readable size, for Connection sections and the Connections page. */
@Composable
private fun ConnectionGlyph(state: String, dark: Boolean, muted: androidx.compose.ui.graphics.Color) {
    val label = IrcRules.connectionLabel(state.ifEmpty { "disconnected" })
    DrawGlyph(if (state == "connected" || state == "connecting") Glyph.Plug else Glyph.Unplugged,
        connectionInk(state.ifEmpty { "disconnected" }, dark, muted),
        Modifier.size(20.dp).semantics { contentDescription = label })
}
