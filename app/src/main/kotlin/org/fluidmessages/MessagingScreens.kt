package org.fluidmessages

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.Consumer
import java.util.function.BiConsumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ThreadRow @JvmOverloads constructor(
    val id: String,
    val name: String,
    val initials: String,
    val preview: String,
    val time: String,
    val unread: Boolean,
    val pinned: Boolean,
    val photo: Int = 0,
    val photoUri: Uri? = null,
    val photoRevision: Int = 0,
    val protocol: String = "",
)

internal data class ChatLine @JvmOverloads constructor(
    val id: String,
    val text: String,
    val day: String,
    val outgoing: Boolean,
    val status: String,
    val time: String,
    val author: String? = null,
    val reactionTarget: String? = null,
    val photo: Uri? = null,
    val format: MessageProtocol? = null,
)

private data class Palette(val appearance: Appearance) {
    val bg = Color(appearance.canvas)
    private val ui = appearance.uiColors
    val ink = Color(ui.ink())
    val action = Color(ui.action())
    private val controlTint = Color(appearance.controls)
    private val tint = Color(appearance.chrome)
    val toolbar = when (appearance.toolbarFinish) {
        "floating" -> tint.copy(alpha = appearance.chromeOpacity / 100f)
        "tinted" -> tint
        else -> bg
    }
    private val toolbarBackground = toolbar.compositeOver(bg)
    val toolbarInk = Color(MessageStyle.textOn(toolbarBackground.toArgb()))
    val inboxAction = coloredActionAgainst(bg)
    val toolbarAction = coloredActionAgainst(toolbarBackground)

