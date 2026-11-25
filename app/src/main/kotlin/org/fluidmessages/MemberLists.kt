package org.fluidmessages

/**
 * Long member lists fold up: a group text, channel, room, or community with more than [COLLAPSE_OVER] people shows the
 * first [PREVIEW] and a "Show all N members" button. Ten or fewer always show in full. FluidMessages Web uses the same
 * numbers (server/chats.js visibleMembers).
 */
object MemberLists {
    const val COLLAPSE_OVER = 10
    const val PREVIEW = 5

    /** Whether a list this long folds up. */
    @JvmStatic fun collapsible(count: Int): Boolean = count > COLLAPSE_OVER

    /** The members to show: all of them when [expanded] or when the list is short, otherwise the first [PREVIEW]. */
    @JvmStatic fun <T> shown(members: List<T>, expanded: Boolean): List<T> =
        if (!expanded && collapsible(members.size)) members.take(PREVIEW) else members

    /** The button under a folded list, or "" when the list doesn't fold. */
    @JvmStatic fun toggleLabel(count: Int, expanded: Boolean): String = when {
        !collapsible(count) -> ""
        expanded -> "Show fewer"
        else -> "Show all $count members"
    }
}
