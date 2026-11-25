package org.fluidmessages

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone's side of IRC, Matrix, and XMPP as everyday chat: running slash commands (directly or through
 * FluidMessages Web), the "/" hint list above the message box, the "Disconnected from <network> · Reconnect" bar in
 * a network's chats and on its Communities header, Keep joined, and the Invitations page. Pages are full pages,
 * never pop-ups.
 */
internal object PhoneChat {
    /** The chat's address on its service (a #channel, !room, or nick), for which command set applies. */
    @JvmStatic
    fun target(prefs: SharedPreferences, conversation: String, format: MessageProtocol): String? =
        (DirectProtocols.route(prefs, conversation, format) ?: GatewayClient.routeOf(prefs, conversation, format))?.optString("target")

    @JvmStatic
    fun family(prefs: SharedPreferences, conversation: String, format: MessageProtocol): String =
        ChatCommands.family(format, target(prefs, conversation, format))

    /**
     * Runs a command off the main thread. Returns {"notice": what happened, "open": an address to switch to}, or
     * {"help": the family} for /help, which the chat shows on its own page.
     */
    @JvmStatic
    fun run(context: Context, conversation: String, format: MessageProtocol, typed: String, line: TypedLine.Command): JSONObject {
        if (line.command.name == "help") return JSONObject().put("help", line.family)
        val prefs = DirectProtocols.prefs(context)
        if (DirectProtocols.on(prefs, format) && DirectProtocols.route(prefs, conversation, format) != null) {
            return DirectProtocols.command(context, conversation, format, line.command)
        }
        val result = GatewayClient.command(prefs, conversation, format, typed.trim())
        val open = result.optJSONObject("open")
        return JSONObject().put("notice", result.optString("notice")).apply { if (open != null) put("open", open.optString("address")) }
    }

    // Keep joined, shown as "Rejoin automatically": on, a channel joins as soon as the connection opens; off, only
    // when you open it. Together with the server's Reconnect automatically, nothing joins on its own when both are off.

    @JvmStatic
    fun keepJoined(prefs: SharedPreferences, link: JSONObject): Boolean {
        val address = link.optString("address")
        return if (prefs.contains("keepJoined.$address")) prefs.getBoolean("keepJoined.$address", true) else link.optBoolean("keep_joined", true)
    }

    @JvmStatic
    fun setKeepJoined(context: Context, link: JSONObject, keep: Boolean) {
        val prefs = DirectProtocols.prefs(context)
        val address = link.optString("address")
        prefs.edit().putBoolean("keepJoined.$address", keep).apply()
        if (!link.optBoolean("direct")) Thread({ runCatching { GatewayClient.keepJoined(prefs, address, keep) } }, "fluid-keep").start()
    }

    /** Opening a channel that isn't kept joined rejoins it, on the phone's connection or FluidMessages Web's. */
    @JvmStatic
    fun opened(context: Context, conversation: String, format: MessageProtocol) {
        if (format != MessageProtocol.IRC) return
        val prefs = DirectProtocols.prefs(context)
        val direct = DirectProtocols.route(prefs, conversation, format)
        if (direct != null) { DirectProtocols.opened(context, conversation, format); return }
        val link = GatewayClient.routeOf(prefs, conversation, format) ?: return
        if (link.optBoolean("group") && !keepJoined(prefs, link)) Thread({ GatewayClient.opened(prefs, conversation) }, "fluid-open").start()
    }

    @JvmStatic
    fun hideJoins(prefs: SharedPreferences) = prefs.getBoolean("hideJoins", false)

    /** Leaves out joins, leaves, and quits while they're hidden; other event lines always show. */
    @JvmStatic
    fun hidden(prefs: SharedPreferences, row: JSONObject) =
        row.optBoolean("event") && hideJoins(prefs) && ChatCommands.hideable(row.optString("event_kind"))

    /** What's sent for a line typed with //: the rest, as written. */
    @JvmStatic
    fun literal(message: OutgoingMessage, text: String) = message.copy(text = text, literal = true)

    // Connections

    private fun ircAccountId(account: JSONObject?) =
        if (account == null) "" else "irc:" + account.optString("nick") + "@" + account.optString("server").lowercase(Locale.ROOT)

