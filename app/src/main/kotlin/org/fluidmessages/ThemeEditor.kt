package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.function.IntConsumer
import kotlin.math.roundToInt
import org.json.JSONObject

object ThemeEditor {
    @JvmStatic
    fun mount(root: LinearLayout, preferences: SharedPreferences, recolor: IntConsumer) = mount(root, preferences, recolor, null)

    /** [openSounds] opens Settings → Sounds and notifications, where the message sounds live now. */
    @JvmStatic
    fun mount(root: LinearLayout, preferences: SharedPreferences, recolor: IntConsumer, openSounds: Runnable?) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { Editor(preferences, recolor::accept, openSounds) }
        root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

}

private const val SAVED_PALETTES = "savedColorThemes"
private const val ACTIVE_PALETTE = "activeColorTheme"
private const val PALETTE_PREFIX = "custom:"
private val internetColors = listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)
private fun colorKey(protocol: MessageProtocol) = protocol.key() + "Color"
private val colorRoles = internetColors.map(::colorKey) + listOf("accent", "rcsColor", "automatedColor", "incoming", "canvas", "chromeColor", "controlColor")
private val paletteKeys = listOf("colorTheme", "dark", "classicColors", "contrastColors") + colorRoles +
    colorRoles.map { it + "Swatch" }

internal fun SharedPreferences.Editor.selectBuiltInTheme(name: String) {
    for (key in Backup.THEME) {
        if (key != "dark" && key != SAVED_PALETTES) remove(key)
    }
    putString("colorTheme", name)
    putString("wallpaper", MessageStyle.Theme.valueOf(name).wallpaper())
}

private fun SharedPreferences.Editor.selectSavedPalette(name: String, colors: JSONObject) {
    val base = MessageStyle.Theme.values().firstOrNull { it.name == colors.optString("colorTheme") }
        ?: MessageStyle.Theme.FLUID
    selectBuiltInTheme(base.name)
    for (key in paletteKeys) {
        when (val value = colors.opt(key)) {
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
        }
    }
    putString(ACTIVE_PALETTE, name)
}

private fun readPalettes(prefs: SharedPreferences): JSONObject = try {
    JSONObject(prefs.getString(SAVED_PALETTES, "{}") ?: "{}")
} catch (_: org.json.JSONException) {
    JSONObject()
}

