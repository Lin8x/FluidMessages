package org.fluidmessages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NickServ's identify notices folded into one card in an IRC chat. Networks such as Libera.Chat (Atheme) and OFTC or
 * Anope networks send the same few lines every time you connect: "This nickname is registered…", how to identify,
 * and then "You are now identified" or "Invalid password". The card says it in one line, takes the password, and
 * keeps NickServ's own words under "Show what NickServ said". The same rules are in server/chat-commands.js.
 */
internal object NickServRules {
    enum class Kind { ASK, IDENTIFIED, FAILED, NOTE }

    /** One chat line as the rules see it. */
    data class Heard(val id: String, val author: String?, val text: String, val incoming: Boolean, val event: Boolean = false)

    /**
     * A card for the lines [first]..[last] (indexes into the chat). [state] is what NickServ said last: ASK, IDENTIFIED,
     * or FAILED. [nick] is the nickname NickServ named, or "". [answered] is true when a later card follows it.
     */
    data class Card(val first: Int, val last: Int, val state: Kind, val nick: String, val said: List<String>, val answered: Boolean)

    private val formatting = Regex("\\u0003(?:\\d{1,2}(?:,\\d{1,2})?)?|[\\u0000-\\u001f\\u007f]")
    private val prefix = Regex("^nickserv:\\s*", RegexOption.IGNORE_CASE)
    private val failed = Regex("\\b(invalid password|password incorrect|incorrect password|wrong password|identify failed|authentication failed)\\b",
        RegexOption.IGNORE_CASE)
    private val identified = Regex("\\b(you are now (identified|recognized|logged in)|password accepted|(you are|you're) (successfully|already) " +
        "(identified|logged in)|successfully identified)\\b", RegexOption.IGNORE_CASE)
    private val ask = Regex("\\b(this )?nick(name)? is registered\\b|\\bnick(name)? is owned by someone else\\b|\\bplease (identify|authenticate)\\b",
        RegexOption.IGNORE_CASE)
    /** The other lines of the same notice: how to identify, the time limit, and the nick change if you don't. */
    private val note = Regex("\\bidentify\\b|\\bnick(name)? (will be|has been|is being|is now being) changed\\b|\\bchoose a different nick|\\bchange your nick|" +
        "\\bif (this|it) is your nick|^last login from\\b", RegexOption.IGNORE_CASE)
    private val named = Regex("(?:identified|recognized|logged in) (?:for|as) ([^\\s.,]+)|password for ([^\\s.,]+)|failed (?:for|as) ([^\\s.,]+)",
        RegexOption.IGNORE_CASE)

    /** NickServ itself. Other services (ChanServ…) and bots aren't folded. */
    @JvmStatic
    fun isNickServ(author: String?): Boolean = author?.trim()?.lowercase() == "nickserv"

    /** The words, without IRC colors and bold, and without the "NickServ: " a server chat puts first. */
    @JvmStatic
    fun plain(text: String): String = text.replace(formatting, "").replace(prefix, "").trim()

    /** What one NickServ line is about, or null when it's something else (an INFO reply, a warning…). */
    @JvmStatic
    fun kind(text: String): Kind? {
        val words = plain(text)
        if (words.isEmpty() || words.length > 400) return null
        return when {
            failed.containsMatchIn(words) -> Kind.FAILED
            identified.containsMatchIn(words) -> Kind.IDENTIFIED
            ask.containsMatchIn(words) -> Kind.ASK
            note.containsMatchIn(words) -> Kind.NOTE
            else -> null
        }
    }

    /** The nickname a line names ("You are now identified for kit." → kit), or "". */
    @JvmStatic
    fun nick(text: String): String = named.find(plain(text))?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }.orEmpty()

    /**
     * The cards in a chat: each run of incoming NickServ lines that are about identifying, with at least one that
     * asks, accepts, or refuses. Anything else NickServ says stays a normal message. [nickServChat] counts lines
     * with no author as NickServ's (its own direct chat).
     */
    @JvmStatic
    fun fold(lines: List<Heard>, nickServChat: Boolean = false): List<Card> {
        val cards = mutableListOf<Card>()
        var start = -1
        fun close(end: Int) {
            if (start < 0) return
            val run = (start..end).map { lines[it] }
            val kinds = run.map { kind(it.text) }
            val last = kinds.lastOrNull { it != null && it != Kind.NOTE }
            if (last != null) {
                val nick = run.map { nick(it.text) }.lastOrNull { it.isNotEmpty() }.orEmpty()
                cards += Card(start, end, last, nick, run.map { plain(it.text) }, false)
            }
            start = -1
        }
        for ((index, line) in lines.withIndex()) {
            val fromNickServ = line.incoming && !line.event && (isNickServ(line.author) || line.author == null && nickServChat)
            if (fromNickServ && kind(line.text) != null) { if (start < 0) start = index }
            else close(index - 1)
        }
        close(lines.size - 1)
        return cards.mapIndexed { index, card -> if (index < cards.size - 1) card.copy(answered = true) else card }
    }

    /** The card's one-line title. */
    @JvmStatic
    fun title(card: Card, fallbackNick: String): String {
        val who = card.nick.ifEmpty { fallbackNick }
        return when (card.state) {
            Kind.IDENTIFIED -> if (who.isEmpty()) "Identified" else "Identified as $who"
            Kind.FAILED -> "That password didn’t work"
            else -> "This nickname is registered"
        }
    }

    /** The line under the title. */
    @JvmStatic
    fun detail(card: Card, saved: Boolean): String = when {
        card.state == Kind.IDENTIFIED -> "NickServ accepted your password."
        card.answered -> "NickServ answered below."
        card.state == Kind.FAILED -> "NickServ didn’t accept it. Check the password and try again."
        saved -> "Your saved password is sent each time you connect. If NickServ asks again, type it here."
        else -> "Type your NickServ password to prove it’s yours. It goes only to NickServ, never to a channel."
    }
}

