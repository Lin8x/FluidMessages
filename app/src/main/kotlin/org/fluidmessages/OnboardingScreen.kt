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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.IntConsumer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

object OnboardingScreen {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, shownStep: Int, next: Runnable, back: Runnable,
              skip: Runnable, sample: Runnable, inbox: Runnable, recolor: IntConsumer, accounts: Runnable,
              web: Runnable, learnWeb: Runnable, backups: Runnable,
              allowNotifications: Runnable, allowTexting: Runnable) {
        // A step saved by an older version with a different number of steps still opens a real step.
        val step = shownStep.coerceIn(0, steps.lastIndex)
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
                            // An ordinary chat, not the color preview: nothing here a new user has to know yet.
                            "welcome" -> {
                                item { WelcomeConversation(appearance) }
                                item { Tips(WelcomeWords.tips, appearance.speed) }
                            }
                            "texting" -> item { TextingStepCard(allowTexting) }
                            "permissions" -> item { PermissionsCard("Allow now", basicPermissions, allowNotifications) }
                            "later" -> item { PermissionsCard("Now or later", laterPermissions, allowNotifications) }
                            "tabs" -> {
                                item { TabsIllustration(appearance.speed) }
                                item { NewContactShowcase(appearance) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.PHONE, "Phone", "Calls, favorites, contacts, keypad"),
                                        Tip(TipIcon.PEOPLE, "Add contacts here", "Phone → Contacts → +. They save to your phone’s contacts, so other apps see them too"),
                                        Tip(TipIcon.CHAT, "Messages", "Every chat in one place"),
                                        Tip(TipIcon.PRACTICE, "Practice first", "Settings → Help → Sample inbox")), appearance.speed)
                                }
                            }
                            "ways" -> {
                                item { WaysIllustration(appearance) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.CHAT, "Texts use your phone plan", "Once FluidMessages is your texting app"),
                                        Tip(TipIcon.TAG, "Tags show how", "A small tag on each chat: text, email, and more"),
                                        Tip(TipIcon.PEOPLE, "More ways? We ask", "Pick how to reach someone each time")), appearance.speed)
                                }
                                // The first page that names a chat service, so it says what one is.
                                item { Text(WelcomeWords.SERVICES, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                            }
                            "organized" -> {
                                item { StayOrganizedShowcase(appearance) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.TAG, "Filters", "The filter button at the top corner: Unread, Favorites, Groups, Direct messages, and each service"),
                                        Tip(TipIcon.OPTIONAL, "Pick your filters", "Settings → Messaging → Inbox filters, in any order"),
                                        Tip(TipIcon.TIMER, "Mute for a while", "Touch and hold a chat → Mute. The bell shows until when, then it unmutes by itself"),
                                        Tip(TipIcon.FOLDER, "Mute a whole server", "A server’s ⋯ menu in Communities")), appearance.speed)
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
                                        Tip(TipIcon.LOCK, "Lock or hide a chat", "A chat’s ⋯ menu → Lock this chat, or Move to private folder"),
                                        Tip(TipIcon.MASK, "Decoy mode", "Shows pretend chats if someone else has your phone"),
                                        Tip(TipIcon.ONION, "Hide where you connect from", "Connect through Tor: Settings → Privacy and security")), appearance.speed)
                                }
                            }
                            "disappear" -> {
                                item { DisappearingIllustration(appearance.speed) }
                                item { SettingNoticeShowcase(appearance) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.TIMER, "For one chat", "A chat’s ⋯ menu → Disappearing messages"),
                                        Tip(TipIcon.OPTIONAL, "For every chat", "Settings → Privacy and security"),
                                        Tip(TipIcon.CHAT, "Older messages stay", "Only new messages disappear"),
                                        Tip(TipIcon.PEOPLE, "They’re told", "Changing the timer or encryption sends a short note. Turn it off with Announce chat setting changes")), appearance.speed)
                                }
                            }
                            "calls" -> {
                                item { CallsIllustration(appearance.speed) }
                                item { CallCards(prefs, appearance) }
                                item {
                                    Text("Change these any time in Settings → Accounts and calls → Calls. Calls start from the Phone tab or a chat’s call button.",
                                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            "more" -> {
                                item { MoreThanTextShowcase(appearance) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.CHAT, "The + button", "Photos, polls, events, money, games, dice, GIFs, and effects"),
                                        Tip(TipIcon.PEOPLE, "Works with everyone", "People on other apps get readable text and can reply to vote, RSVP, or play"),
                                        Tip(TipIcon.PRACTICE, "Edit photos", "Crop, draw, add text, or remove the background before you send"),
                                        Tip(TipIcon.TIMER, "Voice messages", "Tap the mic with nothing typed. Tap Show transcript to read one, made on your phone")), appearance.speed)
                                }
                            }
                            "gestures" -> {
                                item { QuickMovesShowcase(appearance) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.CHAT, "Swipe a message right", "Reply to it"),
                                        Tip(TipIcon.FINGER, "Touch and hold a message", "React, edit, forward, copy, or replay its effect"),
                                        Tip(TipIcon.MASK, "Spoilers", "Write ||secret|| and it stays hidden until tapped"),
                                        Tip(TipIcon.TAG, "Formatting", "*bold*, _italic_, ~strike~, and `code`")), appearance.speed)
                                }
                            }
                            "computer" -> {
                                item { LinkWebShowcase(appearance) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.LAPTOP, "Set it up", "Connect this phone to FluidMessages Web") { web.run() },
                                        Tip(TipIcon.PRACTICE, "Choose what to keep", "If both changed a setting, pick the phone’s or the web’s, one by one or all at once"),
                                        Tip(TipIcon.CHAT, "How it works", "Step-by-step setup guide, opens in your browser") { learnWeb.run() },
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
                                // Motion is part of the look, as in Settings → Themes and appearance. Off shows still pictures here too.
                                item {
                                    ThemeDropdown("Motion preset", appearance.speed.toString(),
                                        listOf(Choice("0", "Off"), Choice("130", "Quick"), Choice("220", "Standard"),
                                            Choice("360", "Relaxed")), appearance.speed,
                                        fallback = "Custom · ${appearance.speed} ms") {
                                        prefs.edit().putInt("speed", it.toInt()).apply()
                                        appearance = Appearance.read(prefs)
                                    }
                                }
                            }
                            "sounds" -> {
                                item { NotificationsShowcase(appearance) }
                                item { NotificationsCard(allowNotifications) }
                                item {
                                    PreferencesCard("Sounds and haptics") {
                                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            ToneDropdown(prefs, receive = true, appearance.speed)
                                            FeedbackOptions(prefs, appearance.speed)
                                        }
                                    }
                                }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.TIMER, "Quiet for a while", "Mute a chat or a whole server, and its notifications wait"),
                                        Tip(TipIcon.OPTIONAL, "Change it later", "Settings → Sounds and notifications")), appearance.speed)
                                }
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
                                item { ChatServiceCards(prefs, appearance) }
                                item {
                                    val paired = CloudBackup.paired(prefs.getString("server", "").orEmpty(),
                                        Privacy.token(prefs))
                                    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                                        OutlinedButton(onClick = accounts::run, modifier = Modifier.fillMaxWidth()) { Text("Set up accounts") }
                                        // Turning a service on costs nothing; only a signed-in account connects (ServiceBattery).
                                        ServiceBatteryCard(paired)
                                    }
                                }
                            }
                            "ready" -> {
                                item { ReadyIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.PRACTICE, "Practice first", "Pretend chats and calls, nothing is sent") { sample.run() },
                                        Tip(TipIcon.CHAT, "Texting", "On once FluidMessages is your texting app. Change it in Settings → Chat services"),
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

    private val steps get() = onboardingSteps

    /** The key of the step at [index], saved with the index so a returning user lands on the same page. */
    @JvmStatic
    fun stepKey(index: Int): String = steps.getOrNull(index)?.first.orEmpty()

    /** Where to reopen setup: the step saved as [key], else [index]. See [onboardingStepIndex]. */
    @JvmStatic
    fun stepIndex(key: String?, index: Int): Int = onboardingStepIndex(key, index)
}

