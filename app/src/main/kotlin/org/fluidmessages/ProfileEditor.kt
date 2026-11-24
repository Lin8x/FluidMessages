package org.fluidmessages

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.graphics.ImageDecoder
import android.widget.LinearLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal val wallpapers = listOf(
    Choice("none", "Solid"),
    Choice("horizon", "Horizon"),
    Choice("aurora", "Aurora"),
    Choice("love", "Love"),
    Choice("orchard", "Orchard"),
)

private fun wallpaperBrush(name: String, base: Color): Brush {
    return when (name) {
        "horizon" -> Brush.verticalGradient(listOf(lerp(base, Color(0xff317bc4), .25f),
            base, lerp(base, Color(0xffe29b70), .27f)))
        "aurora" -> Brush.linearGradient(listOf(base, lerp(base, Color(0xff35b6a4), .28f),
            lerp(base, Color(0xff735bce), .19f), base))
        "love" -> Brush.radialGradient(listOf(lerp(base, Color(0xffe96b9b), .32f),
            lerp(base, Color(0xff9345a0), .15f), base))
        "orchard" -> Brush.verticalGradient(listOf(base, lerp(base, Color(0xff4c9e64), .24f),
            lerp(base, Color(0xffcda05a), .20f)))
        else -> Brush.verticalGradient(listOf(base, base))
    }
}

@Composable
internal fun WallpaperSurface(name: String, base: Color, modifier: Modifier = Modifier,
                              content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, name) {
        value = null
        val uri = WallpaperPhotos.source(context, name)
        if (uri != null) value = withContext(Dispatchers.IO) {
            try {
                val source = if (uri.scheme == "file") ImageDecoder.createSource(java.io.File(requireNotNull(uri.path)))
                    else ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, header, _ ->
                    val size = header.size
                    val ratio = (1600f / maxOf(size.width, size.height)).coerceAtMost(1f)
                    decoder.setTargetSize((size.width * ratio).roundToInt().coerceAtLeast(1),
                        (size.height * ratio).roundToInt().coerceAtLeast(1))
                }.asImageBitmap()
            } catch (_: SecurityException) { null }
              catch (_: java.io.IOException) { null }
              catch (_: IllegalArgumentException) { null }
        }
    }
    Box(modifier.background(wallpaperBrush(name, base))) {
        image?.let {
            Image(it, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(base.copy(alpha = .72f)))
        }
        content()
    }
}

