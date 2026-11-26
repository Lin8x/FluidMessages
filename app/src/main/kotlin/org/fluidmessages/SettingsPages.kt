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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

    /** Cards with a live card of their own above them (a server's Connection, ConnectionsUi). */
    internal fun mountWithHeader(root: LinearLayout, prefs: SharedPreferences, sections: List<SettingsSection>, header: @Composable () -> Unit) {
        mountCards(root, prefs, sections) { header() }
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
                   githubUrl: String, homepageUrl: String, fdroid: Boolean, signingKey: String, openVerify: Runnable) {
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
                            if (fdroid) PreferenceText("Updates come from F-Droid", "Open F-Droid to get the newest version.")
                            else if (managed) PreferenceText("Updates managed by your app store", "Open your store to get its latest release.")
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
                    item { SigningKeyCard(signingKey, fdroid, openVerify) }
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
    fun mountInboxFilters(root: LinearLayout, prefs: SharedPreferences) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(remember { Appearance.read(prefs) }) {
                // FluidMessages Web follows the phone's menu (appearance.inboxFilters).
                InboxFiltersPage(prefs) { SyncJob.syncSoon(root.context) }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    @JvmStatic
    fun mountMessaging(root: LinearLayout, prefs: SharedPreferences, showSample: Runnable, inboxFilters: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            var exactTimes by remember { mutableStateOf(prefs.getBoolean("exactTimes", false)) }
            var communityChats by remember { mutableStateOf(Communities.show(prefs)) }
            val filters = remember { InboxFilters.menu(InboxFilters.read(prefs), InboxFilters.ALL.map { it.service }.toSet()).size }
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
                        PreferencesCard("Inbox") {
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = inboxFilters::run),
                                verticalAlignment = Alignment.CenterVertically) {
                                PreferenceText("Inbox filters", "$filters of ${InboxFilters.ALL.size} shown in the filter menu. Choose and reorder them.",
                                    Modifier.weight(1f).padding(15.dp))
                                Text("›", Modifier.padding(end = 18.dp), color = scheme.onSurfaceVariant, fontSize = 22.sp)
                            }
                            HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                            SettingsSwitch("Show community chats in Chats",
                                "Channels and rooms on your servers also show in Chats, like group chats. Off, they show only under Communities, " +
                                    "which then counts their unread messages itself. Direct messages on those services always show in Chats.",
                                communityChats) {
                                communityChats = it
                                prefs.edit().putBoolean(Communities.SHOW_PREF, it).apply()
                                SyncJob.syncSoon(root.context)
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

/** Settings → Messaging → Inbox filters: a switch for each filter, with Move up and Move down. Inbox is always shown. */
@Composable
private fun InboxFiltersPage(prefs: SharedPreferences, changed: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var order by remember { mutableStateOf(InboxFilters.read(prefs)) }
    fun save(next: List<InboxFilters.Entry>) {
        order = next
        InboxFilters.save(prefs, next)
        changed()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Choose which filters the inbox's filter menu shows, and their order. Inbox is always there. A service shows once you " +
                "have chats or an account there. They work the same in the private folder, decoy mode, and the sample inbox.",
                Modifier.padding(horizontal = 12.dp), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            PreferencesCard("Filter menu") {
                order.forEachIndexed { index, entry ->
                    val filter = InboxFilters.of(entry.id)!!
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (entry.id == "inbox") PreferenceText(filter.label, "Always shown")
                            else SettingsSwitch(filter.label, filter.about, entry.shown) { save(InboxFilters.show(order, entry.id, it)) }
                        }
                        IconButton(onClick = { save(InboxFilters.move(order, index, -1)) }, enabled = index > 0,
                            modifier = Modifier.semantics { contentDescription = "Move ${filter.label} up" }) { Text("↑", fontSize = 18.sp) }
                        IconButton(onClick = { save(InboxFilters.move(order, index, 1)) }, enabled = index < order.size - 1,
                            modifier = Modifier.semantics { contentDescription = "Move ${filter.label} down" }) { Text("↓", fontSize = 18.sp) }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { save(InboxFilters.parse(emptyList())) }, modifier = Modifier.fillMaxWidth()) { Text("Restore the default order") }
        }
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
internal fun SettingsSwitch(title: String, detail: String, checked: Boolean, changed: (Boolean) -> Unit) =
    SettingsSwitch(title, detail, checked, true, changed)

@Composable
internal fun SettingsSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = Color(MessageStyle.filledControl(LocalControlTint.current))
    val offset by animateDpAsState(if (checked) 21.dp else 3.dp, label = "Switch thumb")
    // Disabled (with its reason in [detail]): dimmed, and read out as a switch that can't be changed.
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = changed)
        .alpha(if (enabled) 1f else .5f)
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

/** About and updates → This app's signing key: the fingerprint, Copy, and whether it is the official key. */
@Composable
private fun SigningKeyCard(fingerprint: String, fdroid: Boolean, openVerify: Runnable) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    PreferencesCard("This app's signing key") {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (fingerprint.isEmpty()) Text("Android didn't share this copy's signing key.", color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            else {
                Text("SHA-256 fingerprint", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Text(SigningKey.grouped(fingerprint), color = scheme.onSurface, fontSize = 14.sp, lineHeight = 21.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                OutlinedButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(SigningKey.colons(fingerprint)))
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy") }
            }
            when (SigningKey.status(fingerprint, fdroid)) {
                SigningKey.Status.OFFICIAL -> Text("✓ Matches the official FluidMessages key", color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                SigningKey.Status.FDROID -> Text("Signed by F-Droid. F-Droid built this copy from FluidMessages' source code " +
                    "and signed it with its own key.", color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                SigningKey.Status.SOMEONE_ELSE -> Text("This copy was signed by someone else, not by FluidMessages. It may have been " +
                    "changed. To be safe, get FluidMessages from its official page.", color = scheme.error,
                    style = MaterialTheme.typography.bodyLarge)
            }
            Text("How to check your download ↗", Modifier.clickable(role = Role.Button, onClick = openVerify::run),
                color = scheme.primary, fontSize = 15.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
        }
    }
}
