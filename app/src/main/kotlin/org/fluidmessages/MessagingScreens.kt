package org.fluidmessages

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.Consumer
import androidx.compose.ui.platform.LocalContext
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
    val automated: Boolean = false,
    val members: List<GroupMember> = emptyList(),
    /** A channel or room on a server: the server's account id, for Messages → Communities. */
    val server: String = "",
    /** The newest message's time (epoch millis), for Communities' Recent activity order. */
    val lastAt: Long = 0L,
    /** When it was joined, or its place in the link list for older links (bigger is newer), for Date joined. */
    val joinedAt: Long = 0L,
    /** Muted right now, by itself or its server: "Muted", "Muted until 3:40 PM"…; empty when it isn't (Mutes). */
    val muted: String = "",
    /** A small hint of the account a chat is on, when it isn't the service's first of several (FromAccount.inboxHint). */
    val account: String = "",
    /** An IRC, Matrix, or XMPP chat's connection: "connected", "connecting", "disconnected", or "" for no mark (IrcRules). */
    val connection: String = "",
)

/**
 * A server in Messages → Communities: an IRC network, Matrix account, or XMPP account (ServersScreen). [order] counts
 * up as servers are added; [account] is the address you're signed in as.
 */
internal data class ServerChip @JvmOverloads constructor(val id: String, val name: String, val service: String, val order: Int = 0,
    val account: String = "",
    /** "Muted", "Muted until 3:40 PM"… while the whole server is muted (Mutes); empty when it isn't. */
    val muted: String = "")

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
    /** Any non-photo attachment. A null uri means it is still on the server and needs downloading. */
    val attachment: Attachment? = null,
    /** A channel event such as a join, part, or topic change, shown as a line instead of a message. */
    val event: Boolean = false,
    /** Sent or received encrypted by FluidMessages: shows a small lock by the time. */
    val encrypted: Boolean = false,
    /** When a disappearing message goes (epoch millis), or 0: shows a small timer by the time. */
    val expires: Long = 0L,
    /** A channel or room message that names you: shows a small "@ Mentions you" mark (IRC, Matrix). */
    val mention: Boolean = false,
) {
    /** Photo or other attachment, as one value for forwarding and previews. */
    val media: Attachment? get() = attachment ?: photo?.let(Attachment::photo)
}

internal data class Palette(val appearance: Appearance) {
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
    val toolbarBackground = toolbar.compositeOver(bg)
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
    /** Sample inbox only: puts every sample conversation back the way it started. */
    private val resetSample: Runnable? = null,
    /** The live inbox only: opens the Calls tab from the bottom tab bar. */
    private val calls: Runnable? = null,
    /** The inbox filter menu (Settings → Messaging → Inbox filters), then Private folder and Quick hide. */
    folders: List<String> = listOf("Inbox", "Archived", "Blocked"),
    /** Missed calls and unread chats for the tab bar's badges. */
    private val counts: TabCounts? = null,
) {
    private var search by mutableStateOf(initialSearch)
    /** The filter menu; it grows a service's filter once that service has chats. */
    var folders by mutableStateOf(folders)
    /** Clears the search field, e.g. after it was used to open hidden chats. */
    fun clearSearch() { search = "" }
    var threads by mutableStateOf(emptyList<ThreadRow>())
    /** The live inbox only: reloads messages, contacts, and accounts from scratch. Shown in the filter menu. */
    var resync: Runnable? = null
    /** Decoy mode only: holding the title asks for the passcode to show the real inbox. Nothing on screen hints at it. */
    var titleHold: Runnable? = null
    var error by mutableStateOf<String?>(null)
    /** Messages → Chats | Communities shows (the live inbox, not decoy mode or the private folder). */
    var communitiesOffered by mutableStateOf(false)
    /** Communities is the side showing. */
    var showingCommunities by mutableStateOf(false)
    var pickView: Consumer<Boolean>? = null
    /** Servers for Communities: each IRC network, Matrix account, and XMPP account. */
    var servers by mutableStateOf(emptyList<ServerChip>())
    /** Channels and rooms for Communities, each with its server. */
    var communityRows by mutableStateOf(emptyList<ThreadRow>())
    var communitySort by mutableStateOf(Communities.Sort.RECENT)
    var sortChanged: Consumer<String>? = null
    /** "join", "browse", "details", or "add", with the server's account id. */
    var serverAction: BiConsumer<String, String>? = null
    /** Inside the open private folder: the filter applied to its chats, and the filters the menu offers there. */
    var privateFilter by mutableStateOf<String?>(null)
    var privateFilters by mutableStateOf(emptyList<String>())
    var pickPrivateFilter: Consumer<String>? = null

    init {
        mount(root, prefs) { palette ->
            val dock = palette.appearance.searchPosition == "bottom"
            // Read-only when every chat service is off: no compose button, and a note saying why.
            val canSend = sample || MessageProtocol.anyEnabled(prefs)
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
                    } else if (connected && canSend && !dock) {
                        ActionIcon("Compose", Glyph.Compose, palette.inboxAction) { compose.run() }
                    }
                    if (connected || sample) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            ActionIcon("Filters", Glyph.Filter, palette.inboxAction) { expanded = true }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                // Inside the open private folder, its own filters come first; any other filter leaves it.
                                privateFilter?.let { current ->
                                    Text("In the private folder", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        color = palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    for (option in privateFilters) {
                                        DropdownMenuItem(
                                            text = { Text(if (option == "Inbox") "All private chats" else option) },
                                            trailingIcon = if (option == current) ({ Text("✓", color = palette.inboxAction) }) else null,
                                            onClick = { expanded = false; pickPrivateFilter?.accept(option) },
                                        )
                                    }
                                    HorizontalDivider()
                                }
                                for (option in folders) {
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        trailingIcon = if (option == folder && privateFilter == null) ({ Text("✓", color = palette.inboxAction) }) else null,
                                        onClick = { expanded = false; filters.accept(option) },
                                    )
                                }
                                resync?.let { action ->
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Resync") }, onClick = { expanded = false; action.run() })
                                }
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 22.dp)) {
                    Text(
                        if (folder == "Inbox") "Messages" else privateFilter?.takeIf { it != "Inbox" } ?: folder,
                        Modifier.then(titleHold?.let { hold -> Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { hold.run() }) } } ?: Modifier),
                        color = palette.ink,
                        fontSize = 37.sp,
                        lineHeight = 43.sp,
                        letterSpacing = (-1.45).sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (sample) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sample inbox", Modifier.padding(top = 3.dp), color = palette.muted, fontSize = 14.sp)
                            if (resetSample != null) {
                                Spacer(Modifier.width(4.dp))
                                TextButton(onClick = { resetSample.run() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("Reset", color = palette.action, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    if (privateFilter.let { it != null && it != "Inbox" }) Text("Private folder", Modifier.padding(top = 3.dp),
                        color = palette.muted, fontSize = 14.sp)
                    if (connected && !canSend) Text("Read-only · sending is off in Settings → Chat services",
                        Modifier.padding(top = 3.dp, bottom = 4.dp), color = palette.muted, fontSize = 14.sp)
                    // Chats | Communities: the phone has no room for a third tab, so Communities lives here.
                    if (communitiesOffered && (sample || connected)) InboxSwitch(palette, showingCommunities,
                        if (Communities.show(prefs)) 0 else counts?.communities ?: 0) { side ->
                        showingCommunities = side
                        pickView?.accept(side)
                    }
                    if (!dock && (sample || connected)) SearchBox(search, palette,
                        placeholder = if (communitiesOffered && showingCommunities) "Search communities" else "Search", change = editSearch)
                }
                if (!sample && !connected) {
                    WelcomeCard(palette, connect, compose)
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (communitiesOffered && showingCommunities) {
                            val showTags = remember(communityRows) { DisplayRules.showTags(prefs, communityRows) }
                            CommunitiesList(prefs, palette, servers, communityRows, communitySort, search, showTags, if (dock) 87 else 16,
                                CommunityActions({ open.accept(it) }, { details.accept(it) }, { more.accept(it) },
                                    { action, id -> serverAction?.accept(action, id) },
                                    { choice -> communitySort = choice; sortChanged?.accept(choice.id) }))
                        } else if (threads.isEmpty()) {
                            Text(
                                error ?: if (search.isNotEmpty()) "No matches for “$search”"
                                    else if (folder == TabBadges.UNREAD_FOLDER) "No unread conversations"
                                    else if (folder == "Favorites") "No chats with favorites yet. Star people in Phone → Contacts to see their chats here."
                                    else if (privateFilter != null && privateFilter != "Inbox") "No ${privateFilter!!.lowercase()} in the private folder"
                                    else if (folder != "Inbox" && InboxFilters.byLabel(folder) != null) "Nothing in $folder"
                                    else "No conversations yet",
                                Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
                                color = palette.muted,
                                fontSize = 16.sp,
                            )
                        } else {
                            val showTags = remember(threads) { DisplayRules.showTags(prefs, threads) }
                            val listState = rememberLazyListState()
                            val stretch = rememberInboxOverscroll(palette.appearance.speed)
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().elasticWhenShort(stretch, listState),
                                state = listState,
                                contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 8.dp,
                                    bottom = if (dock) 87.dp else 16.dp),
                                overscrollEffect = stretch,
                            ) {
                                itemsIndexed(threads, key = { _, thread -> thread.id }) { index, thread ->
                                    Box(Modifier.elasticRow(stretch, listState, index)) {
                                        ConversationRow(thread, palette, showTags, { open.accept(thread.id) }, { details.accept(thread.id) },
                                            if (sample) null else ({ more.accept(thread.id) }))
                                    }
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
                                        SearchBox(search, palette, inDock = true,
                                            placeholder = if (communitiesOffered && showingCommunities) "Search communities" else "Search", change = editSearch)
                                    }
                                    if (connected && canSend) ActionIcon("Compose", Glyph.Compose, palette.toolbarAction) { compose.run() }
                                }
                            }
                        }
                    }
                }
                calls?.let { MainTabBar(palette, false, it, {}, counts) }
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
        Text("Texting is off. Turn it on to see and send your texts here, or add a chat account in Settings.",
            color = palette.muted, style = MaterialTheme.typography.bodyLarge)
        AppButton(onClick = connect::run) { Text("Turn on texting") }
        TextButton(onClick = sample::run) { Text("Open sample inbox") }
    }
}