internal data class Appearance(
    val dark: Boolean,
    val theme: MessageStyle.Theme,
    val controls: Int,
    val chrome: Int,
    val searchPosition: String,
    val toolbarFinish: String,
    val chromeOpacity: Int,
    val density: String,
    val tails: Boolean,
    val wallpaper: String,
    val accent: Int,
    val rcs: Int,
    val automated: Int,
    val classic: Boolean,
    val contrast: Boolean,
    val canvas: Int,
    val incoming: Int,
    val radius: Int,
    val size: Int,
    val padding: Int,
    val speed: Int,
    val protocolColors: Map<MessageProtocol, Int>,
) {
    companion object {
        fun read(prefs: SharedPreferences): Appearance {
            val dark = prefs.getBoolean("dark", false)
            return Appearance(
                dark = dark,
                theme = MessageStyle.theme(prefs),
                controls = MessageStyle.controls(prefs),
                chrome = MessageStyle.chrome(prefs),
                searchPosition = prefs.getString("searchPosition", "bottom") ?: "bottom",
                toolbarFinish = prefs.getString("toolbarFinish", "plain") ?: "plain",
                chromeOpacity = prefs.getInt("chromeOpacity", 84).coerceIn(25, 100),
                density = prefs.getString("inboxDensity", "standard") ?: "standard",
                tails = prefs.getBoolean("bubbleTails", false),
                wallpaper = prefs.getString("wallpaper", "none") ?: "none",
                accent = MessageStyle.sms(prefs),
                rcs = MessageStyle.rcs(prefs),
                automated = MessageStyle.automated(prefs),
                classic = prefs.getBoolean("classicColors", false),
                contrast = prefs.getBoolean("contrastColors", false),
                canvas = MessageStyle.canvas(prefs),
                incoming = MessageStyle.incoming(prefs),
                radius = prefs.getInt("radius", 18).coerceIn(2, 30),
                size = prefs.getInt("size", 16).coerceIn(12, 28),
                padding = prefs.getInt("padding", 12).coerceIn(6, 24),
                speed = prefs.getInt("speed", 220).coerceIn(0, 800),
                protocolColors = internetColors.mapNotNull { protocol ->
                    val key = colorKey(protocol)
                    val value = MessageStyle.selection(prefs, key)?.color(dark, prefs.getBoolean("contrastColors", false))
                        ?: prefs.getInt(key, 0).takeIf { prefs.contains(key) }
                    value?.let { protocol to it }
                }.toMap(),
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Editor(prefs: SharedPreferences, recolor: (Int) -> Unit, openSounds: Runnable? = null) {
    var appearance by remember { mutableStateOf(Appearance.read(prefs)) }
    var openGroup by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf(false) }
    var savePalette by remember { mutableStateOf(false) }
    var deletePalette by remember { mutableStateOf(false) }
    var paletteName by remember { mutableStateOf("") }
    var library by remember { mutableStateOf(readPalettes(prefs)) }
    var activePalette by remember { mutableStateOf(prefs.getString(ACTIVE_PALETTE, "").orEmpty()) }
    fun change(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        appearance = Appearance.read(prefs)
        activePalette = prefs.getString(ACTIVE_PALETTE, "").orEmpty()
    }
    fun saveColors(name: String) {
        val colors = JSONObject()
        val values = prefs.all
        paletteKeys.forEach { key -> values[key]?.let { colors.put(key, it) } }
        library = readPalettes(prefs).put(name, colors)
        change { putString(SAVED_PALETTES, library.toString()).putString(ACTIVE_PALETTE, name) }
        savePalette = false
    }
    SideEffect { recolor(appearance.canvas) }
    AppTheme(appearance) {
        val scheme = MaterialTheme.colorScheme
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val themes = MessageStyle.Theme.values().map { theme ->
                    Choice(theme.name, theme.label, theme.colors(appearance.dark)?.sms()?.or(0xff000000.toInt()))
                }
                val custom = library.keys().asSequence().sorted().map { Choice(PALETTE_PREFIX + it, it) }.toList()
                val selected = if (library.has(activePalette)) PALETTE_PREFIX + activePalette else appearance.theme.name
                ThemeDropdown("Color theme", selected, themes + custom, appearance.speed, menuHeight = 460.dp) {
                    change {
                        if (it.startsWith(PALETTE_PREFIX)) {
                            val name = it.removePrefix(PALETTE_PREFIX)
                            library.optJSONObject(name)?.let { colors -> selectSavedPalette(name, colors) }
                        } else selectBuiltInTheme(it)
                    }
                }
            }
            item {
                // Opening Inbox and toolbar switches the preview to the inbox, where those settings show.
                var preview by remember { mutableStateOf("conversation") }
                LaunchedEffect(openGroup) {
                    if (openGroup == "inbox") preview = "inbox" else if (openGroup == "bubbles" || openGroup == "colors") preview = "conversation"
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("conversation" to "Conversation", "inbox" to "Inbox").forEachIndexed { index, (key, label) ->
                            SegmentedButton(selected = preview == key, onClick = { preview = key },
                                shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                        }
                    }
                    if (preview == "inbox") InboxPreview(appearance) else SampleConversation(appearance)
                }
            }
            item {
                ThemeDropdown("Appearance mode", if (appearance.dark) "dark" else "light",
                    listOf(Choice("light", "Light"), Choice("dark", "Dark")), appearance.speed) {
                    change { putBoolean("dark", it == "dark") }
                }
            }
            item {
                AppearanceGroup("Customize colors", "Based on ${appearance.theme.label}", openGroup == "colors",
                    { openGroup = if (openGroup == "colors") "" else "colors" }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { paletteName = activePalette; savePalette = true }) { Text("Save custom palette") }
                        if (library.has(activePalette)) TextButton(onClick = { deletePalette = true }) { Text("Delete saved palette") }
                    }
                    ThemeDropdown("Palette contrast", if (appearance.contrast) "increased" else "standard",
                        listOf(Choice("standard", "Standard"), Choice("increased", "Increased")), appearance.speed) {
                        change { putBoolean("contrastColors", it == "increased") }
                    }
                    ColorDropdown("SMS color", "accent", appearance.accent, appearance, prefs, ::change)
                    ColorDropdown("RCS color (preview)", "rcsColor", appearance.rcs, appearance, prefs, ::change,
                        listOf(MessageStyle.Swatch.INDIGO, MessageStyle.Swatch.PINK, MessageStyle.Swatch.CYAN, MessageStyle.Swatch.MINT))
                    ColorDropdown("Automated color", "automatedColor", appearance.automated, appearance, prefs, ::change)
                    // Every outgoing protocol color sits together, in the same order as the Auto route.
                    for (protocol in internetColors) {
                        ColorDropdown(protocol.label + " color", colorKey(protocol), protocol.tint(appearance).toArgb(),
                            appearance, prefs, ::change)
                    }
                    ColorDropdown("Incoming bubble color", "incoming", appearance.incoming, appearance, prefs, ::change)
                    ColorDropdown("Background color", "canvas", appearance.canvas, appearance, prefs, ::change)
                    ColorDropdown("Buttons and unread indicators", "controlColor", appearance.controls, appearance, prefs, ::change)
                    ColorDropdown("Toolbar and composer tint", "chromeColor", appearance.chrome, appearance, prefs, ::change)
                }
            }
            item {
                AppearanceGroup("Wallpaper", "Built-in designs and photos", openGroup == "wallpaper",
                    { openGroup = if (openGroup == "wallpaper") "" else "wallpaper" }) {
                    WallpaperPicker(appearance.wallpaper, Color(appearance.canvas)) { change { putString("wallpaper", it) } }
                }
            }
            item {
                AppearanceGroup("Inbox and toolbar", "Search placement, density and toolbar finish", openGroup == "inbox",
                    { openGroup = if (openGroup == "inbox") "" else "inbox" }) {
                    ThemeDropdown("Search position", appearance.searchPosition,
                        listOf(Choice("top", "Top search bar"), Choice("bottom", "Bottom dock")), appearance.speed) {
                        change { putString("searchPosition", it) }
                    }
                    ThemeDropdown("Toolbar finish", appearance.toolbarFinish,
                        listOf(Choice("plain", "Plain"), Choice("tinted", "Tinted"), Choice("floating", "Floating translucent")), appearance.speed) {
                        change { putString("toolbarFinish", it) }
                    }
                    if (appearance.toolbarFinish == "floating") {
                        ThemeSlider("Toolbar opacity (%)", appearance.chromeOpacity, 25..100) { change { putInt("chromeOpacity", it) } }
                    }
                    ThemeDropdown("Inbox density", appearance.density,
                        listOf(Choice("compact", "Compact"), Choice("standard", "Standard"), Choice("roomy", "Roomy")), appearance.speed) {
                        change { putString("inboxDensity", it) }
                    }
                }
            }
            item {
                AppearanceGroup("Message bubbles", "Text size, shape and spacing", openGroup == "bubbles",
                    { openGroup = if (openGroup == "bubbles") "" else "bubbles" }) {
                    ThemeToggle("Bubble tails", appearance.tails) { change { putBoolean("bubbleTails", it) } }
                    ThemeSlider("Bubble radius", appearance.radius, 2..30) { change { putInt("radius", it) } }
                    ThemeSlider("Message text size", appearance.size, 12..28) { change { putInt("size", it) } }
                    ThemeSlider("Bubble padding", appearance.padding, 6..24) { change { putInt("padding", it) } }
                }
            }
            item {
                AppearanceGroup("Motion and sound", "Animation and haptics · sounds are in Sounds and notifications", openGroup == "feedback",
                    { openGroup = if (openGroup == "feedback") "" else "feedback" }) {
                    val speeds = listOf(Choice("0", "Off"), Choice("130", "Quick"), Choice("220", "Standard"),
                        Choice("360", "Relaxed"))
                    ThemeDropdown("Motion preset", appearance.speed.toString(), speeds, appearance.speed,
                        fallback = "Custom · ${appearance.speed} ms") { change { putInt("speed", it.toInt()) } }
                    ThemeSlider("Animation duration (ms)", appearance.speed, 0..800) { change { putInt("speed", it) } }
                    FeedbackOptions(prefs, appearance.speed, openSounds?.let { open -> { open.run() } })
                }
            }
            item { TextButton(onClick = { reset = true }) { Text("Reset to theme") } }
        }
        if (reset) FormPage(onDismissRequest = { reset = false }, title = { Text("Reset to ${activePalette.ifEmpty { appearance.theme.label }}?") },
            text = { Text("Restore this theme’s colors, wallpaper, and layout.") },
            confirmButton = { TextButton(onClick = {
                change {
                    val saved = library.optJSONObject(activePalette)
                    if (saved == null) selectBuiltInTheme(appearance.theme.name)
                    else selectSavedPalette(activePalette, saved)
                }
                openGroup = ""
                reset = false
            }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } })
        if (savePalette) {
            val name = paletteName.trim()
            val existing = library.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) }
            val builtIn = MessageStyle.Theme.values().any { it.label.equals(name, ignoreCase = true) }
            val limitReached = existing == null && library.length() >= 20
            FormPage(onDismissRequest = { savePalette = false }, title = { Text("Save custom palette") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(paletteName, { paletteName = it.take(34) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), label = { Text("Palette name") }, isError = builtIn)
                        if (builtIn || limitReached) Text(if (builtIn) "Choose a name other than a built-in theme."
                            else "Delete a saved palette before adding another.", color = scheme.error)
                    }
                },
                confirmButton = { TextButton(enabled = name.isNotEmpty() && !builtIn && !limitReached,
                    onClick = { saveColors(existing ?: name) }) { Text(if (existing == null) "Save" else "Replace") } },
                dismissButton = { TextButton(onClick = { savePalette = false }) { Text("Cancel") } })
        }
        if (deletePalette) FormPage(onDismissRequest = { deletePalette = false },
            title = { Text("Delete ${activePalette}?") },
            confirmButton = { DangerButton(onClick = {
                library = readPalettes(prefs).apply { remove(activePalette) }
                change { putString(SAVED_PALETTES, library.toString()).remove(ACTIVE_PALETTE) }
                deletePalette = false
            }, label = "Delete", modifier = Modifier.padding(start = 4.dp)) },
            dismissButton = { TextButton(onClick = { deletePalette = false }) { Text("Cancel") } })
    }
}