    /** The account id of this phone's own connection on [format] ("irc:kit@irc.libera.chat"), or "". */
    @JvmStatic
    fun directAccountId(context: Context, format: MessageProtocol): String {
        val account = DirectProtocols.account(context, format) ?: return ""
        if (format == MessageProtocol.IRC) return ircAccountId(account)
        val service = format.name.lowercase(Locale.ROOT)
        val fields = account.keys().asSequence().associateWith { account.optString(it) }
        val identity = AccountKeys.identity(service, fields)
        return if (identity.isEmpty()) "" else "$service:$identity"
    }

    private fun serverFormat(account: String) = MessageProtocol.parse(AccountKeys.service(account))

    /** What the bar says for a network's account (a FluidMessages Web or direct connection), or "" while connected. */
    @JvmStatic
    fun connectionText(context: Context, account: String): String {
        val prefs = DirectProtocols.prefs(context)
        val web = runCatching { JSONObject(prefs.getString("gatewayConnections", "{}") ?: "{}").optJSONObject(account) }.getOrNull()
        val format = serverFormat(account)
        val status = web ?: if (account.isNotEmpty() && account == directAccountId(context, format)) DirectProtocols.status(prefs, format) else null
        return status?.let(::words).orEmpty()
    }

    /** The bar's words: why it isn't connected (the network's own reason, in plain words) and what happens next. */
    @JvmStatic
    fun words(status: JSONObject): String {
        val host = status.optString("network")
        val network = (ServerDirectory.ircName(host) ?: host).ifEmpty { "the network" }
        val retry = status.optLong("retry_at")
        val at = if (retry > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(retry)) else ""
        val why = status.optString("error").trim().let { if (it.isEmpty()) "" else " · $it" }
        val state = status.optString("state")
        if (status.optBoolean("manual") && state != "connected" && state != "connecting")
            return "Not connected to $network · Reconnect automatically is off$why"
        return when (state) {
            "reconnecting" -> "Disconnected from $network$why" + (if (at.isEmpty()) "" else " · Trying again at $at")
            "paused" -> "Disconnected from $network · Waiting until $at to avoid reconnecting too often"
            "disconnected" -> "Disconnected from $network" + why.ifEmpty { " · Stopped after ${IrcRules.RECONNECTS_PER_HOUR} tries this hour" }
            "offline" -> "Disconnected from $network · Waiting for a network"
            else -> ""
        }
    }

    /** The account a chat goes through, for its bar: FluidMessages Web's account id, or the phone's own account. */
    @JvmStatic
    fun chatAccount(context: Context, conversation: String, format: MessageProtocol): String {
        if (format != MessageProtocol.IRC && format != MessageProtocol.MATRIX && format != MessageProtocol.XMPP) return ""
        val prefs = DirectProtocols.prefs(context)
        if (DirectProtocols.route(prefs, conversation, format) != null) return directAccountId(context, format)
        return GatewayClient.routeOf(prefs, conversation, format)?.optString("account").orEmpty()
    }

    @JvmStatic
    fun reconnect(context: Context, account: String) {
        val prefs = DirectProtocols.prefs(context)
        val connections = runCatching { JSONObject(prefs.getString("gatewayConnections", "{}") ?: "{}") }.getOrNull()
        if (connections?.has(account) == true) Thread({ runCatching { GatewayClient.reconnect(prefs, account) } }, "fluid-reconnect").start()
        else DirectProtocols.reconnect(context, serverFormat(account))
    }

    /** Reconnect automatically for a server: this phone's own connection, or FluidMessages Web's (on by default). */
    @JvmStatic
    fun autoReconnect(context: Context, account: String): Boolean {
        val prefs = DirectProtocols.prefs(context)
        val format = serverFormat(account)
        if (account == directAccountId(context, format)) return DirectProtocols.autoReconnect(prefs, format)
        val accounts = runCatching { JSONArray(prefs.getString("gatewayAccounts", "[]") ?: "[]") }.getOrNull() ?: return true
        for (index in 0 until accounts.length()) {
            val item = accounts.optJSONObject(index) ?: continue
            if (item.optString("id") == account) return item.optBoolean("reconnect", true)
        }
        return true
    }

