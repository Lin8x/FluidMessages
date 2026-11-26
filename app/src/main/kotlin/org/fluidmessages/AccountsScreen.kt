package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class Field(val key: String, val label: String, val hint: String = "", val secret: Boolean = false,
                         val number: Boolean = false, val optional: Boolean = false, val help: String = "",
                         val default: String = "")

/** Account forms, matching the companion's account fields. Matrix asks for a password once, then keeps a token. */
private val forms = mapOf(
    MessageProtocol.EMAIL to listOf(
        Field("address", "Email address", "you@example.org"),
        Field("imap_host", "Incoming mail (IMAP) server", "imap.example.org"),
        Field(EmailPorts.IMAP_KEY, "IMAP port", EmailPorts.IMAP.toString(), number = true, help = EmailPorts.IMAP_HINT,
            default = EmailPorts.IMAP.toString()),
        Field("smtp_host", "Outgoing mail (SMTP) server", "smtp.example.org"),
        Field(EmailPorts.SMTP_KEY, "SMTP port", EmailPorts.SMTP.toString(), number = true, help = EmailPorts.SMTP_HINT,
            default = EmailPorts.SMTP.toString()),
        Field("imap_user", "Username", "Usually your email address", optional = true),
        Field("imap_password", "Password or app password", secret = true),
        Field("poll_minutes", "Check for mail every … minutes", "1, 5, 15, 30, or 60", number = true, optional = true),
    ),
    MessageProtocol.MATRIX to listOf(
        Field("user_id", "Matrix ID", "@you:matrix.org"),
        Field("homeserver", "Homeserver", "https://matrix.org"),
        Field("password", "Password", "Used once to sign in; only a session token is saved", secret = true, optional = true),
        Field("access_token", "Or an access token", "For advanced setups", secret = true, optional = true),
    ),
    MessageProtocol.XMPP to listOf(
        Field("jid", "XMPP address", "you@example.org"),
        Field("password", "Password", secret = true),
        Field("host", "Server host", "Leave blank to look it up", optional = true),
    ),
    MessageProtocol.IRC to listOf(
        Field("server", "Network", "irc.libera.chat"),
        Field("port", "TLS port", "6697", number = true, optional = true),
        // The stored keys stay sasl_user, sasl_password, and nickserv_password; only the words changed (IrcWords).
        Field("nick", "Nickname", help = IrcWords.NICK),
        Field("sasl_user", "Account name", "Leave blank to use your nickname", optional = true, help = IrcWords.ACCOUNT),
        Field("sasl_password", "Password", secret = true, optional = true, help = IrcWords.PASSWORD),
        Field("nickserv_password", "Older networks: NickServ password", "Only if Password above doesn’t sign you in", secret = true,
            optional = true, help = IrcWords.NICKSERV),
        Field("channels", "Channels to join", "#example, #another", optional = true, help = "You can also browse and join channels later."),
    ),
)

private val notes = mapOf(
    MessageProtocol.EMAIL to "Only conversations you link to a contact appear in FluidMessages. Other email stays in your mail app. Each message you send ends with “Sent with FluidMessages”.",
    MessageProtocol.MATRIX to "Rooms appear when you link them to a contact or group. Through FluidMessages Web, encrypted rooms work: it becomes one of your Matrix devices and decrypts them. From this phone, only unencrypted rooms can be read and sent to.",
    MessageProtocol.XMPP to "One-to-one chats and group chats you link appear here. Chats are locked with OMEMO when the other person’s app uses it: only you and the people you’re talking to can read them. Otherwise they’re encrypted on the way to the server only, and the chat says so. From this phone, group chats aren’t locked yet; FluidMessages Web locks members-only groups.",
    MessageProtocol.IRC to "FluidMessages connects with TLS only. Channels listed here appear in your inbox, and anyone who messages you, including bots like NickServ, gets a chat of their own. If FluidMessages Web already connects to this network, use its connection: it stays online like a bouncer, and the phone won’t open a second one with the same nick.",
)

/** What the account form is showing: an account to edit, or a new one to add (optionally pre-filled). */
private data class Editing(val protocol: MessageProtocol, val existing: JSONObject?, val add: Boolean,
                           val preset: Map<String, String> = emptyMap())

