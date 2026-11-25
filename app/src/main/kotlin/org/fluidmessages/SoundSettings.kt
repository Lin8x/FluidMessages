package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import java.util.function.BooleanSupplier
import kotlin.math.roundToInt

/**
 * Settings → Sounds and notifications: whether Android shows FluidMessages' notifications (and a way to fix it), a
 * real test notification, links to Android's notification settings, and the in-app send and receive sounds with
 * their volume and the volume they follow.
 */
internal object SoundSettings {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, status: NotificationStatus, allow: Runnable,
              test: BooleanSupplier, openSettings: Runnable, openChannel: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            AppTheme(appearance) { Page(prefs, appearance.speed, status, allow, test, openSettings, openChannel) }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

@Composable
private fun Page(prefs: SharedPreferences, motion: Int, status: NotificationStatus, allow: Runnable, test: BooleanSupplier,
                 openSettings: Runnable, openChannel: Runnable) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var volume by remember { mutableIntStateOf(SoundPrefs.volume(prefs.getInt(SoundPrefs.VOLUME, SoundPrefs.DEFAULT_VOLUME))) }
    var stream by remember { mutableStateOf(prefs.getString(SoundPrefs.STREAM, SoundPrefs.NOTIFICATION) ?: SoundPrefs.NOTIFICATION) }
    var sent by remember { mutableStateOf<String?>(null) }
    var showContent by remember { mutableStateOf(prefs.getBoolean(NotificationContent.PREF, true)) }
    var tabCounts by remember { mutableStateOf(prefs.getBoolean(TabBadges.PREF, true)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            PreferencesCard("Notifications") {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PreferenceText(status.label, status.detail, Modifier.weight(1f).padding(15.dp))
                    if (!status.allowed) AppButton(onClick = allow::run) { Text("Allow") }
                }
                HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                SettingsSwitch("Show names and message text in notifications",
                    "Off: a new text only says “New text message”. Chats in the private folder, chats with a code, and everything while app lock is on never show names or text.",
                    showContent) {
                    showContent = it
                    prefs.edit().putBoolean(NotificationContent.PREF, it).apply()
                }
                HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { sent = if (test.asBoolean) "Sent. Pull down the notification shade to see it."
                        else "Not shown: notifications are blocked." }, modifier = Modifier.fillMaxWidth()) {
                        Text("Send a test notification")
                    }
                    Text(sent ?: "Posts a made-up message through the same channel, sound, and lock screen setting as a real one.",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
                TextButton(onClick = openSettings::run, modifier = Modifier.padding(horizontal = 6.dp)) { Text("Open notification settings") }
                TextButton(onClick = openChannel::run, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Text("Message notification sound and vibration")
                }
                Text("New-message notifications play the receive chime below, unless you pick another sound in Android's settings.",
                    Modifier.padding(start = 15.dp, end = 15.dp, bottom = 12.dp), color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            // Badge settings sit with notifications, where Android and iOS keep them. Same pref as before (tabCounts).
            PreferencesCard("App badges") {
                SettingsSwitch("Show counts on tabs", "Missed calls on Phone and unread chats on Messages. Muted, archived, and blocked chats aren't counted.",
                    tabCounts) {
                    tabCounts = it
                    prefs.edit().putBoolean(TabBadges.PREF, it).apply()
                }
            }
        }
        item {
            PreferencesCard("Sound effects") {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToneDropdown(prefs, receive = false, motion)
                    ToneDropdown(prefs, receive = true, motion)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { MessageFeedback.play(context, prefs, false) }, modifier = Modifier.weight(1f)) { Text("Hear send") }
                        TextButton(onClick = { MessageFeedback.play(context, prefs, true) }, modifier = Modifier.weight(1f)) { Text("Hear receive") }
                    }
                }
            }
        }
        item {
            PreferencesCard("Volume") {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sound effect volume · $volume%", color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                    Slider(volume.toFloat(), { volume = it.roundToInt() }, valueRange = 0f..100f, steps = 19,
                        onValueChangeFinished = {
                            prefs.edit().putInt(SoundPrefs.VOLUME, volume).apply()
                            MessageFeedback.play(context, prefs, false)
                        })
                    Text(if (volume == 0) "Off · in-app sounds don't play." else "Relative to the phone's ${SoundPrefs.streamLabel(stream).lowercase()}.",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    ThemeDropdown("Play sounds through", stream, listOf(Choice(SoundPrefs.MEDIA, "Media volume"),
                        Choice(SoundPrefs.NOTIFICATION, "Notification volume")), motion) {
                        stream = it
                        prefs.edit().putString(SoundPrefs.STREAM, it).apply()
                        MessageFeedback.play(context, prefs, false)
                    }
                    Text(if (stream == SoundPrefs.MEDIA) "Uses the media volume, like music and videos, and still plays when the ringer is silent."
                        else "Uses the notification volume and follows silent mode and Do Not Disturb.",
                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { MessageFeedback.play(context, prefs, true) }) { Text("Preview") }
                }
            }
        }
        item {
            Text("Volume and Play sounds through apply to sounds FluidMessages plays itself, such as the send sound and previews. New-message notifications follow Android's notification volume. Haptics are in Themes and appearance → Motion and sound.",
                Modifier.padding(horizontal = 12.dp), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
