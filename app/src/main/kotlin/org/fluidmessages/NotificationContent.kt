package org.fluidmessages

/**
 * What a new-text notification says, and when it says nothing. Pure, so the rules can be tested; SmsDelivery gathers
 * the facts and posts the result.
 *
 * A notification names the sender and shows the message only when nothing asks for privacy: the setting on
 * Settings → Sounds and notifications is on, app lock is off, the chat isn't in the private folder, the chat and its
 * service have no code, and decoy mode isn't hiding the chat. Otherwise it stays the plain "New text message",
 * without Reply or Mark as read, so nothing about the chat can be read or answered from outside the app.
 */
internal object NotificationContent {
    /** Settings → Sounds and notifications → Show names and message text in notifications. On unless turned off. */
    const val PREF = "notifyContent"
    const val GENERIC_TITLE = "New text message"
    /** Shown in place of a text locked with a secret code this phone doesn't have. */
    const val LOCKED_TEXT = "Locked message. Open the chat to read it."
    private const val TEXT_LIMIT = 500
    private const val QUOTE_LIMIT = 120

    /** Why a notification might have to stay plain. */
    data class Guard(
        /** The setting [PREF]. */
        val showContent: Boolean = true,
        /** Settings → Privacy → app lock. */
        val appLock: Boolean = false,
        /** The chat is in the private folder. */
        val hidden: Boolean = false,
        /** The chat, or texts as a whole, open only with a code. */
        val chatCode: Boolean = false,
        /** Decoy mode is on and this chat isn't one it keeps showing. */
        val decoyHides: Boolean = false,
    ) {
        val reveals: Boolean get() = showContent && !appLock && !hidden && !chatCode && !decoyHides
    }

    data class Shown(val title: String, val text: String, val reveals: Boolean)

    /** The saved contact's name, or the number as written. */
    @JvmStatic
    fun title(name: String?, address: String): String = name?.trim().takeUnless { it.isNullOrEmpty() } ?: address

    /**
     * The message as the notification shows it. A reaction an iPhone or another app sent as a text
     * ("Liked “See you at 7”") reads the way the chat shows it: "Sam liked “See you at 7”".
     */
    @JvmStatic
    fun text(body: String, name: String?): String {
        val reaction = parseReactionText(body) ?: return messageExcerpt(body.trim(), TEXT_LIMIT)
        val words = reactionWords(reaction)
        val who = name?.trim()?.substringBefore(' ').orEmpty()
        return if (who.isEmpty()) words.replaceFirstChar { it.uppercase() } else "$who $words"
    }

    private fun reactionWords(reaction: ReactionText): String {
        val target = reaction.quote?.let { "“${messageExcerpt(it, QUOTE_LIMIT)}”" } ?: reactionTargetWords("", reaction.media ?: ReactionMedia.ANY)
        val known = SmsReaction.entries.firstOrNull { it.symbol == reaction.symbol }
        return when (reaction.change) {
            ReactionChange.ADD -> if (known != null) "${known.verb.lowercase()} $target" else "reacted ${reaction.symbol} to $target"
            ReactionChange.REMOVE -> "removed ${known?.noun ?: reaction.symbol} from $target"
            ReactionChange.CHANGE -> "changed their reaction to ${reaction.symbol} on $target"
        }
    }

    /**
     * Title and text for one new text. [name] is the saved contact's name, if there is one. Nothing of the sender or
     * the message is in the result unless [guard] allows it.
     */
    @JvmStatic
    fun build(guard: Guard, name: String?, address: String, body: String): Shown =
        if (!guard.reveals) Shown(GENERIC_TITLE, "", false)
        else Shown(title(name, address), text(body, name), true)
}
