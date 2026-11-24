package org.fluidmessages

import android.provider.CallLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One message as the inbox reads it, newest first, for counting the Messages tab's badge. */
internal data class BadgeMessage @JvmOverloads constructor(
    /** The inbox row it belongs to (address, plus protocol family unless chats are combined). */
    val thread: String,
    /** The conversation's address, as pinned, archived, blocked, and muted lists store it. */
    val address: String,
    val unread: Boolean,
    /** In the private folder. */
    val hidden: Boolean = false,
    /** A channel or room on a server, listed in Messages → Communities. */
    val community: Boolean = false,
)

/** One call as Recents reads it, for counting the Phone tab's badge. */
internal data class BadgeCall(val type: Int, val date: Long, val address: String = "")

/**
 * The counts on the Phone and Messages tabs: missed calls since the Phone tab was last opened, and unread
 * conversations in the inbox. Settings → Sounds and notifications → App badges → "Show counts on tabs" hides both.
 */
internal object TabBadges {
    /** On by default. Mirrored to FluidMessages Web as appearance.tabCounts. */
    const val PREF = "tabCounts"
    /** When the Phone tab was last open; missed calls after it count. */
    const val PHONE_SEEN = "phoneTabSeen"
    const val CAP = 99
    /** The inbox filter menu's Unread folder: the inbox chats this badge counts. */
    const val UNREAD_FOLDER = "Unread"

    /**
     * Whether an inbox conversation counts as unread, from its newest message: the same rule as the Messages badge and
     * the Unread folder. Muted chats never do; archived, blocked and private chats aren't in the inbox. (The badge also
     * leaves out decoy chats; the decoy inbox's own Unread folder still lists them, so it looks like a real inbox.)
     */
    @JvmStatic
    fun unreadThread(newestUnread: Boolean, address: String, muted: Set<String>): Boolean =
        newestUnread && address !in muted

    /**
     * Unread conversations, with the inbox's own rule: a conversation is unread when its newest message is. Muted,
     * archived, blocked, private-folder, and decoy chats don't count. Rows must be newest first.
     */
    @JvmStatic
    fun unreadConversations(newestFirst: Iterable<BadgeMessage>, muted: Set<String>, archived: Set<String>, blocked: Set<String>): Int =
        unreadSplit(newestFirst, muted, archived, blocked, true)[0]

    /**
     * Unread conversations as [Messages badge, Communities count]. Community chats count on the Messages badge while
     * "Show community chats in Chats" is on; while it's off they count on Communities instead.
     */
    @JvmStatic
    fun unreadSplit(newestFirst: Iterable<BadgeMessage>, muted: Set<String>, archived: Set<String>, blocked: Set<String>,
                    showCommunityChats: Boolean): IntArray {
        val seen = HashSet<String>()
        val counts = IntArray(2)
        for (message in newestFirst) {
            // The same rows the main inbox leaves out before it picks each conversation's newest message.
            if (message.hidden || message.address.startsWith(Privacy.DECOY)) continue
            if (message.address in archived || message.address in blocked) continue
            if (!seen.add(message.thread)) continue
            if (!unreadThread(message.unread, message.address, muted)) continue
            if (Communities.showsInChats(message.community, showCommunityChats)) counts[0]++ else counts[1]++
        }
        return counts
    }

    /** Missed calls after since. allowed, when set (decoy mode), keeps only calls Recents would show. */
    @JvmStatic @JvmOverloads
    fun missedCalls(calls: Iterable<BadgeCall>, since: Long, allowed: ((String) -> Boolean)? = null): Int =
        calls.count { it.type == CallLog.Calls.MISSED_TYPE && it.date > since && (allowed == null || allowed(it.address)) }

    /** The badge text: nothing at zero, and "99+" past 99. */
    @JvmStatic
    fun label(count: Int): String = when {
        count <= 0 -> ""
        count > CAP -> "$CAP+"
        else -> count.toString()
    }

    /** What TalkBack reads for a tab, e.g. "Messages, 3 unread" or "Phone, 1 missed call". */
    @JvmStatic
    fun description(phone: Boolean, count: Int): String {
        val tab = if (phone) "Phone" else "Messages"
        if (count <= 0) return tab
        return if (phone) "$tab, $count missed call${if (count == 1) "" else "s"}" else "$tab, $count unread"
    }
}

/** The live counts both tab bars show. Written on the main thread. */
class TabCounts {
    var phone by mutableIntStateOf(0)
    var messages by mutableIntStateOf(0)
    /** Unread channels and rooms shown only under Communities. */
    var communities by mutableIntStateOf(0)
    var shown by mutableStateOf(true)
}
