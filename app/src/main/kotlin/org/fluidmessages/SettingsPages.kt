package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.Consumer

/**
 * One row in a settings or details list. Information rows aren't tappable and show an info mark instead of an
 * arrow; a row with an encryption level shows its colored badge.
 */
data class SettingsAction @JvmOverloads constructor(val label: String, val detail: String, val action: Runnable,
                                                    val info: Boolean = false, val security: Encryption? = null,
                                                    /** On or off for a switch row; null for rows that open something. */
                                                    val toggle: Boolean? = null)
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
                    listOf(Choice("phone", "Phone"), Choice("companion", "FluidMessages Web")), appearance.speed) {
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
            AppTheme(appearance) {
                if (header == null) SettingsCards(sections)
                else SettingsCards(sections) { header(appearance) }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    @JvmStatic
    fun mountAbout(root: LinearLayout, prefs: SharedPreferences, version: String, build: Long, status: String,
                   managed: Boolean, busy: Boolean, check: Runnable, github: Runnable, homepage: Runnable,
                   githubUrl: String, homepageUrl: String) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(Appearance.read(prefs)) {
                var automatic by remember { mutableStateOf(prefs.getBoolean("automaticUpdateChecks", true)) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Image(painterResource(R.drawable.fluid_icon_art), contentDescription = "FluidMessages icon",
                                modifier = Modifier.size(112.dp).clip(RoundedCornerShape(28.dp)))
                            Text("FluidMessages", style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text("Version ${version} · Build ${build}", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        PreferencesCard("App updates") {
                            if (managed) PreferenceText("Updates managed by your app store", "Open your store to get its latest release.")
                            else SettingsSwitch("Check automatically", "Check GitHub once a day while the app is open.", automatic) {
                                automatic = it
                                prefs.edit().putBoolean("automaticUpdateChecks", it).apply()
                            }
                            if (status.isNotEmpty()) PreferenceText(status, "")
                        }
                    }
                    if (!managed) item {
                        AppButton(onClick = check::run, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Check for updates")
                        }
                    }
                    item {
                        PreferencesCard("Links") {
                            LinkRow("Website", homepageUrl, homepage)
                            HorizontalDivider(Modifier.padding(start = 12.dp))
                            LinkRow("GitHub releases", githubUrl, github)
                        }
                    }
                }
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
            var exactTimes by remember { mutableStateOf(prefs.getBoolean("exactTimes", false)) }
            AppTheme(appearance) {
                val scheme = MaterialTheme.colorScheme
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
                        PreferencesCard("Privacy") {
                            ProtocolSwitch(prefs, "stripMetadata", "Remove metadata from files you send",
                                "On every protocol, removes camera, location, author, and editing details from photos, MP4 and WebM video and audio, MP3 tags, PDFs, and Office or OpenDocument files. Other file types are sent as they are.", true)
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
    val fill = Color(MessageStyle.filledControl(LocalControlTint.current))
    val offset by animateDpAsState(if (checked) 21.dp else 3.dp, label = "Switch thumb")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = changed)
        .padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PreferenceText(title, detail, Modifier.weight(1f).padding(end = 12.dp))
        Box(Modifier.size(51.dp, 31.dp)
            .background(if (checked) fill else scheme.outline, CircleShape),
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
                        if (entry.toggle != null) {
                            SettingsSwitch(entry.label, entry.detail, entry.toggle) { entry.action.run() }
                            return@forEachIndexed
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 55.dp)
                            .then(if (entry.info) Modifier else Modifier.clickable(role = Role.Button, onClick = entry.action::run))
                            .padding(horizontal = 15.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.label, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                if (entry.detail.isNotEmpty()) Text(entry.detail, color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            RowEnd(entry, scheme.primary, scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}


/** A plain link: tapping opens the address in the default browser. */
@Composable
private fun LinkRow(title: String, url: String, open: Runnable) {
    val display = url.removePrefix("https://").removeSuffix("/")
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open $display in your browser") { open.run() }
        .padding(horizontal = 12.dp, vertical = 11.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        Text("$display ↗", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
    }
}
