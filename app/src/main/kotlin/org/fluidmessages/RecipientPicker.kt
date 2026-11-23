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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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

private val emailOrJid = Regex("[^@\\s<>]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
private val matrixId = Regex("@[a-z0-9._=\\-/+]{1,255}:[A-Za-z0-9.:-]{1,255}")
private val ircChannel = Regex("[#&][^\\s,]{1,49}")

internal fun addressOptions(query: String, available: Set<MessageProtocol>): List<AddressOption> {
    val text = query.trim()
    val options = mutableListOf<AddressOption>()
    if (matrixId.matches(text)) options += AddressOption(MessageProtocol.MATRIX, text)
    else if (emailOrJid.matches(text)) {
        options += AddressOption(MessageProtocol.EMAIL, text.lowercase())
        options += AddressOption(MessageProtocol.XMPP, text.lowercase())
    } else if (ircChannel.matches(text)) options += AddressOption(MessageProtocol.IRC, text)
    else if (text.startsWith("irc:", ignoreCase = true) && text.length > 4) options += AddressOption(MessageProtocol.IRC, text.substring(4))
    return options.filter { it.protocol in available }
}

internal class RecipientPicker(
    root: LinearLayout, prefs: SharedPreferences, allowContacts: Runnable, keyForNumber: Function<String, String>,
    proceed: Consumer<List<String>>,
    /** Protocols you can message now, and turned-on protocols that still need an account. */
    available: Set<MessageProtocol> = emptySet(), needsAccount: Set<MessageProtocol> = emptySet(),
    addAccount: Runnable? = null,
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
                Column(Modifier.fillMaxSize().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
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
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