    private fun coloredActionAgainst(background: Color): Color {
        val light = maxOf(controlTint.red, controlTint.green, controlTint.blue)
        val dark = minOf(controlTint.red, controlTint.green, controlTint.blue)
        // A toolbar action needs a visible tint and at least 3:1 contrast against its surface.
        if (light - dark >= .10f && contrast(controlTint, background) >= 3f) return controlTint
        val standardBlue = Color(MessageStyle.Swatch.BLUE.color(background.luminance() <= .27f, true))
        if (contrast(standardBlue, background) >= 3f) return standardBlue
        val navy = Color(0xff00346d)
        val ice = Color(0xffc3e6ff)
        return if (contrast(navy, background) > contrast(ice, background)) navy else ice
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.luminance()
        val b = background.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
    val muted = Color(ui.muted())
    val edge = Color(ui.divider())
    val wash = Color(ui.surface())
    val incoming = Color(appearance.incoming)
    val sms = Color(appearance.accent)
    val rcs = Color(appearance.rcs)
}

private val swipeShape = RoundedCornerShape(16.dp)

internal class InboxScreen(
    root: LinearLayout,
    prefs: SharedPreferences,
    private val sample: Boolean,
    private val connected: Boolean,
    private val folder: String,
    initialSearch: String,
    private val searchChanged: Consumer<String>,
    private val settings: Runnable,
    private val filters: Consumer<String>,
    private val compose: Runnable,
    private val connect: Runnable,
    private val open: Consumer<String>,
    private val more: Consumer<String>,
    private val details: Consumer<String>,
) {
    private var search by mutableStateOf(initialSearch)
    var threads by mutableStateOf(emptyList<ThreadRow>())
    var error by mutableStateOf<String?>(null)

    init {
        mount(root, prefs) { palette ->
            val dock = palette.appearance.searchPosition == "bottom"
            val editSearch: (String) -> Unit = {
                search = it
                searchChanged.accept(it)
            }
            Column(Modifier.fillMaxSize().background(palette.bg)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionIcon("Settings", Glyph.Settings, palette.inboxAction) { settings.run() }
                    Spacer(Modifier.weight(1f))
                    if (sample) {
                        TextButton(onClick = { compose.run() }) { Text("My inbox", color = palette.inboxAction) }
                    } else if (connected && !dock) {
                        ActionIcon("Compose", Glyph.Compose, palette.inboxAction) { compose.run() }
                    }
                    if (connected || sample) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            ActionIcon("Filters", Glyph.Filter, palette.inboxAction) { expanded = true }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                for (option in listOf("Inbox", "Archived", "Blocked")) {
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        trailingIcon = if (option == folder) ({ Text("✓", color = palette.inboxAction) }) else null,
                                        onClick = { expanded = false; filters.accept(option) },
                                    )
                                }
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 22.dp)) {
                    Text(
                        if (folder == "Inbox") "Messages" else folder,
                        color = palette.ink,
                        fontSize = 37.sp,
                        lineHeight = 43.sp,
                        letterSpacing = (-1.45).sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (sample) {
                        Text("Sample inbox", Modifier.padding(top = 3.dp), color = palette.muted, fontSize = 14.sp)
                    }
                    if (!dock && (sample || connected)) SearchBox(search, palette, change = editSearch)
                }
                if (!sample && !connected) {
                    WelcomeCard(palette, connect, compose)
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (threads.isEmpty()) {
                            Text(
                                error ?: if (search.isEmpty()) "No conversations yet" else "No matches for “$search”",
                                Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
                                color = palette.muted,
                                fontSize = 16.sp,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 8.dp,
                                    bottom = if (dock) 87.dp else 16.dp),
                            ) {
                                items(threads, key = { it.id }) { thread ->
                                    ConversationRow(thread, palette, prefs.getBoolean("protocolTags", false), { open.accept(thread.id) }, { details.accept(thread.id) },
                                        if (sample) null else ({ more.accept(thread.id) }))
                                }
                            }
                        }
                        if (dock) {
                            val dockColor = if (palette.appearance.toolbarFinish == "plain") palette.bg else palette.toolbar
                            Surface(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = 10.dp),
                                color = dockColor,
                                shape = RoundedCornerShape(27.dp),
                                border = BorderStroke(1.dp, palette.edge),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        SearchBox(search, palette, inDock = true, change = editSearch)
                                    }
                                    if (connected) ActionIcon("Compose", Glyph.Compose, palette.toolbarAction) { compose.run() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeCard(palette: Palette, connect: Runnable, sample: Runnable) {
    Column(
        Modifier.padding(horizontal = 22.dp, vertical = 26.dp).fillMaxWidth()
            .background(palette.wash, RoundedCornerShape(22.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar("F", "FluidMessages", 44)
        Text("Set up your inbox", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Choose FluidMessages as your default SMS app to view conversations and send texts.",
            color = palette.muted, style = MaterialTheme.typography.bodyLarge)
        AppButton(onClick = connect::run) { Text("Set as default SMS app") }
        TextButton(onClick = sample::run) { Text("Open sample inbox") }
    }
}

@Composable
private fun SearchBox(search: String, palette: Palette, inDock: Boolean = false, change: (String) -> Unit) {
    val ink = if (inDock) palette.toolbarInk else palette.ink
    val muted = ink.copy(alpha = .70f)
    BasicTextField(
        value = search,
        onValueChange = change,
        singleLine = true,
        textStyle = TextStyle(color = ink, fontSize = 16.sp),
        cursorBrush = SolidColor(palette.action),
        modifier = Modifier.fillMaxWidth().padding(top = if (inDock) 0.dp else 13.dp, bottom = if (inDock) 0.dp else 5.dp)
            .semantics { contentDescription = "Search messages" },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (inDock) 50.dp else 44.dp)
                    .background(if (inDock) Color.Transparent else palette.wash, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrawGlyph(Glyph.Search, muted, Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (search.isEmpty()) Text("Search", color = muted, fontSize = 16.sp)
                    inner()
                }
                if (search.isNotEmpty()) ActionIcon("Clear search", Glyph.Close, palette.muted) { change("") }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(thread: ThreadRow, palette: Palette, showProtocol: Boolean, open: () -> Unit, details: () -> Unit, menu: (() -> Unit)?) {
    val height = when (palette.appearance.density) {
        "compact" -> 64.dp
        "roomy" -> 91.dp
        else -> 77.dp
    }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = height).clip(swipeShape)
                .combinedClickable(onClick = open, onLongClick = menu)
                .padding(horizontal = 5.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(10.dp)) {
                if (thread.unread) Box(Modifier.size(6.dp).background(palette.inboxAction, CircleShape))
            }
            Box(Modifier.clip(CircleShape).clickable(onClick = details).semantics {
                contentDescription = "Contact info for ${thread.name}"
            }) {
                Avatar(thread.initials, thread.name, 48, thread.photo, thread.photoUri, thread.photoRevision)
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        thread.name, Modifier.weight(1f), color = palette.ink, fontSize = 17.sp,
                        fontWeight = if (thread.unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(if (thread.pinned) "Pinned · ${thread.time}" else thread.time, Modifier.padding(start = 8.dp), color = palette.muted, fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    DrawGlyph(Glyph.Forward, palette.muted, Modifier.size(12.dp))
                }
                if (showProtocol) Text(thread.protocol.ifBlank { "SMS" }, color = palette.action, fontSize = 11.sp)
                Text(thread.preview, Modifier.padding(top = 3.dp, end = 15.dp), color = palette.muted,
                    fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(Modifier.padding(start = 74.dp, end = 6.dp), color = palette.edge)
    }
}

internal class ConversationScreen @JvmOverloads constructor(
    root: LinearLayout,
    prefs: SharedPreferences,
    profile: ChatProfile,
    private val name: String,
    private val initials: String,
    private val transport: String,
    private val sample: Boolean,
    private val senderStyle: SenderStyle,
    initialSim: String?,
    private val back: Runnable,
    private val details: Runnable,
    private val more: Runnable?,
    private val chooseSim: Runnable?,
    private val forward: BiConsumer<String, Uri?>,
    private val delete: Consumer<ChatLine>,
    private val send: java.util.function.Predicate<String>?,
    private val sampleMembers: List<String>,
    private val sendSample: SampleSend?,
    private val photo: Int,
    private val photoUri: Uri? = null,
    private val routing: ChatRouting? = null,
    private val groupMembers: List<GroupMember> = emptyList(),
) {
    var draft by mutableStateOf("")
    var draftPhoto by mutableStateOf<Uri?>(null)
    private var replyTo by mutableStateOf<ChatLine?>(null)
    private val exactTimes = prefs.getBoolean("exactTimes", false)
    var sim by mutableStateOf(initialSim)
    var lines by mutableStateOf(emptyList<ChatLine>())
    var animatedId by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    init {
        mount(root, prefs, profile) { palette ->
            val active = routing?.active ?: MessageProtocol.parse(transport)
            val blocked = active.disabledReason(prefs).ifEmpty { routing?.unavailable.orEmpty() }
            val categoryColor = if (routing != null && senderStyle != SenderStyle.AUTOMATED)
                active.tint(palette.appearance) else when (senderStyle) {
                SenderStyle.AUTOMATED -> Color(palette.appearance.automated)
                SenderStyle.RCS -> palette.rcs
                SenderStyle.SMS -> palette.sms
            }
            val sendColor = profile.outgoing()?.let { Color(it) } ?: categoryColor
            val receiveColor = palette.incoming
            var sendAs by remember { mutableStateOf<String?>(null) }
            val group = sampleMembers.size > 1 || groupMembers.size > 1
            val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
                if (it != null && (sample || routing?.attachments == true)) draftPhoto = it
            }
            fun submit(body: String, reactionTarget: String? = null, attachment: Uri? = null): Boolean {
                if (blocked.isNotEmpty()) {
                    error = blocked
                    return false
                }
                return sendSample?.send(body, sendAs, reactionTarget, attachment) ?: send?.test(body) ?: false
            }
            val messages = remember(lines) { foldSmsReactions(lines) }
            val composerFocus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(replyTo?.id) {
                if (replyTo != null) {
                    composerFocus.requestFocus()
                    keyboard?.show()
                }
            }
            WallpaperSurface(palette.appearance.wallpaper, palette.bg, Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    val floating = palette.appearance.toolbarFinish == "floating"
                    val chromeInk = palette.toolbarInk
                    Row(Modifier.fillMaxWidth()
                        .padding(horizontal = if (floating) 12.dp else 0.dp)
                        .clip(RoundedCornerShape(if (floating) 22.dp else 0.dp))
                        .background(palette.toolbar)
                        .padding(start = 7.dp, end = 10.dp, top = 2.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon("Back to messages", Glyph.Back, palette.toolbarAction) { back.run() }
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .clickable(onClickLabel = "Conversation details") { details.run() },
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(initials, name, 39, photo, photoUri)
                            Column(Modifier.padding(start = 10.dp)) {
                            Text(name, fontSize = 17.sp, color = chromeInk, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val mode = when {
                                sample && group -> "Sample group · ${sampleMembers.size + 1} members"
                                group -> "${groupMembers.size + 1} members · ${active.label}"
                                sample -> "Sample ${routing?.active?.label ?: transport}"
                                else -> routing?.active?.label ?: transport
                            }
                            val category = when {
                                senderStyle == SenderStyle.AUTOMATED -> " · Automated sender"
                                sample && group && active == MessageProtocol.RCS -> " · RCS preview"
                                sample && group -> " · ${active.label}"
                                else -> ""
                            }
                            Text(mode + category,
                                color = chromeInk.copy(alpha = .72f), fontSize = 12.sp)
                            if (prefs.getBoolean("protocolPrivacy", false)) {
                                Text(active.privacy, color = chromeInk.copy(alpha = .80f), fontSize = 11.sp)
                            }
                            }
                        }
                        if (more != null) ActionIcon("Conversation options", Glyph.More, palette.toolbarAction) { more.run() }
                    }
                    if (!floating) HorizontalDivider(color = palette.edge)
                    if (routing != null && routing.formats.size > 1 && routing.switch != null) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            var expanded by remember { mutableStateOf(false) }
                            if (routing.combined) Text("Send via", color = palette.muted, fontSize = 12.sp)
                            for (format in routing.formats) {
                                FilterChip(selected = format == active, onClick = { routing.switch.accept(format) },
                                    label = { Text(format.label) })
                            }
                            if (routing.setDefault != null) Box {
                                TextButton(onClick = { expanded = true }) { Text("Default protocol") }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    for (format in routing.formats) DropdownMenuItem(
                                        text = { Text(format.label) }, onClick = {
                                            expanded = false
                                            routing.setDefault.accept(format)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    val list = rememberLazyListState()
                    val scrollScope = rememberCoroutineScope()
                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex)
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = list,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
                    ) {
                        if (error != null) item {
                            Text(error.orEmpty(), color = palette.muted, modifier = Modifier.padding(12.dp))
                        }
                        itemsIndexed(messages, key = { _, message -> message.line.id }) { index, display ->
                            val line = display.line
                            val previous = messages.getOrNull(index - 1)?.line
                            val next = messages.getOrNull(index + 1)?.line
                            val beginsDay = line.day.isNotEmpty() && line.day != previous?.day
                            if (beginsDay) {
                                Box(Modifier.fillMaxWidth().padding(top = if (index == 0) 4.dp else 16.dp, bottom = 12.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text(line.day, color = palette.muted, fontSize = 12.sp)
                                }
                            }
                            val grouped = !beginsDay && previous != null && line.senderKey() == previous.senderKey()
                            val continues = next != null && line.senderKey() == next.senderKey() && (next.day == line.day || next.day.isEmpty())
                            val quoted = remember(line.text) { parseQuotedReply(line.text) }
                            val forwarded = remember(line.text) { parseForwardedMessage(line.text) }
                            val sourceIndex = quoted?.let { section ->
                                (index - 1 downTo 0).firstOrNull {
                                    val source = messages[it].line.text
                                    source == section.quote || messageExcerpt(source, 240) == section.quote
                                }
                            }
                            if (group && !grouped && !line.outgoing && line.author != null) {
                                Text(line.author, Modifier.padding(start = 9.dp, top = 5.dp, bottom = 3.dp),
                                    color = palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (routing?.combined == true && line.format != null) {
                                Text(line.format.label, Modifier.padding(horizontal = 9.dp), color = palette.muted, fontSize = 11.sp)
                            }
                            val lineColor = line.format?.let { profile.outgoing()?.let(::Color) ?: it.tint(palette.appearance) } ?: sendColor
                            MessageBubble(line, palette, if (line.outgoing) lineColor else receiveColor, grouped, continues, animatedId == line.id,
                                exactTimes, display.reactions, quoted, forwarded, sourceIndex?.let { target ->
                                    { scrollScope.launch {
                                        val listIndex = target + if (error != null) 1 else 0
                                        if (palette.appearance.speed == 0) list.scrollToItem(listIndex)
                                        else list.animateScrollToItem(listIndex)
                                    }; Unit }
                                }, { if (blocked.isEmpty()) replyTo = line else error = blocked }, {
                                    val own = if (sample) line.senderKey() == sendAs else line.outgoing
                                    submit(emojiReactionSms(it, line.text, own), line.id)
                                },
                                { forward.accept(if (forwarded != null) line.text else
                                    forwardAsSms(line.text, if (line.outgoing) "Me" else line.author ?: name), line.photo) },
                                { delete.accept(line) })
                        }
                    }
                    replyTo?.let { reply ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                            .background(palette.wash, RoundedCornerShape(12.dp)).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Reply to “${messageExcerpt(reply.text, 160)}”", Modifier.weight(1f), color = palette.ink,
                                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            ActionIcon("Cancel reply", Glyph.Close, palette.toolbarAction) { replyTo = null }
                        }
                    }
                    if (sampleMembers.isNotEmpty() && blocked.isEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Send as", color = palette.ink, fontSize = 12.sp)
                            FilterChip(selected = sendAs == null, onClick = { sendAs = null }, label = { Text("You") })
                            for (member in sampleMembers) {
                                FilterChip(selected = sendAs == member, onClick = { sendAs = member },
                                    modifier = Modifier.semantics { contentDescription = "Send as $member" },
                                    label = { Text(if (group) member.substringBefore(' ') else member) })
                            }
                        }
                    }
                    draftPhoto?.let { photo ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            AttachmentPhoto(photo, Modifier.size(60.dp), "Selected photo")
                            Text("Photo ready to send", Modifier.weight(1f).padding(start = 10.dp), color = palette.ink)
                            ActionIcon("Remove photo", Glyph.Close, palette.toolbarAction) { draftPhoto = null }
                        }
                    }
                    if (blocked.isNotEmpty()) {
                        Text(blocked, Modifier.fillMaxWidth().padding(16.dp), color = palette.muted, fontSize = 14.sp)
                    } else Composer(draft, { draft = it }, palette, sendColor,
                        sendAs?.let { "Message from $it" } ?: if (sample) "Practice message" else "Text message",
                        sim, chooseSim, composerFocus, draftPhoto != null,
                        if ((sample && active != MessageProtocol.IRC) || routing?.attachments == true)
                            { { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
                        else null) {
                        val caption = if (draftPhoto != null) draft.ifBlank { "Photo" } else draft
                        val body = replyTo?.let { "Reply to “${messageExcerpt(it.text, 240)}”\n\n$caption" } ?: caption
                        if (submit(body, attachment = draftPhoto)) {
                            draft = ""
                            draftPhoto = null
                            replyTo = null
                        }
                    }
                }
            }
        }
    }
}

internal fun interface SampleSend {
    fun send(text: String, author: String?, reactionTarget: String?, attachment: Uri?): Boolean
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    line: ChatLine,
    palette: Palette,
    bubbleColor: Color,
    grouped: Boolean,
    continues: Boolean,
    animate: Boolean,
    exactTimes: Boolean,
    reactions: List<String>,
    quoted: QuotedReply?,
    forwarded: ForwardedMessage?,
    jumpToQuote: (() -> Unit)?,
    reply: () -> Unit,
    react: (String) -> Unit,
    forward: () -> Unit,
    delete: () -> Unit,
) {
    val motion = palette.appearance.speed
    val entrance = remember { Animatable(if (animate && motion > 0) 0f else 1f) }
    LaunchedEffect(animate) {
        if (animate && motion > 0 && entrance.value < 1f) entrance.animateTo(1f, tween(motion))
    }
    val round = palette.appearance.radius.dp
    val inner = minOf(round, 7.dp)
    val shape = RoundedCornerShape(
        topStart = if (!line.outgoing && grouped) inner else round,
        topEnd = if (line.outgoing && grouped) inner else round,
        bottomEnd = if (line.outgoing && !continues) inner else round,
        bottomStart = if (!line.outgoing && !continues) inner else round,
    )
    val chosenFill = bubbleColor
    val fill = accessibleBubbleFill(chosenFill)
    val padding = palette.appearance.padding
    var showTime by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var customReaction by remember { mutableStateOf(false) }
    var selectedAction by remember { mutableStateOf("") }
    var drag by remember { mutableStateOf(0f) }
    val currentReply by rememberUpdatedState(reply)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = if (grouped) 3.dp else 8.dp)) {
        val bubbleWidth = minOf(maxWidth * .80f, 460.dp)
        Column(Modifier.align(if (line.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1 - entrance.value) * 10.dp.toPx()
                translationX = drag
            }, horizontalAlignment = if (line.outgoing) Alignment.End else Alignment.Start) {
            Box {
                Column(Modifier.widthIn(max = bubbleWidth)
                    .bubbleTail(fill, palette.appearance.tails && !continues, line.outgoing).clip(shape).background(fill)
                    .pointerInput(line.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val triggered = drag >= 32.dp.toPx()
                                drag = 0f
                                if (triggered) currentReply()
                            },
                            onDragCancel = { drag = 0f },
                        ) { change, amount ->
                            change.consume()
                            drag = (drag + amount).coerceIn(0f, 64.dp.toPx())
                        }
                    }
                    .combinedClickable(onClick = { showTime = !showTime }, onLongClickLabel = "Message actions",
                        onLongClick = { menu = true })
                    .semantics { customActions = listOf(
                        CustomAccessibilityAction("Reply") { reply(); true },
                        CustomAccessibilityAction("Message actions") { menu = true; true },
                    ) }
                    .padding(horizontal = padding.dp, vertical = (padding * .72f).dp)) {
                    val foreground = bubbleTextColor(chosenFill)
                    line.photo?.let {
                        AttachmentPhoto(it, Modifier.width(220.dp).height(132.dp).padding(bottom = 5.dp), "Message photo")
                    }
                    if (quoted != null) {
                        val quoteModifier = Modifier
                            .padding(bottom = 7.dp).clip(RoundedCornerShape(9.dp))
                            .background(foreground.copy(alpha = .13f))
                            .then(if (jumpToQuote == null) Modifier else Modifier.clickable(
                                onClickLabel = "Jump to quoted message", onClick = jumpToQuote))
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                        Column(quoteModifier) {
                            Text("Replying to", color = foreground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(quoted.quote, color = foreground, fontSize = 13.sp, lineHeight = 16.sp,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (forwarded != null) {
                        Column(Modifier.clip(RoundedCornerShape(10.dp))
                            .background(foreground.copy(alpha = .13f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Forwarded from ${forwarded.author}", color = foreground, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(forwarded.text, color = foreground, fontSize = palette.appearance.size.sp,
                                lineHeight = (palette.appearance.size + 4).sp)
                        }
                    } else Text(quoted?.body ?: line.text, fontSize = palette.appearance.size.sp,
                        lineHeight = (palette.appearance.size + 4).sp, color = foreground)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false },
                    modifier = Modifier.widthIn(max = 310.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.widthIn(max = 256.dp).horizontalScroll(rememberScrollState())) {
                            for (reaction in SmsReaction.entries) {
                                IconButton(
                                    modifier = Modifier.size(42.dp).semantics { contentDescription = reaction.title },
                                    onClick = { menu = false; react(reaction.symbol) },
                                ) { Text(reaction.symbol, fontSize = 22.sp) }
                            }
                        }
                        IconButton(onClick = { menu = false; customReaction = true },
                            modifier = Modifier.size(42.dp).semantics { contentDescription = "More emoji" }) {
                            Text("+", fontSize = 25.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp))
                    DropdownMenuItem(text = { Text("Reply") }, onClick = { menu = false; reply() })
                    DropdownMenuItem(text = { Text("Forward") }, onClick = { menu = false; forward() })
                    DropdownMenuItem(text = { Text("Copy") }, onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(line.text))
                        menu = false
                    })
                    DropdownMenuItem(text = { Text("Select text") }, onClick = { menu = false; selectedAction = "Select text" })
                    DropdownMenuItem(text = { Text("Share") }, onClick = {
                        menu = false
                        val text = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, line.text)
                        context.startActivity(android.content.Intent.createChooser(text, null))
                    })
                    DropdownMenuItem(text = { Text("Message details") }, onClick = { menu = false; selectedAction = "Message details" })
                    DropdownMenuItem(text = { Text("Delete for me", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; delete() })
                }
                if (customReaction) EmojiReactionDialog({ customReaction = false }) {
                    customReaction = false
                    react(it)
                }
                if (selectedAction.isNotEmpty()) {
                    AlertDialog(
                        onDismissRequest = { selectedAction = "" },
                        title = { Text(selectedAction) },
                        text = {
                            if (selectedAction == "Select text") {
                                SelectionContainer {
                                    Text(line.text, Modifier.heightIn(max = 290.dp).verticalScroll(rememberScrollState()))
                                }
                            } else {
                                Text(listOf(if (line.outgoing) "Outgoing" else "Incoming", line.day, line.time, line.status)
                                    .filter(String::isNotEmpty).joinToString(" · "))
                            }
                        },
                        confirmButton = { TextButton(onClick = { selectedAction = "" }) { Text("Done") } },
                    )
                }
            }
            if (reactions.isNotEmpty()) Text(reactions.joinToString(" "), Modifier.padding(top = 2.dp)
                .background(palette.wash, RoundedCornerShape(14.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                fontSize = 14.sp)
            val details = listOfNotNull(line.time.takeIf { (showTime || exactTimes) && it.isNotEmpty() },
                line.status.takeIf { it.isNotEmpty() }).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, Modifier.padding(top = 3.dp, end = 4.dp),
                color = palette.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EmojiReactionDialog(close: () -> Unit, react: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val emoji = input.trim()
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Choose an emoji") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { if (it.length <= 64) input = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Emoji") },
                    supportingText = { Text("Use your keyboard’s emoji picker or paste one emoji.") })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    listOf("🎉", "😂", "🔥", "🙌", "👀", "✅").forEach { suggestion ->
                        IconButton(onClick = { input = suggestion },
                            modifier = Modifier.size(45.dp).semantics { contentDescription = "Use $suggestion" }) {
                            Text(suggestion, fontSize = 23.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = isReactionEmoji(emoji), onClick = { react(emoji) }) { Text("React") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

internal fun Modifier.bubbleTail(fill: Color, enabled: Boolean, outgoing: Boolean): Modifier {
    if (!enabled) return this
    return drawBehind {
        val right = outgoing == (layoutDirection == LayoutDirection.Ltr)
        val edge = if (right) size.width else 0f
        val direction = if (right) 1f else -1f
        fun x(distance: Float) = edge + direction * distance.dp.toPx()
        val path = Path().apply {
            moveTo(x(-13f), size.height - 12.dp.toPx())
            quadraticTo(x(-3f), size.height - 13.dp.toPx(), x(-2f), size.height - 7.dp.toPx())
            quadraticTo(x(-1f), size.height - 3.dp.toPx(), x(5f), size.height + 1.dp.toPx())
            quadraticTo(x(-2f), size.height + 2.dp.toPx(), x(-10f), size.height - 1.dp.toPx())
            close()
        }
        drawPath(path, fill)
    }
}

@Composable
private fun AttachmentPhoto(uri: Uri, modifier: Modifier, description: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val resource = if (uri.scheme == "android.resource" && uri.authority == context.packageName)
                    uri.lastPathSegment?.toIntOrNull() else null
                val source = if (resource != null) ImageDecoder.createSource(context.resources, resource)
                    else ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val size = info.size
                    val scale = (900f / maxOf(size.width, size.height)).coerceAtMost(1f)
                    decoder.setTargetSize((size.width * scale).toInt().coerceAtLeast(1),
                        (size.height * scale).toInt().coerceAtLeast(1))
                }.asImageBitmap()
            } catch (_: java.io.IOException) { null }
              catch (_: SecurityException) { null }
              catch (_: IllegalArgumentException) { null }
        }
    }
    bitmap?.let {
        Image(it, description, modifier.clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
    } ?: Box(modifier, contentAlignment = Alignment.Center) { Text("Photo", color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun Composer(
    draft: String, changed: (String) -> Unit, palette: Palette, sendColor: Color,
    hint: String, sim: String?, chooseSim: Runnable?, focus: FocusRequester,
    hasPhoto: Boolean, attach: (() -> Unit)?, send: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sim != null && chooseSim != null) {
            TextButton(onClick = chooseSim::run, contentPadding = PaddingValues(horizontal = 7.dp)) {
                Text(sim, color = palette.action, fontSize = 13.sp)
            }
        }
        if (attach != null) IconButton(onClick = attach, modifier = Modifier.semantics { contentDescription = "Attach photo" }) {
            Text("+", color = palette.toolbarAction, fontSize = 27.sp)
        }
        Surface(
            Modifier.weight(1f), color = palette.toolbar, shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 13.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom) {
                BasicTextField(
                    value = draft, onValueChange = changed, minLines = 1, maxLines = 4,
                    modifier = Modifier.weight(1f).focusRequester(focus).padding(top = 9.dp, bottom = 9.dp, end = 5.dp)
                        .semantics { contentDescription = hint },
                    textStyle = TextStyle(color = palette.toolbarInk, fontSize = 16.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(palette.action),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) Text(hint, color = palette.toolbarInk.copy(alpha = .65f), fontSize = 16.sp)
                            inner()
                        }
                    },
                )
                val ready = draft.isNotBlank() || hasPhoto
                IconButton(onClick = send, enabled = ready, modifier = Modifier.size(40.dp)
                    .semantics { contentDescription = "Send message" }) {
                    val color = if (ready) Color(MessageStyle.filledControl(sendColor.toArgb())) else palette.wash
                    Box(Modifier.size(33.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                        DrawGlyph(Glyph.Send, if (ready) Color(MessageStyle.filledControlInk(sendColor.toArgb()))
                            else palette.toolbarInk.copy(alpha = .65f), Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun Avatar(initials: String, seed: String, size: Int, photo: Int = 0, photoUri: Uri? = null, photoRevision: Int = 0) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, photoUri, photoRevision) {
        value = null
        value = photoUri?.let { uri ->
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                } catch (_: java.io.IOException) { null }
                  catch (_: SecurityException) { null }
                  catch (_: IllegalArgumentException) { null }
            }
        }
    }
    thumbnail?.let {
        Image(it, contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        return
    }
    if (photo != 0) {
        Image(painterResource(photo), contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        return
    }
    val colors = remember(seed) {
        val palettes = listOf(0xff466c9d to 0xff173b72, 0xff8069a4 to 0xff513477,
            0xff277c78 to 0xff07504e, 0xffb06153 to 0xff803641, 0xff686f9a to 0xff424969)
        val (light, dark) = palettes[Math.floorMod(seed.hashCode(), palettes.size)]
        Brush.linearGradient(listOf(Color(light), Color(dark)))
    }
    Box(Modifier.size(size.dp).background(colors, CircleShape), contentAlignment = Alignment.Center) {
        if (initials.isBlank()) DrawGlyph(Glyph.Person, Color.White, Modifier.size((size / 2).dp))
        else Text(initials, fontSize = (size / 3).sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

private enum class Glyph { Settings, Filter, Compose, Search, Close, Back, Forward, More, Send, Person }

@Composable
private fun ActionIcon(label: String, glyph: Glyph, color: Color, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.semantics { contentDescription = label }) {
        DrawGlyph(glyph, color, Modifier.size(22.dp))
    }
}

@Composable
private fun DrawGlyph(glyph: Glyph, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        fun point(x: Float, y: Float) = Offset(x * size.width, y * size.height)
        val stroke = 1.65.dp.toPx()
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(color, point(x, y), point(x2, y2), stroke, StrokeCap.Round)
        when (glyph) {
            Glyph.Settings -> {
                drawCircle(color, size.minDimension * .38f, style = Stroke(stroke))
                for (index in 0..2) drawCircle(color, stroke * .62f, point(.32f + index * .18f, .5f))
            }
            Glyph.Filter -> {
                line(.14f, .25f, .86f, .25f)
                line(.26f, .50f, .74f, .50f)
                line(.40f, .75f, .60f, .75f)
            }
            Glyph.Compose -> {
                val outline = Path().apply {
                    moveTo(size.width * .55f, size.height * .18f)
                    lineTo(size.width * .20f, size.height * .18f)
                    lineTo(size.width * .20f, size.height * .83f)
                    lineTo(size.width * .84f, size.height * .83f)
                    lineTo(size.width * .84f, size.height * .50f)
                }
                drawPath(outline, color, style = Stroke(stroke, cap = StrokeCap.Round))
                line(.47f, .57f, .87f, .17f)
            }
            Glyph.Search -> {
                drawCircle(color, size.minDimension * .27f, point(.41f, .41f), style = Stroke(stroke))
                line(.62f, .62f, .86f, .86f)
            }
            Glyph.Close -> { line(.30f, .30f, .70f, .70f); line(.70f, .30f, .30f, .70f) }
            Glyph.Back -> { line(.62f, .18f, .30f, .50f); line(.30f, .50f, .62f, .82f) }
            Glyph.Forward -> { line(.32f, .18f, .66f, .50f); line(.66f, .50f, .32f, .82f) }
            Glyph.More -> for (index in 0..2) drawCircle(color, stroke * .75f, point(.24f + index * .26f, .5f))
            Glyph.Send -> { line(.5f, .82f, .5f, .22f); line(.22f, .49f, .5f, .22f); line(.5f, .22f, .78f, .49f) }
            Glyph.Person -> {
                drawCircle(color, size.width * .16f, point(.5f, .29f))
                drawOval(color, point(.15f, .48f), Size(size.width * .70f, size.height * .38f))
            }
        }
    }
}

internal fun accessibleBubbleFill(color: Color): Color {
    if (!isChromaticBubble(color) || color.luminance() <= .1833f) return color
    // Preserve hue while making white text meet 4.5:1 against light, saturated colors.
    var light = 0f
    var dark = 1f
    repeat(12) {
        val middle = (light + dark) / 2f
        if (lerp(color, Color.Black, middle).luminance() <= .1833f) dark = middle else light = middle
    }
    return lerp(color, Color.Black, dark)
}

private fun isChromaticBubble(color: Color): Boolean {
    return maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) >= .12f
}

internal fun bubbleTextColor(background: Color) =
    if (isChromaticBubble(background)) Color.White else Color(MessageStyle.textOn(background.toArgb()))

private fun mount(root: LinearLayout, prefs: SharedPreferences, profile: ChatProfile? = null, content: @Composable (Palette) -> Unit) {
    val base = Appearance.read(prefs)
    val resolved = if (profile == null) base else resolveChatAppearance(base, profile)
    val palette = Palette(resolved)
    val view = ComposeView(root.context)
    view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    view.setContent {
        AppTheme(resolved) { content(palette) }
    }
    root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
}
