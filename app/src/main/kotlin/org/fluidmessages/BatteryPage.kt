package org.fluidmessages

import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp

/**
 * Settings → Battery and sync, in plain words: what uses battery, how often FluidMessages checks for new messages
 * while it's open and while it's closed (SyncPolicy, SyncJob), Wi-Fi only, Battery Saver, attachment downloads, and
 * what keeps a connection open (FluidMessages Web, Stay connected, Nearby's Stay discoverable, Tor), with a way to
 * Running now. The pref keys are SyncPolicy's, unchanged, so existing settings and backups keep working.
 */
internal object BatteryPage {
    const val TOP = "Checking for new messages wakes your phone for a moment. A connection that stays open uses more " +
        "battery than checking now and then."
    const val TEXTS = "Texts (SMS and MMS) arrive on their own and don’t need any of this. Sending always works right away."
    const val HOW_OFTEN = "How often to check for new messages"
    const val OPEN = "While FluidMessages is open"
    const val CLOSED = "While FluidMessages is closed"
    const val CLOSED_DETAIL = "Gets messages from FluidMessages Web and from Matrix and Email accounts on this phone. " +
        "Android may run it a little late to save battery. Accounts with Stay connected don’t need it."
    const val WIFI = "Only check on Wi-Fi"
    const val WIFI_DETAIL = "Skip these checks on mobile data. Sending works anywhere. Connections that stay open aren’t affected."
    const val SAVER = "Pause checks during Battery Saver"
    const val SAVER_DETAIL = "Skip these checks while Android’s Battery Saver is on."
    const val FILES = "Pictures and files"
    const val DOWNLOADS = "Download pictures and files you get"
    const val DOWNLOADS_DETAIL = "Downloads also wait while the checks above are paused."
    const val OPEN_CONNECTIONS = "What keeps a connection open"
    const val RUNNING = "See what’s running now"

    /** "While FluidMessages is open", for each of SyncPolicy.FOREGROUND_CHOICES. */
    fun foregroundLabel(seconds: Int) = when {
        seconds <= 0 -> "Only when I send something"
        seconds < 60 -> "Every $seconds seconds"
        else -> "Every minute"
    }

    /** "While FluidMessages is closed", for each of SyncPolicy.BACKGROUND_CHOICES. */
    fun backgroundLabel(minutes: Int) = when {
        minutes <= 0 -> "Never"
        minutes < 60 -> "Every $minutes minutes"
        minutes == 60 -> "Every hour"
        else -> "Every ${minutes / 60} hours"
    }

    /** What the open-app check does: it only talks to FluidMessages Web, so say so when that isn't set up. */
    fun openDetail(paired: Boolean) = if (paired) "Gets new messages from FluidMessages Web while you’re looking at your chats."
    else "Only used with FluidMessages Web, which isn’t set up. Accounts on this phone stay connected while FluidMessages is open."

    /** The Settings row's summary. */
    fun summary(backgroundMinutes: Int) =
        if (backgroundMinutes <= 0) "Checks only while FluidMessages is open"
        else "Checks " + backgroundLabel(backgroundMinutes).replaceFirstChar { it.lowercase() } + " when closed"

    @JvmStatic
    fun summary(prefs: SharedPreferences) = summary(SyncPolicy.backgroundMinutes(prefs))

    /** What's on now that keeps a connection open, read from settings. */
    data class Now(val paired: Boolean, val stayConnected: List<String>, val discoverable: Boolean, val tor: Boolean)

    data class Use(val title: String, val state: String, val detail: String)

    fun uses(now: Now): List<Use> = listOf(
        Use("FluidMessages Web", if (now.paired) "On" else "Off", if (now.paired)
            "The computer it runs on holds your chat connections, so this phone only checks in now and then."
        else "Set it up on a computer that stays on, and it holds your chat connections instead of this phone."),
        Use("Stay connected", if (now.stayConnected.isEmpty()) "Off" else "On for " + list(now.stayConnected),
            if (now.stayConnected.isEmpty()) "Accounts on this phone close when you leave FluidMessages. Turn it on for an account in Accounts."
            else "Messages arrive right away, even with FluidMessages closed. This uses the most battery. Change it in Accounts."),
        Use("Nearby: Stay discoverable", if (now.discoverable) "On" else "Off", if (now.discoverable)
            "Friends close by can reach you with FluidMessages closed. It keeps looking for them, which uses battery."
        else "Nearby only looks for people while you use it."),
        Use("Connect through Tor", if (now.tor) "On" else "Off", if (now.tor)
            "Each internet connection takes the long way, through three computers. That’s a little more work for your phone."
        else "Internet connections go straight to the server."))

