package org.fluidmessages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * A meeting-link call, sent as plain text ("📹 Join my Zoom video call: https://…") so it works everywhere, and shown
 * in FluidMessages as a call card with a Join button. The browser companion draws the same card. The services, the
 * link checks, and your choices are in MeetingServices.kt.
 */

private fun jitsiServer(context: Context): String =
    MeetingSettings.from(context.getSharedPreferences("fluid", Context.MODE_PRIVATE).all).jitsiServer

internal fun parseMeeting(text: String): MeetingInvite? = MeetingLinks.parse(text)

/** The inbox preview for a call card. */
fun meetingPreview(text: String): String? = parseMeeting(text)?.let { if (it.video) "📹 Video call · tap to join" else "📞 Call · tap to join" }

/** Opens a meeting in its own app or the browser. */
private fun openLink(context: Context, link: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (error: ActivityNotFoundException) { false }

@Composable
internal fun MeetingCard(invite: MeetingInvite, outgoing: Boolean, foreground: Color) {
    val context = LocalContext.current
    Row(Modifier.widthIn(min = 220.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(foreground.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
            HandsetGlyph(foreground, Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(if (invite.video) "Video call" else "Voice call", color = foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text((if (outgoing) "You started a call" else "Started a call") + " · " + invite.place, color = foreground.copy(alpha = .8f),
                fontSize = 12.sp, maxLines = 1)
        }
        Text("Join", Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF34C759))
            .clickable(role = Role.Button) { openLink(context, MeetingLinks.joinLink(invite.service, invite.video, invite.link)) }
            .padding(horizontal = 14.dp, vertical = 7.dp), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** "Join meeting · Zoom" under an everyday message that has a Zoom, Google Meet, Teams, or Jitsi link in it. */
@Composable
internal fun MeetingChip(text: String, foreground: Color) {
    val context = LocalContext.current
    val found = remember(text) { if ("https://" in text) MeetingLinks.find(text, jitsiServer(context)) else null } ?: return
    Text("Join meeting · " + found.first.label, Modifier.padding(top = 6.dp).clip(RoundedCornerShape(16.dp))
        .background(foreground.copy(alpha = .16f)).clickable(role = Role.Button, onClickLabel = "Join the meeting") { openLink(context, found.second) }
        .heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 8.dp), color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

/** Called with the service you picked and the link to send. */
fun interface MeetingReady { fun go(service: MeetingService, link: String) }

/** The meeting-link pages: pick a service when sending, save your own link, and Settings → Calls → Meeting links. */
object MeetingPages {
    private enum class Mode { FIRST, ONCE, SETTINGS }

    private fun settings(prefs: SharedPreferences) = MeetingSettings.from(prefs.all)

    /** The short line under "Meeting link" in the ways to reach someone. */
    @JvmStatic fun summary(prefs: SharedPreferences): String {
        val choices = settings(prefs).enabled
        return when (choices.size) {
            1 -> choices[0].label
            2 -> choices[0].label + " or " + choices[1].label
            else -> settings(prefs).preselected.label + " and " + (choices.size - 1) + " more"
        }
    }

    /**
     * Starting a meeting link: a full page of the services that are on, with the last one used already picked (Zoom
     * the first time). With only one service on, it's used without asking. [ready] gets the link to send.
     */
    @JvmStatic fun start(activity: ComponentActivity, prefs: SharedPreferences, title: String, ready: MeetingReady) {
        val choices = settings(prefs).enabled
        if (choices.size == 1) { choose(activity, prefs, choices[0], false, ready); return }
        SecurityDialogs.present(activity, prefs) { dismiss ->
            val now = remember { settings(prefs) }
            var picked by remember { mutableStateOf(now.preselected) }
            FormPage(onDismissRequest = dismiss, title = { Text(title) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pick a service. They join by tapping the link, in a browser or that service’s app.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp))
                    for (service in choices) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .selectable(picked == service, role = Role.RadioButton) { picked = service }
                            .heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(picked == service, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(service.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(now.detail(service), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Notice(picked.privacy)
                    if (picked.needsSavedLink && now.link(picked) != null) TextButton(onClick = { dismiss(); choose(activity, prefs, picked, true, ready) }) {
                        Text("Use a different link just this once")
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { dismiss(); choose(activity, prefs, picked, false, ready) }) {
                    Text(if (now.ready(picked)) "Continue" else "Set up " + picked.label)
                }
            }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
        }
    }

    private fun choose(activity: ComponentActivity, prefs: SharedPreferences, service: MeetingService, once: Boolean, ready: MeetingReady) {
        val now = settings(prefs)
        val done = { link: String ->
            prefs.edit().putString(MeetingSettings.LAST, service.id).apply()
            ready.go(service, link)
        }
        val saved = now.link(service)
        when {
            !service.needsSavedLink -> {
                val bytes = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
                done(MeetingLinks.newJitsiRoom(now.jitsiServer, bytes.joinToString("") { "%02x".format(it) }))
            }
            saved != null && !once -> done(saved)
            else -> setup(activity, prefs, service, if (saved != null) Mode.ONCE else Mode.FIRST) { link -> if (link != null) done(link) }
        }
    }

    /** Settings → Calls → Meeting links: save, change, or remove your own link for [service]. */
    @JvmStatic fun editLink(activity: ComponentActivity, prefs: SharedPreferences, service: MeetingService, changed: Runnable) =
        setup(activity, prefs, service, Mode.SETTINGS) { changed.run() }

    /**
     * Saving your own link, on a full page: one sentence, three steps, a button to the service's own page, and the
     * field. The link has to be a real link for that service. [done] gets the link to send, or null when nothing is sent.
     */
    private fun setup(activity: ComponentActivity, prefs: SharedPreferences, service: MeetingService, mode: Mode, done: (String?) -> Unit) =
        SecurityDialogs.present(activity, prefs) { dismiss ->
            val saved = remember { settings(prefs).link(service) }
            var value by remember { mutableStateOf(if (mode == Mode.SETTINGS) saved.orEmpty() else "") }
            var problem by remember { mutableStateOf<String?>(null) }
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            /** The checked link, or null after showing what's wrong. */
            val checked = { MeetingLinks.valid(service, value).also { if (it == null) problem = MeetingLinks.problem(service, value) } }
            FormPage(onDismissRequest = dismiss, title = { Text(if (mode == Mode.ONCE) "A different ${service.label} link" else "Your ${service.label} link") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (mode == Mode.ONCE) Text("Paste the ${service.label} link to send this time. Your saved link stays the same.",
                        style = MaterialTheme.typography.bodyMedium)
                    else {
                        Text(service.setupLine, style = MaterialTheme.typography.bodyMedium)
                        service.steps.forEachIndexed { index, step ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("${index + 1}.", Modifier.width(26.dp), fontWeight = FontWeight.SemiBold)
                                Text(step, Modifier.weight(1f))
                            }
                        }
                        Button(onClick = { if (!openLink(context, service.home)) problem = "No browser is available to open ${service.label}." },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open ${service.label}") }
                    }
                    OutlinedTextField(value, { value = it.take(2000); problem = null }, Modifier.fillMaxWidth(),
                        label = { Text("${service.label} link") }, placeholder = { Text(service.example) }, singleLine = true,
                        isError = problem != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedButton(onClick = { clipboard.getText()?.text?.let { value = it.trim().take(2000); problem = null } },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Paste") }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Notice(service.privacy)
                    if (mode == Mode.FIRST) TextButton(onClick = { checked()?.let { dismiss(); done(it) } }) {
                        Text("Use this link just this once")
                    }
                }
            }, confirmButton = {
                TextButton(onClick = {
                    val link = checked() ?: return@TextButton
                    if (mode != Mode.ONCE) prefs.edit().putString(MeetingSettings.linkKey(service), link).apply()
                    dismiss()
                    done(link)
                }) { Text(when (mode) { Mode.FIRST -> "Save and continue"; Mode.ONCE -> "Use this link"; Mode.SETTINGS -> "Save" }) }
            }, dismissButton = {
                Row {
                    if (mode == Mode.SETTINGS && saved != null) TextButton(onClick = {
                        prefs.edit().remove(MeetingSettings.linkKey(service)).apply()
                        dismiss()
                        done(null)
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = dismiss) { Text("Cancel") }
                }
            })
        }

    /**
     * The rows of Settings → Calls → Meeting links: each service on or off, your saved links, and the Jitsi server.
     * [refresh] draws the page again; [jitsiServer] opens the server form.
     */
    @JvmStatic fun settingsSections(activity: ComponentActivity, prefs: SharedPreferences, refresh: Runnable, jitsiServer: Runnable): List<SettingsSection> {
        val now = settings(prefs)
        val services = MeetingService.entries.map { service ->
            val on = now.isOn(service)
            SettingsAction(service.label, now.detail(service), Runnable {
                if (on && !now.canTurnOff(service)) {
                    Toast.makeText(activity, "One service always stays on. To stop meeting links, turn off Meeting links in Calls.", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putBoolean(MeetingSettings.onKey(service), !on).apply()
                    refresh.run()
                }
            }, false, null, on)
        }
        val links = MeetingService.entries.filter { it.needsSavedLink && now.isOn(it) }.map { service ->
            SettingsAction("${service.label} link", now.link(service) ?: "Not saved yet · tap to add yours",
                Runnable { editLink(activity, prefs, service, refresh) })
        }
        val sections = mutableListOf(SettingsSection("Services you can pick when you send a link", services))
        if (links.isNotEmpty()) sections += SettingsSection("Your saved links", links)
        if (now.isOn(MeetingService.JITSI)) sections += SettingsSection("Jitsi Meet", listOf(
            SettingsAction("Jitsi server", now.jitsiServer + " · free and open source, or use your own", jitsiServer)))
        sections += SettingsSection("Good to know", listOf(
            SettingsAction("FluidMessages sends your link", "It can’t make Zoom, Google Meet, or Teams meetings for you. Jitsi Meet makes a new room each time.",
                Runnable {}, true),
            SettingsAction("Who can see a meeting", "Zoom, Google, or Microsoft can see who joins and may record. On Jitsi, the meeting " +
                "server can access the call. For a private call, use an encrypted call instead.", Runnable {}, true)))
        return sections
    }
}