internal object AccountsScreen {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, openCompanion: Runnable) = mount(root, prefs, openCompanion, null, emptyMap(), null)

    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, openCompanion: Runnable, add: MessageProtocol?,
              preset: Map<String, String>, done: Runnable?) = mount(root, prefs, openCompanion, add, preset, done, done)

    /**
     * Opens straight on the form to add another [protocol] account ("Add server"), pre-filled with [preset], for
     * checking. Saving calls [done]; the form's Back calls [back] (the list of servers).
     */
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, openCompanion: Runnable, add: MessageProtocol?,
              preset: Map<String, String>, done: Runnable?, back: Runnable?) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { AppTheme(Appearance.read(prefs)) { Accounts(prefs, openCompanion, add?.let { Editing(it, null, true, preset) }, done, back) } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

@Composable
private fun Accounts(prefs: SharedPreferences, openCompanion: Runnable, start: Editing? = null, done: Runnable? = null,
                     back: Runnable? = done) {
    val scope = rememberCoroutineScope()
    val paired = CloudBackup.paired(prefs.getString("server", "").orEmpty(), Privacy.token(prefs))
    var accounts by remember { mutableStateOf<JSONArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(start) }
    // The account whose guide page shows before its form: a first account on a service from this list. Add server
    // shows its guide before the list of servers (AddServerFlow), so its form opens straight away.
    var guided by remember { mutableStateOf<Editing?>(null) }
    fun load() = scope.launch {
        error = null
        runCatching { withContext(Dispatchers.IO) { GatewayClient.accounts(prefs) } }
            .onSuccess { accounts = it ?: JSONArray() }.onFailure { error = it.message ?: "Couldn’t reach FluidMessages Web." }
    }
    LaunchedEffect(paired) { if (paired) load() else accounts = JSONArray() }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun accountsOf(protocol: MessageProtocol): List<JSONObject> = accounts?.let { list ->
        (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("protocol") == protocol.name.lowercase() }
    }.orEmpty()
    val current = editing
    if (current != null) {
        // Opened from Add server, closing goes back there; from this list, back to the list.
        val close: () -> Unit = { if (back != null && start != null) back.run() else editing = null }
        val finish: () -> Unit = { if (done != null && start != null) done.run() else editing = null }
        if (guided == current) {
            fun form(preset: Map<String, String>) {
                val next = current.copy(preset = current.preset + preset)
                guided = null
                editing = next
            }
            AccountGuide(current.protocol, ::form, { form(emptyMap()) }, close)
            return
        }
        // Saving no longer shows the Communities page: Add server shows it first, while no server is added yet.
        AccountForm(prefs, current, close) { updated ->
            accounts = updated
            finish()
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        if (!paired) item {
            PreferencesCard("FluidMessages Web") {
                PreferenceText("FluidMessages Web isn’t set up", "FluidMessages Web keeps internet connections open so your phone doesn’t have to. You can also connect an account from this phone, which uses more battery.")
                TextButton(onClick = openCompanion::run, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Set up FluidMessages Web") }
            }
        }
        error?.let { item { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) } }
        // One card per service: every account on it, and Add another.
        for (protocol in listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)) item(protocol.name) {
            val entries = accountsOf(protocol)
            val direct = DirectProtocols.account(context, protocol)?.takeIf { DirectProtocols.on(prefs, protocol) }
            PreferencesCard(protocol.label) {
                var shown = 0
                if (direct != null) {
                    PreferenceText(summary(protocol, JSONObject().put("fields", direct)).ifEmpty { "Connected" },
                        "On this phone" + if (DirectProtocols.stayConnected(prefs, protocol)) " · stays connected" else "",
                        Modifier.fillMaxWidth().clickable { editing = Editing(protocol, null, false) }.padding(15.dp))
                    shown++
                }
                for (entry in entries) {
                    if (shown > 0) HorizontalDivider(Modifier.padding(start = 15.dp))
                    val detail = when {
                        entry.has("error") -> entry.optString("error")
                        entry.optString("source") == "compose" -> "Set in Docker Compose · read-only"
                        entries.size > 1 && entry.optBoolean("primary") -> "On FluidMessages Web · first account, chats from before stay here"
                        else -> "On FluidMessages Web"
                    }
                    PreferenceText(entry.optString("name").ifEmpty { summary(protocol, entry) }.ifEmpty { protocol.label }, detail,
                        Modifier.fillMaxWidth().clickable { editing = Editing(protocol, entry, false) }.padding(15.dp))
                    shown++
                }
                if (shown == 0) Text(if (accounts == null) "Checking…" else "Not set up", Modifier.padding(horizontal = 15.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shown > 0) HorizontalDivider(Modifier.padding(start = 15.dp))
                TextButton(enabled = accounts != null, onClick = {
                    val next = Editing(protocol, null, shown > 0)
                    // A first account on a service starts with its guide; another one goes straight to the form.
                    if (shown == 0) guided = next
                    editing = next
                },
                    modifier = Modifier.padding(horizontal = 6.dp)) {
                    Text(if (shown > 0) "Add another" else "Add")
                }
                // "What is Matrix?" and the rest: the service's guide page, the same one a first account starts with.
                TextButton(onClick = { val next = Editing(protocol, null, shown > 0); guided = next; editing = next },
                    modifier = Modifier.padding(horizontal = 6.dp)) {
                    Text(ServiceGuides.of(protocol).title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (protocol == MessageProtocol.IRC) {
                    // Call and meeting-link buttons in IRC chats: off by default, with the reason right under it (IrcCalls).
                    HorizontalDivider(Modifier.padding(start = 15.dp))
                    var ircCalls by remember { mutableStateOf(IrcCalls.allowed(prefs)) }
                    SettingsSwitch(IrcCalls.TITLE, "Phone calls, video calls, and meeting links. Off by default.", ircCalls) {
                        ircCalls = it
                        prefs.edit().putBoolean(IrcCalls.KEY, it).apply()
                    }
                    Text(IrcCalls.WARNING, Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp),
                        color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        }
        item {
            Text("Link a contact to their addresses from the contact’s details. Turning a protocol off in Message protocols keeps its account and history. This phone can connect one account per service itself; add more through FluidMessages Web.",
                Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The server picked in Add server, by name, for the form's "You picked" line; "" when none was. */
private fun pickedServer(editing: Editing): String =
    editing.preset["server"]?.let { ServerDirectory.ircName(it) ?: it }
        ?: editing.preset["homeserver"]?.let { address -> ServerDirectory.matrix.firstOrNull { it.address == address }?.name ?: address }
        ?: editing.preset[ServerDirectory.XMPP_DOMAIN].orEmpty()

/** A nickname to offer in the IRC form, from an account this phone already has (its Matrix, XMPP, or email name). */
private fun suggestedNick(context: android.content.Context): String {
    for ((protocol, key) in listOf(MessageProtocol.MATRIX to "user_id", MessageProtocol.XMPP to "jid", MessageProtocol.EMAIL to "address")) {
        val nick = AccountSetup.nick(DirectProtocols.account(context, protocol)?.optString(key).orEmpty())
        if (nick.length >= 2) return nick
    }
    return ""
}

/**
 * Where a homeserver says it really lives (https://example.org/.well-known/matrix/client), for an address that was
 * guessed from a Matrix ID. Any problem keeps the guess.
 */
private fun discoverHomeserver(guess: String): String = runCatching {
    NetworkGate.requireInternet()
    val connection = TorRoute.open(java.net.URL("$guess/.well-known/matrix/client")) as javax.net.ssl.HttpsURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        if (connection.responseCode != 200) return@runCatching guess
        val reply = connection.inputStream.use { String(it.readNBytes(64 * 1024)) }
        val base = JSONObject(reply).optJSONObject("m.homeserver")?.optString("base_url").orEmpty().trimEnd('/')
        if (Regex("https://[A-Za-z0-9.-]+(:\\d{1,5})?(/[^\\s]*)?").matches(base)) base else guess
    } finally { connection.disconnect() }
}.getOrDefault(guess)

private fun summary(protocol: MessageProtocol, entry: JSONObject): String {
    val fields = entry.optJSONObject("fields") ?: return ""
    return when (protocol) {
        MessageProtocol.EMAIL -> fields.optString("address")
        MessageProtocol.MATRIX -> fields.optString("user_id")
        MessageProtocol.XMPP -> fields.optString("jid")
        else -> fields.optString("nick") + " on " + fields.optString("server")
    }
}

@Composable
private fun AccountForm(prefs: SharedPreferences, editing: Editing, close: () -> Unit, saved: (JSONArray) -> Unit) {
    val protocol = editing.protocol
    val existing = editing.existing
    val adding = editing.add
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val paired = CloudBackup.paired(prefs.getString("server", "").orEmpty(), Privacy.token(prefs))
    // Adding another account doesn't show (or replace) the one this phone connects.
    val direct = remember { if (adding) null else DirectProtocols.account(context, protocol) }
    // This phone connects one account per service; more go through FluidMessages Web.
    val phoneFree = !adding || !DirectProtocols.on(prefs, protocol)
    // Where the connection runs. "This phone" needs the battery notice accepted first.
    var onPhone by remember { mutableStateOf(if (existing != null) false else if (adding) !paired else DirectProtocols.on(prefs, protocol) || !paired) }
    var consent by remember { mutableStateOf(DirectProtocols.on(prefs, protocol)) }
    var asking by remember { mutableStateOf(false) }
    var always by remember { mutableStateOf(DirectProtocols.stayConnected(prefs, protocol)) }
    val readOnly = !onPhone && existing?.optString("source") == "compose"
    // Add server's pick fills in the form wherever it connects; an XMPP server leaves only the name to type.
    val xmppDomain = editing.preset[ServerDirectory.XMPP_DOMAIN]
    val stored = if (adding) JSONObject().apply { for ((key, value) in editing.preset) put(key, value) }
    else if (onPhone && direct != null) JSONObject().apply {
        for (key in direct.keys()) {
            val secret = forms.getValue(protocol).any { it.key == key && it.secret }
            put(key, if (secret) JSONObject().put("set", true) else direct.get(key))
        }
    } else existing?.optJSONObject("fields") ?: JSONObject().apply { for ((key, value) in editing.preset) put(key, value) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val values = remember(onPhone) {
        mutableStateMapOf<String, String>().apply {
            for (field in forms.getValue(protocol)) {
                val value = stored.opt(field.key)
                put(field.key, when (value) {
                    is JSONArray -> (0 until value.length()).joinToString(", ") { value.optString(it) }
                    is String, is Number -> value.toString()
                    // Ports start filled in, for new accounts and ones saved before they could be changed.
                    else -> field.default
                })
            }
        }
    }
    // What FluidMessages filled in by itself. Typing more only replaces these, never something you typed yourself.
    val auto = remember(onPhone) { mutableStateMapOf<String, String>() }
    fun fill(found: Map<String, String>) {
        for ((key, was) in auto.toMap()) if (key !in found) {
            if (values[key] == was) values[key] = forms.getValue(protocol).first { it.key == key }.default
            auto.remove(key)
        }
        for ((key, value) in found) {
            val now = values[key].orEmpty()
            if (now.isBlank() || now == auto[key] || now == forms.getValue(protocol).first { it.key == key }.default) {
                values[key] = value
                auto[key] = value
            }
        }
    }
    fun typed(key: String, text: String) {
        val value = AccountSetup.tidy(key, text)
        values[key] = value
        if (protocol == MessageProtocol.EMAIL && key == "address") fill(EmailProviders.detect(value)?.fields.orEmpty())
        if (protocol == MessageProtocol.MATRIX && key == "user_id") fill(AccountSetup.matrixHomeserver(value)?.let { mapOf("homeserver" to it) }.orEmpty())
    }
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    // Email: a known provider's settings stay tucked away under "Show details" while they're the ones in use.
    val mail = if (protocol == MessageProtocol.EMAIL) EmailProviders.detect(values["address"].orEmpty()) else null
    val mailApplied = mail != null && !mail.blocked && mail.fields.all { (key, value) -> values[key] == value }
    var details by remember { mutableStateOf(false) }
    val tucked = if (mailApplied && mail?.guess == false && !details) setOf("imap_host", EmailPorts.IMAP_KEY, "smtp_host", EmailPorts.SMTP_KEY, "imap_user") else emptySet()
    val nickIdea = remember { if (protocol == MessageProtocol.IRC && existing == null && direct == null) suggestedNick(context) else "" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(if (adding) "Add a ${protocol.label} account" else existing?.optString("name")?.takeIf { it.isNotEmpty() } ?: protocol.label,
            style = MaterialTheme.typography.headlineSmall) }
        // Add server: one line naming the server picked from the list, whose details fill the form below.
        val picked = if (adding && existing == null) pickedServer(editing) else ""
        if (picked.isNotEmpty()) item { Text("You picked $picked. Check the details, then save.", style = MaterialTheme.typography.titleMedium) }
        if (adding) item { Text(AccountKeys.help(protocol.name.lowercase()), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (adding && protocol == MessageProtocol.IRC) item {
            // TLS is always on: FluidMessages never sends IRC passwords or messages in plain text.
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use TLS", style = MaterialTheme.typography.bodyLarge)
                    Text("Always on. FluidMessages doesn’t connect to IRC without it.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Switch(checked = true, onCheckedChange = null, enabled = false)
            }
        }
        item { Text(notes.getValue(protocol), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Connect through", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onPhone, enabled = paired && !busy, onClick = { onPhone = false },
                        label = { Text("FluidMessages Web") })
                    FilterChip(selected = onPhone, enabled = !busy && phoneFree && existing == null, onClick = { if (consent) onPhone = true else asking = true },
                        label = { Text("This phone") })
                }
                Text(if (onPhone) "Your phone connects to ${protocol.label} itself" +
                    (if (paired) ", and FluidMessages Web pauses this account so messages arrive once." else ".") + when (protocol) {
                        MessageProtocol.IRC, MessageProtocol.XMPP -> " It needs an open connection to receive messages."
                        else -> " It checks on your Battery and sync schedule."
                    } else if (paired) "Recommended. FluidMessages Web keeps the connection open, and your phone syncs on your Battery and sync schedule."
                    else "Pair FluidMessages Web to connect through it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (!phoneFree) Text("This phone already connects a ${protocol.label} account. More accounts go through FluidMessages Web.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (onPhone && (protocol == MessageProtocol.IRC || protocol == MessageProtocol.XMPP)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !always, onClick = { always = false }, label = { Text("While FluidMessages is open") })
                        FilterChip(selected = always, onClick = { always = true }, label = { Text("Stay connected") })
                    }
                    if (always) Text("Keeps a connection open in the background with an ongoing notification. This uses the most battery.",
                        color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        }
        // The battery notice opens in place, under the choice, instead of in a pop-up.
        if (asking) item { BatteryConsent(protocol, { asking = false }) { consent = true; onPhone = true; asking = false } }
        if (readOnly) item { Text("This account is set in Docker Compose. Change it in your compose environment.", color = MaterialTheme.colorScheme.primary) }
        // IRC: well-known networks to pick from, unless Add server already picked one.
        if (protocol == MessageProtocol.IRC && existing == null && direct == null && "server" !in editing.preset) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pick a network, or type another one below", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (network in ServerDirectory.irc.filter { it.tls }.take(6)) {
                        FilterChip(selected = values["server"].equals(network.address, ignoreCase = true), enabled = !busy,
                            onClick = { values["server"] = network.address; values["port"] = network.port.toString() },
                            label = { Text(network.name) })
                    }
                }
            }
        }
        for (field in forms.getValue(protocol)) {
          // IRC: what a nickname and a registered account are, right above the account name and password.
          if (field.key == "sasl_user") item("irc-names") {
              Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                  Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                      Text(IrcWords.WHATS_THIS_TITLE, style = MaterialTheme.typography.titleSmall)
                      Text(IrcWords.WHATS_THIS, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                  }
              }
          }
          if (field.key !in tucked) item(field.key) {
            val isSet = stored.optJSONObject(field.key)?.optBoolean("set") == true
            val value = values[field.key].orEmpty()
            val help = when {
                field.key == "homeserver" && value.isNotBlank() && value == auto["homeserver"] -> "Filled in from your Matrix ID. Change it if your server uses another address."
                field.key == "jid" && xmppDomain == null && AccountSetup.xmppServer(value) != null -> "We’ll find the server for ${AccountSetup.xmppServer(value)} by itself."
                field.key == "imap_password" && mail?.appPassword == true -> "Use the app password from ${mail.provider}, not your normal password."
                else -> field.help
            }
            OutlinedTextField(value, { typed(field.key, it) }, Modifier.fillMaxWidth(),
                enabled = !readOnly && !busy, singleLine = true, label = { Text(field.label + if (field.optional) " (optional)" else "") },
                placeholder = { Text(if (field.secret && isSet) "Saved · leave blank to keep" else if (field.key == "jid" && xmppDomain != null) "you" else field.hint) },
                supportingText = if (help.isNotEmpty()) ({ Text(help) }) else null,
                suffix = if (field.key == "jid" && xmppDomain != null && !values[field.key].orEmpty().contains('@')) ({ Text("@$xmppDomain") }) else null,
                visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = when {
                    field.secret -> KeyboardType.Password
                    field.number -> KeyboardType.Number
                    field.key.contains("address") || field.key == "jid" -> KeyboardType.Email
                    field.key.contains("host") || field.key == "homeserver" || field.key == "server" -> KeyboardType.Uri
                    else -> KeyboardType.Text
                }))
          }
          // Right under the email address: what was set up, in one small card.
          if (field.key == "address" && mail != null && !readOnly) item("mail-setup") {
              Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                  Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                      Text(if (mailApplied || mail.blocked) mail.title else "These aren’t the usual settings for ${mail.provider}",
                          style = MaterialTheme.typography.titleMedium)
                      if (mail.note.isNotEmpty()) Text(mail.note)
                      if (mailApplied && details) Text(mail.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                          if (mail.helpUrl.isNotEmpty()) AppButton(onClick = { runCatching { links.openUri(mail.helpUrl) } }) { Text(mail.helpLabel) }
                          if (mailApplied && !mail.guess) TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details") }
                          if (!mailApplied && !mail.blocked) TextButton(enabled = !busy, onClick = {
                              for ((key, value) in mail.fields) { values[key] = value; auto[key] = value }
                          }) { Text("Use the usual settings") }
                      }
                  }
              }
          }
          // A nickname idea from an account this phone already has.
          if (field.key == "nick" && nickIdea.isNotEmpty() && values["nick"].isNullOrBlank()) item("nick-idea") {
              TextButton(enabled = !busy, onClick = { values["nick"] = nickIdea }) { Text("Use “$nickIdea” as my nickname") }
          }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = close) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if ((if (onPhone) direct != null else existing != null) && !readOnly) TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            if (onPhone) { DirectProtocols.removeAccount(context, protocol); if (paired) GatewayClient.accounts(prefs) else JSONArray() }
                            else GatewayClient.deleteAccount(prefs, protocol.name.lowercase(), existing?.optString("id").orEmpty())
                        } }.onSuccess { saved(it ?: JSONArray()) }.onFailure { error = it.message; busy = false }
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                if (!readOnly) AppButton(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val fields = JSONObject()
                                for ((key, value) in values) if (value.isNotBlank() && key != "password" || key == "password" && protocol != MessageProtocol.MATRIX && value.isNotBlank()) {
                                    when (key) {
                                        EmailPorts.IMAP_KEY, EmailPorts.SMTP_KEY -> fields.put(key, EmailPorts.parse(value)
                                            ?: throw IllegalArgumentException("Enter ${if (key == EmailPorts.IMAP_KEY) "an IMAP" else "an SMTP"} port from 1 to 65535."))
                                        "port", "poll_minutes" -> fields.put(key, value.trim().toInt())
                                        "channels" -> fields.put(key, JSONArray(AccountSetup.channels(value)))
                                        "jid" -> fields.put(key, AccountSetup.lowerDomain(ServerDirectory.xmppAddress(value, xmppDomain)))
                                        "password", "imap_password", "sasl_password", "nickserv_password", "access_token" -> fields.put(key, value.trim())
                                        else -> fields.put(key, AccountSetup.saved(key, value))
                                    }
                                }
                                // A homeserver guessed from the Matrix ID may live at another address; ask the server itself.
                                if (protocol == MessageProtocol.MATRIX && fields.optString("homeserver").isNotEmpty()
                                    && values["homeserver"] == auto["homeserver"] && fields.optString("homeserver") == "https://" + fields.optString("user_id").substringAfter(':')) {
                                    fields.put("homeserver", discoverHomeserver(fields.optString("homeserver")))
                                }
                                if (protocol == MessageProtocol.MATRIX && values["password"].orEmpty().isNotBlank()) {
                                    fields.put("access_token", GatewayClient.matrixLogin(fields.optString("homeserver"),
                                        fields.optString("user_id"), values["password"].orEmpty(),
                                        if (onPhone) "FluidMessages on this phone" else "FluidMessages Web"))
                                }
                                if (onPhone) {
                                    checkDirect(protocol, fields)
                                    DirectProtocols.saveAccount(context, protocol, fields, always)
                                    SyncJob.reschedule(context)
                                    if (paired) GatewayClient.accounts(prefs) else JSONArray()
                                } else {
                                    // Moving back to the companion turns off the phone connection but keeps its links.
                                    if (!adding && existing == null && DirectProtocols.on(prefs, protocol)) prefs.edit().putBoolean(DirectProtocols.key(protocol), false).apply()
                                    DirectProtocols.restart(context)
                                    GatewayClient.saveAccount(prefs, protocol.name.lowercase(), fields, existing?.optString("id").orEmpty(), adding)
                                }
                            }
                        }.onSuccess { saved(it ?: JSONArray()) }.onFailure { error = it.message ?: "Couldn’t save the account."; busy = false }
                    }
                }) { Text(if (busy) "Saving…" else "Save") }
            }
        }
    }
}