    /** Turns Reconnect automatically on or off for a server. Call off the main thread. */
    @JvmStatic
    fun setAutoReconnect(context: Context, account: String, on: Boolean) {
        val prefs = DirectProtocols.prefs(context)
        val format = serverFormat(account)
        if (account == directAccountId(context, format)) { DirectProtocols.setAutoReconnect(context, format, on); return }
        // Shown at once; FluidMessages Web saves it and connects again when it's turned back on.
        runCatching {
            val accounts = JSONArray(prefs.getString("gatewayAccounts", "[]") ?: "[]")
            for (index in 0 until accounts.length()) accounts.optJSONObject(index)?.takeIf { it.optString("id") == account }?.put("reconnect", on)
            prefs.edit().putString("gatewayAccounts", accounts.toString()).apply()
        }
        runCatching { GatewayClient.autoReconnect(prefs, account, on) }
    }

    /**
     * The inbox's connection mark for each IRC, Matrix, and XMPP chat: "connected", "connecting", "disconnected", or ""
     * (no mark: not a server chat, or nothing known). Built once per inbox load.
     */
    internal class ConnectionStates(private val context: Context) {
        private val prefs = DirectProtocols.prefs(context)
        private val web = runCatching { JSONObject(prefs.getString("gatewayConnections", "{}") ?: "{}") }.getOrNull() ?: JSONObject()
        private val direct = HashMap<MessageProtocol, String>()

        fun of(conversation: String, format: MessageProtocol): String {
            if (format != MessageProtocol.IRC && format != MessageProtocol.MATRIX && format != MessageProtocol.XMPP) return ""
            if (DirectProtocols.route(prefs, conversation, format) != null) {
                if (!DirectProtocols.on(prefs, format)) return ""
                return direct.getOrPut(format) {
                    val account = DirectProtocols.account(context, format)
                    // The phone uses FluidMessages Web's IRC connection instead of a second one with the same nick.
                    if (DirectProtocols.deferredToWeb(prefs, format, account)) webState(ircAccountId(account))
                    else IrcRules.connectionState(DirectProtocols.status(prefs, format).optString("state"))
                }
            }
            return webState(GatewayClient.routeOf(prefs, conversation, format)?.optString("account").orEmpty())
        }

        private fun webState(account: String) =
            if (account.isEmpty()) "" else IrcRules.connectionState(web.optJSONObject(account)?.optString("state").orEmpty())
    }

    // Invitations

    @JvmStatic
    fun invites(prefs: SharedPreferences): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        for (key in listOf("gatewayInvites", "directInvites")) {
            val items = runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrNull() ?: continue
            for (index in 0 until items.length()) items.optJSONObject(index)?.let(all::add)
        }
        return all.sortedByDescending { it.optLong("date") }
    }

    /** Accepts or declines an invitation. Off the main thread. Returns the address of the chat it opened, or "". */
    @JvmStatic
    fun answer(context: Context, invite: JSONObject, accept: Boolean): String {
        val prefs = DirectProtocols.prefs(context)
        if (!invite.optBoolean("direct")) {
            return GatewayClient.answerInvite(prefs, invite.optString("id"), accept).optJSONObject("route")?.optString("address").orEmpty()
        }
        val items = JSONArray(prefs.getString("directInvites", "[]"))
        val kept = JSONArray()
        for (index in 0 until items.length()) items.optJSONObject(index)?.takeIf { it.optString("id") != invite.optString("id") }?.let(kept::put)
        prefs.edit().putString("directInvites", kept.toString()).apply()
        if (!accept) return ""
        val channel = invite.optString("target")
        val address = "irc-" + Integer.toHexString(channel.lowercase(Locale.ROOT).hashCode())
        DirectProtocols.link(context, address, MessageProtocol.IRC, channel, channel, true)
        return address
    }
}

/** The "/" hints above the message box in an IRC, Matrix, or XMPP chat. Tapping one fills it in. */
@Composable
internal fun CommandHints(family: String, draft: String, palette: Palette, pick: (String) -> Unit) {
    val hints = remember(family, draft) { ChatCommands.hints(family, draft) }
    if (hints.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
        .background(palette.wash).border(1.dp, palette.edge, RoundedCornerShape(14.dp))) {
        for (hint in hints) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClickLabel = "Use ${hint.usage}") {
                pick("/" + hint.name + if (hint.name == "help") "" else " ")
            }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hint.usage, Modifier.widthIn(min = 120.dp), color = palette.ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)
                Text(hint.description, color = palette.muted, fontSize = 13.sp)
            }
        }
    }
}