@Composable
internal fun SearchBox(search: String, palette: Palette, inDock: Boolean = false, placeholder: String = "Search", change: (String) -> Unit) {
    val ink = if (inDock) palette.toolbarInk else palette.ink
    val muted = ink.copy(alpha = .70f)
    BasicTextField(
        value = search,
        onValueChange = change,
        singleLine = true,
        textStyle = TextStyle(color = ink, fontSize = 16.sp),
        cursorBrush = SolidColor(palette.action),
        modifier = Modifier.fillMaxWidth().padding(top = if (inDock) 0.dp else 13.dp, bottom = if (inDock) 0.dp else 5.dp)
            .semantics { contentDescription = if (placeholder == "Search") "Search messages" else placeholder },
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
                    if (search.isEmpty()) Text(placeholder, color = muted, fontSize = 16.sp)
                    inner()
                }
                if (search.isNotEmpty()) ActionIcon("Clear search", Glyph.Close, palette.muted) { change("") }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(thread: ThreadRow, palette: Palette, showProtocol: Boolean, open: () -> Unit, details: () -> Unit, menu: (() -> Unit)?) {
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
                if (thread.members.size > 1 && thread.photo == 0 && thread.photoUri == null)
                    GroupAvatar(thread.members, 48, palette.bg)
                else Avatar(thread.initials, thread.name, 48, thread.photo, thread.photoUri, thread.photoRevision)
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
                if (showProtocol) ProtocolTags(thread, palette.appearance, palette.muted)
                else if (thread.muted.isNotEmpty() || thread.account.isNotEmpty() || thread.connection.isNotEmpty()) Row(Modifier.padding(top = 3.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (thread.connection.isNotEmpty()) ConnectionMark(thread.connection, palette.appearance, palette.muted)
                    if (thread.muted.isNotEmpty()) MutedMark(thread.muted, palette.muted)
                    if (thread.account.isNotEmpty()) AccountHint(thread.account, palette.muted)
                }
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
    private val forward: BiConsumer<String, Attachment?>,
    private val delete: Consumer<ChatLine>,
    private val send: java.util.function.Predicate<String>?,
    private val sampleMembers: List<String>,
    private val sendSample: SampleSend?,
    private val photo: Int,
    private val photoUri: Uri? = null,
    private val routing: ChatRouting? = null,
    private val groupMembers: List<GroupMember> = emptyList(),
    private val download: Consumer<ChatLine>? = null,
    /** Removes a failed outgoing message without asking, after it is resent or moved back into the composer. */
    private val removeFailed: Consumer<ChatLine>? = null,
) {
    var draft by mutableStateOf("")
    /** Keeps the unsent draft for this chat (Drafts); set by the live chat. Called shortly after typing stops and on leaving. */
    var saveDraft: Consumer<String>? = null
    /** Whether a message's author (null in a one-to-one chat) is a saved contact (LinkLoading.savedSenders); set by the live chat. */
    var savedSender: java.util.function.Predicate<String?>? = null
    /** Messages whose "Tap to load preview" was tapped while this chat is open. */
    private val loadedRemote = mutableStateMapOf<String, Boolean>()
    /** "Muted until 3:40 PM" and the like while this chat or its server is muted (Mutes); empty when it isn't. */
    var muted by mutableStateOf("")
    /** Opens the mute page from the header's mark: how long, or Unmute. */
    var openMute: Runnable? = null
    var draftAttachment by mutableStateOf<Attachment?>(null)
    /** Opens Settings → GIFs and stickers from the picker; set by the live chat. */
    var openGifSettings: Runnable? = null
    fun setDraftPhoto(uri: Uri?) { draftAttachment = uri?.let(Attachment::photo) }
    private var sendVia by mutableStateOf<MessageProtocol?>(null)
    private var replyTo by mutableStateOf<ChatLine?>(null)
    private val exactTimes = prefs.getBoolean("exactTimes", false)
    var sim by mutableStateOf(initialSim)
    var lines by mutableStateOf(emptyList<ChatLine>())
    var animatedId by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    /** "irc", "matrix", or "xmpp" in those chats, for the "/" hints above the message box (ChatCommands); "" elsewhere. */
    var commandFamily by mutableStateOf("")
    /** The network account whose connection shows "Disconnected · Reconnect" in this chat (ChatExtras); "" for none. */
    var connectionAccount by mutableStateOf("")
    /** /help's page for this chat's service, while it's open. */
    var helpFamily by mutableStateOf<String?>(null)
    /** Accept, Delete, Block, and Block and report spam, while this chat is a message request (MessageRequests). */
    var request by mutableStateOf<RequestBar?>(null)
    /** The NickServ card's Identify in an IRC chat (NickServ.kt); null elsewhere and in the sample inbox. */
    var nickServ by mutableStateOf<NickServIdentify?>(null)
    /** Whether this IRC account has a NickServ password saved, your nick on it, and whether this is NickServ's own chat. */
    var nickServSaved by mutableStateOf(false)
    var nickServNick by mutableStateOf("")
    var nickServChat by mutableStateOf(false)

    init {
        mount(root, prefs, profile) { palette ->
            val active = routing?.active ?: MessageProtocol.parse(transport)
            // What the composer sends with. RCS always offers SMS as the fallback in the same conversation.
            val sendOptions = routing?.formats.orEmpty().flatMap {
                if (it == MessageProtocol.RCS && MessageProtocol.SMS !in routing!!.formats) listOf(it, MessageProtocol.SMS) else listOf(it)
            }
            val via = sendVia?.takeIf { it in sendOptions } ?: routing?.preferredVia?.takeIf { it in sendOptions } ?: active
            val blocked = via.disabledReason(prefs).ifEmpty { if (via == active) routing?.unavailable.orEmpty() else "" }
            fun styleColor(format: MessageProtocol) = if (routing != null && senderStyle != SenderStyle.AUTOMATED)
                format.tint(palette.appearance) else when (senderStyle) {
                SenderStyle.AUTOMATED -> Color(palette.appearance.automated)
                SenderStyle.RCS -> palette.rcs
                SenderStyle.SMS -> palette.sms
            }
            val activeColor = profile.outgoing()?.let { Color(it) } ?: styleColor(active)
            val sendColor = if (via == active) activeColor else styleColor(via)
            var viaMenu by remember { mutableStateOf(false) }
            var fromInfo by remember { mutableStateOf(false) }
            fun chooseVia(next: MessageProtocol) {
                viaMenu = false
                // Within one conversation (combined, or RCS falling back to SMS) only the next send changes.
                if (routing?.combined == true || next.family() == active.family()) sendVia = next
                else routing?.switch?.accept(next)
            }
            val receiveColor = palette.incoming
            var sendAs by remember { mutableStateOf<String?>(null) }
            val group = sampleMembers.size > 1 || groupMembers.size > 1
            val context = LocalContext.current
            // Locations and contact cards work on every protocol; other files need a protocol that carries files.
            val filesAllowed = sample || if (via == active) routing?.attachments == true else via.transport().capabilities.attachments
            val sources = AttachmentSource.entries.filter { (filesAllowed || !it.needsFiles) &&
                (it != AttachmentSource.GIF || GifSettings.gifsOn(prefs)) && (it != AttachmentSource.STICKER || GifSettings.stickersOn(prefs)) &&
                (it != AttachmentSource.GAMES || FunSettings.games(prefs)) && (it != AttachmentSource.RANDOM || FunSettings.random(prefs)) &&
                (it != AttachmentSource.EFFECT || FunSettings.effects(prefs)) }
            var sheet by remember { mutableStateOf(false) }
            var moneyDialog by remember { mutableStateOf(false) }
            var gifSheet by remember { mutableStateOf<Boolean?>(null) }
            var pollDialog by remember { mutableStateOf(false) }
            var eventDialog by remember { mutableStateOf(false) }
            var gamesDialog by remember { mutableStateOf(false) }
            var effectsDialog by remember { mutableStateOf(false) }
            // An effect picked from + waits here until the next message is sent; it can be changed or removed.
            var pendingEffect by remember { mutableStateOf<MessageEffect?>(null) }
            var randomDialog by remember { mutableStateOf(false) }
            var editing by remember { mutableStateOf<Uri?>(null) }
            val pickToEdit = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) editing = uri }
            var notice by remember { mutableStateOf<String?>(null) }
            var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
            var cameraTarget by remember { mutableStateOf<Uri?>(null) }
            fun attach(uri: Uri?, kind: AttachmentKind? = null) {
                if (uri != null) draftAttachment = runCatching { Attachment.describe(context, uri, kind) }.getOrNull()
            }
            val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { attach(it) }
            val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
                if (taken) attach(cameraTarget, AttachmentKind.PHOTO)
            }
            val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { attach(it) }
            val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { picked ->
                if (picked != null) draftAttachment = runCatching { AttachmentFiles.contactCard(context, picked) }
                    .onFailure { notice = "Couldn’t read that contact." }.getOrNull()
            }
            fun findLocation() {
                notice = "Finding your location…"
                AttachmentFiles.currentLocation(context) { fix ->
                    if (fix == null) notice = "Location isn’t available. Turn on Location in Android settings and try again."
                    else { notice = null; draftAttachment = Attachment.location(fix.latitude, fix.longitude) }
                }
            }
            val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                if (it.values.any { granted -> granted }) findLocation() else notice = "Allow location access to share where you are."
            }
            fun startRecording() {
                val next = VoiceRecorder(context)
                runCatching { next.start() }.onSuccess { recorder = next }
                    .onFailure { notice = "Couldn’t start recording." }
            }
            val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (it) startRecording() else notice = "Allow microphone access to record voice messages."
            }
            fun choose(source: AttachmentSource) {
                sheet = false
                when (source) {
                    AttachmentSource.GALLERY -> gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    AttachmentSource.CAMERA -> {
                        cameraTarget = AttachmentFiles.uri(context, AttachmentFiles.scratch(context, "jpg"))
                        runCatching { camera.launch(cameraTarget!!) }.onFailure { notice = "No camera app is available." }
                    }
                    AttachmentSource.DOCUMENT -> document.launch(arrayOf("*/*"))
                    AttachmentSource.MONEY -> moneyDialog = true
                    AttachmentSource.GIF -> gifSheet = false
                    AttachmentSource.STICKER -> gifSheet = true
                    AttachmentSource.POLL -> pollDialog = true
                    AttachmentSource.EVENT -> eventDialog = true
                    AttachmentSource.GAMES -> gamesDialog = true
                    AttachmentSource.EFFECT -> effectsDialog = true
                    AttachmentSource.RANDOM -> randomDialog = true
                    AttachmentSource.EDIT -> pickToEdit.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    AttachmentSource.AUDIO -> document.launch(arrayOf("audio/*"))
                    AttachmentSource.CONTACT -> runCatching { contactPicker.launch(null) }.onFailure { notice = "No contacts app is available." }
                    AttachmentSource.LOCATION -> if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) findLocation()
                        else locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            fun submit(text: String, relation: MessageRelation? = null, attachment: Attachment? = null): Boolean {
                if (blocked.isNotEmpty()) {
                    error = blocked
                    return false
                }
                return sendSample?.send(text, sendAs, relation, attachment, via)
                    ?: send?.test(OutgoingMessage(name, emptyList(), text, attachment, -1, relation).fallbackBody()) ?: false
            }
            DisposableEffect(Unit) { onDispose { recorder?.cancel() } }
            LaunchedEffect(draft) { kotlinx.coroutines.delay(500); saveDraft?.accept(draft) }
            DisposableEffect(Unit) { onDispose { saveDraft?.accept(draft) } }
            val ircMode = active == MessageProtocol.IRC && routing?.combined != true
            val messages = remember(lines) { foldSmsReactions(lines) }
            // NickServ's identify notices fold into one card each in IRC chats (NickServ.kt); the rest show as usual.
            val nickServCards = remember(messages, ircMode, nickServChat) {
                if (!ircMode) emptyMap() else NickServRules.fold(messages.map { NickServRules.Heard(it.line.id, it.line.author, it.line.text,
                    !it.line.outgoing, it.line.event || it.line.media != null) }, nickServChat).associateBy { it.first }
            }
            val nickServFolded = remember(nickServCards) { nickServCards.values.flatMap { (it.first + 1)..it.last }.toSet() }
            val currentSubmit by rememberUpdatedState<(String) -> Boolean>({ text -> submit(text) })
            val previewsOn = remember { GifSettings.linkPreviews(prefs) }
            val picturesOn = remember { GifSettings.autoPictures(prefs) }
            val rich = remember(lines, blocked) {
                richContext(lines, if (blocked.isEmpty()) ({ text -> currentSubmit(text); Unit }) else null, previewsOn, picturesOn,
                    FunSettings.games(prefs), FunSettings.random(prefs),
                    // Links from people who aren't saved contacts wait for a tap before the phone contacts their website.
                    loadsRemote = { line -> loadedRemote[line.id] == true ||
                        LinkLoading.auto(line.outgoing, savedSender?.test(line.author) == true, sample) },
                    allowRemote = { line -> loadedRemote[line.id] = true })
            }
            val headerMembers = remember(groupMembers, sampleMembers) {
                groupMembers.ifEmpty { sampleMembers.map { GroupMember(it) } }
            }
            val memberByName = remember(headerMembers) { headerMembers.associateBy { it.name } }
            val avatarSlot = if (group) 34.dp else 0.dp
            val composerFocus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(replyTo?.id) {
                if (replyTo != null) {
                    composerFocus.requestFocus()
                    keyboard?.show()
                }
            }
            var screenEffect by remember { mutableStateOf<Pair<MessageEffect, Long>?>(null) }
            var reactionEffect by remember { mutableStateOf<ReactionShow?>(null) }
            Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalEditPicture provides (if (filesAllowed) { uri: Uri -> editing = uri } else null),
                LocalScreenEffect provides { effect: MessageEffect -> screenEffect = effect to System.nanoTime() },
                LocalReactionEffect provides { animation: ReactionAnimation, anchor: androidx.compose.ui.geometry.Offset ->
                    reactionEffect = ReactionShow(animation, anchor, System.nanoTime()) }) {
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
                            if (headerMembers.size > 1) GroupAvatar(headerMembers, 39, palette.toolbar)
                            else Avatar(initials, name, 39, photo, photoUri)
                            Column(Modifier.padding(start = 10.dp)) {
                            Text(name, fontSize = 17.sp, color = chromeInk, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val mode = when {
                                sample && group -> "Sample group · ${sampleMembers.size + 1} members"
                                group -> "${groupMembers.size + 1} members · ${active.label}" + routing?.account.orEmpty().let { if (it.isEmpty()) "" else " · $it" }
                                sample -> "Sample ${routing?.active?.label ?: transport}"
                                else -> (routing?.active?.label ?: transport) + routing?.account.orEmpty().let { if (it.isEmpty()) "" else " · $it" }
                            }
                            val category = when {
                                senderStyle == SenderStyle.AUTOMATED -> " · Automated sender"
                                sample && group && active == MessageProtocol.RCS -> " · RCS preview"
                                sample && group -> " · ${active.label}"
                                else -> ""
                            }
                            val switchable = routing != null && !routing.combined && routing.formats.size > 1 && routing.switch != null
                            var historyMenu by remember { mutableStateOf(false) }
                            val protection = routing?.encryption?.takeIf { via == active } ?: via.transport().capabilities.encryption
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    Text(mode + category + if (switchable) "  ▾" else "",
                                        (if (switchable) Modifier.clip(RoundedCornerShape(6.dp))
                                            .clickable(onClickLabel = "Switch conversation protocol") { historyMenu = true } else Modifier),
                                        color = chromeInk.copy(alpha = .72f), fontSize = 12.sp)
                                    if (switchable) DropdownMenu(expanded = historyMenu, onDismissRequest = { historyMenu = false }) {
                                        for (format in routing!!.formats) DropdownMenuItem(
                                            text = { Text(if (format == active) "${format.label} ✓" else format.label) },
                                            onClick = { historyMenu = false; if (format != active) routing.switch!!.accept(format) },
                                        )
                                    }
                                }
                                // A small colored padlock; the details' Security section explains it.
                                if (DisplayRules.encryptionLine(prefs, protection) != null) {
                                    SecurityLock(protection, palette.toolbarBackground, Modifier.padding(start = 5.dp)
                                        .clickable(onClickLabel = "Encryption details") { details.run() })
                                }
                                // Muted: the bell with a slash; tapping it says until when, with Unmute.
                                if (muted.isNotEmpty()) MutedMark(muted, chromeInk.copy(alpha = .72f), Modifier.padding(start = 2.dp), 14,
                                    openMute?.let { action -> { action.run() } })
                            }
                            }
                        }
                        if (more != null) ActionIcon("Conversation options", Glyph.More, palette.toolbarAction) { more.run() }
                    }
                    if (!floating) HorizontalDivider(color = palette.edge)
                    // While this chat's network is disconnected: "Disconnected from <network> · Reconnect" (ChatExtras).
                    ConnectionBar(connectionAccount, palette)
                    helpFamily?.let { family -> CommandHelpPage(family, palette) { helpFamily = null } }
                    val list = rememberLazyListState()
                    val scrollScope = rememberCoroutineScope()
                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex)
                    }
                    // A link preview loads after its bubble is drawn, so the newest message (often the link you just sent)
                    // grows below the composer. While it's on screen, follow it by the height it gained.
                    LaunchedEffect(list) {
                        var before: Pair<Int, Int>? = null
                        snapshotFlow {
                            val info = list.layoutInfo
                            info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }?.let { it.index to it.size }
                        }.collect { now ->
                            val grew = now != null && before?.first == now.first && now.second > before!!.second
                            if (grew && list.canScrollForward && !list.isScrollInProgress) list.scrollBy((now!!.second - before!!.second).toFloat())
                            before = now
                        }
                    }
                    request?.let { RequestBanner(it, palette) }
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
                            if (index in nickServFolded) return@itemsIndexed
                            val previous = messages.getOrNull(index - 1)?.line
                            val next = messages.getOrNull(index + 1)?.line
                            val beginsDay = line.day.isNotEmpty() && line.day != previous?.day
                            if (beginsDay) {
                                Box(Modifier.fillMaxWidth().padding(top = if (index == 0) 4.dp else 16.dp, bottom = 12.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text(line.day, color = palette.muted, fontSize = 12.sp)
                                }
                            }
                            nickServCards[index]?.let { card ->
                                NickServCard(card, palette, nickServNick, nickServSaved, nickServ)
                                return@itemsIndexed
                            }
                            if (line.event) {
                                // Channel events (joins, parts, topic changes) are small outlined notes in the IRC color.
                                ChannelEvent(line, palette, MessageProtocol.IRC.tint(palette.appearance))
                                return@itemsIndexed
                            }
                            if (line.attachment == null && line.photo == null && isChatNotice(line.text)) {
                                // Chat setting notices (disappearing messages, encryption), sent or received, use the same outlined note.
                                ChannelEvent(line, palette, line.format?.let { if (it == active) activeColor else it.tint(palette.appearance) } ?: activeColor)
                                return@itemsIndexed
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
                            if (ircMode && !line.outgoing && line.author != null && (!grouped || previous?.event == true)) {
                                // IRC keeps normal bubbles but always names the speaker in their nick color, with the time.
                                Text(androidx.compose.ui.text.buildAnnotatedString {
                                    pushStyle(androidx.compose.ui.text.SpanStyle(color = nickColor(line.author, palette), fontWeight = FontWeight.SemiBold))
                                    append(line.author)
                                    pop()
                                    if (line.time.isNotEmpty()) {
                                        pushStyle(androidx.compose.ui.text.SpanStyle(color = palette.muted))
                                        append("  " + line.time)
                                        pop()
                                    }
                                }, Modifier.padding(start = 9.dp + avatarSlot, top = 5.dp, bottom = 3.dp), fontSize = 12.sp)
                            } else if (group && !grouped && !line.outgoing && line.author != null) {
                                Text(line.author, Modifier.padding(start = 9.dp + avatarSlot, top = 5.dp, bottom = 3.dp),
                                    color = palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            // Label any message that went another way than the conversation's main protocol.
                            if (line.format != null && (routing?.combined == true || line.format != active)) {
                                Text(line.format.label, Modifier.fillMaxWidth().padding(horizontal = 9.dp), color = palette.muted,
                                    fontSize = 11.sp, textAlign = if (line.outgoing) TextAlign.End else TextAlign.Start)
                            }
                            // Automated senders keep their own color; other bubbles use the protocol they were sent with.
                            val lineColor = if (senderStyle == SenderStyle.AUTOMATED) activeColor
                                else line.format?.let { if (it == active) activeColor else it.tint(palette.appearance) } ?: activeColor
                            val showAvatars = group && !line.outgoing
                            Row(verticalAlignment = Alignment.Bottom) {
                            if (showAvatars) Box(Modifier.width(avatarSlot).padding(bottom = 2.dp)) {
                                val member = line.author?.let(memberByName::get)
                                if (!continues) Avatar(member?.initials() ?: initialsOf(line.author ?: "?"), line.author ?: "",
                                    28, member?.photoRes ?: 0, member?.photo)
                            }
                            Box(Modifier.weight(1f)) {
                            MessageBubble(line, palette, if (line.outgoing) lineColor else receiveColor, grouped, continues, animatedId == line.id,
                                exactTimes, display.reactions, quoted, forwarded, rich, display.earlier, index >= messages.size - 3,
                                if (line.outgoing && line.attachment == null && blocked.isEmpty() && !line.status.startsWith("Sending") &&
                                    !line.status.startsWith("Couldn’t")) { updated: String -> submit(editText(line.text, updated)); Unit } else null,
                                sourceIndex?.let { target ->
                                    { scrollScope.launch {
                                        val listIndex = target + if (error != null) 1 else 0
                                        if (palette.appearance.speed == 0) list.scrollToItem(listIndex)
                                        else list.animateScrollToItem(listIndex)
                                    }; Unit }
                                }, { if (blocked.isEmpty()) replyTo = line else error = blocked }, {
                                    val own = if (sample) line.senderKey() == sendAs else line.outgoing
                                    // Tapping your current reaction takes it back; another one replaces it.
                                    val current = display.bySender[if (sample) sendAs else null]
                                    submit("", MessageRelation(RelationKind.REACTION, line.id, line.text, line.author.orEmpty(),
                                        it, own, removed = current == it, previous = if (current != null && current != it) current else "",
                                        targetMedia = line.wordlessMedia()))
                                },
                                { forward.accept(if (forwarded != null) line.text else
                                    forwardAsSms(line.text, if (line.outgoing) "Me" else line.author ?: name), line.media) },
                                { delete.accept(line) }, download?.let { action -> { action.accept(line) } },
                                removeFailed?.let { remove -> {
                                    // Resend the same readable text and file, then drop the failed copy.
                                    val wasReply = quoted != null
                                    if (submit(if (wasReply) line.text else line.text.takeUnless { it == line.media?.caption }.orEmpty(),
                                            null, line.media)) remove.accept(line)
                                    Unit
                                } },
                                removeFailed?.let { remove -> {
                                    draft = quoted?.body ?: line.text.takeUnless { it == line.media?.caption }.orEmpty()
                                    draftAttachment = line.media
                                    remove.accept(line)
                                    composerFocus.requestFocus()
                                } })
                            }
                            }
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
                    pendingEffect?.let { chosen ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                            .background(palette.wash, RoundedCornerShape(12.dp)).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${chosen.emoji}  Sends with ${chosen.label}", Modifier.weight(1f), color = palette.ink, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { effectsDialog = true }) { Text("Change", color = palette.toolbarAction) }
                            ActionIcon("Remove effect", Glyph.Close, palette.toolbarAction) { pendingEffect = null }
                        }
                    }
                    draftAttachment?.let { item ->
                        DraftAttachment(item, palette.ink, palette.muted, { draftAttachment = null }, palette.toolbarAction,
                            item.uri?.let { uri -> { editing = uri } })
                    }
                    notice?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        color = palette.muted, fontSize = 13.sp) }
                    if (moneyDialog) MoneyDialog({ moneyDialog = false }) { text -> submit(text) }
                    if (pollDialog) PollDialog({ pollDialog = false }) { text -> submit(text) }
                    if (eventDialog) EventDialog({ eventDialog = false }) { text -> submit(text) }
                    if (gamesDialog) GamesDialog({ gamesDialog = false }) { text -> submit(text) }
                    if (randomDialog) RandomDialog({ randomDialog = false }) { text -> submit(text) }
                    if (effectsDialog) EffectsDialog({ effectsDialog = false }) { chosen ->
                        effectsDialog = false
                        pendingEffect = chosen
                        composerFocus.requestFocus()
                    }
                    editing?.let { uri ->
                        MediaEditor(uri, { editing = null }) { edited, sendNow ->
                            editing = null
                            // Send goes straight to the chat; Done puts the edit in the composer for a caption.
                            if (sendNow && filesAllowed) { if (submit("", replyTo?.let(MessageRelation::reply), edited)) replyTo = null }
                            else draftAttachment = edited
                        }
                    }
                    gifSheet?.let { stickersFirst ->
                        GifSheet(stickersFirst, filesAllowed, { gifSheet = null }, { url -> submit(url, replyTo?.let(MessageRelation::reply)); replyTo = null },
                            { picked -> if (picked.isSticker() || picked.name == "GIF.mp4") { if (submit("", replyTo?.let(MessageRelation::reply), picked)) replyTo = null } else draftAttachment = picked },
                            openGifSettings?.let { open -> { open.run() } })
                    }
                    if (sheet) AttachmentSheet(sources, sendColor, when {
                        !filesAllowed -> "${via.label} can share locations and contact cards here. Files need another protocol."
                        via == MessageProtocol.SMS -> "A file turns this text into MMS. Carriers limit MMS size, often to 300 KB–1 MB."
                        via == MessageProtocol.IRC -> "IRC has no file transfer of its own, so files arrive as a link."
                        else -> ""
                    }, { sheet = false }, ::choose)
                    if (sendOptions.size > 1) Box(Modifier.padding(start = 16.dp, top = 2.dp)) {
                        val chipFill = accessibleBubbleFill(sendColor)
                        Text("via ${via.label}  ▾", Modifier.clip(RoundedCornerShape(10.dp)).background(chipFill)
                            .clickable(onClickLabel = "Choose how to send") { viaMenu = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Send via ${via.label}. Choose how to send" },
                            color = bubbleTextColor(sendColor), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        DropdownMenu(expanded = viaMenu, onDismissRequest = { viaMenu = false }) {
                            for (format in sendOptions) DropdownMenuItem(
                                text = { Text(if (format == via) "${format.label} ✓" else format.label) },
                                onClick = { chooseVia(format) },
                            )
                            if (routing?.setDefault != null) {
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Always use ${via.label} for $name") }, onClick = {
                                    viaMenu = false
                                    routing.setDefault.accept(via)
                                })
                            }
                        }
                    }
                    val from = routing?.from.orEmpty()
                    if (from.isNotEmpty() && !sample) {
                        // Which account replies go out from, like a mail app's From field. Replies always use the
                        // chat's own account; another account means a new message. Tapping opens the note in place.
                        Row(Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp).clip(RoundedCornerShape(10.dp))
                            .background(palette.wash).clickable(onClickLabel = "About sending from this account") { fromInfo = !fromInfo }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "Sending from $from. About sending from this account" },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("From: ", color = palette.muted, fontSize = 12.sp)
                            Text(from, Modifier.weight(1f, fill = false), color = palette.ink, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (fromInfo) "  ▴" else "  ▾", color = palette.inboxAction, fontSize = 12.sp)
                        }
                        if (fromInfo) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(14.dp)).background(palette.wash).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(FromAccount.explain(from), color = palette.ink, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (routing?.newFromAnother != null) Text(FromAccount.NEW_MESSAGE,
                                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                                        fromInfo = false
                                        routing.newFromAnother.run()
                                    }.padding(vertical = 6.dp), color = palette.inboxAction, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Done", Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { fromInfo = false }
                                    .padding(vertical = 6.dp), color = palette.inboxAction, fontSize = 14.sp)
                            }
                        }
                    }
                    val recording = recorder
                    // The commands matching what's typed after "/" (ChatExtras).
                    if (blocked.isEmpty() && recording == null) CommandHints(commandFamily, draft, palette) { draft = it }
                    if (blocked.isNotEmpty()) {
                        Text(blocked, Modifier.fillMaxWidth().padding(16.dp), color = palette.muted, fontSize = 14.sp)
                    } else if (recording != null) RecordingBar(recording, sendColor, palette.ink, palette.muted, {
                        recording.cancel(); recorder = null
                    }) {
                        recorder = null
                        val voice = recording.stop()
                        if (voice == null) notice = "Hold on a little longer to record a voice message."
                        else if (submit("", replyTo?.let(MessageRelation::reply), voice)) { replyTo = null; sendVia = null; notice = null }
                    } else Composer(draft, { draft = it }, palette, sendColor,
                        sendAs?.let { "Message from $it" } ?: if (sample) "Practice message"
                            else if (via == MessageProtocol.SMS || via == MessageProtocol.MMS) "Text message" else "${via.label} message",
                        sim, chooseSim, composerFocus, draftAttachment != null, { notice = null; sheet = true },
                        if (sendOptions.size > 1) { { viaMenu = true } } else null,
                        if (filesAllowed && draftAttachment == null) { {
                            notice = null
                            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
                            else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        } } else null, if (filesAllowed) { uri, type ->
                            // A GIF or sticker from the keyboard: stickers send right away, GIFs wait in the composer.
                            val picked = runCatching { Attachment.describe(context, uri, AttachmentKind.PHOTO) }.getOrNull()
                            if (picked != null) {
                                val keyboardSticker = type == "image/png" || type == "image/webp"
                                val item = if (keyboardSticker) picked.copy(name = "sticker." + type.substringAfter('/')) else picked
                                if (keyboardSticker && GifSettings.stickersOn(prefs)) submit("", null, item) else draftAttachment = item
                            }
                        } else null) {
                        val words = pendingEffect?.takeIf { draft.isNotBlank() }?.let { effectText(draft, it) } ?: draft
                        if (submit(words, replyTo?.let(MessageRelation::reply), draftAttachment)) {
                            if (draft.isNotBlank()) pendingEffect = null
                            sendVia = null
                            draft = ""
                            draftAttachment = null
                            replyTo = null
                            notice = null
                        }
                    }
                }
            }
            }
            screenEffect?.let { (effect, key) -> ScreenEffectOverlay(effect, key) { screenEffect = null } }
            ReactionEffectOverlay(reactionEffect) { reactionEffect = null }
            }
        }
    }
}

