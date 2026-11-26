package org.fluidmessages

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Tor per chat: whether a chat can use Tor and why not, whether it goes through Tor right now (the onion mark in the
 * inbox, the chat's top bar, and its details' Connection section), and the chat details' "Connect through Tor" switch.
 *
 * Tor routes per connection, not per chat: one IRC network, one Matrix or XMPP account, one email account. So the
 * switch changes that connection (TorRoute.choose on this phone, /api/tor/connection on FluidMessages Web), and says
 * so: "Also applies to other chats on <server>". It starts out matching Settings → Privacy and security → Connect
 * through Tor, which stays the choice for every connection without one of its own.
 */
internal object TorChat {
    const val TEXTS = "Texts go over your phone plan, not the internet"
    const val NEARBY = "Nearby doesn't use the internet"
    const val NO_SERVER = "This chat isn't connected to a server yet"
    const val OLD_WEB = "Update FluidMessages Web to choose Tor for this chat"
    /** The same words as FluidMessages Web's (tor_route.E2EE_SWITCH, tor.js TOR_E2EE). */
    const val E2EE = "Encrypted Matrix rooms on FluidMessages Web can't go through Tor yet, because their library opens its own connections"
    const val THROUGH = "Through Tor"
    const val WAITING = "Waiting for Tor"
    const val NOT_THROUGH = "Not through Tor"

    /** Who opens this chat's connection: this phone, FluidMessages Web, or nobody (texts, Nearby, no account). */
    enum class Handler { PHONE, WEB, NONE }

    /** How this chat's traffic goes right now. Only TOR shows the onion mark. */
    enum class Route { TOR, WAIT, DIRECT }

    /** Whether the switch can change anything, and in words why not when it can't. */
    data class Choice(val allowed: Boolean, val why: String)

    /**
     * Can this chat use Tor, and why not. The one place that decides it, for the switch and the tests:
     * - texts (SMS, MMS, the RCS preview) and phone calls use the phone plan; Nearby uses no internet at all;
     * - a FluidMessages Web chat it can't route there ([webHeld]: XMPP, encrypted Matrix rooms) says why;
     * - a server on your own network ([local]) connects directly, since Tor can't reach it.
     */
    @JvmStatic
    fun choice(protocol: MessageProtocol, handler: Handler, local: Boolean, webHeld: String = "", where: String = ""): Choice = when {
        protocol == MessageProtocol.SMS || protocol == MessageProtocol.MMS || protocol == MessageProtocol.RCS -> Choice(false, TEXTS)
        protocol == MessageProtocol.NEARBY -> Choice(false, NEARBY)
        handler == Handler.NONE -> Choice(false, NO_SERVER)
        handler == Handler.WEB && webHeld.isNotEmpty() -> Choice(false, webHeld)
        local -> Choice(false, "${where.ifEmpty { "This server" }} is on your own network, which Tor can't reach, so it " +
            "connects directly and stays on your network")
        else -> Choice(true, "")
    }

    /** The line under the switch while it can be changed. */
    @JvmStatic
    fun scope(where: String, handler: Handler): String =
        "Also applies to other chats on ${where.ifEmpty { "this server" }}" + if (handler == Handler.WEB) ", on FluidMessages Web" else ""

    @JvmStatic
    fun route(way: TorRoute.Way): Route = when (way) {
        TorRoute.Way.TOR -> Route.TOR
        TorRoute.Way.WAIT -> Route.WAIT
        else -> Route.DIRECT
    }

    /** FluidMessages Web's word for a connection's route ("tor", "wait", or "direct"). */
    @JvmStatic
    fun route(web: String): Route = when (web) { "tor" -> Route.TOR; "wait" -> Route.WAIT; else -> Route.DIRECT }

    @JvmStatic
    fun words(route: Route): String = when (route) { Route.TOR -> THROUGH; Route.WAIT -> WAITING; Route.DIRECT -> NOT_THROUGH }

    /** The onion shows only while a chat's traffic really goes through Tor: not while it waits, and never for texts. */
    @JvmStatic
    fun marked(choice: Choice, route: Route): Boolean = choice.allowed && route == Route.TOR