/** Required fields for a phone connection, checked before saving (the companion checks its own). */
private fun checkDirect(protocol: MessageProtocol, fields: JSONObject) {
    val required = when (protocol) {
        MessageProtocol.EMAIL -> listOf("address", "imap_host", "smtp_host", "imap_password")
        MessageProtocol.MATRIX -> listOf("homeserver", "user_id", "access_token")
        MessageProtocol.XMPP -> listOf("jid", "password")
        else -> listOf("server", "nick")
    }
    for (key in required) if (fields.optString(key).isBlank()) {
        if (key == "access_token") throw IllegalArgumentException("Type your Matrix password to sign in.")
        throw IllegalArgumentException("Fill in “" + (forms.getValue(protocol).firstOrNull { it.key == key }?.label ?: key.replace("_", " ")) + "” first.")
    }
    if (protocol == MessageProtocol.MATRIX && !fields.getString("homeserver").startsWith("https://"))
        throw IllegalArgumentException("Enter an https:// homeserver")
    // A nickname the network would refuse is caught here, in plain words, instead of failing to connect later.
    if (protocol == MessageProtocol.IRC) IrcRules.nickProblem(fields.optString("nick"))?.let { throw IllegalArgumentException(it) }
    if (protocol == MessageProtocol.IRC && fields.optInt("port", 6697) in listOf(6667, 194))
        throw IllegalArgumentException("Use a TLS port such as 6697. FluidMessages doesn’t connect to IRC without TLS.")
    if (protocol == MessageProtocol.EMAIL && !fields.has("smtp_password")) fields.put("smtp_password", fields.getString("imap_password"))
}

/** The battery notice. "Continue" stays off until the person turns on "I understand". */
@Composable
private fun BatteryConsent(protocol: MessageProtocol, dismiss: () -> Unit, accept: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    // Shown in the form itself, under "Connect through", rather than as a dialog.
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect ${protocol.label} from this phone?", style = MaterialTheme.typography.titleMedium)
            Text(when (protocol) {
                MessageProtocol.IRC, MessageProtocol.XMPP -> "${protocol.label} needs an open connection to receive messages. On this phone that keeps the radio awake while FluidMessages is open, or all the time if you choose Stay connected, which drains the battery noticeably faster."
                else -> "Your phone will check ${protocol.label} itself on your Battery and sync schedule, which uses more battery and data than letting FluidMessages Web do it."
            })
            Text("Your account details are encrypted on this phone and aren’t included in backups.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsSwitch("I understand this uses more battery", "", understood) { understood = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = dismiss) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                AppButton(enabled = understood, onClick = accept) { Text("Continue") }
            }
        }
    }
}
