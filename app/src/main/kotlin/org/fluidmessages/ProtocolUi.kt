package org.fluidmessages

import android.content.SharedPreferences
import android.net.Uri
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import java.util.function.Consumer

internal data class ChatRouting @JvmOverloads constructor(
    val active: MessageProtocol,
    val formats: List<MessageProtocol> = listOf(active),
    val combined: Boolean = false,
    val switch: Consumer<MessageProtocol>? = null,
    val setDefault: Consumer<MessageProtocol>? = null,
    val unavailable: String = "",
    val attachments: Boolean = false,
    /** The contact's saved default, when it is a send choice inside this conversation (SMS instead of RCS). */
    val preferredVia: MessageProtocol? = null,
    /** This conversation's encryption when it differs from the protocol's usual, such as an encrypted Matrix room. */
    val encryption: Encryption? = null,
    /** "from alice@example.org" when the service has more than one account (AccountKeys.via), for the header. */
    val account: String = "",
    /** The address this chat sends from, shown as "From: …" above the composer; "" with one account (FromAccount). */
    val from: String = "",
    /** Opens New message, to write from another account. */
    val newFromAnother: Runnable? = null,
)

data class GroupMember @JvmOverloads constructor(
    val name: String, val address: String = "", val photo: Uri? = null, val open: Runnable? = null,
    val photoRes: Int = 0,
) {
    fun initials(): String = initialsOf(name)
}

internal fun initialsOf(name: String): String =
    name.split(' ', '-', '_', '.').mapNotNull { word -> word.firstOrNull { it.isLetter() }?.uppercaseChar() }.take(2)
        .joinToString("").ifEmpty { "?" }

/** Two overlapping member photos, the usual way messaging apps show a group without its own picture. */
@Composable
internal fun GroupAvatar(members: List<GroupMember>, size: Int, ring: Color) {
    val small = (size * .68f).toInt()
    Box(Modifier.size(size.dp).semantics { contentDescription = members.joinToString { it.name } }) {
        members.getOrNull(1)?.let { Box(Modifier.align(Alignment.TopEnd)) { Avatar(it.initials(), it.name, small, it.photoRes, it.photo) } }
        members.getOrNull(0)?.let {
            Box(Modifier.align(Alignment.BottomStart).border(2.dp, ring, CircleShape).padding(2.dp)) {
                Avatar(it.initials(), it.name, small - 4, it.photoRes, it.photo)
            }
        }
    }
}