@Composable
private fun AppearanceGroup(label: String, detail: String, expanded: Boolean, toggle: () -> Unit,
                            content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(role = Role.Button, onClick = toggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (expanded) "−" else "+", color = scheme.primary, fontSize = 22.sp)
        }
        if (expanded) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

internal data class Choice(val key: String, val label: String, val color: Int? = null)

@Composable
internal fun ThemeDropdown(
    label: String, selected: String, options: List<Choice>, motion: Int,
    fallback: String = "", menuHeight: androidx.compose.ui.unit.Dp = 355.dp, changed: (String) -> Unit,
) {
    val current = options.firstOrNull { it.key == selected }
    var expanded by remember { mutableStateOf(false) }
    val tilt by animateFloatAsState(if (expanded) 180f else 0f, tween(motion.coerceAtMost(220)), label = "Dropdown")
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceContainerLow)
                .clickable(role = Role.Button, onClickLabel = "Choose $label") { expanded = true }
                .semantics {
                    contentDescription = label
                    stateDescription = current?.label ?: fallback
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            current?.color?.let { ColorDot(it) }
            Column(Modifier.weight(1f)) {
                Text(label, color = scheme.onSurface, style = MaterialTheme.typography.labelMedium)
                Text(current?.label ?: fallback, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            }
            Text("⌄", Modifier.rotate(tilt), color = scheme.primary, fontSize = 20.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.width(255.dp).heightIn(max = menuHeight)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label)
                            option.color?.let { Text(hex(it), style = MaterialTheme.typography.labelSmall) }
                        }
                    },
                    leadingIcon = option.color?.let { fill -> { ColorDot(fill) } },
                    trailingIcon = if (selected == option.key) ({ Text("✓", color = scheme.primary) }) else null,
                    onClick = {
                        expanded = false
                        changed(option.key)
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorDot(value: Int) {
    Box(Modifier.size(23.dp).background(Color(value), CircleShape)
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .18f), CircleShape))
}