@Composable
internal fun WallpaperPicker(selected: String, base: Color, inherit: Boolean = false, changed: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var feedback by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                feedback = ""
                changed(uri.toString())
            } catch (_: SecurityException) {
                feedback = "This file provider could not keep the wallpaper available."
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Conversation wallpaper", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            val choices = if (inherit) listOf(Choice("", "Global")) + wallpapers else wallpapers
            for (choice in choices) {
                val active = if (choice.key == "none" && !inherit) selected in listOf("none", "") else choice.key == selected
                Surface(
                    modifier = Modifier.width(91.dp).semantics { this.selected = active },
                    onClick = { changed(choice.key); feedback = "" }, shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(if (active) 2.dp else 1.dp, if (active) scheme.primary else scheme.outlineVariant),
                ) {
                    Column {
                        WallpaperSurface(choice.key, base, Modifier.fillMaxWidth().height(58.dp)) {}
                        Text(choice.label, Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            color = scheme.onSurface, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) {
            Text(if (WallpaperPhotos.isPhoto(selected)) "Change photo" else "Choose a photo")
        }
        if (WallpaperPhotos.isPhoto(selected)) Text("Photo selected", color = scheme.onSurfaceVariant)
        if (feedback.isNotEmpty()) Text(feedback, color = scheme.error)
    }
}

internal fun resolveChatAppearance(base: Appearance, profile: ChatProfile): Appearance {
    val theme = MessageStyle.Theme.values().firstOrNull { it.name == profile.theme() }
    val colors = theme?.let { it.colors(base.dark) ?: MessageStyle.fluidColors(base.dark, base.contrast) }
    fun shade(value: Int?, fallback: Int) = value?.or(0xff000000.toInt()) ?: fallback
    return base.copy(
        theme = theme ?: base.theme,
        canvas = profile.background() ?: shade(colors?.background(), base.canvas),
        accent = profile.outgoing() ?: shade(colors?.sms(), base.accent),
        rcs = profile.outgoing() ?: shade(colors?.rcs(), base.rcs),
        automated = shade(colors?.automated(), base.automated),
        incoming = profile.incoming() ?: if (theme == null) base.incoming else
            MessageStyle.Swatch.GRAY_5.color(base.dark, base.contrast),
        controls = shade(colors?.controls(), base.controls),
        chrome = shade(colors?.chrome(), base.chrome),
        wallpaper = profile.wallpaper().ifEmpty { base.wallpaper },
    )
}

object ConversationDetails {
    @JvmStatic @JvmOverloads
    fun mount(root: LinearLayout, prefs: SharedPreferences, name: String, initials: String, subtitle: String, photo: Int, photoUri: Uri?,
              person: String, reach: List<ReachOption>, edit: Runnable?, actions: List<SettingsAction>, members: List<GroupMember>,
              security: List<SettingsAction>, info: List<SettingsAction> = emptyList()) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            AppTheme(appearance) {
                val scheme = MaterialTheme.colorScheme
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Avatar(initials, name, 80, photo, photoUri)
                            Text(name, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface,
                                fontWeight = FontWeight.SemiBold)
                            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                            if (edit != null) TextButton(onClick = edit::run) { Text("Edit contact") }
                        }
                    }
                    // The same Message, Call, and Video buttons as a contact's page in the Phone tab.
                    if (reach.isNotEmpty()) item {
                        ReachTiles(prefs, person, reach, Palette(appearance).inboxAction, scheme.surfaceContainer)
                    }
                    // Every number, email, and other detail saved on the contact, like a contact card.
                    if (info.isNotEmpty()) item { DetailGroup("Contact info", info) }
                    if (members.isNotEmpty()) item { GroupMembers(members) }
                    item { DetailGroup("Customize", actions.take(1)) }
                    if (actions.size > 1) item { DetailGroup("Conversation", actions.drop(1)) }
                    if (security.isNotEmpty()) item { DetailGroup("Security", security) }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

@Composable
private fun DetailGroup(title: String, actions: List<SettingsAction>) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer) {
            Column {
                actions.forEachIndexed { index, action ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 15.dp), color = scheme.outlineVariant)
                    Row(Modifier.fillMaxWidth()
                        .then(if (action.info) Modifier else Modifier.clickable(role = Role.Button, onClick = action.action::run))
                        .heightIn(min = 62.dp).padding(horizontal = 15.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(action.label, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                            if (action.detail.isNotEmpty()) Text(action.detail, color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (action.security != null) SecurityLock(action.security, scheme.surfaceContainer, size = 15)
                        else if (action.info) InfoMark(scheme.onSurfaceVariant)
                        else CanvasChevron(scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasChevron(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(13.dp)) {
        val start = size.width * .35f
        val end = size.width * .7f
        drawLine(color, androidx.compose.ui.geometry.Offset(start, size.height * .2f),
            androidx.compose.ui.geometry.Offset(end, center.y), strokeWidth = 2.dp.toPx())
        drawLine(color, androidx.compose.ui.geometry.Offset(end, center.y),
            androidx.compose.ui.geometry.Offset(start, size.height * .8f), strokeWidth = 2.dp.toPx())
    }
}

object ProfileEditor {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, key: String) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { ProfileForm(prefs, key) }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

@Composable
private fun ProfileForm(prefs: SharedPreferences, key: String) {
    val global = remember { Appearance.read(prefs) }
    val old = remember { ChatProfile.read(prefs, key) }
    var profile by remember { mutableStateOf(old) }
    var status by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf(false) }
    fun apply(next: ChatProfile) {
        try {
            next.save(prefs, key)
            profile = next
            status = "Appearance saved"
        } catch (_: IllegalArgumentException) {
            status = "Could not save this appearance change"
        }
    }
    fun useTheme(selected: String) {
        val wallpaper = MessageStyle.Theme.values().firstOrNull { it.name == selected }?.wallpaper().orEmpty()
        apply(ChatProfile(old.alias(), selected, wallpaper, null, null, null))
    }
    val resolved = resolveChatAppearance(global, profile)
    AppTheme(global) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { SampleConversation(resolved) }
                item {
                    ThemeDropdown("Conversation theme", profile.theme(),
                        listOf(Choice("", "Use global theme")) + MessageStyle.Theme.values().sortedBy { it.name != "LOVE" }.map {
                            Choice(it.name, it.label, it.colors(global.dark)?.sms()?.or(0xff000000.toInt()))
                        }, global.speed, menuHeight = 460.dp, changed = ::useTheme)
                }
                item {
                    WallpaperPicker(profile.wallpaper(), Color(resolved.canvas), inherit = true) { selected ->
                        apply(ChatProfile(old.alias(), profile.theme(), selected,
                            profile.background(), profile.outgoing(), profile.incoming()))
                    }
                }
                item {
                    Text("Bubble and background colors", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    val fields = listOf(
                        Triple("Outgoing bubble", profile.outgoing(), resolved.accent),
                        Triple("Incoming bubble", profile.incoming(), resolved.incoming),
                        Triple("Background", profile.background(), resolved.canvas),
                    )
                    fields.forEachIndexed { index, (label, selected, preview) ->
                        ProfileColor(label, selected, preview, global) { value ->
                            apply(ChatProfile(old.alias(), profile.theme(), profile.wallpaper(),
                                if (index == 2) value else profile.background(),
                                if (index == 0) value else profile.outgoing(),
                                if (index == 1) value else profile.incoming()))
                        }
                    }
                }
                item { TextButton(onClick = { reset = true }) { Text("Reset to theme") } }
            }
            if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        if (reset) FormPage(onDismissRequest = { reset = false }, title = { Text("Reset this chat’s theme?") },
            text = { Text("Restore the selected theme’s colors and wallpaper for this chat.") },
            confirmButton = { TextButton(onClick = { useTheme(profile.theme()); reset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ProfileColor(label: String, selected: Int?, preview: Int, global: Appearance, changed: (Int?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 49.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { editing = true }.padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(25.dp).background(Color(preview), CircleShape))
        Text(label, color = scheme.onSurface, modifier = Modifier.weight(1f))
        Text(if (selected == null) "Theme" else "Custom", color = scheme.primary)
    }
    if (editing) VisualColorDialog(label, preview, global, { editing = false }) {
        changed(it)
        editing = false
    }
}

@Composable
internal fun VisualColorDialog(title: String, current: Int, appearance: Appearance,
                               close: () -> Unit, changed: (Int?) -> Unit) {
    val hsv = remember(current) { FloatArray(3).also { android.graphics.Color.colorToHSV(current, it) } }
    var hue by remember(current) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(current) { mutableFloatStateOf(hsv[1]) }
    var brightness by remember(current) { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
    FormPage(onDismissRequest = close, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.fillMaxWidth().height(45.dp).background(Color(color), RoundedCornerShape(10.dp)))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MessageStyle.Swatch.values().forEach { swatch ->
                        Box(Modifier.size(42.dp).clip(CircleShape)
                            .background(Color(swatch.color(appearance.dark, appearance.contrast)))
                            .clickable(onClickLabel = swatch.label) {
                                android.graphics.Color.colorToHSV(swatch.color(appearance.dark, appearance.contrast), hsv)
                                hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                            })
                    }
                }
                Text("Hue"); Slider(hue, { hue = it }, valueRange = 0f..359.9f)
                Text("Saturation"); Slider(saturation, { saturation = it })
                Text("Brightness"); Slider(brightness, { brightness = it })
            }
        },
        confirmButton = { TextButton(onClick = { changed(color) }) { Text("Use color") } },
        dismissButton = { TextButton(onClick = { changed(null) }) { Text("Use theme color") } },
    )
}