/** The first page's tips, and what a chat service is, said where one is first named. Matches server/welcome.js. */
internal object WelcomeWords {
    const val SERVICES = "Chat services are other ways to message people over the internet, like Matrix or Email. You don’t need any to start."
    val tips = listOf(
        Tip(TipIcon.CHAT, "Text like you always do", "Your texts and calls, in one simple app"),
        Tip(TipIcon.PEOPLE, "Reach friends on other chat apps too", "We’ll show you how in a minute"),
        Tip(TipIcon.PRACTICE, "Practice with pretend chats first", "Nothing in them is sent"))
}

/** Steps taken out of setup, and the step that came after each, so a saved one reopens where it would have gone next. */
internal val removedOnboardingSteps = mapOf("communities" to "organized")

/**
 * The step to reopen: the one saved as [key] (or, for a step since removed, the one after it), so the saved setup
 * step still lines up when steps were added or removed. Without a known key, [index] within the steps.
 */
internal fun onboardingStepIndex(key: String?, index: Int): Int {
    val keys = onboardingSteps.map { it.first }
    val found = keys.indexOf(removedOnboardingSteps[key] ?: key)
    return if (found >= 0) found else index.coerceIn(0, keys.lastIndex)
}

/**
 * The setup guide's pages, in order: (key, title, subtitle). Titles match FluidMessages Web's setup guide
 * (server/welcome.js) where both have the page, so the two read the same.
 */
