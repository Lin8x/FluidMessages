package org.fluidmessages

import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * An XMPP chat's Encryption (OMEMO) page: everyone's devices with fingerprints in groups of 8, a choice per
 * device (Trusted, Verified, Off), your own devices and xmpp: link, and checking a link someone shared. The data
 * comes from this phone's OMEMO device (PhoneOmemo) or from FluidMessages Web, in the same shape as its
 * /api/protocols/omemo answer.
 */
internal object OmemoPage {
    fun interface Loader { @Throws(Exception::class) fun load(): JSONObject }
    fun interface TrustSetter { @Throws(Exception::class) fun set(jid: String, device: Int, trust: String): JSONObject }
    fun interface LinkChecker { @Throws(Exception::class) fun check(uri: String): JSONObject }

    @JvmStatic
    fun show(activity: ComponentActivity, prefs: SharedPreferences, where: String, load: Loader, setTrust: TrustSetter,
             checkLink: LinkChecker) = SecurityDialogs.present(activity, prefs) { dismiss ->
        var info by remember { mutableStateOf<JSONObject?>(null) }
        var problem by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var link by remember { mutableStateOf("") }
        fun run(work: () -> JSONObject, after: (JSONObject) -> Unit = {}) {
            Thread {
                try {
                    val answer = work()
                    activity.runOnUiThread { info = answer; problem = ""; after(answer) }
                } catch (error: Exception) {
                    activity.runOnUiThread { problem = error.message ?: "Something went wrong. Try again." }
                }
            }.start()
        }
        LaunchedEffect(Unit) { run({ load.load() }) }
        FormPage(onDismissRequest = dismiss, title = { Text("Encryption (OMEMO)") },
            confirmButton = { TextButton(onClick = dismiss) { Text("Done") } }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val current = info
                    when {
                        problem.isNotEmpty() -> Text(problem, color = MaterialTheme.colorScheme.error)
                        current == null -> Text("Checking devices…", style = MaterialTheme.typography.bodyMedium)
                        !current.optBoolean("available") -> Text((current.optString("reason").ifEmpty { "OMEMO isn't set up." })
                            + " This chat is encrypted in transit only.", style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            Text(current.optString("words"), style = MaterialTheme.typography.bodyLarge)
                            Text("Locks here come from $where. Compare a device's fingerprint with what their app shows (in person or on a call), " +
                                "then choose Verified. After you verify one of their devices, new ones wait for your OK.",
                                style = MaterialTheme.typography.bodySmall)
                            val people = current.optJSONArray("people") ?: JSONArray()
                            for (i in 0 until people.length()) {
                                val person = people.getJSONObject(i)
                                HorizontalDivider()
                                Text(person.optString("jid"), style = MaterialTheme.typography.titleSmall)
                                val devices = person.optJSONArray("devices") ?: JSONArray()
                                if (devices.length() == 0) Text("No OMEMO devices: their app doesn't use OMEMO.", style = MaterialTheme.typography.bodySmall)
                                for (d in 0 until devices.length()) {
                                    DeviceRow(devices.getJSONObject(d)) { trust -> run({ setTrust.set(person.optString("jid"), devices.getJSONObject(d).optInt("id"), trust) }) }
                                }
                            }
                            val own = current.optJSONObject("own")
                            if (own != null) {
                                HorizontalDivider()
                                Text("Your devices", style = MaterialTheme.typography.titleSmall)
                                val devices = own.optJSONArray("devices") ?: JSONArray()
                                for (d in 0 until devices.length()) {
                                    DeviceRow(devices.getJSONObject(d)) { trust -> run({ setTrust.set(own.optString("jid"), devices.getJSONObject(d).optInt("id"), trust) }) }
                                }
                            }
                            HorizontalDivider()
                            Text("Your OMEMO link", style = MaterialTheme.typography.titleSmall)
                            val clipboard = LocalClipboardManager.current
                            Text(current.optString("uri"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(current.optString("uri"))) }) { Text("Copy") }
                            Text("Paste an xmpp: link they shared to verify their devices in one step.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(link, { link = it; note = "" }, Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("xmpp:name@example.org?omemo-sid-…") })
                            Button(onClick = {
                                run({ checkLink.check(link.trim()) }) { answer ->
                                    val checked = answer.optJSONObject("checked") ?: JSONObject()
                                    note = when {
                                        checked.optInt("verified") > 0 -> "Verified ${checked.optInt("verified")} device(s) from the link."
                                        checked.optInt("mismatched") > 0 -> "The link's fingerprint doesn't match. Don't verify this device; ask them to check."
                                        else -> "No device in the link matches this chat."
                                    }
                                }
                            }, enabled = link.isNotBlank()) { Text("Check") }
                            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            })
    }

    private fun fingerprint(device: JSONObject): String {
        val raw = device.opt("fingerprint")
        return if (raw is JSONArray) (0 until raw.length()).joinToString(" ") { raw.optString(it) } else device.optString("fingerprint")
    }

    @Composable
    private fun DeviceRow(device: JSONObject, choose: (String) -> Unit) {
        val self = device.optBoolean("this")
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (self) "This device" else "Device ${device.optInt("id")}", style = MaterialTheme.typography.bodyLarge)
            Text(fingerprint(device).ifEmpty { "Fingerprint not available yet" }, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            Text(if (self) "Your messages here are locked with this key." else OmemoRules.trustLabel(device.optString("trust")),
                style = MaterialTheme.typography.bodySmall)
            if (!self) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in listOf(OmemoRules.BLIND to "Trusted", OmemoRules.VERIFIED to "Verified", OmemoRules.DISTRUSTED to "Off")) {
                    if (device.optString("trust") == value) FilledTonalButton(onClick = {}) { Text(label) }
                    else OutlinedButton(onClick = { choose(value) }) { Text(label) }
                }
            }
        }
    }
}
