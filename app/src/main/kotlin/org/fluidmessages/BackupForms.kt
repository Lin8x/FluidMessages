package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.function.Consumer

enum class BackupFormMode { EXPORT, SHARE, RESTORE, LOCAL, LOCAL_EXISTING, CLOUD, CLOUD_NOW, CLOUD_RESTORE }

enum class BackupCategory(val flag: Int, val label: String) {
    SMS(1, "Text messages (SMS)"),
    APPEARANCE(2, "Global theme and layout"),
    EFFECTS(4, "Sounds, haptics and animations"),
    CHAT_THEMES(8, "Per-chat themes"),
    CHAT_SETTINGS(16, "Conversation settings"),
    WALLPAPER_PHOTOS(32, "Wallpaper photos"),
    PROTOCOLS(64, "MMS, internet messages, and protocol settings"),
}

data class BackupFormDefaults(val categories: Int, val intervalMinutes: Int, val wifi: Boolean, val savedPassword: Boolean)
data class BackupFormSelection(
    val categories: Int, val password: String, val keepPassword: Boolean,
    val intervalMinutes: Int, val wifi: Boolean,
)

object BackupForms {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, mode: BackupFormMode, smsAllowed: Boolean,
              defaults: BackupFormDefaults, submit: Consumer<BackupFormSelection>) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(Appearance.read(prefs)) {
                BackupForm(mode, smsAllowed, defaults, submit::accept)
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    @JvmStatic
    fun review(root: LinearLayout, prefs: SharedPreferences, summary: List<String>, restore: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(Appearance.read(prefs)) {
                var working by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("Items selected for restore", color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium)
                    BackupCard {
                        if (summary.isEmpty()) Text("No selected categories found in this archive",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        summary.forEach { Text(it, color = MaterialTheme.colorScheme.onSurface) }
                    }
                    Text("Existing messages are checked for duplicates.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    AppButton(onClick = { working = true; restore.run() }, enabled = !working && summary.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) { Text(if (working) "Restoring…" else "Restore selected items") }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

@Composable
private fun BackupForm(mode: BackupFormMode, smsAllowed: Boolean, defaults: BackupFormDefaults,
                       submit: (BackupFormSelection) -> Unit) {
    val importing = mode == BackupFormMode.RESTORE || mode == BackupFormMode.CLOUD_RESTORE
    val scheduled = mode in setOf(BackupFormMode.LOCAL, BackupFormMode.LOCAL_EXISTING, BackupFormMode.CLOUD)
    val available = BackupCategory.entries.filter { it != BackupCategory.SMS || smsAllowed }
        .fold(0) { flags, item -> flags or item.flag }
    var categories by remember { mutableIntStateOf(defaults.categories and available) }
    var encrypted by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var wifi by remember { mutableStateOf(defaults.wifi) }
    var hours by remember { mutableStateOf((defaults.intervalMinutes / 60).toString()) }
    var minutes by remember { mutableStateOf((defaults.intervalMinutes % 60).toString().padStart(2, '0')) }
    var error by remember { mutableStateOf("") }
    val h = hours.toIntOrNull() ?: 0
    val m = minutes.toIntOrNull() ?: 0
    val interval = h * 60 + m
    val scheme = MaterialTheme.colorScheme
    val action = when (mode) {
        BackupFormMode.EXPORT -> "Choose save location"
        BackupFormMode.SHARE -> "Choose storage app"
        BackupFormMode.RESTORE -> "Choose backup file"
        BackupFormMode.CLOUD_NOW -> "Upload backup"
        BackupFormMode.CLOUD_RESTORE -> "Download newest"
        BackupFormMode.LOCAL -> "Choose backup folder"
        BackupFormMode.LOCAL_EXISTING, BackupFormMode.CLOUD -> "Save schedule"
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(when (mode) {
                    BackupFormMode.EXPORT -> "Save a portable FluidMessages archive."
                    BackupFormMode.SHARE -> "Create a backup and pass it to an app you choose from the Android share menu."
                    BackupFormMode.RESTORE -> "Choose what to restore, then open a FluidMessages backup file."
                    BackupFormMode.CLOUD_RESTORE -> "Download and review the newest archive before restoring."
                    BackupFormMode.CLOUD_NOW -> "Create and upload a backup through your paired companion."
                    BackupFormMode.LOCAL -> "Choose a device folder or a writable folder offered by a storage app."
                    BackupFormMode.LOCAL_EXISTING -> "Update the interval or contents. Your current folder stays selected."
                    BackupFormMode.CLOUD -> "Save recurring backups through your paired companion."
                }, color = scheme.onSurfaceVariant)
            }
            if (scheduled) item {
                BackupCard {
                    Text("Repeat every", color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IntervalInput("Hours", hours, Modifier.weight(1f)) { hours = it }
                        IntervalInput("Minutes", minutes, Modifier.weight(1f)) { minutes = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30 to "30 min", 360 to "6 hr", 1440 to "Daily").forEach { (value, label) ->
                            FilterChip(selected = interval == value && m < 60, onClick = {
                                hours = (value / 60).toString()
                                minutes = (value % 60).toString().padStart(2, '0')
                            }, label = { Text(label) })
                        }
                    }
                    Text("Minimum 15 minutes. Android decides when background jobs can run.",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                BackupCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (importing) "What to restore" else "Include in backup", Modifier.weight(1f),
                            color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { categories = if (categories == available) 0 else available }) {
                            Text(if (categories == available) "Clear all" else "Select all")
                        }
                    }
                    BackupCategory.entries.forEach { item ->
                        BackupToggle(item.label, categories and item.flag != 0, available and item.flag != 0) {
                            categories = if (it) categories or item.flag else categories and item.flag.inv()
                        }
                    }
                    if (!smsAllowed) Text("Set FluidMessages as your SMS app to include texts.",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text("Message attachments and group chats require MMS or RCS support.",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                BackupCard {
                    if (!importing) BackupToggle("Encrypt backup", encrypted) { encrypted = it }
                    if (importing || encrypted) OutlinedTextField(
                        value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        label = { Text(when {
                            importing -> "Password, if encrypted"
                            defaults.savedPassword -> "New password (optional)"
                            else -> "Backup password"
                        }) },
                    )
                    if (encrypted && defaults.savedPassword) Text("Leave blank to keep your saved password.",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (scheduled) BackupToggle("Require unmetered network", wifi) { wifi = it }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = scheme.error, modifier = Modifier.padding(bottom = 7.dp))
        AppButton(onClick = {
            error = when {
                scheduled && (m > 59 || interval !in 15..10080) -> "Choose an interval from 15 minutes to 7 days."
                categories == 0 -> "Choose at least one item."
                !importing && encrypted && password.length < 8 && !(password.isEmpty() && defaults.savedPassword) ->
                    "Use a password with at least 8 characters."
                else -> ""
            }
            if (error.isEmpty()) submit(BackupFormSelection(categories,
                if (importing || encrypted) password else "", encrypted && password.isEmpty() && defaults.savedPassword,
                interval, wifi))
        }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) { Text(action) }
    }
}

@Composable
private fun BackupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
        .padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun IntervalInput(label: String, value: String, modifier: Modifier, changed: (String) -> Unit) {
    OutlinedTextField(value, { changed(it.filter(Char::isDigit).take(3)) }, modifier, singleLine = true,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun BackupToggle(label: String, value: Boolean, enabled: Boolean = true, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { changed(!value) },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = value, enabled = enabled, onCheckedChange = null, modifier = Modifier.size(40.dp))
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