    /** The servers a phone connection opens, which its switch changes together: an email account has two. */
    @JvmStatic
    fun hosts(protocol: MessageProtocol, fields: Map<String, String>): List<String> {
        fun host(value: String?) = value.orEmpty().trim().lowercase(Locale.ROOT)
        val found = when (protocol) {
            MessageProtocol.IRC -> listOf(host(fields["server"]))
            MessageProtocol.MATRIX -> listOf(host(fields["homeserver"]).removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore(':'))
            MessageProtocol.XMPP -> listOf(host(fields["host"]).ifEmpty { host(fields["jid"]).substringAfter('@').substringBefore('/') })
            MessageProtocol.EMAIL -> listOf(host(fields["imap_host"]), host(fields["smtp_host"]))
            else -> emptyList()
        }
        return found.filter { it.isNotEmpty() }.distinct()
    }

    // This phone and FluidMessages Web

    /** A chat, for its details and top bar: its conversation, protocol, and a sample chat's name ("" when live). */
    data class Ref @JvmOverloads constructor(val conversation: String, val protocol: MessageProtocol, val sample: String = "")

    /** A chat's Tor right now. [account] is FluidMessages Web's account id for a web chat. */
    data class Now(val choice: Choice, val on: Boolean, val route: Route, val where: String, val handler: Handler,
                   val hosts: List<String> = emptyList(), val account: String = "", val tor: String = "") {
        val marked get() = marked(choice, route)
    }

    /** A sample chat: Harbor Net's channels go through Tor, so the mark shows; nothing really connects. */
    @JvmStatic
    fun sample(name: String, protocol: MessageProtocol, on: Boolean = SampleInbox.tor(name)): Now {
        val server = SampleInbox.server(SampleInbox.serverOf(name))?.name ?: "the sample server"
        val choice = choice(protocol, Handler.PHONE, false, "", server)
        return Now(choice, choice.allowed && on, if (choice.allowed && on) Route.TOR else Route.DIRECT, server, Handler.PHONE)
    }

    private fun fields(account: JSONObject?): Map<String, String> =
        account?.keys()?.asSequence()?.associateWith { account.optString(it) }.orEmpty()

    /** This phone's own account on [protocol] when it opens [conversation]'s connection itself, else null. */
    private fun phoneAccount(context: Context, prefs: SharedPreferences, conversation: String, protocol: MessageProtocol): JSONObject? {
        if (DirectProtocols.route(prefs, conversation, protocol) == null) return null
        val account = DirectProtocols.account(context, protocol) ?: return null
        if (protocol != MessageProtocol.EMAIL && DirectProtocols.deferredToWeb(prefs, protocol, account)) return null
        return account
    }

    private fun webAccount(prefs: SharedPreferences, conversation: String, protocol: MessageProtocol): String {
        val route = GatewayClient.routeOf(prefs, conversation, protocol) ?: return ""
        val named = route.optString("account")
        if (named.isNotEmpty()) return named
        val service = protocol.name.lowercase(Locale.ROOT)
        val accounts = runCatching { org.json.JSONArray(prefs.getString("gatewayAccounts", "[]") ?: "[]") }.getOrNull() ?: return ""
        for (index in 0 until accounts.length()) {
            val item = accounts.optJSONObject(index) ?: continue
            if (item.optString("protocol") == service) return item.optString("id")
        }
        return ""
    }

    private fun webEntries(prefs: SharedPreferences): JSONObject =
        runCatching { JSONObject(prefs.getString("gatewayTor", "{}") ?: "{}") }.getOrNull() ?: JSONObject()

    private fun where(protocol: MessageProtocol, account: JSONObject, accountId: String): String = when (protocol) {
        MessageProtocol.EMAIL -> account.optString("address").ifEmpty { account.optString("imap_host") }
        else -> AccountKeys.serverName(accountId).ifEmpty { hosts(protocol, fields(account)).firstOrNull().orEmpty() }
    }