internal val onboardingSteps: List<Triple<String, String, String>> = listOf(
    Triple("welcome", "Welcome", "Your texts, calls, and chats, all in one place."),
    Triple("texting", TextingGate.TITLE, TextingGate.WHY),
    Triple("permissions", "Allow the basics", "Tap Allow for each one you want. You can skip any of them and turn it on later."),
    Triple("later", "Ask now or later", "Each of these is for one feature. Allow them now, or FluidMessages asks the first time you use that feature."),
    Triple("tabs", "Two tabs", "Phone on the left. Messages on the right."),
    Triple("ways", "One inbox", "Texts and other chat apps, side by side."),
    Triple("organized", "Stay organized", "Filters, unread, and mutes keep a busy inbox calm."),
    Triple("locks", "Who can read it?", "Every chat shows a small lock."),
    Triple("protect", "Keep chats private", "Lock the app, a chat, or tuck chats away."),
    Triple("disappear", "Disappearing messages", "Messages can remove themselves after a while."),
    Triple("more", "More than text", "Polls, events, games, money, and more, right in a chat."),
    Triple("gestures", "Quick moves", "A few gestures that make chatting faster."),
    Triple("colors", "Pick your colors", "Choose a look and preview your bubbles. You can change it any time."),
    Triple("sounds", "Sounds and notifications", "Hear and see new messages, even when FluidMessages is closed."),
    Triple("backup", "Keep a backup", "Save your messages and settings to a file, so you don’t lose them if you change phones. Backups are password-protected."),
    Triple("computer", "On a computer too", "Optional. Link FluidMessages Web to read and reply from a browser. Set it up later if you like."),
    Triple("services", "Chat services", "Turn on the chat services you use, and sign in when you’re ready."),
    Triple("calls", "Calls", "Call from the Phone tab or from a chat. Turn on the ways to call you want."),
    Triple("ready", "You’re ready", "Practice with pretend chats first, or open your real inbox. RCS (the newer texting standard) isn’t available in FluidMessages yet."),
)

/**
 * Notifications in the setup guide: whether Android shows them, Allow notifications (Android's prompt), and a real
 * test notification. The status is read again every moment, so it follows Android's prompt and settings.
 */
@Composable
private fun NotificationsCard(allow: Runnable) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var status by remember { mutableStateOf(SmsDelivery.status(context)) }
    var sent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(1_200); status = SmsDelivery.status(context) } }
    PreferencesCard("Notifications") {
        PreferenceText(status.label, status.detail)
        Column(Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Android 13 and later ask for POST_NOTIFICATIONS; after a refusal this opens the app's notification settings.
            if (!status.allowed) AppButton(onClick = allow::run, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                Text("Allow notifications")
            }
            OutlinedButton(onClick = {
                sent = if (SmsDelivery.notifyTest(context)) "Sent. Pull down the notification shade to see it."
                    else "Not shown: notifications are blocked. Tap Allow notifications first."
            }, modifier = Modifier.fillMaxWidth()) { Text("Send a test notification") }
            Text(sent ?: "Posts a made-up message the way a real one arrives, with the same sound and lock screen setting.",
                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
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