@Composable
private fun ColorDropdown(
    label: String, key: String, value: Int, appearance: Appearance, prefs: SharedPreferences,
    change: (SharedPreferences.Editor.() -> Unit) -> Unit,
    featured: List<MessageStyle.Swatch> = emptyList(),
) {
    val swatch = MessageStyle.selection(prefs, key)
    val custom = prefs.contains(key) && swatch == null
    var editingHex by remember(swatch, custom) { mutableStateOf(false) }
    var editingColor by remember { mutableStateOf(false) }
    val options = listOf(Choice("default", "Theme default", if (!custom && swatch == null) value else null)) +
        featured.map { Choice(it.name, "Apple ${it.label}", it.color(appearance.dark, appearance.contrast)) } +
        listOf(
            Choice("visual", "Custom color", if (custom) value else null),
            Choice("custom", "Enter a hex code")) +
        MessageStyle.Swatch.values().filterNot { it in featured }
            .map { Choice(it.name, it.label, it.color(appearance.dark, appearance.contrast)) }
    Column {
        ThemeDropdown(label, swatch?.name ?: if (custom) "visual" else "default", options, appearance.speed) { choice ->
            if (choice == "visual") {
                editingHex = false
                editingColor = true
            } else if (choice == "custom") {
                editingHex = true
            } else {
                editingHex = false
                change {
                    remove(key)
                    if (choice == "default") remove(key + "Swatch")
                    else putString(key + "Swatch", choice)
                }
            }
        }
        AnimatedVisibility(editingHex) {
            Box(Modifier.padding(top = 6.dp)) {
                HexColor("Custom $label", value) { color ->
                    change { remove(key + "Swatch"); putInt(key, color) }
                    editingHex = false
                }
            }
        }
    }
    if (editingColor) VisualColorDialog(label, value, appearance, { editingColor = false }) { color ->
        change {
            remove(key + "Swatch")
            if (color == null) remove(key) else putInt(key, color)
        }
        editingColor = false
    }
}

