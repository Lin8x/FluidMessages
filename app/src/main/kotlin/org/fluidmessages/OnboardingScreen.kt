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
              skip: Runnable, sample: Runnable, inbox: Runnable, recolor: IntConsumer, accounts: Runnable,
              web: Runnable, learnWeb: Runnable, backups: Runnable) {
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
                        val (key, title, subtitle) = steps[step]
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(title, fontSize = 32.sp, lineHeight = 36.sp, color = scheme.onSurface,
                                    fontWeight = FontWeight.Bold)
                                Text(subtitle, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        when (key) {
                            "welcome" -> item { SampleConversation(appearance) }
                            "tabs" -> {
                                item { TabsIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.PHONE, "Phone", "Calls, favorites, contacts, keypad"),
                                        Tip(TipIcon.CHAT, "Messages", "Every chat in one place"),
                                        Tip(TipIcon.PRACTICE, "Practice first", "Settings → Help → Sample inbox")), appearance.speed)
                                }
                            }
                            "ways" -> {
                                item { WaysIllustration(appearance) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.CHAT, "Texts work right away", "Using your phone plan"),
                                        Tip(TipIcon.TAG, "Tags show how", "SMS, Matrix, Email, and more"),
                                        Tip(TipIcon.PEOPLE, "More ways? We ask", "Pick how to reach someone each time")), appearance.speed)
                                }
                            }
                            "locks" -> {
                                item { LocksIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.LOCK, "Green", "Only you and them can read it"),
                                        Tip(TipIcon.LOCK, "Yellow", "Protected on the way, but the service can read it"),
                                        Tip(TipIcon.LOCK, "Red, open", "Not encrypted, like regular texts"),
                                        Tip(TipIcon.CHAT, "Where to look", "Next to the name at the top of a chat. Tap it to learn more.")), appearance.speed)
                                }
                            }
                            "protect" -> {
                                item { PrivacyToolsIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.FINGER, "App lock", "Settings → Privacy and security"),
                                        Tip(TipIcon.LOCK, "Lock one chat", "A chat’s ⋯ menu → Lock this chat"),
                                        Tip(TipIcon.FOLDER, "Private folder", "Inbox filter menu → Private folder"),
                                        Tip(TipIcon.MASK, "Decoy mode", "Shows pretend chats if someone else has your phone")), appearance.speed)
                                }
                            }
                            "disappear" -> {
                                item { DisappearingIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.TIMER, "For one chat", "A chat’s ⋯ menu → Disappearing messages"),
                                        Tip(TipIcon.OPTIONAL, "For every chat", "Settings → Privacy and security"),
                                        Tip(TipIcon.CHAT, "Older messages stay", "Only new messages disappear")), appearance.speed)
                                }
                            }
                            "calls" -> {
                                item { CallsIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.PHONE, "Phone calls", "Use your phone number, from the Phone tab"),
                                        Tip(TipIcon.PEOPLE, "Internet calls", "Voice and video with Matrix contacts"),
                                        Tip(TipIcon.OPTIONAL, "Meeting links", "Optional: Settings → Chats and calls → Calls")), appearance.speed)
                                }
                            }
                            "computer" -> {
                                item { ComputerIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.LAPTOP, "Set it up", "Connect this phone to FluidMessages Web") { web.run() },
                                        Tip(TipIcon.PRACTICE, "How it works", "Step-by-step setup guide, opens in your browser") { learnWeb.run() },
                                        Tip(TipIcon.OPTIONAL, "Not needed", "Your phone works on its own. Skip this if you like.")), appearance.speed)
                                }
                            }
                            "colors" -> {
                                item { ColorsIllustration(appearance) }
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
                            "motion" -> {
                                item { MotionIllustration(appearance.speed) }
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
                            "backup" -> {
                                item { BackupIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.FOLDER, "Save a backup file", "Pick exactly what to include") { backups.run() },
                                        Tip(TipIcon.TIMER, "Back up automatically", "On a schedule, to a folder you choose"),
                                        Tip(TipIcon.LAPTOP, "Or to the cloud", "OneDrive, Google Drive, or S3, through FluidMessages Web"),
                                        Tip(TipIcon.LOCK, "Password-protected", "Only you can open your backups")), appearance.speed)
                                }
                            }
                            "services" -> {
                                item {
                                    val on = remember(appearance) {
                                        listOf(MessageProtocol.SMS, MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)
                                            .filter { it.enabled(prefs) }.map { if (it == MessageProtocol.SMS) "SMS" else it.label }.toSet()
                                    }
                                    ServicesIllustration(appearance.speed, on)
                                }
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
                                        Privacy.token(prefs))
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = accounts::run, modifier = Modifier.fillMaxWidth()) { Text("Set up accounts") }
                                        Text(if (paired) "FluidMessages Web keeps these connections open, so your phone doesn’t have to."
                                            else "For the best battery life, pair FluidMessages Web to keep these connections open. You can also connect an account from this phone.",
                                            color = scheme.onSurfaceVariant)
                                    }
                                }
                            }
                            "ready" -> {
                                item { ReadyIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.PRACTICE, "Practice first", "Pretend chats and calls, nothing is sent") { sample.run() },
                                        Tip(TipIcon.CHAT, "Make it your texting app", "In your inbox, tap Set as default SMS app"),
                                        Tip(TipIcon.OPTIONAL, "Come back anytime", "Settings → Help → Setup guide")), appearance.speed)
                                }
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
        Triple("welcome", "Welcome", "Your texts, calls, and chats, all in one place."),
        Triple("tabs", "Two tabs", "Phone on the left. Messages on the right."),
        Triple("ways", "One inbox", "Texts and other chat apps, side by side."),
        Triple("locks", "Who can read it?", "Every chat shows a small lock."),
        Triple("protect", "Keep chats private", "Lock the app, a chat, or tuck chats away."),
        Triple("disappear", "Disappearing messages", "Messages can remove themselves after a while."),
        Triple("calls", "Calls", "Call from the Phone tab, or from a chat."),
        Triple("colors", "Pick your colors", "Choose a look and preview your bubbles. You can change it any time."),
        Triple("motion", "Motion, sound and haptics", "Decide how a message should feel when it arrives or sends."),
        Triple("backup", "Keep a backup", "Save your messages and settings to a file, so you don’t lose them if you change phones. Backups are password-protected."),
        Triple("computer", "On a computer too", "Optional. Set it up later if you like."),
        Triple("services", "Choose your services", "Texts work right away. Turn on any other chat services you use, and sign in when you’re ready."),
        Triple("ready", "You’re ready", "Practice with pretend chats first, or open your real inbox. RCS (the newer texting standard) isn’t available in FluidMessages yet."),
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
