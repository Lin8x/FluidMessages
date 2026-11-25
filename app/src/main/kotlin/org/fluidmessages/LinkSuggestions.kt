package org.fluidmessages

import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Someone you already chat with on a service: their name, their address there, and the service (matrix, xmpp, email, irc). */
data class LinkSuggestion(val name: String, val address: String, val service: String)

/**
 * Suggestions while linking an address to a contact: people and addresses from your own chats on that service, found
 * on this phone only. What you type is never sent anywhere to search; typing a new address still works.
 */
object LinkSuggestions {
    val SERVICES = listOf("matrix", "xmpp", "email", "irc")
    const val LIMIT = 6

    /**
     * The [known] people matching [query] on [service] (any service when null): names or addresses that start with it
     * first, then ones that contain it. Nothing for an empty query, and no repeat of exactly what's typed.
     */
    fun match(known: List<LinkSuggestion>, query: String, service: String?, limit: Int = LIMIT): List<LinkSuggestion> {
        val typed = query.trim().lowercase(Locale.ROOT)
        if (typed.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val found = known.filter { item ->
            (service == null || item.service == service) && item.address.isNotBlank()
                && !item.address.equals(query.trim(), ignoreCase = true)
                && (item.name.lowercase(Locale.ROOT).contains(typed) || item.address.lowercase(Locale.ROOT).contains(typed))
                && seen.add(item.service + "\u0000" + item.address.lowercase(Locale.ROOT))
        }
        fun starts(item: LinkSuggestion) = item.name.lowercase(Locale.ROOT).split(' ').any { it.startsWith(typed) }
            || item.address.lowercase(Locale.ROOT).removePrefix("@").startsWith(typed.removePrefix("@"))
        return found.sortedWith(compareBy<LinkSuggestion> { if (starts(it)) 0 else 1 }.thenBy { it.name.lowercase(Locale.ROOT) }).take(limit)
    }

    /** Fictional people for the sample inbox's contact editor. */
    @JvmField val SAMPLE = listOf(
        LinkSuggestion("Maya Chen", "@maya:example.org", "matrix"),
        LinkSuggestion("Maya Chen", "maya@example.org", "xmpp"),
        LinkSuggestion("Leo Park", "leo.park@example.org", "email"),
        LinkSuggestion("Kestrel", "@kestrel:example.org", "matrix"),
        LinkSuggestion("Orbital", "orbital", "irc"),
        LinkSuggestion("Ana Ortiz", "ana@example.org", "xmpp"),
    )

    /**
     * Everyone in your chats on Matrix, XMPP, Email, and IRC, from what this phone already has: one-to-one chats with
     * their names, and the people in group chats and channels. Read from saved routes only; nothing is fetched.
     */
    @JvmStatic fun known(prefs: SharedPreferences): List<LinkSuggestion> {
        val result = ArrayList<LinkSuggestion>()
        val channels = runCatching { org.json.JSONObject(prefs.getString("gatewayChannels", "{}") ?: "{}") }.getOrNull()
        for (route in runCatching { GatewayClient.allRoutes(prefs) }.getOrDefault(emptyList())) {
            val service = route.optString("protocol").lowercase(Locale.ROOT)
            if (service !in SERVICES) continue
            val target = route.optString("target")
            val group = route.optBoolean("group") || target.startsWith("#") || target.startsWith("!")
            if (!group && target.isNotBlank()) result += LinkSuggestion(route.optString("title").ifBlank { target }, target, service)
            val people = route.optJSONArray("members")
            for (index in 0 until minOf(people?.length() ?: 0, 500)) {
                val person = people!!.optString(index).trim()
                if (person.isNotEmpty()) result += LinkSuggestion(person, person, service)
            }
            if (service == "irc") channels?.optJSONObject(route.optString("address"))?.optJSONArray("members")?.let { nicks ->
                for (index in 0 until minOf(nicks.length(), 500)) {
                    // Operators and voiced people show @ and + before their nickname; the nickname is what links.
                    val nick = nicks.optString(index).trim().trimStart('@', '+', '%', '~', '&')
                    if (nick.isNotEmpty()) result += LinkSuggestion(nick, nick, service)
                }
            }
        }
        return result
    }
}

/** The tappable suggestions under an address field: name, address, and a service tag. */
@Composable
internal fun LinkSuggestionList(suggestions: List<LinkSuggestion>, pick: (LinkSuggestion) -> Unit) {
    if (suggestions.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("From your chats", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainer) {
            Column {
                suggestions.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = scheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .clickable(role = Role.Button, onClickLabel = "Use this address") { pick(item) }
                        .semantics { contentDescription = item.name + ", " + item.address + ", " + NewAddress.label(item.service) }
                        .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            if (item.address != item.name) Text(item.address, color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Surface(shape = RoundedCornerShape(8.dp), color = scheme.secondaryContainer) {
                            Text(NewAddress.label(item.service), Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                color = scheme.onSecondaryContainer, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Link an address, step two: their address on [service], with suggestions from your chats there, on its own page. */
object LinkAddressPage {
    /** [submit] gets the address and, for XMPP, whether it's a group chat; it returns a problem to show, or null to close. */
    @JvmStatic
    fun show(activity: ComponentActivity, prefs: SharedPreferences, title: String, service: String, hint: String,
             known: List<LinkSuggestion>, submit: java.util.function.BiFunction<String, Boolean, String?>) =
        SecurityDialogs.present(activity, prefs) { dismiss ->
            var address by remember { mutableStateOf("") }
            var group by remember { mutableStateOf(false) }
            var problem by remember { mutableStateOf<String?>(null) }
            val suggestions = remember(address) { LinkSuggestions.match(known, address, service) }
            FormPage(onDismissRequest = dismiss, title = { Text(title) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(address, { address = it.take(300); problem = null }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(NewAddress.label(service) + " address") }, placeholder = { Text(hint) },
                        supportingText = { Text("Type a new address, or pick someone from your " + NewAddress.label(service) + " chats.") },
                        keyboardOptions = KeyboardOptions(keyboardType = if (service == "irc") KeyboardType.Text else KeyboardType.Email,
                            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                    LinkSuggestionList(suggestions) { address = it.address; problem = null }
                    if (service == "xmpp") Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("This is a group chat", Modifier.weight(1f))
                        Switch(group, { group = it })
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
                confirmButton = {
                    TextButton(onClick = {
                        if (address.isBlank()) { problem = "Enter an address."; return@TextButton }
                        problem = submit.apply(address.trim(), group)
                        if (problem == null) dismiss()
                    }) { Text("Link") }
                })
        }
}
