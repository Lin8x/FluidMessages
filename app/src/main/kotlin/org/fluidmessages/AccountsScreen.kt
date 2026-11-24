package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
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
        Field("homeserver", "Homeserver", "https://matrix.org"),
        Field("user_id", "Matrix ID", "@you:matrix.org"),
        Field("password", "Password", "Used once to sign in; only a session token is saved", secret = true, optional = true),
        Field("access_token", "Or an access token", "For advanced setups", secret = true, optional = true),
    ),
    MessageProtocol.XMPP to listOf(
        Field("jid", "XMPP address", "you@example.org"),
        Field("password", "Password", secret = true),
        Field("host", "Server host", "Leave blank to look it up", optional = true),
    ),
    MessageProtocol.IRC to listOf(
        Field("server", "Server", "irc.libera.chat"),
        Field("port", "TLS port", "6697", number = true, optional = true),
        Field("nick", "Nickname"),
        Field("sasl_user", "SASL account", "Leave blank to use your nickname", optional = true),
        Field("sasl_password", "SASL password", secret = true, optional = true),
        Field("nickserv_password", "NickServ password", "Identifies on connect when SASL isn’t used", secret = true, optional = true),
        Field("channels", "Channels", "#example, #another", optional = true),
    ),
)

private val notes = mapOf(
    MessageProtocol.EMAIL to "Only conversations you link to a contact appear in FluidMessages. Other email stays in your mail app. Each message you send ends with “Sent with FluidMessages”.",
    MessageProtocol.MATRIX to "Rooms appear when you link them to a contact or group. Through FluidMessages Web, encrypted rooms work: it becomes one of your Matrix devices and decrypts them. From this phone, only unencrypted rooms can be read and sent to.",
    MessageProtocol.XMPP to "One-to-one chats and group chats you link appear here. OMEMO encryption isn’t supported, so XMPP shows as encrypted in transit only.",
    MessageProtocol.IRC to "FluidMessages connects with TLS only. Channels listed here appear in your inbox, and anyone who messages you, including bots like NickServ, gets a chat of their own. If FluidMessages Web already connects to this network, use its connection: it stays online like a bouncer, and the phone won’t open a second one with the same nick.",
)

/** What the account form is showing: an account to edit, or a new one to add (optionally pre-filled). */
private data class Editing(val protocol: MessageProtocol, val existing: JSONObject?, val add: Boolean,
                           val preset: Map<String, String> = emptyMap())

internal object AccountsScreen {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, openCompanion: Runnable) = mount(root, prefs, openCompanion, null, emptyMap(), null)

    /** Opens straight on the form to add another [protocol] account ("Add server"), pre-filled with [preset]. */
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, openCompanion: Runnable, add: MessageProtocol?,
              preset: Map<String, String>, done: Runnable?) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { AppTheme(Appearance.read(prefs)) { Accounts(prefs, openCompanion, add?.let { Editing(it, null, true, preset) }, done) } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

@Composable
private fun Accounts(prefs: SharedPreferences, openCompanion: Runnable, start: Editing? = null, done: Runnable? = null) {
    val scope = rememberCoroutineScope()
    val paired = CloudBackup.paired(prefs.getString("server", "").orEmpty(), Privacy.token(prefs))
    var accounts by remember { mutableStateOf<JSONArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(start) }
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
        AccountForm(prefs, current, { if (done != null && current == start) done.run() else editing = null }) { updated ->
            accounts = updated
            if (done != null && current == start) done.run() else editing = null
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
                TextButton(enabled = accounts != null, onClick = { editing = Editing(protocol, null, shown > 0) },
                    modifier = Modifier.padding(horizontal = 6.dp)) {
                    Text(if (shown > 0) "Add another" else "Add")
                }
            }
        }
        item {
            Text("Link a contact to their addresses from the contact’s details. Turning a protocol off in Message protocols keeps its account and history. This phone can connect one account per service itself; add more through FluidMessages Web.",
                Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
    else if (onPhone) JSONObject().apply {
        direct?.let { account -> for (key in account.keys()) {
            val secret = forms.getValue(protocol).any { it.key == key && it.secret }
            put(key, if (secret) JSONObject().put("set", true) else account.get(key))
        } }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(if (adding) "Add a ${protocol.label} account" else existing?.optString("name")?.takeIf { it.isNotEmpty() } ?: protocol.label,
            style = MaterialTheme.typography.headlineSmall) }
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
        for (field in forms.getValue(protocol)) item {
            val isSet = stored.optJSONObject(field.key)?.optBoolean("set") == true
            OutlinedTextField(values[field.key].orEmpty(), { values[field.key] = it }, Modifier.fillMaxWidth(),
                enabled = !readOnly && !busy, singleLine = true, label = { Text(field.label + if (field.optional) " (optional)" else "") },
                placeholder = { Text(if (field.secret && isSet) "Saved · leave blank to keep" else if (field.key == "jid" && xmppDomain != null) "you" else field.hint) },
                supportingText = if (field.help.isNotEmpty()) ({ Text(field.help) }) else null,
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
                                        "channels" -> fields.put(key, JSONArray(value.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                                        "jid" -> fields.put(key, ServerDirectory.xmppAddress(value, xmppDomain))
                                        else -> fields.put(key, value.trim())
                                    }
                                }
                                if (protocol == MessageProtocol.MATRIX && values["password"].orEmpty().isNotBlank()) {
                                    fields.put("access_token", GatewayClient.matrixLogin(values["homeserver"].orEmpty().trim(),
                                        values["user_id"].orEmpty().trim(), values["password"].orEmpty(),
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
    for (key in required) if (fields.optString(key).isBlank()) throw IllegalArgumentException("Enter " + key.replace("_", " "))
    if (protocol == MessageProtocol.MATRIX && !fields.getString("homeserver").startsWith("https://"))
        throw IllegalArgumentException("Enter an https:// homeserver")
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