/** Inbox tags: small outlined labels in each protocol's color, shaded so they stay readable on the inbox background. */
@Composable
internal fun ProtocolTags(thread: ThreadRow, appearance: Appearance, mutedColor: Color = Color.Gray) {
    val labels = thread.protocol.ifBlank { "SMS" }.split(" · ")
    Row(Modifier.padding(top = 3.dp, bottom = 1.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (label in labels) {
            val format = MessageProtocol.values().firstOrNull { it.label == label }
            val automated = thread.automated && (format == null || format == MessageProtocol.SMS || format == MessageProtocol.MMS)
            val base = if (automated) Color(appearance.automated) else format?.tint(appearance) ?: Color(appearance.accent)
            val ink = if (appearance.dark) base else accessibleBubbleFill(base)
            Text(if (automated) "Automated" else label, Modifier.border(1.dp, ink.copy(alpha = .75f), RoundedCornerShape(5.dp))
                .padding(horizontal = 5.dp), color = ink, fontSize = 10.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
        // Muted: the bell with a slash, right after the tags.
        if (thread.muted.isNotEmpty()) MutedMark(thread.muted, mutedColor, Modifier.align(Alignment.CenterVertically))
        if (thread.account.isNotEmpty()) AccountHint(thread.account, mutedColor, Modifier.align(Alignment.CenterVertically))
    }
}

/** The inbox's tiny account hint, "from alice@work.example.org", for a chat on another account than the first. */
@Composable
internal fun AccountHint(account: String, color: Color, modifier: Modifier = Modifier) {
    Text("from $account", modifier.semantics { contentDescription = "On your account $account" }, color = color,
        fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

internal fun MessageProtocol.tint(appearance: Appearance): Color = when (this) {
    MessageProtocol.SMS, MessageProtocol.MMS -> Color(appearance.accent)
    MessageProtocol.RCS -> Color(appearance.rcs)
    else -> {
        val stock = when (this) {
            MessageProtocol.EMAIL -> MessageStyle.Swatch.ORANGE
            MessageProtocol.IRC -> MessageStyle.Swatch.RED
            else -> MessageStyle.Swatch.PINK
        }
        val override = appearance.protocolColors[this]
        Color(override ?: appearance.theme.protocol(this, appearance.dark) ?: stock.color(appearance.dark, appearance.contrast))
    }
}

@Composable
internal fun GroupMembers(members: List<GroupMember>) {
    PreferencesCard("Members") {
        members.forEachIndexed { index, member ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 58.dp))
            Row(Modifier.fillMaxWidth().then(member.open?.let { Modifier.clickable(onClick = it::run) } ?: Modifier)
                .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(member.initials(), member.name, 36, member.photoRes, member.photo)
                PreferenceText(member.name, if (member.address != member.name) member.address else "",
                    Modifier.weight(1f).padding(12.dp))
            }
        }
    }
}

object ProtocolSettingsScreen {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(Appearance.read(prefs)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    item {
                        PreferencesCard("Text messages") {
                            ProtocolSwitch(prefs, MessageProtocol.RCS.key(), "RCS (preview)",
                                "Used first when available. Falls back to SMS when RCS can’t connect.", true)
                            ProtocolSwitch(prefs, MessageProtocol.SMS.key(), "SMS", "Carrier texts from your SIM.", true)
                            ProtocolSwitch(prefs, MessageProtocol.MMS.key(), "Allow MMS",
                                "SMS switches to MMS for photos, files, and group chats.", true)
                        }
                    }
                    item {
                        PreferencesCard("Internet protocols") {
                            for (protocol in MessageProtocol.values().filter { !it.isText() }) {
                                ProtocolSwitch(prefs, protocol.key(), protocol.label, protocol.description, true)
                            }
                        }
                    }
                    item {
                        PreferencesCard("Conversations") {
                            ProtocolSwitch(prefs, "combineProtocols", "Combine linked conversations",
                                "Show every protocol for a contact in one chat and label how each message was sent.", false)
                            DisplayModeChoice(prefs, DisplayRules.TAGS, "Inbox protocol tags",
                                "Automatic shows tags once you use more than one protocol.")
                            DisplayModeChoice(prefs, DisplayRules.ENCRYPTION, "Encryption status",
                                "Shows a colored lock under the chat name: green for end-to-end or FluidMessages encryption, yellow for in transit only, red for not encrypted. Every chat’s Security section says the same.")
                        }
                    }
                    item {
                        Text("New messages use the first available protocol: RCS, SMS or MMS, Matrix, XMPP, Email, then IRC. Choose another for a contact from the composer in its chat. A disabled protocol stays readable.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    @JvmStatic
    fun mountBattery(root: LinearLayout, prefs: SharedPreferences) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(Appearance.read(prefs)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    item { BatteryAndSync(prefs, root.context) }
                    item {
                        Text("Carrier SMS and MMS arrive by push and aren’t affected. Sending always works right away.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

@Composable
internal fun ProtocolSwitch(prefs: SharedPreferences, key: String, title: String, detail: String, default: Boolean) {
    var enabled by remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }
    SettingsSwitch(title, detail, enabled) {
        enabled = it
        prefs.edit().putBoolean(key, it).apply()
    }
}


/** Battery and data controls. Every choice here lowers or raises how often FluidMessages wakes the radio. */
@Composable
internal fun BatteryAndSync(prefs: SharedPreferences, context: android.content.Context) {
    val motion = Appearance.read(prefs).speed
    var foreground by remember { mutableStateOf(SyncPolicy.foregroundSeconds(prefs)) }
    var background by remember { mutableStateOf(SyncPolicy.backgroundMinutes(prefs)) }
    var downloads by remember { mutableStateOf(SyncPolicy.downloads(prefs)) }
    PreferencesCard("Battery and sync") {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeDropdown("Check while the app is open", foreground.toString(),
                SyncPolicy.FOREGROUND_CHOICES.map { Choice(it.toString(), SyncPolicy.foregroundLabel(it)) }, motion) {
                foreground = it.toInt()
                prefs.edit().putInt(SyncPolicy.FOREGROUND, foreground).apply()
            }
            ThemeDropdown("Check in the background", background.toString(),
                SyncPolicy.BACKGROUND_CHOICES.map { Choice(it.toString(), SyncPolicy.backgroundLabel(it)) }, motion) {
                background = it.toInt()
                prefs.edit().putInt(SyncPolicy.BACKGROUND, background).apply()
                SyncJob.reschedule(context)
            }
            ThemeDropdown("Download received attachments", downloads, listOf(
                Choice("wifi", "Automatically on Wi-Fi"), Choice("always", "Automatically on any network"),
                Choice("never", "Only when I tap them")), motion) {
                downloads = it
                prefs.edit().putString(SyncPolicy.DOWNLOADS, it).apply()
            }
        }
        var wifi by remember { mutableStateOf(SyncPolicy.wifiOnly(prefs)) }
        SettingsSwitch("Sync only on Wi-Fi", "Skip automatic checks on mobile data. Sending still works anywhere.", wifi) {
            wifi = it
            prefs.edit().putBoolean(SyncPolicy.WIFI_ONLY, it).apply()
            SyncJob.reschedule(context)
        }
        ProtocolSwitch(prefs, SyncPolicy.BATTERY_SAVER, "Pause during Battery Saver",
            "Skip automatic checks while Android Battery Saver is on.", true)
    }
}


/**
 * Tags and the encryption line default to Automatic: they appear only when they tell you something, so a
 * person who only texts never sees them. Always and Off are there for people who want them fixed.
 */
object DisplayRules {
    const val TAGS = "tagMode"
    const val ENCRYPTION = "encryptionMode"
    private val legacy = mapOf(TAGS to "protocolTags", ENCRYPTION to "protocolPrivacy")

    @JvmStatic
    fun mode(prefs: SharedPreferences, key: String): String {
        prefs.getString(key, null)?.let { return it }
        // Earlier versions had on/off switches; keep a choice someone already made.
        val old = legacy.getValue(key)
        return if (!prefs.contains(old)) "auto" else if (prefs.getBoolean(old, false)) "always" else "off"
    }

    /** Tags show in Automatic mode when the inbox holds more than one kind of conversation. */
    internal fun showTags(prefs: SharedPreferences, threads: List<ThreadRow>): Boolean = when (mode(prefs, TAGS)) {
        "always" -> true
        "off" -> false
        else -> threads.flatMap { row ->
            row.protocol.ifBlank { "SMS" }.split(" · ").map { label ->
                MessageProtocol.values().firstOrNull { it.label == label }?.family() ?: MessageProtocol.SMS
            }
        }.distinct().size > 1
    }

    /** Whether the chat header shows its encryption badge. Automatic shows it whenever the level is known. */
    internal fun encryptionLine(prefs: SharedPreferences, encryption: Encryption): String? = when (mode(prefs, ENCRYPTION)) {
        "off" -> null
        else -> encryption.label
    }
}

@Composable
internal fun DisplayModeChoice(prefs: SharedPreferences, key: String, title: String, detail: String) {
    var mode by remember(key) { mutableStateOf(DisplayRules.mode(prefs, key)) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ThemeDropdown(title, mode, listOf(Choice("auto", "Automatic"), Choice("always", "Always"), Choice("off", "Off")),
            Appearance.read(prefs).speed) {
            mode = it
            prefs.edit().putString(key, it).apply()
        }
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}
