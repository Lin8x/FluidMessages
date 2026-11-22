package org.fluidmessages

import android.content.SharedPreferences
import android.net.Uri
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
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
)

data class GroupMember @JvmOverloads constructor(
    val name: String, val address: String = "", val photo: Uri? = null, val open: Runnable? = null,
)

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
        Color(override ?: stock.color(appearance.dark, appearance.contrast))
    }
}

@Composable
internal fun GroupMembers(members: List<GroupMember>) {
    PreferencesCard("Members") {
        members.forEachIndexed { index, member ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 58.dp))
            Row(Modifier.fillMaxWidth().then(member.open?.let { Modifier.clickable(onClick = it::run) } ?: Modifier)
                .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(member.name.split(" ").take(2).mapNotNull { it.firstOrNull() }.joinToString(""),
                    member.name, 36, photoUri = member.photo)
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
                        PreferencesCard("Routing and display") {
                            ProtocolSwitch(prefs, "protocolAuto", "Auto", "Prefer RCS, SMS or MMS, Matrix, XMPP, Email, then IRC.", true)
                            ProtocolSwitch(prefs, "combineProtocols", "Combine linked conversations",
                                "Show linked formats together and label each message.", false)
                            ProtocolSwitch(prefs, "syncInternetProtocols", "Connect internet protocols", "Sync configured routes from the paired companion.", false)
                            ProtocolSwitch(prefs, "protocolTags", "Show inbox protocol tags", "Show the formats available in each chat.", false)
                            ProtocolSwitch(prefs, "protocolPrivacy", "Show encryption status", "Show protection below the conversation title.", false)
                            ProtocolSwitch(prefs, "stripMetadata", "Remove photo metadata", "Re-encode outgoing images to remove embedded camera and location data.", true)
                        }
                    }
                    item {
                        PreferencesCard("Message protocols") {
                            for (protocol in MessageProtocol.values()) {
                                ProtocolSwitch(prefs, protocol.key(), protocol.label, protocol.description, true)
                            }
                        }
                    }
                    item {
                        Text("A disabled protocol stays readable. Configure internet accounts on your paired companion. Samples let you try the interface before connecting.",
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