/** Sends what the person typed plus an optional reply or reaction. Live chats hand this to a [MessageTransport]. */
internal fun interface SampleSend {
    fun send(text: String, author: String?, relation: MessageRelation?, attachment: Attachment?, via: MessageProtocol): Boolean
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
    rich: RichContext?,
    earlier: List<String>,
    recent: Boolean,
    editMessage: ((String) -> Unit)?,
    jumpToQuote: (() -> Unit)?,
    reply: () -> Unit,
    react: (String) -> Unit,
    forward: () -> Unit,
    delete: () -> Unit,
    download: (() -> Unit)? = null,
    retry: (() -> Unit)? = null,
    edit: (() -> Unit)? = null,
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
    val loadsRemote = rich == null || rich.loadsRemote(line)
    val pictureLink = remember(line.text) { if (line.attachment == null && line.photo == null && rich?.pictures != false) parsePictureLink(line.text) else null }
    // A linked picture that waits for a tap stays a plain link, with "Tap to load picture" under it.
    val picture = pictureLink.takeIf { loadsRemote }
    // Stickers float on their own, with no bubble behind them.
    val sticker = picture?.sticker == true || line.attachment?.isSticker() == true
    val fill = if (sticker) Color.Transparent else accessibleBubbleFill(chosenFill)
    val padding = if (sticker) 0 else palette.appearance.padding
    var showTime by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var customReaction by remember { mutableStateOf(false) }
    var selectedAction by remember { mutableStateOf("") }
    var drag by remember { mutableStateOf(0f) }
    val currentReply by rememberUpdatedState(reply)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // Message effects: bubble effects move this bubble, screen effects play over the whole chat. Each plays once.
    val effect = remember(line.text) { if (line.attachment == null) parseEffect(line.text) else null }
    val inked = effect?.second == MessageEffect.INVISIBLE_INK
    var inkHidden by remember(line.id, effect) { mutableStateOf(inked) }
    // Tapped ink stays readable for a moment, then covers itself again.
    LaunchedEffect(line.id, inked, inkHidden) {
        if (inked && !inkHidden) { kotlinx.coroutines.delay(INK_REVEAL_MILLIS); inkHidden = true }
    }
    val effectProgress = remember(line.id) { Animatable(1f) }
    val playScreen = LocalScreenEffect.current
    val effectScope = rememberCoroutineScope()
    fun playEffect() {
        val chosen = effect?.second ?: return
        if (palette.appearance.speed == 0) return
        if (chosen.screen) playScreen(chosen)
        else effectScope.launch { effectProgress.snapTo(0f); effectProgress.animateTo(1f, tween(900)) }
    }
    LaunchedEffect(line.id, effect) {
        if (effect != null && recent && FunSettings.effects(context.getSharedPreferences("fluid", android.content.Context.MODE_PRIVATE)) &&
            EffectMemory.firstTime(context, line.id)) playEffect()
    }
    // Reactions: a new one plays once, yours or theirs. Ones already there when the bubble first shows (opening the
    // chat, scrolling back) don't, because the first list seen is the starting point.
    val playReaction = LocalReactionEffect.current
    var reactionsSeen by remember(line.id) { mutableStateOf(reactions) }
    var reactionPop by remember(line.id) { mutableStateOf<Pair<String, Long>?>(null) }
    var reactionMotion by remember(line.id) { mutableStateOf<ReactionAnimation?>(null) }
    val reactionProgress = remember(line.id) { Animatable(1f) }
    val bubbleBounds = remember(line.id) { floatArrayOf(0f, 0f, 0f) }
    val inset = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    LaunchedEffect(line.id, reactions) {
        val added = addedReaction(reactionsSeen, reactions)
        reactionsSeen = reactions
        if (added == null || !reactionAnimationsOn(context, palette.appearance.speed)) return@LaunchedEffect
        reactionPop = added to System.nanoTime()
        val animation = reactionAnimation(added) ?: return@LaunchedEffect
        // Anchored at the bubble's top corner, on the side facing the middle of the screen.
        playReaction(animation, androidx.compose.ui.geometry.Offset(
            if (line.outgoing) bubbleBounds[0] + inset else bubbleBounds[0] + bubbleBounds[2] - inset, bubbleBounds[1] + inset))
        reactionMotion = animation
        reactionProgress.snapTo(0f)
        reactionProgress.animateTo(1f, tween(animation.millis, easing = androidx.compose.animation.core.LinearEasing))
    }
    var editingMessage by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = if (grouped) 3.dp else 8.dp)) {
        val bubbleWidth = minOf(maxWidth * .80f, 460.dp)
        Column(Modifier.align(if (line.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1 - entrance.value) * 10.dp.toPx()
                translationX = drag
                val (effectScale, effectTurn) = bubbleEffectScale(effect?.second, effectProgress.value)
                val moved = reactionBubbleMotion(reactionMotion, reactionProgress.value)
                scaleX = effectScale * moved.scale; scaleY = effectScale * moved.scale; rotationZ = effectTurn + moved.rotation
                translationX += moved.dx.dp.toPx(); translationY += moved.dy.dp.toPx()
            }, horizontalAlignment = if (line.outgoing) Alignment.End else Alignment.Start) {
            Box(Modifier.onGloballyPositioned { coordinates ->
                val at = coordinates.positionInRoot()
                bubbleBounds[0] = at.x; bubbleBounds[1] = at.y; bubbleBounds[2] = coordinates.size.width.toFloat()
            }) {
                Column(Modifier.widthIn(max = bubbleWidth)
                    .bubbleTail(fill, palette.appearance.tails && !continues && !sticker, line.outgoing).clip(shape).background(fill)
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
                    .combinedClickable(onClick = { if (inkHidden) inkHidden = false else showTime = !showTime }, onLongClickLabel = "Message actions",
                        onLongClick = { menu = true })
                    .semantics { customActions = listOf(
                        CustomAccessibilityAction("Reply") { reply(); true },
                        CustomAccessibilityAction("Message actions") { menu = true; true },
                    ) }
                    .padding(horizontal = padding.dp, vertical = (padding * .72f).dp)) {
                    val foreground = bubbleTextColor(chosenFill)
                    line.photo?.let { MessagePicture(it, null, "Message photo", "image/jpeg") }
                    line.attachment?.let { AttachmentContent(it, foreground, download) }
                    val location = remember(line.text) { if (line.attachment == null) parseLocation(line.text) else null }
                    location?.let { LocationCard(it, foreground) }
                    val money = remember(line.text) { if (line.attachment == null) parseMoney(line.text) else null }
                    money?.let { MoneyCard(it, line.outgoing, foreground) }
                    val meeting = remember(line.text) { if (line.attachment == null) parseMeeting(line.text) else null }
                    meeting?.let { MeetingCard(it, line.outgoing, foreground) }
                    picture?.let { MessagePicture(null, it.url, if (it.sticker) "Sticker" else "GIF", "image/gif", sticker = it.sticker) }
                    val poll = remember(line.text) { if (line.attachment == null) parsePoll(line.text) else null }
                    poll?.let { item -> PollCard(item, rich?.polls?.get(item.id), foreground,
                        rich?.respond?.let { respond -> { index: Int -> respond(voteText(item, index)) } }) }
                    val event = remember(line.text) { if (line.attachment == null) parseEvent(line.text) else null }
                    event?.let { EventCard(it, foreground, rich?.rsvps?.get(line.id), rich?.respond) }
                    val game = rich?.games?.get(line.id)
                    game?.let { GameCard(it, foreground, rich.respond) }
                    val rps = rich?.rps?.get(line.id)
                    rps?.let { RpsCard(it, foreground, rich.respond) }
                    val random = remember(line.text) { if (line.attachment == null && rich?.random != false) parseRandom(line.text) else null }
                    random?.let { RandomCard(it, line.outgoing, foreground, motion > 0) }
                    val gameLink = remember(line.text) { if (line.attachment == null) parseGameLink(line.text) else null }
                    gameLink?.let { GameLinkCard(it, line.outgoing, foreground) }
                    val card = picture != null || poll != null || event != null || game != null || money != null || meeting != null ||
                        rps != null || random != null || gameLink != null
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
                    } else {
                        if (line.mention && !line.outgoing) Text("@ Mentions you", Modifier.padding(bottom = 2.dp).semantics { contentDescription = "Mentions you" },
                            color = foreground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        // Hide automatic captions ("Photo", a file name) that only repeat the attachment card.
                        val action = line.text.startsWith("/me ")
                        val shown = when {
                            location != null -> location.note
                            money != null -> ""
                            meeting != null -> ""
                            card -> ""
                            parseVote(line.text) != null -> "🗳️ Voted “" + parseVote(line.text)!!.second + "”"
                            quoted != null -> stripEffect(quoted.body)
                            line.media != null && line.text == line.media?.caption -> ""
                            // IRC and Matrix actions read as "* name does something".
                            action -> "* " + (if (line.outgoing) "you" else line.author ?: "") + " " + line.text.removePrefix("/me ")
                            else -> effect?.first ?: line.text
                        }
                        // *Bold*, _italic_, ~strike~, `code`, and ||spoilers|| that stay covered until tapped.
                        var revealed by remember(shown) { mutableStateOf(setOf<Int>()) }
                        val formatted = remember(shown, revealed, foreground) {
                            if (hasFormatting(shown)) formatMessage(shown, foreground, revealed) { revealed = revealed + it } else null
                        }
                        val style = if (action) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
                        // Invisible ink covers the words themselves (not quotes or cards). Reduced motion stills the sparkles only.
                        val ink = Modifier.invisibleInk(inkHidden, foreground, motion > 0)
                            .then(if (inkHidden) Modifier.clearAndSetSemantics { contentDescription = "Invisible ink message. Tap to reveal." } else Modifier)
                        // While inked, spoiler links stay out of the way so a tap reveals the ink first.
                        if (formatted != null && !inkHidden) Text(formatted, ink, fontSize = palette.appearance.size.sp,
                            lineHeight = (palette.appearance.size + 4).sp, color = foreground, fontStyle = style)
                        else if (shown.isNotEmpty()) Text(shown, ink, fontSize = palette.appearance.size.sp,
                            lineHeight = (palette.appearance.size + 4).sp, color = foreground, fontStyle = style)
                        // A preview of the first link, like a card for a video, song, or article.
                        val link = remember(shown) { if (rich?.previews == true && !card && line.attachment == null) previewableLink(shown) else null }
                        if (pictureLink != null && picture == null) TapToLoad(LinkLoading.TAP_PICTURE, foreground) { rich?.allowRemote?.invoke(line) }
                        else link?.takeIf { !inkHidden }?.let {
                            if (loadsRemote) LinkPreviewCard(it, foreground)
                            else TapToLoad(LinkLoading.TAP_PREVIEW, foreground) { rich?.allowRemote?.invoke(line) }
                        }
                        // A Zoom, Google Meet, Teams, or Jitsi link in an everyday message gets a Join button.
                        if (!card && !inkHidden && line.attachment == null) MeetingChip(shown, foreground)
                    }
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
                    if (editMessage != null) DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editingMessage = true })
                    if (effect != null && palette.appearance.speed > 0) DropdownMenuItem(text = { Text("Replay ${effect.second.label.lowercase()}") },
                        onClick = { menu = false; inkHidden = false; playEffect() })
                    DropdownMenuItem(text = { Text("Forward") }, onClick = { menu = false; forward() })
                    DropdownMenuItem(text = { Text("Copy") }, onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(line.text))
                        menu = false
                    })
                    line.media?.takeIf { it.uri != null && it.kind != AttachmentKind.LOCATION }?.let { media ->
                        DropdownMenuItem(text = { Text("Save to phone") }, onClick = {
                            menu = false
                            effectScope.launch {
                                val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    saveToPhone(context, media.uri!!, media.mime, media.name)
                                }
                                android.widget.Toast.makeText(context, saved ?: "Couldn't save it", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
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
                if (editingMessage && editMessage != null) {
                    var words by remember { mutableStateOf(effect?.first ?: line.text) }
                    FormPage(onDismissRequest = { editingMessage = false }, title = { Text("Edit message") }, text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(words, { words = it.take(1500) }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 8)
                            Text("FluidMessages updates the message. People on other apps get the new text as a correction.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, confirmButton = {
                        TextButton(enabled = words.isNotBlank() && words.trim() != (effect?.first ?: line.text).trim(),
                            onClick = { editingMessage = false; editMessage(words) }) { Text("Save") }
                    }, dismissButton = { TextButton(onClick = { editingMessage = false }) { Text("Cancel") } })
                }
                if (customReaction) EmojiReactionDialog({ customReaction = false }) {
                    customReaction = false
                    react(it)
                }
                if (selectedAction.isNotEmpty()) {
                    FormPage(
                        onDismissRequest = { selectedAction = "" },
                        title = { Text(selectedAction) },
                        text = {
                            if (selectedAction == "Select text") {
                                SelectionContainer {
                                    Text(line.text, Modifier.heightIn(max = 290.dp).verticalScroll(rememberScrollState()))
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(listOf(if (line.outgoing) "Outgoing" else "Incoming", line.day, line.time, line.status)
                                        .filter(String::isNotEmpty).joinToString(" · "))
                                    if (earlier.isNotEmpty()) {
                                        Text("Earlier versions", fontWeight = FontWeight.SemiBold)
                                        for (version in earlier) Text(stripEffect(version), fontSize = 14.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { selectedAction = "" }) { Text("Done") } },
                    )
                }
            }
            if (reactions.isNotEmpty()) ReactionPill(reactions, palette, line.outgoing, reactionPop)
            val failed = line.outgoing && line.status.startsWith("Couldn’t send")
            val shown = listOfNotNull("Edited".takeIf { earlier.isNotEmpty() }, line.time.takeIf { (showTime || exactTimes) && it.isNotEmpty() },
                line.status.takeIf { it.isNotEmpty() && !failed }).joinToString(" · ")
            // A small outlined lock marks every message FluidMessages encrypted, so you can check a chat at a glance.
            val details = if (line.encrypted) "🔒\uFE0E" + (if (shown.isEmpty()) "" else " $shown") else shown
            if (details.isNotEmpty() || line.expires > 0) Row(Modifier.padding(top = 3.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (details.isNotEmpty()) Text(details, color = palette.muted, fontSize = 11.sp)
                if (line.expires > 0) DisappearTimer(line.expires, palette.muted)
            }
            if (failed) {
                // Like a "Not Delivered" notice: red text under the bubble, with resend and edit choices.
                var failedMenu by remember { mutableStateOf(false) }
                val red = Color(MessageStyle.Swatch.RED.color(palette.appearance.dark, palette.appearance.contrast))
                Box {
                    Text("Not delivered · Tap to resend or edit", Modifier.padding(top = 3.dp, end = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = "Resend or edit this message") { failedMenu = true }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                        color = red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    DropdownMenu(expanded = failedMenu, onDismissRequest = { failedMenu = false }) {
                        if (retry != null) DropdownMenuItem(text = { Text("Try again") }, onClick = { failedMenu = false; retry() })
                        if (edit != null) DropdownMenuItem(text = { Text("Edit and resend") }, onClick = { failedMenu = false; edit() })
                        DropdownMenuItem(text = { Text("Delete", color = red) }, onClick = { failedMenu = false; delete() })
                    }
                }
                if (line.status.length > "Couldn’t send".length + 3) Text(line.status.removePrefix("Couldn’t send").trimStart(' ', '·', ':'),
                    Modifier.padding(end = 4.dp), color = palette.muted, fontSize = 11.sp)
            }
        }
    }
}

/** A small timer by a disappearing message's time. Tapping it says how long is left, counting down while the chat is open. */
@Composable
private fun DisappearTimer(expires: Long, color: Color) {
    var open by remember(expires) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expires, open) {
        while (open) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(if (expires - now < 120_000) 1_000 else 30_000)
        }
    }
    val left = Disappearing.timeLeft(expires - now)
    Row(Modifier.clip(RoundedCornerShape(6.dp))
        .clickable(onClickLabel = if (open) "Hide time left" else "Show time left") { now = System.currentTimeMillis(); open = !open }
        .semantics(mergeDescendants = true) { contentDescription = Disappearing.timeLeft(expires - System.currentTimeMillis()) }
        .padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⏱︎", color = color, fontSize = 12.sp)
        if (open) Text(" $left", color = color, fontSize = 11.sp)
    }
}

/**
 * The reactions under a bubble. [pop] is the newest reaction, set only when reaction animations are on: the pill
 * pops, and a custom emoji also floats a copy of itself up. Built-in reactions play their own animation instead.
 */
@Composable
private fun ReactionPill(reactions: List<String>, palette: Palette, outgoing: Boolean, pop: Pair<String, Long>?) {
    val scale = remember { Animatable(1f) }
    val float = remember { Animatable(1f) }
    var floating by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pop?.second) {
        val (added, _) = pop ?: return@LaunchedEffect
        launch { scale.snapTo(.4f); scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .45f, stiffness = 500f)) }
        if (reactionAnimation(added) == null) {
            floating = added
            float.snapTo(0f); float.animateTo(1f, tween(900)); floating = null
        }
    }
    Box(contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(reactions.joinToString(" "), Modifier.padding(top = 2.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .background(palette.wash, RoundedCornerShape(14.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
            fontSize = 14.sp)
        floating?.let { emoji ->
            Text(emoji, Modifier.graphicsLayer {
                translationY = -float.value * 70.dp.toPx()
                alpha = 1f - float.value
                scaleX = 1f + float.value; scaleY = 1f + float.value
            }, fontSize = 22.sp)
        }
    }
}

@Composable
private fun EmojiReactionDialog(close: () -> Unit, react: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val emoji = input.trim()
    FormPage(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    draft: String, changed: (String) -> Unit, palette: Palette, sendColor: Color,
    hint: String, sim: String?, chooseSim: Runnable?, focus: FocusRequester,
    hasAttachment: Boolean, attach: (() -> Unit)?, longSend: (() -> Unit)? = null, voice: (() -> Unit)? = null,
    receive: ((Uri, String) -> Unit)? = null,
    send: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sim != null && chooseSim != null) {
            TextButton(onClick = chooseSim::run, contentPadding = PaddingValues(horizontal = 7.dp)) {
                Text(sim, color = palette.action, fontSize = 13.sp)
            }
        }
        if (attach != null) IconButton(onClick = attach, modifier = Modifier.semantics { contentDescription = "Attach" }) {
            DrawGlyph(Glyph.Plus, palette.toolbarAction, Modifier.size(24.dp))
        }
        Surface(
            Modifier.weight(1f), color = palette.toolbar, shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 13.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom) {
                // The text lives in a TextFieldState so keyboards can insert GIFs and stickers straight into the chat.
                val field = androidx.compose.foundation.text.input.rememberTextFieldState(draft)
                val latestDraft by rememberUpdatedState(draft)
                val latestChanged by rememberUpdatedState(changed)
                LaunchedEffect(draft) {
                    if (field.text.toString() != draft) field.edit { replace(0, length, draft) }
                }
                LaunchedEffect(field) {
                    snapshotFlow { field.text.toString() }.collect { if (it != latestDraft) latestChanged(it) }
                }
                val latestReceive by rememberUpdatedState(receive)
                BasicTextField(
                    state = field,
                    lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(1, 4),
                    modifier = Modifier.weight(1f).focusRequester(focus).padding(top = 9.dp, bottom = 9.dp, end = 5.dp)
                        .semantics { contentDescription = hint }
                        .then(if (receive == null) Modifier else Modifier.contentReceiver { content ->
                            val clip = content.clipEntry.clipData
                            var rest: androidx.compose.foundation.content.TransferableContent? = content
                            for (index in 0 until clip.itemCount) {
                                val uri = clip.getItemAt(index).uri ?: continue
                                val type = clip.description.getMimeType(0) ?: "image/*"
                                latestReceive?.invoke(uri, type)
                                rest = null
                            }
                            rest
                        }),
                    textStyle = TextStyle(color = palette.toolbarInk, fontSize = 16.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(palette.action),
                    decorator = { inner ->
                        Box {
                            if (draft.isEmpty()) Text(hint, color = palette.toolbarInk.copy(alpha = .65f), fontSize = 16.sp)
                            inner()
                        }
                    },
                )
                val ready = draft.isNotBlank() || hasAttachment
                // With nothing typed, the button records a voice message instead.
                if (!ready && voice != null) Box(Modifier.size(40.dp).clip(CircleShape)
                    .clickable(onClickLabel = "Record voice message", onClick = voice)
                    .semantics { contentDescription = "Record voice message" }, contentAlignment = Alignment.Center) {
                    DrawGlyph(Glyph.Mic, palette.toolbarAction, Modifier.size(22.dp))
                } else
                // Touch and hold Send to pick another protocol, such as SMS instead of RCS.
                Box(Modifier.size(40.dp).clip(CircleShape).combinedClickable(enabled = ready, role = Role.Button,
                    onLongClickLabel = if (longSend != null) "Choose how to send" else null,
                    onLongClick = longSend, onClick = send)
                    .semantics { contentDescription = "Send message" }, contentAlignment = Alignment.Center) {
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

private object AvatarCache {
    private val photos = android.util.LruCache<String, ImageBitmap>(120)
    fun get(key: String): ImageBitmap? = photos.get(key)
    fun put(key: String, photo: ImageBitmap) { photos.put(key, photo) }
}

@Composable
internal fun Avatar(initials: String, seed: String, size: Int, photo: Int = 0, photoUri: Uri? = null, photoRevision: Int = 0) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Decoded photos are kept for the whole app run, so scrolling and coming back never flash initials.
    val cacheKey = photoUri?.let { "$it@$photoRevision" }
    val thumbnail by produceState(cacheKey?.let { AvatarCache.get(it) }, photoUri, photoRevision) {
        if (cacheKey == null) { value = null; return@produceState }
        AvatarCache.get(cacheKey)?.let { value = it; return@produceState }
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(photoUri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap()
                }
            } catch (_: java.io.IOException) { null }
              catch (_: SecurityException) { null }
              catch (_: IllegalArgumentException) { null }
        }?.also { AvatarCache.put(cacheKey, it) }
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

internal enum class Glyph { Settings, Filter, Compose, Search, Close, Back, Forward, More, Send, Person,
    Plus, Photo, Camera, Document, Music, Pin, Mic, Play, Pause, Video, Stop, Money, Gif, Sticker, Poll, Calendar, Chess, Brush, Sparkle, Dice,
    /** An outlined bell with a slash: a muted chat or server. */
    Muted }

@Composable
internal fun ActionIcon(label: String, glyph: Glyph, color: Color, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.semantics { contentDescription = label }) {
        DrawGlyph(glyph, color, Modifier.size(22.dp))
    }
}

@Composable
internal fun DrawGlyph(glyph: Glyph, color: Color, modifier: Modifier) {
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
            Glyph.Plus -> { line(.5f, .22f, .5f, .78f); line(.22f, .5f, .78f, .5f) }
            Glyph.Photo -> {
                drawRoundRect(color, point(.14f, .2f), Size(size.width * .72f, size.height * .6f),
                    androidx.compose.ui.geometry.CornerRadius(stroke * 2), style = Stroke(stroke))
                drawCircle(color, stroke * 1.2f, point(.36f, .4f))
                line(.2f, .74f, .45f, .52f); line(.45f, .52f, .6f, .64f); line(.6f, .64f, .8f, .46f)
            }
            Glyph.Camera -> {
                drawRoundRect(color, point(.12f, .3f), Size(size.width * .76f, size.height * .52f),
                    androidx.compose.ui.geometry.CornerRadius(stroke * 2), style = Stroke(stroke))
                line(.36f, .3f, .42f, .2f); line(.42f, .2f, .58f, .2f); line(.58f, .2f, .64f, .3f)
                drawCircle(color, size.minDimension * .15f, point(.5f, .56f), style = Stroke(stroke))
            }
            Glyph.Document -> {
                val outline = Path().apply {
                    moveTo(size.width * .26f, size.height * .14f); lineTo(size.width * .6f, size.height * .14f)
                    lineTo(size.width * .76f, size.height * .3f); lineTo(size.width * .76f, size.height * .86f)
                    lineTo(size.width * .26f, size.height * .86f); close()
                }
                drawPath(outline, color, style = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                line(.36f, .5f, .66f, .5f); line(.36f, .66f, .66f, .66f)
            }
            Glyph.Music -> {
                line(.42f, .7f, .42f, .2f); line(.42f, .2f, .76f, .14f); line(.76f, .14f, .76f, .62f)
                drawCircle(color, size.minDimension * .1f, point(.33f, .72f))
                drawCircle(color, size.minDimension * .1f, point(.67f, .64f))
            }
            Glyph.Pin -> {
                drawCircle(color, size.minDimension * .24f, point(.5f, .38f), style = Stroke(stroke))
                drawCircle(color, stroke * .9f, point(.5f, .38f))
                line(.33f, .55f, .5f, .86f); line(.67f, .55f, .5f, .86f)
            }
            Glyph.Mic -> {
                drawRoundRect(color, point(.38f, .14f), Size(size.width * .24f, size.height * .44f),
                    androidx.compose.ui.geometry.CornerRadius(size.width * .12f), style = Stroke(stroke))
                drawArc(color, 0f, 180f, false, point(.26f, .3f), Size(size.width * .48f, size.height * .42f), style = Stroke(stroke))
                line(.5f, .72f, .5f, .86f)
            }
            Glyph.Play -> drawPath(Path().apply {
                moveTo(size.width * .32f, size.height * .2f); lineTo(size.width * .8f, size.height * .5f)
                lineTo(size.width * .32f, size.height * .8f); close()
            }, color)
            Glyph.Pause -> {
                drawRect(color, point(.3f, .22f), Size(size.width * .14f, size.height * .56f))
                drawRect(color, point(.56f, .22f), Size(size.width * .14f, size.height * .56f))
            }
            Glyph.Stop -> drawRoundRect(color, point(.28f, .28f), Size(size.width * .44f, size.height * .44f),
                androidx.compose.ui.geometry.CornerRadius(stroke))
            Glyph.Money -> {
                // A banknote: outline, a coin in the middle, and corner marks.
                drawRoundRect(color, point(.1f, .27f), Size(size.width * .8f, size.height * .46f),
                    androidx.compose.ui.geometry.CornerRadius(stroke * 1.4f), style = Stroke(stroke))
                drawCircle(color, size.minDimension * .11f, point(.5f, .5f), style = Stroke(stroke))
                drawCircle(color, stroke * .7f, point(.24f, .5f)); drawCircle(color, stroke * .7f, point(.76f, .5f))
            }
            Glyph.Video -> {
                drawRoundRect(color, point(.12f, .28f), Size(size.width * .5f, size.height * .44f),
                    androidx.compose.ui.geometry.CornerRadius(stroke * 2), style = Stroke(stroke))
                line(.62f, .44f, .86f, .3f); line(.86f, .3f, .86f, .7f); line(.86f, .7f, .62f, .56f)
            }
            Glyph.Gif -> {
                // A rounded tag with three short marks, read as "GIF" at small sizes.
                drawRoundRect(color, point(.1f, .26f), Size(size.width * .8f, size.height * .48f),
                    androidx.compose.ui.geometry.CornerRadius(stroke * 2), style = Stroke(stroke))
                line(.3f, .4f, .3f, .6f); line(.45f, .4f, .45f, .6f); line(.6f, .4f, .6f, .6f); line(.6f, .4f, .72f, .4f); line(.6f, .5f, .7f, .5f)
            }
            Glyph.Sticker -> {
                // A square with its corner peeled over.
                val outline = Path().apply {
                    moveTo(size.width * .2f, size.height * .2f); lineTo(size.width * .8f, size.height * .2f)
                    lineTo(size.width * .8f, size.height * .56f); lineTo(size.width * .56f, size.height * .8f)
                    lineTo(size.width * .2f, size.height * .8f); close()
                }
                drawPath(outline, color, style = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                line(.56f, .8f, .56f, .6f); line(.56f, .6f, .8f, .56f)
            }
            Glyph.Poll -> { line(.25f, .78f, .25f, .5f); line(.5f, .78f, .5f, .22f); line(.75f, .78f, .75f, .4f) }
            Glyph.Brush -> {
                // A pencil drawing a short squiggle.
                line(.62f, .18f, .82f, .38f); line(.62f, .18f, .3f, .5f); line(.82f, .38f, .5f, .7f); line(.3f, .5f, .5f, .7f)
                line(.3f, .5f, .24f, .76f); line(.24f, .76f, .5f, .7f)
            }
            Glyph.Calendar -> {
                drawRoundRect(color, point(.16f, .24f), Size(size.width * .68f, size.height * .6f),
                    androidx.compose.ui.geometry.CornerRadius(stroke * 1.6f), style = Stroke(stroke))
                line(.16f, .42f, .84f, .42f); line(.34f, .16f, .34f, .3f); line(.66f, .16f, .66f, .3f)
                drawCircle(color, stroke * .7f, point(.36f, .6f)); drawCircle(color, stroke * .7f, point(.5f, .6f)); drawCircle(color, stroke * .7f, point(.64f, .6f))
            }
            Glyph.Sparkle -> {
                // A four-pointed star and a small one.
                line(.45f, .14f, .45f, .76f); line(.14f, .45f, .76f, .45f)
                line(.45f, .14f, .52f, .38f); line(.45f, .76f, .38f, .52f); line(.14f, .45f, .38f, .38f); line(.76f, .45f, .52f, .52f)
                line(.78f, .66f, .78f, .86f); line(.68f, .76f, .88f, .76f)
            }
            Glyph.Dice -> {
                drawRoundRect(color, point(.2f, .2f), Size(size.width * .6f, size.height * .6f),
                    androidx.compose.ui.geometry.CornerRadius(stroke * 2f), style = Stroke(stroke))
                drawCircle(color, stroke * .9f, point(.36f, .36f)); drawCircle(color, stroke * .9f, point(.5f, .5f)); drawCircle(color, stroke * .9f, point(.64f, .64f))
            }
            Glyph.Chess -> {
                // A pawn: head, collar, and base.
                drawCircle(color, size.minDimension * .12f, point(.5f, .28f), style = Stroke(stroke))
                line(.38f, .46f, .62f, .46f); line(.43f, .46f, .37f, .74f); line(.57f, .46f, .63f, .74f); line(.28f, .8f, .72f, .8f)
            }
            Glyph.Muted -> {
                // The same outline as FluidMessages Web's (chats.js muteMark), on a 16-unit grid: a bell with a flared
                // base, its clapper, and a slash across.
                fun at(x: Float, y: Float) = Offset(x / 16f * size.width, y / 16f * size.height)
                val bell = Path().apply {
                    moveTo(at(4f, 10.8f).x, at(4f, 10.8f).y)
                    lineTo(at(4f, 7.5f).x, at(4f, 7.5f).y)
                    cubicTo(at(4f, 5.1f).x, at(4f, 5.1f).y, at(5.8f, 3.5f).x, at(5.8f, 3.5f).y, at(8f, 3.5f).x, at(8f, 3.5f).y)
                    cubicTo(at(10.2f, 3.5f).x, at(10.2f, 3.5f).y, at(12f, 5.1f).x, at(12f, 5.1f).y, at(12f, 7.5f).x, at(12f, 7.5f).y)
                    lineTo(at(12f, 10.8f).x, at(12f, 10.8f).y)
                    lineTo(at(13.3f, 12.2f).x, at(13.3f, 12.2f).y)
                    lineTo(at(2.7f, 12.2f).x, at(2.7f, 12.2f).y)
                    close()
                }
                drawPath(bell, color, style = Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawArc(color, 0f, 180f, false, at(6.5f, 12.2f), Size(3f / 16f * size.width, 3f / 16f * size.height),
                    style = Stroke(stroke, cap = StrokeCap.Round))
                drawLine(color, at(2.2f, 2.2f), at(13.8f, 13.8f), stroke, StrokeCap.Round)
            }
        }
    }
}

/** The muted mark beside a header's lock, a row's protocol tags, and a server's heading. Tappable when [open] is set. */
@Composable
internal fun MutedMark(label: String, color: Color, modifier: Modifier = Modifier, size: Int = 13, open: (() -> Unit)? = null) {
    val base = if (open == null) modifier.semantics { contentDescription = label }
        else modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Change or unmute") { open() }.semantics { contentDescription = label }
    Box(base.padding(if (open == null) 0.dp else 3.dp)) { DrawGlyph(Glyph.Muted, color, Modifier.size(size.dp)) }
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


/**
 * A small inbox drawn with the real inbox row, search, and dock code, so toolbar finish, search
 * position, density, and protocol tags preview exactly as they will look.
 */
@Composable
internal fun InboxPreview(appearance: Appearance) {
    val palette = Palette(appearance)
    val dock = appearance.searchPosition == "bottom"
    val rows = listOf(
        ThreadRow("preview-maya", "Maya Chen", "MC", "See you there!", "9:41 AM", true, false, R.drawable.sample_maya,
            protocol = "RCS · Matrix · Email"),
        ThreadRow("preview-jordan", "Jordan Ellis", "JE", "That looks like a great walk.", "Yesterday", false, false,
            R.drawable.sample_jordan, protocol = "RCS"),
        ThreadRow("preview-alerts", "Trail Alerts", "TA", "Trail update: the south entrance is open.", "Today", false, false,
            protocol = "SMS", automated = true),
        ThreadRow("preview-irc", "#radio-club", "RC", "I’ll bring the analyzer.", "Today", false, false, protocol = "IRC"),
    )
    WallpaperSurface("none", palette.bg, Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(bottom = if (dock) 70.dp else 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    DrawGlyph(Glyph.Settings, palette.inboxAction, Modifier.padding(12.dp).size(22.dp))
                    Spacer(Modifier.weight(1f))
                    if (!dock) DrawGlyph(Glyph.Compose, palette.inboxAction, Modifier.padding(12.dp).size(22.dp))
                    DrawGlyph(Glyph.Filter, palette.inboxAction, Modifier.padding(12.dp).size(22.dp))
                }
                Column(Modifier.padding(horizontal = 18.dp)) {
                    Text("Messages", color = palette.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (!dock) SearchBox("", palette) {}
                }
                Column(Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp)) {
                    for (row in rows) ConversationRow(row, palette, true, {}, {}, null)
                }
            }
            if (dock) {
                val dockColor = if (appearance.toolbarFinish == "plain") palette.bg else palette.toolbar
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    color = dockColor, shape = RoundedCornerShape(27.dp), border = BorderStroke(1.dp, palette.edge)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SearchBox("", palette, inDock = true) {} }
                        DrawGlyph(Glyph.Compose, palette.toolbarAction, Modifier.padding(14.dp).size(22.dp))
                    }
                }
            }
        }
    }
}


private val nickHues = listOf(0xff3b82c4, 0xffc0504d, 0xff2f9e6e, 0xff9b59b6, 0xffd4842a, 0xff1f9bb0, 0xffc2457a, 0xff6d8a2b)

/** A stable color per nickname, shaded for the background like other text colors. */
@Composable
private fun nickColor(nick: String, palette: Palette): Color {
    val base = Color(nickHues[Math.floorMod(nick.lowercase().hashCode(), nickHues.size)].toInt())
    return if (palette.appearance.dark) lerp(base, Color.White, .25f) else accessibleBubbleFill(base)
}

@Composable
private fun ChannelEvent(line: ChatLine, palette: Palette, tint: Color) {
    val ink = if (palette.appearance.dark) tint else accessibleBubbleFill(tint)
    Box(Modifier.fillMaxWidth().padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
        // A chat setting notice shows only its note, not the line meant for other apps.
        val text = remember(line.text) { parseChatNotice(line.text)?.let(::chatNoticeLabel) ?: line.text }
        Text(text + if (line.time.isNotEmpty()) " · " + line.time else "",
            Modifier.border(1.dp, ink.copy(alpha = .7f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
            color = ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
