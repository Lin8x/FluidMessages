package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.IntConsumer

object OnboardingScreen {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, step: Int, next: Runnable, back: Runnable,
              skip: Runnable, sample: Runnable, inbox: Runnable, recolor: IntConsumer, accounts: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            var appearance by remember { mutableStateOf(Appearance.read(prefs)) }
            fun setting(key: String, value: Boolean) {
                prefs.edit().putBoolean(key, value).apply()
                appearance = Appearance.read(prefs)
            }
            SideEffect { recolor.accept(appearance.canvas) }
            AppTheme(appearance) {
                val scheme = MaterialTheme.colorScheme
                Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        if (step > 0) TextButton(onClick = back::run) { Text("Back") }
                        else Text("FluidMessages", color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
                        if (step < steps.lastIndex) TextButton(onClick = skip::run) { Text("Skip setup") }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .semantics { contentDescription = "Step ${step + 1} of ${steps.size}" },
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (index in steps.indices) {
                            Box(Modifier.weight(1f).height(4.dp)
                                .background(if (index <= step) scheme.primary else scheme.surfaceVariant, RoundedCornerShape(4.dp)))
                        }
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        val (title, subtitle) = steps[step]
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(title, fontSize = 32.sp, lineHeight = 36.sp, color = scheme.onSurface,
                                    fontWeight = FontWeight.Bold)
                                Text(subtitle, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        when (step) {
                            0 -> item { SampleConversation(appearance) }
                            1 -> {
                                item { SampleConversation(appearance) }
                                item {
                                    ThemeDropdown("Color theme", appearance.theme.name,
                                        MessageStyle.Theme.values().map { Choice(it.name, it.label) }, appearance.speed) {
                                        prefs.edit().apply { selectBuiltInTheme(it) }.apply()
                                        appearance = Appearance.read(prefs)
                                    }
                                }
                                item {
                                    ThemeDropdown("Appearance mode", if (appearance.dark) "dark" else "light",
                                        listOf(Choice("light", "Light"), Choice("dark", "Dark")), appearance.speed) {
                                        setting("dark", it == "dark")
                                    }
                                }
                                item {
                                    ThemeDropdown("SMS/RCS color mapping", if (appearance.classic) "classic" else "theme",
                                        listOf(Choice("theme", "Theme colors"), Choice("classic", "Green SMS · Blue RCS")), appearance.speed) {
                                        MessageStyle.preset(prefs, it == "classic")
                                        appearance = Appearance.read(prefs)
                                    }
                                    if (appearance.classic) Text("Automated uses purple so it stays distinct.", color = scheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            2 -> {
                                item {
                                    ThemeDropdown("Motion preset", appearance.speed.toString(),
                                        listOf(Choice("0", "Off"), Choice("130", "Quick"), Choice("220", "Standard"),
                                            Choice("360", "Relaxed")), appearance.speed,
                                        fallback = "Custom · ${appearance.speed} ms") {
                                        prefs.edit().putInt("speed", it.toInt()).apply()
                                        appearance = Appearance.read(prefs)
                                    }
                                }
                                item { FeedbackOptions(prefs, appearance.speed) }
                            }
                            3 -> item {
                                SetupOptions(listOf("Export or restore a file" to "Choose SMS, themes, settings, wallpaper photos, or any combination.",
                                    "Back up on a schedule" to "Use a device folder or a writable folder from a compatible storage app."))
                            }
                            4 -> item {
                                val paired = CloudBackup.paired(prefs.getString("server", "").orEmpty(),
                                    prefs.getString("token", "").orEmpty())
                                SetupOptions(listOf("App lock" to "Use your phone’s screen lock to protect the inbox.",
                                    "Web companion" to if (paired) "Set up" else "Not set up",
                                    "Notifications" to "Message text stays out of notification previews."))
                            }
                            5 -> {
                                item {
                                    PreferencesCard("Text messages") {
                                        PreferenceText("RCS, SMS, and MMS", "One conversation per person. RCS is a preview; SMS switches to MMS for files and groups.")
                                    }
                                }
                                item {
                                    PreferencesCard("Internet protocols") {
                                        for (protocol in listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)) {
                                            ProtocolSwitch(prefs, protocol.key(), protocol.label, when (protocol) {
                                                MessageProtocol.MATRIX -> "Rooms and people on any Matrix homeserver"
                                                MessageProtocol.XMPP -> "Jabber and other XMPP chat services"
                                                MessageProtocol.EMAIL -> "Only conversations you choose, never your whole mailbox"
                                                else -> "Channels and direct messages, over TLS"
                                            }, true)
                                        }
                                    }
                                }
                                item {
                                    val paired = CloudBackup.paired(prefs.getString("server", "").orEmpty(),
                                        prefs.getString("token", "").orEmpty())
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = accounts::run, modifier = Modifier.fillMaxWidth()) { Text("Set up accounts") }
                                        Text(if (paired) "Your web companion keeps these connections open, so your phone doesn’t have to."
                                            else "For the best battery life, pair a web companion to keep these connections open. You can also connect an account from this phone.",
                                            color = scheme.onSurfaceVariant)
                                    }
                                }
                            }
                            6 -> item {
                                SetupOptions(listOf("Sample inbox" to "Try direct chats and a group chat with fictional contacts.",
                                    "Your SMS inbox" to "Choose FluidMessages as the default SMS app when you’re ready."))
                            }
                        }
                    }
                    if (step == steps.lastIndex) {
                        AppButton(onClick = sample::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) {
                            Text("Open sample inbox")
                        }
                        TextButton(onClick = inbox::run, modifier = Modifier.fillMaxWidth()) { Text("Open my inbox") }
                    } else AppButton(onClick = next::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) { Text("Continue") }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private val steps = listOf(
        "Make it yours" to "Welcome to FluidMessages. Start with a look you like and try the sample inbox before connecting SMS.",
        "Pick your colors" to "Choose a preset and preview your bubbles. You can fine-tune colors and wallpapers any time.",
        "Motion, sound and haptics" to "Decide how a message should feel when it lands or leaves.",
        "Keep a backup" to "Save just the items you need in one file. Backup encryption starts on.",
        "Privacy and the web" to "Choose where your messages go and how you access them.",
        "Choose your protocols" to "Text messages work right away. Turn on the internet protocols you use, and add accounts when you’re ready.",
        "Ready to text" to "Start with a practice conversation or open your inbox. Carrier MMS and RCS are still in development.",
    )
}

@Composable
private fun SetupOptions(entries: List<Pair<String, String>>) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(18.dp))) {
        entries.forEachIndexed { index, (title, detail) ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
            PreferenceText(title, detail)
        }
    }
}
