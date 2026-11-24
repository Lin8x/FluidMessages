package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The bar at the top of a request chat, like Signal's and WhatsApp's: who it's from, then Accept (with Add to
 * contacts), Delete, Block, and Block and report spam. For a Matrix or XMPP invitation, Accept and Decline answer it.
 */
class RequestBar(
    val title: String,
    val detail: String,
    val invite: Boolean,
    /** Whether Add to contacts is offered (a number, or an address a contact can hold). */
    val canSave: Boolean,
    val accept: java.util.function.Consumer<Boolean>,
    val delete: Runnable,
    val block: Runnable,
    val report: Runnable,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RequestBanner(bar: RequestBar, palette: Palette) {
    var save by remember(bar) { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).clip(shape).background(palette.wash)
        .border(1.dp, palette.edge, shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(bar.title, Modifier.semantics { heading() }, color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(bar.detail, color = palette.muted, fontSize = 14.sp)
        if (bar.canSave && !bar.invite) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Checkbox) { save = !save }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(save, { save = it })
            Text("Add to contacts", color = palette.ink)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(onClick = { bar.accept.accept(save) }) { Text(if (bar.invite) "Accept invite" else "Accept") }
            OutlinedButton(onClick = bar.delete::run) { Text(if (bar.invite) "Decline" else "Delete", color = palette.ink) }
            OutlinedButton(onClick = bar.block::run) { Text("Block", color = palette.ink) }
            OutlinedButton(onClick = bar.report::run) { Text("Block and report spam", color = Color(0xffd0342c)) }
        }
    }
}

/** Settings → Privacy and security → Requests and spam. Shared with FluidMessages Web through the merge. */
internal object RequestSettingsPage {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, changed: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(remember { Appearance.read(prefs) }) { Page(prefs) { changed.run() } }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /**
     * A full page that confirms a request action that can't be undone, such as Delete: what happens, then the action
     * and Cancel. MainActivity gives it a heading with Back.
     */
    @JvmStatic
    fun mountConfirm(root: LinearLayout, prefs: SharedPreferences, explanation: String, actionLabel: String, action: Runnable, cancel: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(remember { Appearance.read(prefs) }) {
                val scheme = MaterialTheme.colorScheme
                Column(Modifier.fillMaxSize().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(explanation, Modifier.padding(horizontal = 12.dp), color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = action::run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xffc4141f), contentColor = Color.White)) { Text(actionLabel) }
                    OutlinedButton(onClick = cancel::run, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    @Composable
    private fun Page(prefs: SharedPreferences, changed: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        var settings by remember { mutableStateOf(MessageRequests.settings(prefs)) }
        fun flip(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
            settings = MessageRequests.settings(prefs)
            changed()
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text("Chats from people you don't know wait under Requests in the inbox filter menu, out of your inbox and the Messages " +
                    "count. Open one to accept, delete, block, or report it. Replying accepts it. Shared with FluidMessages Web.",
                    Modifier.padding(horizontal = 12.dp), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            item {
                PreferencesCard("Message requests") {
                    SettingsSwitch("Filter unknown senders (texts)", "Texts from numbers that aren't in your contacts go to Requests. Off by " +
                        "default, so delivery and sign-in codes are never missed.", settings.texts) { flip(MessageRequests.TEXTS, it) }
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    SettingsSwitch("Filter unknown senders (chat services)", "Matrix and XMPP invitations, and Matrix, XMPP, IRC, and email " +
                        "messages from people who aren't in your contacts or favorites and whom you've never messaged. NickServ and other " +
                        "network services, and channels and rooms you joined, are never requests.", settings.chats) { flip(MessageRequests.CHATS, it) }
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeDropdown("Request notifications", if (settings.notifyQuiet) "quiet" else "off",
                            listOf(Choice("quiet", "Quiet: no sound or vibration"), Choice("off", "Off: no notification")),
                            Appearance.read(prefs).speed) { choice ->
                            if (choice == "off") prefs.edit().putString(MessageRequests.NOTIFY, "off").apply()
                            else prefs.edit().remove(MessageRequests.NOTIFY).apply()
                            settings = MessageRequests.settings(prefs)
                            changed()
                        }
                    }
                }
            }
            item {
                PreferencesCard("Automated messages") {
                    PreferenceText("Under the Automated filter", "Short codes and sender IDs, like delivery updates and sign-in codes, or any " +
                        "chat whose Sender type is Automated sender.")
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    SettingsSwitch("Keep automated messages out of the inbox", "They show only under Automated and don't count on the " +
                        "Messages tab.", settings.automatedOut) { flip(MessageRequests.AUTOMATED_OUT, it) }
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    SettingsSwitch("Don't notify for automated messages", "They still arrive and show unread.", settings.automatedQuiet) {
                        flip(MessageRequests.AUTOMATED_QUIET, it)
                    }
                    HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                    SettingsSwitch("Block automated senders", "They go to Blocked, without notifications.", settings.automatedBlock) {
                        flip(MessageRequests.AUTOMATED_BLOCK, it)
                    }
                    if (settings.automatedBlock) Text("Sign-in codes, bank alerts, and delivery updates come from automated senders. " +
                        "While this is on, you won't see them in your inbox or get notified.",
                        Modifier.fillMaxWidth().padding(15.dp).clip(RoundedCornerShape(12.dp))
                            .background(scheme.errorContainer).padding(12.dp), color = scheme.onErrorContainer)
                }
            }
        }
    }
}
