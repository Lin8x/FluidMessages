package org.fluidmessages

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator

internal enum class SmsReaction(val symbol: String, val verb: String, val title: String, val noun: String) {
    LIKE("👍", "Liked", "Thumbs up", "a like"),
    LOVE("❤️", "Loved", "Heart", "a heart"),
    DISLIKE("👎", "Disliked", "Thumbs down", "a dislike"),
    LAUGH("😆", "Laughed at", "Laugh", "a laugh"),
    EMPHASIZE("‼️", "Emphasized", "Exclamation", "an exclamation"),
    QUESTION("❓", "Questioned", "Question", "a question mark");

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

/** Readable removal, worded the way iPhones and Google Messages send it, so those apps can fold it too. */
internal fun reactionRemovedSms(emoji: String, quote: String, ownMessage: Boolean): String {
    val what = SmsReaction.entries.firstOrNull { it.symbol == emoji }?.noun ?: emoji
    return "Removed $what from “${messageExcerpt(quote, 700)}”" + if (ownMessage) " (my message)" else ""
}

/** Readable change from one reaction to another, so people on other apps know the old one is gone. */
internal fun reactionChangedSms(previous: String, emoji: String, quote: String, ownMessage: Boolean): String =
    "Changed reaction from $previous to $emoji on “${messageExcerpt(quote, 700)}”" + if (ownMessage) " (my message)" else ""

internal enum class ReactionChange { ADD, REMOVE, CHANGE }

/** A reaction written as text: what changed, the emoji, and the quoted message it refers to. */
internal data class ReactionText(val change: ReactionChange, val symbol: String, val quote: String?, val ownMessage: Boolean)

private val removedPattern = Regex("""(?s)^Removed (.{1,64}?) from (.+)$""")
private val changedPattern = Regex("""(?s)^Changed reaction from (\S{1,64}) to (\S{1,64}) on (.+)$""")

private fun unquote(text: String): String? = when {
    text.length >= 2 && text.startsWith("“") && text.endsWith("”") -> text.drop(1).dropLast(1)
    text.length >= 2 && text.startsWith("\"") && text.endsWith("\"") -> text.drop(1).dropLast(1)
    else -> null
}

internal fun parseReactionText(text: String): ReactionText? {
    val ownMessage = text.endsWith(" (my message)")
    val wire = text.removeSuffix(" (my message)")
    SmsReaction.entries.firstOrNull { wire.startsWith("${it.verb} “") || wire.startsWith("${it.verb} \"") }?.let {
        return ReactionText(ReactionChange.ADD, it.symbol, unquote(wire.removePrefix("${it.verb} ")) ?: return null, ownMessage)
    }
    if (wire.startsWith("Reacted ")) {
        val emoji = wire.removePrefix("Reacted ").substringBefore(" to ")
        if (!isReactionEmoji(emoji)) return null
        return ReactionText(ReactionChange.ADD, emoji, unquote(wire.removePrefix("Reacted $emoji to ")) ?: return null, ownMessage)
    }
    removedPattern.matchEntire(wire)?.let { match ->
        val what = match.groupValues[1]
        val symbol = SmsReaction.entries.firstOrNull { it.noun == what }?.symbol
            ?: what.takeIf(::isReactionEmoji) ?: return null
        return ReactionText(ReactionChange.REMOVE, symbol, unquote(match.groupValues[2]) ?: return null, ownMessage)
    }
    changedPattern.matchEntire(wire)?.let { match ->
        val emoji = match.groupValues[2]
        if (!isReactionEmoji(emoji)) return null
        return ReactionText(ReactionChange.CHANGE, emoji, unquote(match.groupValues[3]) ?: return null, ownMessage)
    }
    return null
}

private val attributionStart = Regex("""^\s*(On|Am|Le|El|Il|Em|Op)\b.*""")
private val attributionEnd = Regex("""(?s)^\s*(On|Am|Le|El|Il|Em|Op)\b.{0,400}?\b(wrote|schrieb|a écrit|escribió|ha scritto|escreveu|schreef)\b.{0,200}:\s*$""")
private val originalMessage = Regex("""^\s*-{2,}\s*Original Message\s*-{2,}\s*$""", RegexOption.IGNORE_CASE)
private val headerFrom = Regex("""^\s*\*?From:\*?\s.+""")
private val headerNext = Regex("""^\s*\*?(Sent|Date|To|Subject):\*?\s.*""")

/**
 * Removes the earlier conversation a mail app quotes under a reply: the "On … wrote:" line, Outlook's
 * "Original Message" and From/Sent header blocks, and the trailing "> " lines. The reply card and the bubble's
 * own date and time stand in for them. Inline replies, where answers sit between quotes, keep their text.
 */
fun stripQuotedEmail(text: String): String {
    val lines = text.replace("\r\n", "\n").split("\n")
    var cut = lines.size
    var skip = 0
    for (i in lines.indices) {
        val line = lines[i]
        val size = if (!attributionStart.matches(line)) 0 else (0..3).firstOrNull { extra ->
            i + extra < lines.size && attributionEnd.matches(lines.subList(i, i + extra + 1).joinToString(" "))
        }?.plus(1) ?: 0
        val attribution = size > 0
        val outlook = originalMessage.matches(line) || headerFrom.matches(line) &&
            (1..4).any { i + it < lines.size && headerNext.matches(lines[i + it]) } &&
            (i == 0 || lines[i - 1].isBlank() || lines[i - 1].trim().all { it == '_' || it == '-' })
        val trailingQuote = line.trimStart().startsWith(">") &&
            lines.subList(i, lines.size).all { it.isBlank() || it.trimStart().startsWith(">") }
        if (attribution || outlook || trailingQuote) { cut = i; skip = size; break }
    }
    val kept = lines.subList(0, cut).dropLastWhile { it.isBlank() || it.trim().all { c -> c == '_' || c == '-' } }
        .joinToString("\n").trim()
    if (kept.isNotEmpty()) return kept
    // Everything sat under the quote (an inline reply): drop the "wrote:" line and the quoted lines only.
    val rest = lines.subList(0, cut) + lines.subList(minOf(lines.size, cut + skip), lines.size)
    val inline = rest.filterNot { it.trimStart().startsWith(">") }.joinToString("\n").trim()
    return inline.ifEmpty { text.trim() }
}

internal fun messageExcerpt(text: String, limit: Int): String {
    if (text.codePointCount(0, text.length) <= limit) return text
    return text.substring(0, text.offsetByCodePoints(0, limit)) + "…"
}

/** A message with the reactions folded onto it: one per sender, keyed by [senderKey]. */
internal data class DisplayMessage(val line: ChatLine, val bySender: Map<String?, String>) {
    val reactions: List<String> get() = bySender.values.toList()
}

/** Still sending or failed: keep it visible as its own bubble so its status shows. */
private fun ChatLine.unsettled() = status.startsWith("Sending") || status.startsWith("Couldn’t")

internal fun foldSmsReactions(lines: List<ChatLine>): List<DisplayMessage> {
    val visible = mutableListOf<ChatLine>()
    val reactions = mutableMapOf<String, LinkedHashMap<String?, String>>()
    for (line in lines) {
        val parsed = if (line.unsettled() || line.event) null else parseReactionText(line.text)
        if (parsed == null) {
            visible.add(line)
            continue
        }
        val match = if (line.reactionTarget != null) visible.lastOrNull { it.id == line.reactionTarget }
        else parsed.quote?.let { quoted ->
            // Long messages are quoted as an excerpt; some phones shorten them further and end with "…".
            val prefix = quoted.removeSuffix("…").takeIf { quoted.endsWith("…") && it.length >= 20 }
            fun ChatLine.hasQuote() = text == quoted || messageExcerpt(text, 700) == quoted ||
                (prefix != null && text.startsWith(prefix))
            visible.lastOrNull { (it.senderKey() == line.senderKey()) == parsed.ownMessage && it.hasQuote() }
                ?: if (parsed.ownMessage) null else visible.lastOrNull { it.hasQuote() }
        }
        if (match == null) {
            visible.add(line)
            continue
        }
        val folded = reactions.getOrPut(match.id) { LinkedHashMap() }
        when (parsed.change) {
            ReactionChange.REMOVE -> folded.remove(line.senderKey())
            // Re-adding moves the sender's reaction to the end, so the newest shows last.
            else -> { folded.remove(line.senderKey()); folded[line.senderKey()] = parsed.symbol }
        }
    }
    return visible.map { DisplayMessage(it, reactions[it.id].orEmpty()) }
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