    /** A chat's Tor right now. Reads settings and may decrypt the account, so call it off the main thread. */
    @JvmStatic
    fun read(context: Context, ref: Ref): Now {
        if (ref.sample.isNotEmpty()) return sample(ref.sample, ref.protocol)
        val prefs = DirectProtocols.prefs(context)
        val protocol = ref.protocol
        val idle = Now(choice(protocol, Handler.NONE, false), false, Route.DIRECT, "", Handler.NONE)
        if (!idle.choice.allowed && idle.choice.why != NO_SERVER) return idle
        val phone = phoneAccount(context, prefs, ref.conversation, protocol)
        if (phone != null) {
            val hosts = hosts(protocol, fields(phone))
            val accountId = if (protocol == MessageProtocol.EMAIL) "" else PhoneChat.directAccountId(context, protocol)
            val where = where(protocol, phone, accountId)
            val local = hosts.isNotEmpty() && hosts.all { TorRoute.localHost(it) }
            val choice = choice(protocol, Handler.PHONE, local, "", where)
            val on = hosts.isNotEmpty() && hosts.all { TorRoute.wanted(prefs, it) }
            val ways = hosts.map { TorRoute.wayNow(prefs, it) }
            val route = when {
                ways.isEmpty() -> Route.DIRECT
                ways.all { it == TorRoute.Way.TOR } -> Route.TOR
                ways.any { it == TorRoute.Way.WAIT } -> Route.WAIT
                else -> Route.DIRECT
            }
            val tor = if (route == Route.WAIT) TorRoute.status(context).words else ""
            return Now(choice, on && choice.allowed, route, where, Handler.PHONE, hosts, tor = tor)
        }
        val account = webAccount(prefs, ref.conversation, protocol)
        if (account.isEmpty()) return idle
        val entry = webEntries(prefs).optJSONObject(account)
        val where = entry?.optString("where").orEmpty().ifEmpty {
            if (protocol == MessageProtocol.EMAIL) AccountKeys.address(account) else AccountKeys.serverName(account)
        }
        if (entry == null) return Now(Choice(false, OLD_WEB), false, Route.DIRECT, where, Handler.WEB, account = account)
        val encrypted = protocol == MessageProtocol.MATRIX &&
            GatewayClient.routeOf(prefs, ref.conversation, protocol)?.optBoolean("encrypted") == true
        val held = when {
            encrypted -> E2EE
            entry.optBoolean("allowed", true) -> ""
            else -> entry.optString("why").ifEmpty { OLD_WEB }
        }
        val choice = choice(protocol, Handler.WEB, entry.optBoolean("local"), held, where)
        return Now(choice, entry.optBoolean("on"), route(entry.optString("route")), where, Handler.WEB, account = account,
            tor = entry.optString("status"))
    }

    /** The switch: this phone's own connection reconnects with its new route; FluidMessages Web changes its own. */
    @JvmStatic
    fun set(context: Context, now: Now, through: Boolean) {
        val prefs = DirectProtocols.prefs(context)
        when (now.handler) {
            Handler.PHONE -> {
                TorRoute.choose(prefs, now.hosts, through)
                DirectProtocols.restart(context)
            }
            Handler.WEB -> {
                val reply = GatewayClient.post(prefs, "tor/connection", JSONObject().put("account", now.account).put("on", through))
                val entries = webEntries(prefs)
                entries.put(now.account, reply)
                prefs.edit().putString("gatewayTor", entries.toString()).apply()
            }
            Handler.NONE -> {}
        }
    }

    /**
     * The inbox's onion marks, built once per inbox load like PhoneChat.ConnectionStates: true only for chats whose
     * connection goes through Tor right now.
     */
    internal class Marks(private val context: Context) {
        private val prefs = DirectProtocols.prefs(context)
        private val web = webEntries(prefs)
        private val phone = HashMap<MessageProtocol, Boolean>()
        private val anyPhone = TorRoute.on(prefs) || TorRoute.anyThrough(TorRoute.overrides(prefs))