/**
 * Sends your password to NickServ over the connection (never as a chat message), and with [save] keeps it on the IRC
 * account for next time. Returns a note to show (or null), and throws with what went wrong. Runs off the main thread.
 */
fun interface NickServIdentify { fun identify(password: String, save: Boolean): String? }

/** The phone's side of the card: the phone's own IRC connection, or FluidMessages Web's for chats that go through it. */
internal object PhoneNickServ {
    private fun direct(prefs: android.content.SharedPreferences, conversation: String) =
        DirectProtocols.on(prefs, MessageProtocol.IRC) && DirectProtocols.route(prefs, conversation, MessageProtocol.IRC) != null

    @JvmStatic
    fun identifier(context: android.content.Context, conversation: String): NickServIdentify = NickServIdentify { password, save ->
        val prefs = DirectProtocols.prefs(context)
        if (direct(prefs, conversation)) {
            DirectProtocols.ircIdentify(context, password, save)
            null
        } else GatewayClient.identify(prefs, conversation, password, save).optString("notice").ifEmpty { null }
    }

    /** The IRC account this chat goes through (its fields, with secrets shown only as set), or null. */
    private fun account(context: android.content.Context, conversation: String): org.json.JSONObject? {
        val prefs = DirectProtocols.prefs(context)
        if (direct(prefs, conversation)) return DirectProtocols.account(context, MessageProtocol.IRC)
        val id = GatewayClient.routeOf(prefs, conversation, MessageProtocol.IRC)?.optString("account").orEmpty()
        val list = runCatching { org.json.JSONArray(prefs.getString("gatewayAccounts", "[]") ?: "[]") }.getOrDefault(org.json.JSONArray())
        val irc = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optString("protocol") == "irc" }
        return (irc.firstOrNull { it.optString("id") == id } ?: irc.firstOrNull())?.optJSONObject("fields")
    }

    /** Whether a NickServ password is saved on that account. */
    @JvmStatic
    fun saved(context: android.content.Context, conversation: String): Boolean {
        val password = account(context, conversation)?.opt("nickserv_password") ?: return false
        return password is org.json.JSONObject && password.optBoolean("set") || password is String && password.isNotEmpty()
    }

    /** Your nickname on that account, for "Identified as …" when NickServ doesn't name it. */
    @JvmStatic
    fun nick(context: android.content.Context, conversation: String): String = account(context, conversation)?.optString("nick").orEmpty()
}

/** The NickServ card in an IRC chat. [identify] is null in the sample inbox and when the chat can't send. */
@Composable
internal fun NickServCard(card: NickServRules.Card, palette: Palette, nick: String, saved: Boolean, identify: NickServIdentify?) {
    val shape = RoundedCornerShape(18.dp)
    var open by remember(card.first) { mutableStateOf(false) }
    var password by remember(card.first) { mutableStateOf("") }
    var save by remember(card.first) { mutableStateOf(!saved) }
    var busy by remember(card.first) { mutableStateOf(false) }
    var result by remember(card.first) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val tint = MessageProtocol.IRC.tint(palette.appearance)
    val asking = !card.answered && card.state != NickServRules.Kind.IDENTIFIED
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(palette.wash).border(1.dp, tint.copy(alpha = .55f), shape)
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(NickServRules.title(card, nick), Modifier.semantics { heading() }, color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(NickServRules.detail(card, saved), color = palette.muted, fontSize = 14.sp)
        if (asking && identify != null) {
            OutlinedTextField(password, { password = it.take(300); result = null }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text("NickServ password") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Checkbox) { save = !save },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(save, { save = it })
                Text(if (saved) "Replace the saved password on this IRC account" else "Save it to this IRC account", color = palette.ink, fontSize = 14.sp)
            }
            AppButton(enabled = !busy && password.isNotBlank(), onClick = {
                busy = true
                result = null
                val typed = password
                scope.launch {
                    val sent = withContext(Dispatchers.IO) { runCatching { identify.identify(typed, save) } }
                    busy = false
                    sent.onSuccess { note ->
                        password = ""
                        result = note ?: "Sent to NickServ. Its answer shows here in a moment."
                    }.onFailure { result = it.message ?: "Couldn’t send it. Try again." }
                }
            }) { Text(if (busy) "Sending…" else "Identify") }
            result?.let { Text(it, color = palette.muted, fontSize = 13.sp) }
        }
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (open) "Hide what NickServ said" else "Show what NickServ said", color = tint)
        }
        if (open) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(palette.bg).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (line in card.said) Text(line, color = palette.ink, fontSize = 13.sp)
        }
    }
}
