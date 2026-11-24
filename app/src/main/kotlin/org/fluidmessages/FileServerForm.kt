package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.function.BiConsumer
import org.json.JSONObject

/** Adds or edits a file server for backups: type, address, port, sign-in, and folder. */
object FileServerForm {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, existing: JSONObject?, submit: BiConsumer<JSONObject, Boolean>) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { AppTheme(Appearance.read(prefs)) { Form(existing, submit) } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

private val kinds = listOf(
    FileServers.SFTP to "Secure file transfer over SSH. Most Linux servers, NAS boxes, and hosting accounts have it.",
    FileServers.WEBDAV to "Nextcloud, ownCloud, Synology, QNAP, and other WebDAV servers, over HTTPS.",
    FileServers.FTPS to "FTP with encryption (explicit TLS).",
    FileServers.FTP_PLAIN to "Plain FTP isn't encrypted: your password and backup travel as-is. Use it only on your home network with an encrypted backup.",
)

@Composable
private fun Form(existing: JSONObject?, submit: BiConsumer<JSONObject, Boolean>) {
    val scheme = MaterialTheme.colorScheme
    var kind by remember { mutableStateOf(existing?.optString("kind") ?: FileServers.SFTP) }
    var name by remember { mutableStateOf(existing?.optString("name") ?: "") }
    var host by remember { mutableStateOf(existing?.optString("host") ?: "") }
    var port by remember { mutableStateOf((existing?.optInt("port") ?: FileServers.defaultPort(kind)).toString()) }
    var user by remember { mutableStateOf(existing?.optString("username") ?: "") }
    var password by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(existing?.optString("folder", "FluidMessages") ?: "FluidMessages") }
    var error by remember { mutableStateOf("") }

    fun build(): JSONObject? {
        val number = port.toIntOrNull()
        val address = host.trim()
        error = when {
            address.isEmpty() -> "Enter the server address"
            address.contains(' ') -> "The address can't contain spaces"
            kind != FileServers.WEBDAV && address.contains("/") -> "Enter just the address, like files.example.com. Put folders below."
            number == null || number !in 1..65535 -> "Enter a port from 1 to 65535"
            user.isBlank() -> "Enter the username"
            existing == null && password.isEmpty() -> "Enter the password"
            else -> ""
        }
        if (error.isNotEmpty()) return null
        return JSONObject().put("id", existing?.optString("id") ?: "").put("kind", kind)
            .put("name", name.trim().ifEmpty { address.removePrefix("https://").substringBefore('/') })
            .put("host", address).put("port", number).put("username", user.trim()).put("password", password)
            .put("folder", folder.trim().trim('/'))
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Backups go straight from this phone to your server. Nothing passes through anyone else.", color = scheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type of server", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { (value, _) ->
                        FilterChip(selected = kind == value, onClick = {
                            // Keep a custom port; swap a default one for the new type's default.
                            if (port.toIntOrNull() == FileServers.defaultPort(kind)) port = FileServers.defaultPort(value).toString()
                            kind = value
                        }, label = { Text(FileServers.label(value)) })
                    }
                }
                Text(kinds.first { it.first == kind }.second,
                    color = if (kind == FileServers.FTP_PLAIN) scheme.error else scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(host, { host = it.trim() }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Server address") },
                    placeholder = { Text(if (kind == FileServers.WEBDAV) "cloud.example.com/remote.php/dav/files/me" else "files.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), singleLine = true,
                    label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            SecretField(password, { password = it }, if (existing != null) "Password · leave blank to keep" else "Password", copy = false)
            OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Folder on the server") },
                supportingText = { Text("Created if it doesn't exist. Leave blank for the top folder.") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name (optional)") },
                placeholder = { Text("Home NAS") })
            if (error.isNotEmpty()) Text(error, color = scheme.error)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { build()?.let { submit.accept(it, true) } }, modifier = Modifier.weight(1f)) { Text("Test connection") }
            AppButton(onClick = { build()?.let { submit.accept(it, false) } }, modifier = Modifier.weight(1f)) { Text("Save") }
        }
    }
}