        fun of(conversation: String, format: MessageProtocol): Boolean {
            if (format == MessageProtocol.SMS || format == MessageProtocol.MMS || format == MessageProtocol.RCS || format == MessageProtocol.NEARBY) return false
            if (DirectProtocols.route(prefs, conversation, format) != null) {
                if (!anyPhone) return false
                return phone.getOrPut(format) { runCatching { read(context, Ref(conversation, format)).marked }.getOrDefault(false) }
            }
            if (web.length() == 0) return false
            val account = webAccount(prefs, conversation, format)
            return account.isNotEmpty() && web.optJSONObject(account)?.optString("route") == "tor"
        }
    }
}

/** The onion's color: violet, readable on light and dark rows. Forced to the row's ink in [muted] when asked. */
internal fun torInk(dark: Boolean): Color = if (dark) Color(0xFFC3A6FF) else Color(0xFF6A3FB5)

/**
 * The Tor mark beside a chat's other marks (muted, lock, connection), in the same size and line style: an onion,
 * shown only while the chat's traffic goes through Tor right now. It reads out as "Through Tor".
 */
@Composable
internal fun TorMark(dark: Boolean, modifier: Modifier = Modifier, size: Int = 13) {
    DrawGlyph(Glyph.Onion, torInk(dark), modifier.size(size.dp).semantics { contentDescription = TorChat.THROUGH })
}

/** A chat's Tor, checked every few seconds, since Tor starts and connections change on their own. */
@Composable
internal fun rememberTorNow(ref: TorChat.Ref?): MutableState<TorChat.Now?> {
    val context = LocalContext.current
    val state = remember(ref) { mutableStateOf<TorChat.Now?>(null) }
    LaunchedEffect(ref) {
        if (ref == null) return@LaunchedEffect
        while (true) {
            state.value = withContext(Dispatchers.IO) { runCatching { TorChat.read(context, ref) }.getOrNull() }
            delay(3000)
        }
    }
    return state
}

/**
 * The Tor rows of a chat's Connection section: the onion (while through Tor) with "Through Tor", "Waiting for Tor",
 * or "Not through Tor", then the "Connect through Tor" switch. It's disabled with its reason where Tor can't apply, and
 * otherwise says it changes the whole connection.
 */
@Composable
internal fun TorRows(ref: TorChat.Ref) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val now = rememberTorNow(ref)
    var sampleOn by remember(ref) { mutableStateOf<Boolean?>(null) }
    var note by remember(ref) { mutableStateOf("") }
    val shown = sampleOn?.let { TorChat.sample(ref.sample, ref.protocol, it) } ?: now.value ?: return
    val dark = scheme.surfaceContainer.luminance() < .4f
    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (shown.marked) TorMark(dark, size = 20)
        val words = TorChat.words(shown.route) + if (shown.route == TorChat.Route.WAIT && shown.tor.isNotEmpty()) " · ${shown.tor}" else ""
        Text(words, Modifier.weight(1f), color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.outlineVariant)
    val detail = if (shown.choice.allowed) TorChat.scope(shown.where, shown.handler) else shown.choice.why
    SettingsSwitch("Connect through Tor", detail, shown.on, shown.choice.allowed) { through ->
        if (ref.sample.isNotEmpty()) { sampleOn = through; note = "Sample: nothing really connects."; return@SettingsSwitch }
        now.value = shown.copy(on = through)
        note = ""
        scope.launch(Dispatchers.IO) {
            val failed = runCatching { TorChat.set(context, shown, through) }.exceptionOrNull()
            val next = runCatching { TorChat.read(context, ref) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (next != null) now.value = next
                note = failed?.message.orEmpty()
            }
        }
    }
    if (note.isNotEmpty()) Text(note, Modifier.padding(start = 15.dp, end = 15.dp, bottom = 12.dp), color = scheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall)
}

/** The Connection section of a chat with no server connection of its own (texts, email, Nearby): its Tor rows only. */
@Composable
internal fun TorCard(ref: TorChat.Ref) {
    PreferencesCard("Connection") { TorRows(ref) }
}
