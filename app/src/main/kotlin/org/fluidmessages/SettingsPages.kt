package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.function.Consumer

data class SettingsAction(val label: String, val detail: String, val action: Runnable)
data class SettingsSection(val label: String, val entries: List<SettingsAction>)

object SettingsPages {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, sections: List<SettingsSection>) {
        mountCards(root, prefs, sections)
    }

    @JvmStatic
    fun mountBackupProvider(root: LinearLayout, prefs: SharedPreferences, phone: Boolean,
                            description: String, sections: List<SettingsSection>, changed: Consumer<Boolean>) {
        mountCards(root, prefs, sections) { appearance ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeDropdown("Backup method", if (phone) "phone" else "companion",
                    listOf(Choice("phone", "Phone"), Choice("companion", "Companion")), appearance.speed) {
                    changed.accept(it == "phone")
                }
                if (description.isNotEmpty()) Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    private fun mountCards(root: LinearLayout, prefs: SharedPreferences, sections: List<SettingsSection>,
                           header: (@Composable (Appearance) -> Unit)? = null) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = Appearance.read(prefs)
            MaterialTheme(colorScheme = appColorScheme(appearance)) {
                if (header == null) SettingsCards(sections)
                else SettingsCards(sections) { header(appearance) }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    @JvmStatic
    fun mountMessaging(root: LinearLayout, prefs: SharedPreferences, showSample: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            val scheme = appColorScheme(appearance)
            var exactTimes by remember { mutableStateOf(prefs.getBoolean("exactTimes", false)) }
            MaterialTheme(colorScheme = scheme) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item {
                        PreferencesCard("Message display") {
                            SettingsSwitch("Show exact message times", "Show the clock time in the inbox and under each message.",
                                exactTimes) {
                                exactTimes = it
                                prefs.edit().putBoolean("exactTimes", it).apply()
                            }
                        }
                    }
                    item {
                        PreferencesCard("Gestures") {
                            PreferenceText("Swipe right to reply", "Quote a message and add your reply underneath.")
                            HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                            PreferenceText("Touch and hold for actions", "React, forward, copy, or see message details.")
                        }
                    }
                    item {
                        OutlinedButton(onClick = showSample::run, modifier = Modifier.fillMaxWidth()) { Text("Try in sample inbox") }
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

internal fun appColorScheme(appearance: Appearance): ColorScheme {
    val fill = Color(MessageStyle.filledControl(appearance.controls))
    val ink = Color(MessageStyle.filledControlInk(appearance.controls))
    return if (appearance.dark) darkColorScheme(primary = fill, onPrimary = ink)
    else lightColorScheme(primary = fill, onPrimary = ink)
}

@Composable
internal fun PreferencesCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(start = 12.dp), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainer), content = content)
    }
}

@Composable
internal fun PreferenceText(title: String, detail: String, modifier: Modifier = Modifier.padding(15.dp)) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        if (detail.isNotEmpty()) Text(detail, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SettingsSwitch(title: String, detail: String, checked: Boolean, changed: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val offset by animateDpAsState(if (checked) 21.dp else 3.dp, label = "Switch thumb")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = changed)
        .padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PreferenceText(title, detail, Modifier.weight(1f).padding(end = 12.dp))
        Box(Modifier.size(51.dp, 31.dp)
            .background(if (checked) scheme.primary else scheme.onSurface.copy(alpha = .25f), CircleShape),
            contentAlignment = Alignment.CenterStart) {
            Box(Modifier.offset(x = offset).size(27.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun SettingsCards(sections: List<SettingsSection>, header: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (header != null) item { header() }
        for (section in sections) item {
            Column {
                Text(section.label, Modifier.padding(start = 12.dp, bottom = 7.dp),
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp))
                    .background(scheme.surfaceContainer)) {
                    section.entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().heightIn(min = 55.dp)
                            .clickable(role = Role.Button, onClick = entry.action::run)
                            .padding(horizontal = 15.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.label, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                if (entry.detail.isNotEmpty()) Text(entry.detail, color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text("›", Modifier.padding(start = 10.dp), color = scheme.primary,
                                style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}
