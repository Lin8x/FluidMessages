package org.fluidmessages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings → Privacy and security → Connect through Tor (TorRoute). Off by default. Turning it on opens the warning
 * in place, and "Turn on" stays off until "I understand" is on, like the battery notice in AccountsScreen. Tor runs
 * inside FluidMessages (BuiltInTor) unless "Use the Orbot app instead" is on.
 */
internal object TorSettingsPage {
    @JvmStatic
    fun summary(prefs: SharedPreferences): String =
        if (TorRoute.on(prefs)) "On · chat services, FluidMessages Web, previews, and updates go through Tor"
        else "Off · hide your internet address from chat servers. Slower."

    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, changed: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { AppTheme(remember { Appearance.read(prefs) }) { Page(prefs) { changed.run() } } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    @Composable
    private fun Page(prefs: SharedPreferences, changed: () -> Unit) {
        val context = LocalContext.current
        val scheme = MaterialTheme.colorScheme
        var on by remember { mutableStateOf(TorRoute.on(prefs)) }
        var asking by remember { mutableStateOf(false) }
        var calls by remember { mutableStateOf(TorRoute.callsDirect(prefs)) }
        var orbotApp by remember { mutableStateOf(TorRoute.usesOrbot(prefs)) }
        var now by remember { mutableStateOf<TorRoute.Now?>(null) }
        val status = now?.state ?: if (on) TorRoute.Status.WAITING else TorRoute.Status.OFF
        var installed by remember { mutableStateOf(TorRoute.orbotInstalled(context)) }
        // Orbot says when Tor is starting, on, or off; the SOCKS port is checked every few seconds too.
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // Tor inside the app says the same words, for this app only; only Orbot's count here.
                    if (intent.getStringExtra(BuiltInTor.EXTRA_SERVICE_PACKAGE_NAME) == context.packageName) return
                    TorRoute.orbot = intent.getStringExtra(TorRoute.ORBOT_STATUS_EXTRA).orEmpty()
                }
            }
            context.registerReceiver(receiver, IntentFilter(TorRoute.ORBOT_STATUS), Context.RECEIVER_EXPORTED)
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
        LaunchedEffect(on, orbotApp) {
            if (on && orbotApp) TorRoute.askOrbot(context)
            while (true) {
                now = withContext(Dispatchers.IO) { TorRoute.status(context) }
                installed = TorRoute.orbotInstalled(context)
                // Faster while Tor starts, so "Starting Tor… 45%" keeps up.
                delay(if (now?.state == TorRoute.Status.STARTING) 700 else 3000)
            }
        }
        fun open(link: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                PreferencesCard("Tor") {
                    SettingsSwitch("Connect through Tor", if (on) now?.words ?: TorRoute.words(status) else
                        "Off · Each chat's details can still send its own connection through Tor", on || asking) { want ->
                        if (want) asking = true
                        else {
                            asking = false
                            on = false
                            TorRoute.set(prefs, false)
                            changed()
                        }
                    }
                    // The warning opens here, under the switch, instead of in a pop-up.
                    if (asking && !on) Consent(orbotApp, { asking = false }) {
                        asking = false
                        on = true
                        TorRoute.set(prefs, true)
                        TorRoute.start(context)
                        changed()
                    }
                    if (!asking) Text(TorRoute.WARNING, Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp),
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (on && !orbotApp && status == TorRoute.Status.CANT_START) item {
                PreferencesCard("Tor couldn't start") {
                    PreferenceText(TorRoute.words(status), BuiltInTor.problem().ifEmpty { "Chat services wait instead of connecting without Tor." })
                    Row(Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppButton(onClick = { TorRoute.start(context) }) { Text("Try again") }
                        OutlinedButton(onClick = { orbotApp = true; TorRoute.setOrbot(prefs, true); changed() }) { Text("Use Orbot instead") }
                    }
                }
            }
            if (on && orbotApp && (status == TorRoute.Status.NO_ORBOT || !installed)) item {
                PreferencesCard("Install Orbot") {
                    PreferenceText("FluidMessages uses Orbot to run Tor", "Orbot is free and made by the Guardian Project. Install it, open " +
                        "it, and tap Start. Until Tor is running, chat services and FluidMessages Web wait instead of connecting without Tor.")
                    Row(Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppButton(onClick = { open(TorRoute.ORBOT_FDROID) }) { Text("Get Orbot on F-Droid") }
                        OutlinedButton(onClick = { open(TorRoute.ORBOT_GUARDIAN) }) { Text("Guardian Project") }
                    }
                }
            } else if (on && orbotApp && status != TorRoute.Status.CONNECTED) item {
                PreferencesCard("Start Tor") {
                    PreferenceText(TorRoute.words(status), "Open Orbot and tap Start. Chat services connect once Tor is running.")
                    Row(Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp)) {
                        AppButton(onClick = {
                            TorRoute.askOrbot(context)
                            context.packageManager.getLaunchIntentForPackage(TorRoute.ORBOT)?.let { runCatching { context.startActivity(it) } }
                        }) { Text("Open Orbot") }
                    }
                }
            }
            item {
                PreferencesCard("Where Tor runs") {
                    SettingsSwitch("Use the Orbot app instead", if (orbotApp) "On: FluidMessages uses Tor in Orbot (SOCKS port 9050, " +
                        "HTTP port 8118 for email). Orbot has to be running." else "Off: Tor runs inside FluidMessages. It starts when " +
                        "a connection needs it and stops a while after nothing does, unless a Stay connected connection uses it.",
                        orbotApp) {
                        orbotApp = it
                        TorRoute.setOrbot(prefs, it)
                        if (!it && on) TorRoute.start(context)
                        changed()
                    }
                }
            }
            item {
                PreferencesCard("What goes through Tor") {
                    PreferenceText("Through Tor", "Matrix, XMPP, IRC, and email connected from this phone. FluidMessages Web on the " +
                        "internet. Link previews, pictures from links, GIF search, Lichess games, and update checks. SFTP and WebDAV backups.")
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    PreferenceText("Never through Tor", "Texts (SMS and MMS) and calls over your phone plan. FluidMessages Web and file " +
                        "servers on your home network, which connect directly and stay on your network. FTP backups to the internet and " +
                        "GIF sites (a web page) are off while Tor is on.")
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    PreferenceText("One chat at a time", "A chat's details have Connect through Tor for its connection: one IRC network, " +
                        "one Matrix or XMPP account, or one email account. It also applies to the other chats there. Chats going " +
                        "through Tor show an onion beside their other marks.")
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    PreferenceText("FluidMessages Web", "It has its own Connect through Tor setting, for the connections your computer makes.")
                }
            }
            item {
                PreferencesCard("Internet calls") {
                    SettingsSwitch("Allow calls without Tor", if (calls) "On: Matrix, XMPP, and SIP calls connect directly, so the " +
                        "other person and the call server can see your internet address."
                        else "Off: Matrix, XMPP, and SIP calls are turned off while Tor is on, because calls can't go through Tor. " +
                            "This keeps your internet address hidden. Phone calls still work.", calls) {
                        calls = it
                        prefs.edit().putBoolean(TorRoute.CALLS_KEY, it).apply()
                        changed()
                    }
                }
            }
        }
    }

    /** The warning, in place. "Turn on" stays off until "I understand" is on. */
    @Composable
    private fun Consent(orbotApp: Boolean, cancel: () -> Unit, accept: () -> Unit) {
        var understood by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, bottom = 15.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect through Tor?", style = MaterialTheme.typography.titleMedium)
            Text(TorRoute.WARNING)
            Text((if (orbotApp) "Tor runs in the Orbot app, which you install and start. " else "Tor runs inside FluidMessages. " +
                "The first connection takes a little while as Tor starts. ") + "If Tor isn't ready, FluidMessages waits instead " +
                "of connecting without it. Internet calls can't use Tor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsSwitch("I understand it's slower and less reliable", "", understood) { understood = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = cancel) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                AppButton(enabled = understood, onClick = accept) { Text("Turn on") }
            }
        }
    }
}