/**
 * "Disconnected from <network> · Reconnect", for one network's account; nothing while it's connected. It checks the
 * connection every few seconds, since the phone's and the server's connections change on their own.
 */
@Composable
internal fun ConnectionBar(account: String, palette: Palette, modifier: Modifier = Modifier) {
    if (account.isEmpty()) return
    val context = LocalContext.current
    var text by remember(account) { mutableStateOf(PhoneChat.connectionText(context, account)) }
    LaunchedEffect(account) {
        while (true) {
            delay(3000)
            text = withContext(Dispatchers.IO) { PhoneChat.connectionText(context, account) }
        }
    }
    if (text.isEmpty()) return
    val warning = Color(0xFFFF9F0A)
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
        .background(warning.copy(alpha = .14f)).border(1.dp, warning.copy(alpha = .6f), RoundedCornerShape(12.dp))
        .padding(start = 12.dp, end = 4.dp).semantics(mergeDescendants = false) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).padding(vertical = 8.dp), color = palette.ink, fontSize = 14.sp)
        TextButton(onClick = { PhoneChat.reconnect(context, account); text = "" }) {
            Text("Reconnect", color = palette.inboxAction, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** /help: the commands this chat's service takes, on a full page. */
@Composable
internal fun CommandHelpPage(family: String, palette: Palette, close: () -> Unit) {
    FormPage(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text("Done") } },
        title = { Text(mapOf("irc" to "IRC", "matrix" to "Matrix", "xmpp" to "XMPP")[family].orEmpty() + " commands") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (command in ChatCommands.available(family)) {
                    Column {
                        Text(command.usage, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        Text(command.description, color = palette.muted, fontSize = 14.sp)
                    }
                }
                HorizontalDivider()
                Text("Start a message with // to send a line that begins with / as written. Commands only work in IRC, Matrix, "
                    + "and XMPP chats; they’re never sent to texts or email.", color = palette.muted, fontSize = 14.sp)
            }
        })
}

/** Invitations to channels, rooms, and group chats, and XMPP contact requests, on a full page with Join and Decline. */
@Composable
internal fun InvitesPage(palette: Palette, close: () -> Unit, opened: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { DirectProtocols.prefs(context) }
    var invites by remember { mutableStateOf(PhoneChat.invites(prefs)) }
    var status by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    FormPage(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text("Done") } },
        title = { Text("Invitations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (status.isNotEmpty()) Text(status, color = palette.muted)
                if (invites.isEmpty()) Text("No invitations are waiting. When someone invites you to an IRC channel, a Matrix room, "
                    + "or an XMPP group chat, or asks to add you on XMPP, it shows here.", color = palette.muted)
                for (invite in invites) {
                    val contact = invite.optString("kind") == "contact"
                    val service = MessageProtocol.parse(invite.optString("protocol")).label
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.wash).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(invite.optString("name").ifEmpty { invite.optString("target") }, color = palette.ink, fontWeight = FontWeight.SemiBold)
                        Text(if (contact) invite.optString("target") + " asked to add you on $service."
                            else (invite.optString("from").ifEmpty { "Someone" }) + " invited you to " + invite.optString("target") + " on $service.",
                            color = palette.muted, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            fun answer(accept: Boolean) {
                                status = if (accept) "Joining…" else "Declining…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { PhoneChat.answer(context, invite, accept) } }
                                    invites = PhoneChat.invites(prefs).filter { it.optString("id") != invite.optString("id") }
                                    status = result.exceptionOrNull()?.message ?: if (accept) "Joined." else "Declined."
                                    result.getOrNull()?.takeIf { it.isNotEmpty() }?.let(opened)
                                }
                            }
                            AppButton(onClick = { answer(true) }) { Text(if (contact) "Accept" else "Join") }
                            TextButton(onClick = { answer(false) }) { Text("Decline") }
                        }
                    }
                }
            }
        })
}
