package org.fluidmessages

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

@Composable
internal fun FeedbackOptions(prefs: SharedPreferences, motion: Int) {
    val context = LocalContext.current
    val view = LocalView.current
    var haptics by remember { mutableStateOf(MessageFeedback.haptics(prefs)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToneDropdown(prefs, receive = false, motion)
        ToneDropdown(prefs, receive = true, motion)
        ThemeDropdown("Send haptics", haptics, listOf(Choice("off", "Off"), Choice("soft", "Soft tick"),
            Choice("tap", "Tap"), Choice("confirm", "Confirmation"), Choice("long", "Long press")), motion) {
            haptics = it
            prefs.edit().putString("sendHaptics", it).apply()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { MessageFeedback.play(context, prefs, false) }, modifier = Modifier.weight(1f)) { Text("Hear send") }
            TextButton(onClick = { MessageFeedback.play(context, prefs, true) }, modifier = Modifier.weight(1f)) { Text("Hear receive") }
            TextButton(onClick = { MessageFeedback.vibrate(view, prefs) }, modifier = Modifier.weight(1f)) { Text("Test haptics") }
        }
    }
}

@Composable
private fun ToneDropdown(prefs: SharedPreferences, receive: Boolean, motion: Int) {
    val context = LocalContext.current
    var tone by remember(receive) { mutableStateOf(MessageFeedback.selection(prefs, receive)) }
    var error by remember { mutableStateOf(false) }
    fun save(choice: String) {
        tone = choice
        error = false
        prefs.edit().putString(MessageFeedback.toneKey(receive), choice).apply()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            save(MessageFeedback.fromPicker(result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)))
        }
    }
    val choices = listOf(
        Choice(MessageFeedback.SILENT, "Silent"),
        Choice(MessageFeedback.DEFAULT, "Device default"),
        Choice(MessageFeedback.FLUID, if (receive) "Fluid receive chime" else "Fluid send sound"),
        Choice("picker", "Choose a device tone"),
    )
    ThemeDropdown(if (receive) "Receive chime" else "Outgoing sound", tone, choices, motion, fallback = "Selected device tone") {
        if (it == "picker") {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, if (receive) "Choose receive chime" else "Choose outgoing sound")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, MessageFeedback.toneUri(context, tone, receive))
            try { picker.launch(intent) } catch (_: ActivityNotFoundException) { error = true }
        } else save(it)
    }
    if (error) Text("A device tone picker is unavailable.", color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
}
