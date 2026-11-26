package org.fluidmessages

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.util.Locale

/**
 * Messages → Chats | Communities, the same design as FluidMessages Web (server/servers.js). Communities lists each
 * server (IRC network, Matrix account, XMPP account) under a collapsible header, with its channels and rooms as chat
 * rows. Settings → Messaging → "Show community chats in Chats" decides whether those rows also show in Chats.
 */
internal object Communities {
    /** On by default. Mirrored to FluidMessages Web as appearance.showCommunityChats. */
    const val SHOW_PREF = "showCommunityChats"
    /** "recent", "name", or "joined". Mirrored as appearance.communitySort. */
    const val SORT_PREF = "communitySort"
    /** Servers whose sections are collapsed, as a JSON array of account ids. This phone only. */
    const val COLLAPSED_PREF = "communityCollapsed"
    /** "chats" or "communities": which side of the switch the inbox opens on. */
    const val VIEW_PREF = "inboxView"

    enum class Sort(val id: String, val label: String) {
        RECENT("recent", "Recent activity"), NAME("name", "Name (A–Z)"), JOINED("joined", "Date joined");
        companion object {
            @JvmStatic fun parse(id: String?) = entries.firstOrNull { it.id == id } ?: RECENT
        }
    }

    data class Group(val server: ServerChip, val channels: List<ThreadRow>) {
        val unread get() = channels.count { it.unread }
        val last get() = channels.maxOfOrNull { it.lastAt } ?: 0L
    }

    /** Whether Chats lists a row: a community chat (a channel or room on a server) only while the setting is on. */
    @JvmStatic
    fun showsInChats(community: Boolean, showCommunityChats: Boolean) = !community || showCommunityChats

    @JvmStatic
    fun show(prefs: SharedPreferences) = prefs.getBoolean(SHOW_PREF, true)

    @JvmStatic
    fun sort(prefs: SharedPreferences) = Sort.parse(prefs.getString(SORT_PREF, Sort.RECENT.id))

    /** "#radio-club" sorts as "radio-club". */
    fun sortKey(name: String) = name.trimStart('#', '&', '!', '+').lowercase(Locale.ROOT)

    private val byName = compareBy<ThreadRow> { sortKey(it.name) }

    fun channelOrder(sort: Sort): Comparator<ThreadRow> = when (sort) {
        Sort.RECENT -> compareByDescending<ThreadRow> { it.lastAt }.then(byName)
        Sort.NAME -> byName.thenByDescending { it.lastAt }
        Sort.JOINED -> compareByDescending<ThreadRow> { it.joinedAt }.then(byName)
    }

    /**
     * Each server with its channels in [sort] order. Servers follow their channels: the newest message first, by name,
     * or the most recently added first (ServerChip.order counts up as servers are added).
     */
    @JvmStatic
    fun group(servers: List<ServerChip>, channels: List<ThreadRow>, sort: Sort): List<Group> {
        val groups = servers.map { server -> Group(server, channels.filter { it.server == server.id }.sortedWith(channelOrder(sort))) }
        val serverName = compareBy<Group> { sortKey(it.server.name) }
        return groups.sortedWith(when (sort) {
            Sort.RECENT -> compareByDescending<Group> { it.last }.then(serverName)
            Sort.NAME -> serverName
            Sort.JOINED -> compareByDescending<Group> { it.server.order }.then(serverName)
        })
    }

    @JvmStatic
    fun collapsed(prefs: SharedPreferences): Set<String> = try {
        val array = JSONArray(prefs.getString(COLLAPSED_PREF, "[]"))
        (0 until array.length()).map(array::getString).toSet()
    } catch (error: Exception) { emptySet() }

    fun toggle(prefs: SharedPreferences, id: String): Set<String> {
        val next = collapsed(prefs).let { if (id in it) it - id else it + id }
        prefs.edit().putString(COLLAPSED_PREF, JSONArray(next.toList()).toString()).apply()
        return next
    }