    private fun list(names: List<String>) = when (names.size) {
        1 -> names[0]
        2 -> names[0] + " and " + names[1]
        else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
    }

    fun now(context: Context, prefs: SharedPreferences) = Now(
        paired = CloudBackup.paired(context),
        stayConnected = listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)
            .filter { DirectProtocols.on(prefs, it) && DirectProtocols.stayConnected(prefs, it) }.map { it.label },
        discoverable = DirectProtocols.on(prefs, MessageProtocol.NEARBY) && MessageProtocol.NEARBY.enabled(prefs)
            && DirectProtocols.stayConnected(prefs, MessageProtocol.NEARBY),
        tor = TorRoute.on(prefs))

    /** [running] opens Settings → Running now. */
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, running: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { AppTheme(remember { Appearance.read(prefs) }) { Page(prefs, root.context) { running.run() } } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    @Composable
    private fun Page(prefs: SharedPreferences, context: Context, running: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        val motion = Appearance.read(prefs).speed
        val now = remember { now(context, prefs) }
        var foreground by remember { mutableStateOf(SyncPolicy.foregroundSeconds(prefs)) }
        var background by remember { mutableStateOf(SyncPolicy.backgroundMinutes(prefs)) }
        var downloads by remember { mutableStateOf(SyncPolicy.downloads(prefs)) }
        var wifi by remember { mutableStateOf(SyncPolicy.wifiOnly(prefs)) }
        var saver by remember { mutableStateOf(prefs.getBoolean(SyncPolicy.BATTERY_SAVER, true)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(TOP, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                    Text(TEXTS, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                PreferencesCard(HOW_OFTEN) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeDropdown(OPEN, foreground.toString(),
                            SyncPolicy.FOREGROUND_CHOICES.map { Choice(it.toString(), foregroundLabel(it)) }, motion) {
                            foreground = it.toInt()
                            prefs.edit().putInt(SyncPolicy.FOREGROUND, foreground).apply()
                        }
                        Detail(openDetail(now.paired))
                        Spacer(Modifier.height(6.dp))
                        ThemeDropdown(CLOSED, background.toString(),
                            SyncPolicy.BACKGROUND_CHOICES.map { Choice(it.toString(), backgroundLabel(it)) }, motion) {
                            background = it.toInt()
                            prefs.edit().putInt(SyncPolicy.BACKGROUND, background).apply()
                            SyncJob.reschedule(context)
                        }
                        Detail(CLOSED_DETAIL)
                    }
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    SettingsSwitch(WIFI, WIFI_DETAIL, wifi) {
                        wifi = it
                        prefs.edit().putBoolean(SyncPolicy.WIFI_ONLY, it).apply()
                        SyncJob.reschedule(context)
                    }
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    SettingsSwitch(SAVER, SAVER_DETAIL, saver) {
                        saver = it
                        prefs.edit().putBoolean(SyncPolicy.BATTERY_SAVER, it).apply()
                    }
                }
            }
            item {
                PreferencesCard(FILES) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemeDropdown(DOWNLOADS, downloads, listOf(
                            Choice("wifi", "On Wi-Fi only"), Choice("always", "On Wi-Fi and mobile data"),
                            Choice("never", "Only when I tap them")), motion) {
                            downloads = it
                            prefs.edit().putString(SyncPolicy.DOWNLOADS, it).apply()
                        }
                        Detail(DOWNLOADS_DETAIL)
                    }
                }
            }
            item {
                PreferencesCard(OPEN_CONNECTIONS) {
                    uses(now).forEachIndexed { index, use ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                        PreferenceText(use.title + " · " + use.state, use.detail)
                    }
                    Row(Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp)) {
                        OutlinedButton(onClick = running) { Text(RUNNING) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Detail(text: String) =
        Text(text, Modifier.padding(horizontal = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
}