private fun hex(value: Int) = String.format(Locale.ROOT, "#%06X", value and 0xffffff)

private val internetSamples = listOf(MessageProtocol.MATRIX to "Matrix preview", MessageProtocol.XMPP to "XMPP preview",
    MessageProtocol.EMAIL to "Email preview", MessageProtocol.IRC to "IRC preview")

@Composable
internal fun SampleConversation(appearance: Appearance) {
    val radius by animateDpAsState(
        targetValue = appearance.radius.dp,
        animationSpec = tween(durationMillis = appearance.speed),
        label = "Bubble radius",
    )
    val duration = appearance.speed.coerceAtMost(360)
    val canvas by animateColorAsState(Color(appearance.canvas), tween(duration), label = "Preview background")
    val incoming by animateColorAsState(Color(appearance.incoming), tween(duration), label = "Incoming color")
    val sms by animateColorAsState(Color(appearance.accent), tween(duration), label = "SMS color")
    val rcs by animateColorAsState(Color(appearance.rcs), tween(duration), label = "RCS color")
    val automated by animateColorAsState(Color(appearance.automated), tween(duration), label = "Automated color")
    WallpaperSurface(appearance.wallpaper, canvas, Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sample conversation", color = readableOn(canvas), fontWeight = FontWeight.Medium)
            SampleBubble("Coffee at ten?", incoming, radius, appearance, Modifier.align(Alignment.Start))
            SampleBubble("See you there!", sms, radius, appearance, Modifier.align(Alignment.End), outgoing = true)
            SampleBubble("RCS preview", rcs, radius, appearance, Modifier.align(Alignment.End), outgoing = true)
            SampleBubble("Automated preview", automated, radius, appearance, Modifier.align(Alignment.End), outgoing = true)
            for ((protocol, text) in internetSamples) {
                val fill by animateColorAsState(protocol.tint(appearance), tween(duration), label = protocol.label + " color")
                SampleBubble(text, fill, radius, appearance, Modifier.align(Alignment.End), outgoing = true)
            }
        }
    }
}

