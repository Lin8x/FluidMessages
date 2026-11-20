package org.fluidmessages

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator

internal enum class SmsReaction(val symbol: String, val verb: String, val title: String) {
    LIKE("👍", "Liked", "Thumbs up"),
    LOVE("❤️", "Loved", "Heart"),
    DISLIKE("👎", "Disliked", "Thumbs down"),
    LAUGH("😆", "Laughed at", "Laugh"),
    EMPHASIZE("‼️", "Emphasized", "Exclamation"),
    QUESTION("❓", "Questioned", "Question");

    fun sms(quote: String, ownMessage: Boolean): String =
        "$verb “${messageExcerpt(quote, 700)}”" + if (ownMessage) " (my message)" else ""
}

internal fun isReactionEmoji(value: String): Boolean {
    if (value.isEmpty() || value.length > 64) return false
    val graphemes = BreakIterator.getCharacterInstance()
    graphemes.setText(value)
    if (graphemes.next() != value.length) return false
    val points = value.codePoints().toArray()
    fun pictograph(code: Int): Boolean = code > 0x7f && (
        UCharacter.hasBinaryProperty(code, UProperty.EMOJI_PRESENTATION) ||
            UCharacter.hasBinaryProperty(code, UProperty.EXTENDED_PICTOGRAPHIC))
    return (points.any(::pictograph) || points.last() == 0x20e3) && points.all { code ->
        pictograph(code) || UCharacter.hasBinaryProperty(code, UProperty.EMOJI_COMPONENT) || code == 0xfe0f
    }
}

internal fun emojiReactionSms(emoji: String, quote: String, ownMessage: Boolean): String {
    return SmsReaction.entries.firstOrNull { it.symbol == emoji }?.sms(quote, ownMessage)
        ?: ("Reacted $emoji to “${messageExcerpt(quote, 700)}”" + if (ownMessage) " (my message)" else "")
}

internal fun messageExcerpt(text: String, limit: Int): String {
    if (text.codePointCount(0, text.length) <= limit) return text
    return text.substring(0, text.offsetByCodePoints(0, limit)) + "…"
}

internal data class DisplayMessage(val line: ChatLine, val reactions: List<String>)

internal fun foldSmsReactions(lines: List<ChatLine>): List<DisplayMessage> {
    val visible = mutableListOf<ChatLine>()
    val reactions = mutableMapOf<String, MutableMap<String?, String>>()
    for (line in lines) {
        // Pending or failed sends stay visible so the user can see their status.
        val ownMessage = line.text.endsWith(" (my message)")
        val wire = line.text.removeSuffix(" (my message)")
        val standard = SmsReaction.entries.firstOrNull { wire.startsWith("${it.verb} ") }
        val custom = if (wire.startsWith("Reacted ")) wire.removePrefix("Reacted ").substringBefore(" to ")
            .takeIf(::isReactionEmoji) else null
        val symbol = standard?.symbol ?: custom
        if (line.status.isNotEmpty() || symbol == null) {
            visible.add(line)
            continue
        }
        val quote = wire.removePrefix(if (standard != null) "${standard.verb} " else "Reacted $custom to ")
        val body = when {
            quote.startsWith("“") && quote.endsWith("”") -> quote.drop(1).dropLast(1)
            quote.startsWith("\"") && quote.endsWith("\"") -> quote.drop(1).dropLast(1)
            else -> null
        }
        val match = if (line.reactionTarget != null) visible.lastOrNull { it.id == line.reactionTarget }
        else body?.let { quoted ->
            fun ChatLine.hasQuote() = text == quoted || messageExcerpt(text, 700) == quoted
            visible.lastOrNull { (it.senderKey() == line.senderKey()) == ownMessage && it.hasQuote() }
                ?: if (ownMessage) null else visible.lastOrNull { it.hasQuote() }
        }
        if (match == null) visible.add(line)
        else reactions.getOrPut(match.id) { mutableMapOf() }[line.senderKey()] = symbol
    }
    return visible.map { DisplayMessage(it, reactions[it.id]?.values?.toList().orEmpty()) }
}

internal fun ChatLine.senderKey(): String? = if (outgoing) null else author ?: ""

internal data class QuotedReply(val quote: String, val body: String)

private val replyPattern = Regex("""(?s)^Reply to (?:“(.+?)”|"(.+?)")[ \t]*\r?\n(?:\r?\n)?(.+)$""")

internal fun parseQuotedReply(text: String): QuotedReply? {
    val match = replyPattern.matchEntire(text) ?: return null
    val quote = match.groupValues[1].ifEmpty { match.groupValues[2] }
    val body = match.groupValues[3]
    if (quote.isBlank() || body.isBlank()) return null
    return QuotedReply(quote, body)
}

internal data class ForwardedMessage(val text: String, val author: String)

private val forwardPattern = Regex("""(?s)^Forwarded (?:“(.+?)”|"(.+?)") by (?:“([^”\r\n]+)”|"([^"\r\n]+)")[ \t]*$""")

internal fun parseForwardedMessage(text: String): ForwardedMessage? {
    val match = forwardPattern.matchEntire(text) ?: return null
    val content = match.groupValues[1].ifEmpty { match.groupValues[2] }
    val author = match.groupValues[3].ifEmpty { match.groupValues[4] }
    return if (content.isBlank() || author.isBlank()) null else ForwardedMessage(content, author)
}

internal fun forwardAsSms(text: String, author: String): String {
    val safeAuthor = messageExcerpt(author.replace(Regex("[\\r\\n“”]"), " ").trim()
        .ifEmpty { "Unknown sender" }, 90)
    val suffix = "” by “$safeAuthor”"
    val prefix = "Forwarded “"
    val limit = 1600 - prefix.length - suffix.length
    val quoted = if (text.length <= limit) text else {
        val end = if (text[limit - 2].isHighSurrogate()) limit - 2 else limit - 1
        text.take(end) + "…"
    }
    return prefix + quoted + suffix
}
