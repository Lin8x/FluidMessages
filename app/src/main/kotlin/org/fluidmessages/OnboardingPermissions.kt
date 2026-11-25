package org.fluidmessages

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * The setup guide's permission pages. Each permission FluidMessages really uses gets one row: what it is for and
 * what stops working without it, whether it is allowed, and an Allow button that opens Android's own request.
 * Every one can be skipped; FluidMessages asks again the first time the feature that needs it is used.
 */

/**
 * One thing to allow. [permissions] are asked for together, the way Android groups them in one prompt.
 * With [anyIsEnough], one of them granted counts as allowed (approximate location alone is fine).
 */
internal data class PermissionAsk(
    val key: String,
    val title: String,
    val why: String,
    val permissions: List<String>,
    val anyIsEnough: Boolean = false,
) {
    /** Allowed, given which of [permissions] are granted, in the same order. */
    fun allowed(granted: List<Boolean>): Boolean =
        granted.isNotEmpty() && if (anyIsEnough) granted.any { it } else granted.all { it }
}

/** Notifications use Android's notification switch and channel as well, so their state comes from SmsDelivery.status. */
internal const val NOTIFICATIONS_ASK = "notifications"

/** What most people want from the start: the page "Allow the basics". */
internal val basicPermissions: List<PermissionAsk> = listOf(
    PermissionAsk(NOTIFICATIONS_ASK, "Notifications",
        "Tells you when a message or call comes in. Without it, you only see new messages when you open FluidMessages.",
        listOf(Manifest.permission.POST_NOTIFICATIONS)),
    PermissionAsk("contacts", "Contacts",
        "Shows names and photos, and saves the contacts you add. Without it, you see phone numbers with no names.",
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)),
    PermissionAsk("phone", "Phone",
        "Starts calls from FluidMessages, lets you pick a SIM, and keeps group texts in one chat. Without it, calls open in your phone app first.",
        listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)),
    PermissionAsk("callLog", "Call history",
        "Shows your recent and missed phone calls in the Phone tab. Without it, only internet calls show there.",
        listOf(Manifest.permission.READ_CALL_LOG)),
)

/** Each needed for one feature only: the page "Ask now or later". */
internal val laterPermissions: List<PermissionAsk> = listOf(
    PermissionAsk("microphone", "Microphone",
        "For voice messages and calls. Without it, you can’t record a voice message and people can’t hear you on a call.",
        listOf(Manifest.permission.RECORD_AUDIO)),
    PermissionAsk("camera", "Camera",
        "For video calls. Without it, people on a video call can’t see you.",
        listOf(Manifest.permission.CAMERA)),
    PermissionAsk("location", "Location",
        "For sending where you are in a chat, only when you tap it. Without it, you can’t share your location.",
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), anyIsEnough = true),
    PermissionAsk("calendar", "Calendar",
        "For adding an event from a chat to your calendar in one tap. Without it, the event opens in your calendar app to save there.",
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
)

private fun PermissionAsk.allowed(context: Context): Boolean =
    if (key == NOTIFICATIONS_ASK) SmsDelivery.status(context).allowed
    else allowed(permissions.map { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
    } catch (ignored: RuntimeException) {}
}

/** The default messages app step: its state, the button that opens Android's request, and what skipping means. */
@Composable
internal fun TextingStepCard(allow: Runnable) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var held by remember { mutableStateOf(TextingGate.roleHeld(context)) }
    LaunchedEffect(Unit) { while (true) { delay(1_200); held = TextingGate.roleHeld(context) } }
    PreferencesCard("Texting app") {
        Row(Modifier.fillMaxWidth().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            PreferenceText(if (held) "FluidMessages is your texting app" else "Another app is your texting app",
                if (held) "Your texts come here now." else "Your texts stay in that app until you switch.",
                Modifier.weight(1f).padding(15.dp))
            StatusLabel(if (held) "Allowed" else "Not yet")
        }
        Column(Modifier.padding(start = 15.dp, end = 15.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!held) AppButton(onClick = allow::run, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                Text(TextingGate.ALLOW)
            }
            Text(if (held) "To stop, pick another texting app in Android’s settings, or turn texting off in Settings → Chat services."
                else "You can skip this. Texting then stays off in FluidMessages, and you can still use the other chat " +
                    "services and FluidMessages Web. Turn texting on later in Settings → Chat services.",
                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A card of permissions, one row each. [allowNotifications] is the app's own notification request (MainActivity). */
@Composable
internal fun PermissionsCard(title: String, asks: List<PermissionAsk>, allowNotifications: Runnable) {
    val scheme = MaterialTheme.colorScheme
    PreferencesCard(title) {
        asks.forEachIndexed { index, ask ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 15.dp), color = scheme.outlineVariant)
            PermissionRow(ask, allowNotifications)
        }
    }
}

@Composable
private fun PermissionRow(ask: PermissionAsk, allowNotifications: Runnable) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var allowed by remember(ask.key) { mutableStateOf(ask.allowed(context)) }
    // Android stops showing its request after it was refused twice; the row then points to Android's settings.
    var refused by remember(ask.key) { mutableStateOf(false) }
    // Read again every moment, so the row follows Android's request and a change made in Android's settings.
    LaunchedEffect(ask.key) { while (true) { delay(1_200); allowed = ask.allowed(context) } }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = ask.allowed(context)
        val activity = context.activity()
        refused = !allowed && activity != null && ask.permissions.none { activity.shouldShowRequestPermissionRationale(it) }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = if (allowed) 0.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            PreferenceText(ask.title, ask.why, Modifier.weight(1f).padding(15.dp))
            StatusLabel(if (allowed) "Allowed" else "Not yet")
        }
        if (!allowed) {
            AppButton(onClick = {
                if (ask.key == NOTIFICATIONS_ASK) allowNotifications.run() else request.launch(ask.permissions.toTypedArray())
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp).heightIn(min = 46.dp)) {
                Text("Allow ${ask.title.lowercase()}")
            }
            if (refused) {
                Text("Android didn’t ask? It stops asking after two tries. You can still turn it on in Android’s settings.",
                    Modifier.padding(start = 15.dp, end = 15.dp, top = 8.dp), color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openAppSettings(context) }, modifier = Modifier.padding(horizontal = 7.dp)) {
                    Text("Open Android settings for ${ask.title.lowercase()}")
                }
            }
        }
    }
}
