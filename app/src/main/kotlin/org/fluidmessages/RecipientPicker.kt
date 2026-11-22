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

internal class RecipientPicker(
    root: LinearLayout, prefs: SharedPreferences, allowContacts: Runnable, keyForNumber: Function<String, String>,
    proceed: Consumer<List<String>>,
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
                        label = { Text("Name or phone number") },
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
                        if (results.isEmpty() && typedNumbers.isEmpty()) item {
                            Text(if (loading) "Loading recipients…" else if (search.empty()) "Enter a phone number to start"
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
