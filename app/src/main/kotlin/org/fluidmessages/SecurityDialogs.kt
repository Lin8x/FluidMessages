package org.fluidmessages

import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.Consumer
import java.util.function.IntConsumer
import org.json.JSONObject

/** Dialogs for keys, codes, timers, and encryption, in the app's own theme. Each one is opened from Java. */
object SecurityDialogs {
    private fun present(activity: ComponentActivity, prefs: SharedPreferences, content: @Composable (dismiss: () -> Unit) -> Unit) {
        val host = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = ComposeView(activity)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        val dismiss = { host.post { host.removeView(view) }; Unit }
        view.setContent { AppTheme(Appearance.read(prefs)) { content(dismiss) } }
        host.addView(view, ViewGroup.LayoutParams(0, 0))
    }

    /** A shared key: shown hidden with an eye to reveal it, a copy button, and a button to make a new one. */
    @JvmStatic
    fun sharedKey(activity: ComponentActivity, prefs: SharedPreferences, title: String, explanation: String, current: String,
                  save: Consumer<String>, remove: Runnable?) = present(activity, prefs) { dismiss ->
        var key by remember { mutableStateOf(current) }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                SecretField(key, { key = it; problem = null }, "Encryption key", monospace = true, revealedAtStart = current.isEmpty())
                OutlinedButton(onClick = { key = SmsCrypto.newKey(); problem = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (key.isEmpty()) "Generate a key" else "Generate a new key")
                }
                Notice("Share the key in person or read it aloud. Anyone with it can read these messages.")
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val value = key.trim()
                if (value.length < 12) { problem = "Use at least 12 characters"; return@TextButton }
                save.accept(value); dismiss()
            }) { Text("Save") }
        }, dismissButton = {
            Row {
                if (remove != null && current.isNotEmpty()) TextButton(onClick = { remove.run(); dismiss() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = dismiss) { Text("Cancel") }
            }
        })
    }

    /** A new code, typed twice when confirm is on. An empty code is allowed only when allowEmpty (removes it). */
    @JvmStatic
    fun code(activity: ComponentActivity, prefs: SharedPreferences, title: String, explanation: String, confirm: Boolean,
             minLength: Int, allowEmpty: Boolean, save: Consumer<String>) = present(activity, prefs) { dismiss ->
        var code by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (explanation.isNotEmpty()) Text(explanation, style = MaterialTheme.typography.bodyMedium)
                SecretField(code, { code = it; problem = null }, if (allowEmpty) "New passcode (leave empty to turn off the lock)" else "Code", copy = false)
                if (confirm) SecretField(again, { again = it; problem = null }, "Type it again", copy = false)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                when {
                    code.isEmpty() && allowEmpty -> { save.accept(""); dismiss() }
                    code.length < minLength -> problem = "Use at least $minLength characters"
                    confirm && code != again -> problem = "The passcodes don't match"
                    else -> { save.accept(code); dismiss() }
                }
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    }

    /** Asks for an existing code. check returns whether it matched; the dialog stays open on a wrong code. */
    @JvmStatic
    fun askCode(activity: ComponentActivity, prefs: SharedPreferences, title: String, check: java.util.function.Predicate<String>,
                opened: Runnable) = present(activity, prefs) { dismiss ->
        var code by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        var checking by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecretField(code, { code = it; wrong = false }, "Code", copy = false)
                if (wrong) Text("Incorrect passcode.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = !checking && code.isNotEmpty(), onClick = {
                checking = true
                scope.launch {
                    val match = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { check.test(code) }
                    checking = false
                    if (match) { dismiss(); opened.run() } else wrong = true
                }
            }) { Text(if (checking) "Checking…" else "Open") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    }

    /** A bottom sheet of choices, each with a title and a detail line. pick gets the chosen index. */
    @OptIn(ExperimentalMaterial3Api::class)
    @JvmStatic
    fun chooseOne(activity: ComponentActivity, prefs: SharedPreferences, title: String, explanation: String,
                  items: List<Array<String>>, pick: IntConsumer) = present(activity, prefs) { dismiss ->
        ModalBottomSheet(onDismissRequest = dismiss) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (explanation.isNotEmpty()) Text(explanation, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                items.forEachIndexed { index, item ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { dismiss(); pick.accept(index) }
                        .padding(vertical = 12.dp, horizontal = 4.dp)) {
                        Text(item[0], style = MaterialTheme.typography.bodyLarge)
                        if (item.size > 1 && item[1].isNotEmpty()) Text(item[1], style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    /** Asks for a passcode and always continues, so a wrong one looks the same as a right one with nothing behind it. */
    @JvmStatic
    fun passcodeEntry(activity: ComponentActivity, prefs: SharedPreferences, title: String, submit: Consumer<String>) = present(activity, prefs) { dismiss ->
        var code by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
            SecretField(code, { code = it }, "Passcode", copy = false)
        }, confirmButton = {
            TextButton(enabled = code.isNotEmpty(), onClick = { dismiss(); submit.accept(code) }) { Text("Open") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    }

    /** Disappearing timer. current is -1 for "use the default" when defaultLabel is given. */
    @JvmStatic
    fun disappearing(activity: ComponentActivity, prefs: SharedPreferences, title: String, current: Int, defaultLabel: String?,
                     pick: IntConsumer) = present(activity, prefs) { dismiss ->
        val presets = Disappearing.CHOICES.toList()
        var chosen by remember { mutableIntStateOf(if (current in presets || current == -1) current else -2) }
        var amount by remember { mutableStateOf(if (current > 0 && current !in presets) customAmount(current).first.toString() else "") }
        var unit by remember { mutableIntStateOf(if (current > 0 && current !in presets) customAmount(current).second else 1) }
        var problem by remember { mutableStateOf<String?>(null) }
        val units = listOf("Minutes" to 60, "Hours" to 3600, "Days" to 86400, "Weeks" to 7 * 86400)
        AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item {
                    Text("Messages sent after you turn this on disappear from this phone and FluidMessages Web. Earlier messages stay.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (defaultLabel != null) item { Choice("Use the default ($defaultLabel)", chosen == -1) { chosen = -1 } }
                for (seconds in presets) item { Choice(Disappearing.label(seconds), chosen == seconds) { chosen = seconds } }
                item { Choice("Custom", chosen == -2) { chosen = -2 } }
                if (chosen == -2) item {
                    Column(Modifier.padding(start = 12.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(amount, { amount = it.filter(Char::isDigit).take(4); problem = null }, label = { Text("Amount") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            units.forEachIndexed { index, (label, _) ->
                                FilterChip(unit == index, onClick = { unit = index }, label = { Text(label) })
                            }
                        }
                    }
                }
                problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            }
        }, confirmButton = {
            TextButton(onClick = {
                if (chosen != -2) { pick.accept(chosen); dismiss(); return@TextButton }
                val seconds = (amount.toLongOrNull() ?: 0) * units[unit].second
                if (seconds !in 60..366L * 86400) { problem = "Choose between 1 minute and 1 year"; return@TextButton }
                pick.accept(seconds.toInt()); dismiss()
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    }

    private fun customAmount(seconds: Int): Pair<Int, Int> = when {
        seconds % (7 * 86400) == 0 -> seconds / (7 * 86400) to 3
        seconds % 86400 == 0 -> seconds / 86400 to 2
        seconds % 3600 == 0 -> seconds / 3600 to 1
        else -> maxOf(1, seconds / 60) to 0
    }

    /**
     * FluidMessages encryption for one chat: follow the protocol default, always, or never, and this chat's own key.
     * The chat's choice always wins over the default.
     */
    @JvmStatic
    fun chatEncryption(activity: ComponentActivity, prefs: SharedPreferences, name: String, protocolLabel: String,
                       choice: String, defaultOn: Boolean, ownKey: String, hasDefaultKey: Boolean,
                       setChoice: Consumer<String>, saveKey: Consumer<String>, removeKey: Runnable) = present(activity, prefs) { dismiss ->
        var selected by remember { mutableStateOf(choice) }
        var key by remember { mutableStateOf(ownKey) }
        var problem by remember { mutableStateOf<String?>(null) }
        val on = selected == "on" || selected.isEmpty() && defaultOn
        AlertDialog(onDismissRequest = dismiss, title = { Text("FluidMessages encryption") }, text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Scrambles messages with $name before they leave your phone. Works on texts and internet chats.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Column {
                        Choice("Use the $protocolLabel default (${if (defaultOn) "on" else "off"})", selected.isEmpty()) { selected = "" }
                        Choice("Always encrypt this chat", selected == "on") { selected = "on" }
                        Choice("Never encrypt this chat", selected == "off") { selected = "off" }
                    }
                }
                if (on) {
                    item { Text("Key for this chat", style = MaterialTheme.typography.titleSmall) }
                    item {
                        SecretField(key, { key = it; problem = null }, if (hasDefaultKey) "Chat's own key (empty uses your default encryption key)" else "Encryption key",
                            monospace = true, revealedAtStart = ownKey.isEmpty())
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { key = SmsCrypto.newKey(); problem = null }) { Text("Generate a key") }
                            if (ownKey.isNotEmpty()) TextButton(onClick = { key = "" }) { Text("Clear") }
                        }
                    }
                    item {
                        Notice("$name needs FluidMessages, or an app that reads FluidMessages encryption, with the same key. " +
                            "Everyone else sees scrambled text starting with FM1:. Messages can be up to 1,000 characters.")
                    }
                }
                problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val value = key.trim()
                if (on && value.isNotEmpty() && value.length < 12) { problem = "Use a key of at least 12 characters"; return@TextButton }
                if (on && value.isEmpty() && !hasDefaultKey) { problem = "Add a key, or set a default encryption key in Privacy and security"; return@TextButton }
                setChoice.accept(selected)
                if (value != ownKey) { if (value.isEmpty()) removeKey.run() else saveKey.accept(value) }
                dismiss()
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    }

    /**
     * Pick several items from a list, each with a title and a short explanation, marked with a check. Used for
     * hidden services and for the chats that stay visible in decoy mode.
     */
    @JvmStatic
    fun chooseMany(activity: ComponentActivity, prefs: SharedPreferences, title: String, explanation: String,
                   items: List<Array<String>>, selected: List<Int>, action: String, save: Consumer<List<Int>>) = present(activity, prefs) { dismiss ->
        val chosen = remember { mutableStateListOf<Int>().apply { addAll(selected) } }
        AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (explanation.isNotEmpty()) item {
                    Text(explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
                }
                if (items.isEmpty()) item { Text("Nothing to choose yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(items) { index, item ->
                    val on = index in chosen
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable(role = Role.Checkbox) { if (on) chosen.remove(index) else chosen.add(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item[0], style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            if (item.size > 1 && item[1].isNotEmpty()) Text(item[1], style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.size(24.dp).clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .border(1.5.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (on) Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { save.accept(chosen.sorted()); dismiss() }) { Text(action) } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    }

    /** The default for each protocol, and the default key used by chats without their own. */
    @JvmStatic
    fun protocolEncryption(activity: ComponentActivity, prefs: SharedPreferences, done: Runnable) = present(activity, prefs) { dismiss ->
        val states = remember { SmsCrypto.PROTOCOLS.associateWith { SmsCrypto.globalOn(prefs, it) }.toMutableMap().let { mutableStateMapOf(*it.toList().toTypedArray()) } }
        val original = remember { SmsCrypto.defaultKey(prefs) }
        var key by remember { mutableStateOf(original) }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = dismiss, title = { Text("Encrypt with FluidMessages") }, text = {
            LazyColumn(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Turn on encryption for whole services at once. It only works with people who also use FluidMessages and the same key. A chat's own setting in its Security section always wins.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                for (protocol in SmsCrypto.PROTOCOLS) item {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { states[protocol] = !(states[protocol] ?: false) }
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (protocol) {
                            MessageProtocol.SMS -> "Texts (SMS)"
                            MessageProtocol.MMS -> "Group and picture texts (MMS)"
                            else -> protocol.label
                        }, Modifier.weight(1f))
                        Switch(states[protocol] ?: false, onCheckedChange = { states[protocol] = it })
                    }
                }
                item { Text("Default encryption key", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                item { SecretField(key, { key = it; problem = null }, "Default encryption key", monospace = true, revealedAtStart = original.isEmpty()) }
                item { OutlinedButton(onClick = { key = SmsCrypto.newKey() }) { Text("Generate a key") } }
                item {
                    Notice("Turn a protocol on only if the people you talk to there use FluidMessages with this key. " +
                        "Messages you send from FluidMessages Web aren't encrypted this way.")
                }
                problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val value = key.trim()
                if (value.isNotEmpty() && value.length < 12) { problem = "Use a key of at least 12 characters"; return@TextButton }
                if (value.isEmpty() && states.values.any { it }) { problem = "Add a default encryption key to encrypt by default"; return@TextButton }
                for ((protocol, enabled) in states) SmsCrypto.setGlobal(prefs, protocol, enabled)
                if (value != original) SmsCrypto.setDefaultKey(prefs, value)
                dismiss(); done.run()
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = choose).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** A highlighted note for things that matter, such as who can read encrypted messages. */
@Composable
internal fun Notice(text: String) {
    Text(text, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
        .padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall)
}

/** A hidden value with an eye button to show it, and a copy button. */
@Composable
internal fun SecretField(value: String, change: (String) -> Unit, label: String, monospace: Boolean = false, copy: Boolean = true,
                         revealedAtStart: Boolean = false) {
    var shown by remember { mutableStateOf(revealedAtStart) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedTextField(value, { change(it); copied = false }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        textStyle = if (monospace) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, letterSpacing = .5.sp) else LocalTextStyle.current,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        supportingText = if (copied) ({ Text("Copied") }) else null,
        trailingIcon = {
            Row {
                IconButton(onClick = { shown = !shown }, modifier = Modifier.semantics { contentDescription = if (shown) "Hide" else "Show" }) {
                    EyeGlyph(color, crossed = shown)
                }
                if (copy) IconButton(onClick = { clipboard.setText(AnnotatedString(value)); copied = true }, enabled = value.isNotEmpty(),
                    modifier = Modifier.semantics { contentDescription = "Copy" }) { CopyGlyph(color) }
            }
        })
}

@Composable
private fun EyeGlyph(color: Color, crossed: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.7.dp.toPx()
        drawArc(color, 200f, 140f, false, Offset(size.width * .06f, size.height * .25f), Size(size.width * .88f, size.height * .7f), style = Stroke(stroke))
        drawArc(color, 20f, 140f, false, Offset(size.width * .06f, size.height * .05f), Size(size.width * .88f, size.height * .7f), style = Stroke(stroke))
        drawCircle(color, size.minDimension * .14f, center, style = Stroke(stroke))
        if (crossed) drawLine(color, Offset(size.width * .15f, size.height * .15f), Offset(size.width * .85f, size.height * .85f), stroke)
    }
}

@Composable
private fun CopyGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val corner = androidx.compose.ui.geometry.CornerRadius(stroke * 1.5f)
        drawRoundRect(color, Offset(size.width * .32f, size.height * .3f), Size(size.width * .54f, size.height * .58f), corner, style = Stroke(stroke))
        drawRoundRect(color, Offset(size.width * .14f, size.height * .12f), Size(size.width * .54f, size.height * .58f), corner, style = Stroke(stroke))
    }
}

/**
 * Builds a made-up conversation the way it will look: bubbles from them on the left and from you on the right,
 * typed in place. It's saved on this phone only and never sent.
 */
object DecoyEditor {
    private data class Line(val outgoing: Boolean, val text: String)

    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, existing: String?, done: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            AppTheme(appearance) {
                val palette = remember { Palette(appearance) }
                val start = remember {
                    Privacy.decoys(prefs).filter { existing != null && it.optString("address") == Privacy.DECOY + existing }
                        .sortedBy { it.optLong("date") }.map { Line(it.optInt("type") == 2, it.optString("body")) }
                }
                var name by remember { mutableStateOf(existing.orEmpty()) }
                val lines = remember { mutableStateListOf<Line>().apply { addAll(start.ifEmpty { listOf(Line(false, "")) }) } }
                var problem by remember { mutableStateOf<String?>(null) }
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(name, { name = it.take(60); problem = null }, label = { Text("Name shown in the inbox") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Tap a bubble to type. It looks like a normal chat in your inbox while hidden chats are closed, and nothing is ever sent.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.bg).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(lines) { index, line ->
                            val fill = if (line.outgoing) palette.sms else palette.incoming
                            val ink = bubbleTextColor(fill)
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.outgoing) Alignment.End else Alignment.Start) {
                                BasicTextField(line.text, { lines[index] = line.copy(text = it.take(500)); problem = null },
                                    textStyle = TextStyle(color = ink, fontSize = appearance.size.sp), cursorBrush = SolidColor(ink),
                                    modifier = Modifier.widthIn(min = 80.dp, max = 280.dp).background(fill, RoundedCornerShape(18.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    decorationBox = { field ->
                                        if (line.text.isEmpty()) Text(if (line.outgoing) "Your message" else "Their message", color = ink.copy(alpha = .6f),
                                            fontSize = appearance.size.sp)
                                        field()
                                    })
                                Row {
                                    TextButton(onClick = { lines[index] = line.copy(outgoing = !line.outgoing) }) {
                                        Text(if (line.outgoing) "Move to them" else "Move to me", fontSize = 12.sp)
                                    }
                                    if (lines.size > 1) TextButton(onClick = { lines.removeAt(index) }) {
                                        Text("Remove", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { lines.add(Line(false, "")) }, modifier = Modifier.weight(1f)) { Text("+ From them") }
                        OutlinedButton(onClick = { lines.add(Line(true, "")) }, modifier = Modifier.weight(1f)) { Text("+ From me") }
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (existing != null) OutlinedButton(onClick = {
                            Privacy.removeDecoyChat(prefs, existing); done.run()
                        }, modifier = Modifier.weight(1f)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        AppButton(onClick = {
                            val who = name.trim()
                            val kept = lines.filter { it.text.isNotBlank() }
                            when {
                                who.isEmpty() -> problem = "Add a name"
                                kept.isEmpty() -> problem = "Add at least one message"
                                else -> {
                                    if (existing != null) Privacy.removeDecoyChat(prefs, existing)
                                    if (who != existing) Privacy.removeDecoyChat(prefs, who)
                                    // Spread the messages over the last hour so they read like a real conversation.
                                    val now = System.currentTimeMillis()
                                    kept.forEachIndexed { index, line ->
                                        Privacy.addDecoy(prefs, who, line.text.trim(), line.outgoing, now - (kept.size - index) * 4 * 60_000L)
                                    }
                                    done.run()
                                }
                            }
                        }, modifier = Modifier.weight(1f)) { Text("Save") }
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