/** The setup guide's first page: an ordinary chat between two made-up friends. No service names or tags. */
internal val welcomeMessages = listOf(
    "Hi! Are we still on for lunch?" to false,
    "Yes! Noon at the park?" to true,
    "Perfect. See you there!" to false,
)

@Composable
internal fun WelcomeConversation(appearance: Appearance) {
    val radius = appearance.radius.dp
    val canvas = Color(appearance.canvas)
    WallpaperSurface(appearance.wallpaper, canvas, Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Maya Chen", color = readableOn(canvas), fontWeight = FontWeight.Medium)
            for ((text, outgoing) in welcomeMessages) {
                SampleBubble(text, Color(if (outgoing) appearance.accent else appearance.incoming), radius, appearance,
                    Modifier.align(if (outgoing) Alignment.End else Alignment.Start), outgoing = outgoing)
            }
        }
    }
}

@Composable
private fun SampleBubble(text: String, fill: Color, radius: androidx.compose.ui.unit.Dp, appearance: Appearance, modifier: Modifier, outgoing: Boolean = false) {
    val shown = accessibleBubbleFill(fill)
    Box(modifier.widthIn(max = 270.dp).bubbleTail(shown, appearance.tails, outgoing).background(shown, RoundedCornerShape(radius)).padding(
        horizontal = appearance.padding.dp,
        vertical = (appearance.padding * 0.75f).dp,
    )) {
        Text(text, color = bubbleTextColor(fill), fontSize = appearance.size.sp)
    }
}

private fun readableOn(background: Color) = if (background.luminance() > 0.179f) Color.Black else Color.White

@Composable
private fun ThemeSlider(label: String, value: Int, range: IntRange, changed: (Int) -> Unit) {
    Column {
        Text("$label · $value", color = MaterialTheme.colorScheme.onSurface)
        Slider(value.toFloat(), { changed(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

@Composable
private fun ThemeToggle(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    SettingsSwitch(label, "", checked, changed)
}

@Composable
private fun HexColor(label: String, value: Int, changed: (Int) -> Unit) {
    var input by remember(value) { mutableStateOf(hex(value)) }
    val parsed = input.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 7) input = it },
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        TextButton(
            enabled = parsed != null && (parsed or 0xff000000.toInt()) != value,
            onClick = { parsed?.let { changed(it or 0xff000000.toInt()) } },
        ) { Text("Apply") }
    }
}
