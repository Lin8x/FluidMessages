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
 * in place, and "Turn on" stays off until "I understand" is on, like the battery notice in AccountsScreen.
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
        var status by remember { mutableStateOf(if (on) TorRoute.Status.CONNECTING else TorRoute.Status.OFF) }
        var installed by remember { mutableStateOf(TorRoute.orbotInstalled(context)) }
        // Orbot says when Tor is starting, on, or off; the SOCKS port is checked every few seconds too.
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    TorRoute.orbot = intent.getStringExtra(TorRoute.ORBOT_STATUS_EXTRA).orEmpty()
                }
            }
            context.registerReceiver(receiver, IntentFilter(TorRoute.ORBOT_STATUS), Context.RECEIVER_EXPORTED)
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
        LaunchedEffect(on) {
            if (on) TorRoute.askOrbot(context)
            while (true) {
                val next = withContext(Dispatchers.IO) { TorRoute.status(context) }
                status = next
                installed = TorRoute.orbotInstalled(context)
                delay(3000)
            }
        }
        fun open(link: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                PreferencesCard("Tor") {
                    SettingsSwitch("Connect through Tor", if (on) TorRoute.words(status) else "Off", on || asking) { want ->
                        if (want) asking = true
                        else {
                            asking = false
                            on = false
                            TorRoute.set(prefs, false)
                            changed()
                        }
                    }
                    // The warning opens here, under the switch, instead of in a pop-up.
                    if (asking && !on) Consent({ asking = false }) {
                        asking = false
                        on = true
                        TorRoute.set(prefs, true)
                        changed()
                    }
                    if (!asking) Text(TorRoute.WARNING, Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp),
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (on && (status == TorRoute.Status.NO_ORBOT || !installed)) item {
                PreferencesCard("Install Orbot") {
                    PreferenceText("FluidMessages uses Orbot to run Tor", "Orbot is free and made by the Guardian Project. Install it, open " +
                        "it, and tap Start. Until Tor is running, chat services and FluidMessages Web wait instead of connecting without Tor.")
                    Row(Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppButton(onClick = { open(TorRoute.ORBOT_FDROID) }) { Text("Get Orbot on F-Droid") }
                        OutlinedButton(onClick = { open(TorRoute.ORBOT_GUARDIAN) }) { Text("Guardian Project") }
                    }
                }
            } else if (on && status != TorRoute.Status.CONNECTED) item {
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
                PreferencesCard("What goes through Tor") {
                    PreferenceText("Through Tor", "Matrix, XMPP, IRC, and email connected from this phone. FluidMessages Web on the " +
                        "internet. Link previews, pictures from links, GIF search, Lichess games, and update checks. SFTP and WebDAV backups.")
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    PreferenceText("Never through Tor", "Texts (SMS and MMS) and calls over your phone plan. FluidMessages Web and file " +
                        "servers on your home network, which connect directly and stay on your network. FTP backups to the internet and " +
                        "GIF sites (a web page) are off while Tor is on.")
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
    private fun Consent(cancel: () -> Unit, accept: () -> Unit) {
        var understood by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, bottom = 15.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect through Tor?", style = MaterialTheme.typography.titleMedium)
            Text(TorRoute.WARNING)
            Text("Tor runs in the Orbot app, which you install and start. If Tor isn't running, FluidMessages waits instead of " +
                "connecting without it. Internet calls can't use Tor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsSwitch("I understand it's slower and less reliable", "", understood) { understood = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = cancel) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                AppButton(enabled = understood, onClick = accept) { Text("Turn on") }
            }
        }
    }
}