    /** A server's initials: "LC" for Libera.Chat, "EX" for example.org. */
    fun initials(name: String): String {
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return (if (words.size > 1) "${words[0][0]}${words[1][0]}" else words.firstOrNull()?.take(2) ?: "?").uppercase(Locale.ROOT)
    }

    fun noun(service: String) = when (service) { "irc" -> "channel"; "matrix" -> "room"; else -> "group chat" }
}

/** Chats | Communities, under the Messages title. [unread] shows on Communities when its chats are hidden from Chats. */
@Composable
internal fun InboxSwitch(palette: Palette, communities: Boolean, unread: Int, pick: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(11.dp)).background(palette.wash).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (side in listOf(false, true)) {
            val on = side == communities
            val label = if (side) "Communities" else "Chats"
            val count = if (side) unread else 0
            Row(Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(9.dp))
                .background(if (on) palette.bg else Color.Transparent)
                .clickable(role = Role.Tab) { if (!on) pick(side) }
                .semantics(mergeDescendants = true) {
                    contentDescription = label + if (count > 0) ", $count unread" else ""
                    stateDescription = if (on) "Selected" else "Not selected"
                },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = palette.ink, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                if (count > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(TabBadges.label(count), Modifier.clip(RoundedCornerShape(50)).background(palette.inboxAction)
                        .widthIn(min = 20.dp).padding(horizontal = 6.dp), color = Color(MessageStyle.textOn(palette.inboxAction.toArgb())),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** What the Communities list does: open a chat or its details, a server's actions, and Add server. */
internal class CommunityActions(
    val open: (String) -> Unit,
    val details: (String) -> Unit,
    val menu: ((String) -> Unit)?,
    /** ("browse" | "join" | "details" | "add", account id). */
    val server: (String, String) -> Unit,
    val sort: (Communities.Sort) -> Unit,
)

/**
 * Communities: the sort menu, then each server's section with its channels as chat rows, then Add server. With no
 * servers, a plain explanation and Add server. While searching, servers with no matching channel are left out.
 */
@Composable
internal fun CommunitiesList(prefs: SharedPreferences, palette: Palette, servers: List<ServerChip>, channels: List<ThreadRow>,
                             sort: Communities.Sort, search: String, showTags: Boolean, bottom: Int, actions: CommunityActions) {
    if (servers.isEmpty()) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 26.dp).fillMaxWidth()
            .background(palette.wash, RoundedCornerShape(22.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("No communities yet", color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Communities are public chat networks with many rooms: IRC networks, Matrix homeservers and their Spaces, and XMPP "
                + "servers. Add one to join its channels and rooms. They show here, and in Chats if you like.", color = palette.muted, fontSize = 15.sp)
            AppButton(onClick = { actions.server("add", "") }) { Text("Add server") }
            TextButton(onClick = { actions.server("guide", "") }) { Text("How communities work", color = palette.inboxAction) }
        }
        return
    }
    var collapsed by remember { mutableStateOf(Communities.collapsed(prefs)) }
    val groups = remember(servers, channels, sort, search) {
        Communities.group(servers, channels, sort).filter { search.isEmpty() || it.channels.isNotEmpty() }
    }
    // Invitations to channels, rooms, and group chats, and contact requests, wait on their own page (ChatExtras).
    val waiting = PhoneChat.invites(prefs).size
    var invitesOpen by remember { mutableStateOf(false) }
    if (invitesOpen) InvitesPage(palette, { invitesOpen = false }) { address -> invitesOpen = false; actions.open(address) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 2.dp, bottom = bottom.dp)) {
        if (waiting > 0) item(key = "invites") {
            TextButton(onClick = { invitesOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (waiting == 1) "1 invitation waiting" else "$waiting invitations waiting", color = palette.inboxAction,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        item(key = "sort") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Sort communities: ${sort.label}" }) {
                        Text("Sort: ", color = palette.muted, fontSize = 14.sp)
                        Text(sort.label, color = palette.inboxAction, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (choice in Communities.Sort.entries) DropdownMenuItem(
                            text = { Text(choice.label) },
                            trailingIcon = if (choice == sort) ({ Text("✓", color = palette.inboxAction) }) else null,
                            onClick = { open = false; actions.sort(choice) })
                    }
                }
            }
        }
        if (groups.isEmpty()) item(key = "none") {
            Text("No channels or rooms match “$search”", Modifier.padding(horizontal = 8.dp, vertical = 20.dp), color = palette.muted, fontSize = 16.sp)
        }
        for (group in groups) {
            val server = group.server
            val open = server.id !in collapsed || search.isNotEmpty()
            item(key = "server:" + server.id) {
                ServerHeader(palette, group, open, { collapsed = Communities.toggle(prefs, server.id) }) { action -> actions.server(action, server.id) }
                // "Disconnected from <network> · Reconnect" while this network's connection is down (ChatExtras).
                ConnectionBar(server.id, palette)
            }
            if (open) {
                items(group.channels, key = { "row:" + it.id }) { thread ->
                    Box(Modifier.padding(start = 10.dp)) {
                        ConversationRow(thread, palette, showTags, { actions.open(thread.id) }, { actions.details(thread.id) },
                            actions.menu?.let { menu -> { menu(thread.id) } })
                    }
                }
                if (group.channels.isEmpty()) item(key = "empty:" + server.id) {
                    Text("No ${Communities.noun(server.service)}s joined yet. Browse ${server.name} to find some.",
                        Modifier.padding(start = 56.dp, end = 8.dp, top = 2.dp, bottom = 12.dp), color = palette.muted, fontSize = 14.sp)
                }
            }
        }
        item(key = "add") {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = { actions.server("add", "") }) { Text("+ Add server", color = palette.inboxAction, fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = { actions.server("guide", "") }) { Text("How communities work", color = palette.muted, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun ServerHeader(palette: Palette, group: Communities.Group, open: Boolean, toggle: () -> Unit, action: (String) -> Unit) {
    val server = group.server
    val format = MessageProtocol.parse(server.service)
    val tint = accessibleBubbleFill(format.tint(palette.appearance))
    val noun = Communities.noun(server.service)
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = if (open) "Collapse" else "Expand", onClick = toggle)
            .semantics(mergeDescendants = true) {
                contentDescription = server.name + ", " + format.label + (if (group.unread > 0) ", ${group.unread} unread" else "")
                stateDescription = if (open) "Expanded" else "Collapsed"
            }
            .padding(start = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            DrawGlyph(Glyph.Forward, palette.muted, Modifier.size(12.dp).rotate(if (open) 90f else 0f))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(tint), contentAlignment = Alignment.Center) {
                Text(Communities.initials(server.name), color = Color(bubbleTextColor(tint).toArgb()), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name, Modifier.weight(1f, fill = false), color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    ProtocolTags(ThreadRow(server.id, server.name, "", "", "", false, false, protocol = format.label), palette.appearance)
                    // A muted server: every channel, room, and DM on it. Tapping says until when, with Unmute.
                    if (server.muted.isNotEmpty()) MutedMark(server.muted, palette.muted, Modifier.padding(start = 4.dp), 14) { action("mute") }
                }
                Text("${group.channels.size} $noun${if (group.channels.size == 1) "" else "s"} · ${server.account}", color = palette.muted,
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (group.unread > 0) {
                Text(TabBadges.label(group.unread), Modifier.padding(start = 6.dp).clip(CircleShape).background(palette.inboxAction)
                    .widthIn(min = 22.dp).padding(horizontal = 7.dp, vertical = 1.dp), color = Color(MessageStyle.textOn(palette.inboxAction.toArgb())),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                ActionIcon("Actions for ${server.name}", Glyph.More, palette.inboxAction) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Browse ${server.name}") }, onClick = { menu = false; action("browse") })
                    DropdownMenuItem(text = { Text("Join a $noun") }, onClick = { menu = false; action("join") })
                    DropdownMenuItem(text = { Text(if (server.muted.isEmpty()) "Mute notifications" else "Muted · change or unmute") },
                        onClick = { menu = false; action("mute") })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Server details") }, onClick = { menu = false; action("details") })
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 64.dp, end = 6.dp), color = palette.edge)
    }
}
