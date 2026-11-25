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

    @JvmOverloads fun sms(quote: String, ownMessage: Boolean, media: ReactionMedia? = null): String =
        "$verb ${reactionTargetWords(quote, media)}" + if (ownMessage) REACTION_OWN else ""
}

/** The kind of message a reaction names when there are no words to quote: "Liked an image". */
internal enum class ReactionMedia { PHOTO, VIDEO, AUDIO, ANY }

/**
 * The wording of reactions sent as a text in one language. English is the only one so far: add a language by adding
 * its own entry to [REACTION_LANGUAGES] and to REACTION_WORDS in server/reactions.js, with wording taken from real
 * messages. The first phrase of a kind in [media] is the one sent.
 */
internal class ReactionWords(
    val verbs: List<Pair<String, String>>,
    val reacted: Pair<String, String>,
    val removed: Pair<String, String>,
    val nouns: Map<String, String>,
    val media: List<Pair<String, ReactionMedia>>,
)

internal val REACTION_LANGUAGES = listOf(ReactionWords(
    verbs = SmsReaction.entries.map { it.verb to it.symbol },
    reacted = "Reacted " to " to ",
    removed = "Removed " to " from ",
    nouns = SmsReaction.entries.associate { it.noun to it.symbol },
    media = listOf("an image" to ReactionMedia.PHOTO, "a photo" to ReactionMedia.PHOTO, "a picture" to ReactionMedia.PHOTO,
        "a video" to ReactionMedia.VIDEO, "a movie" to ReactionMedia.VIDEO, "an audio message" to ReactionMedia.AUDIO,
        "a voice message" to ReactionMedia.AUDIO, "an attachment" to ReactionMedia.ANY, "a file" to ReactionMedia.ANY),
))

private const val REACTION_OWN = " (my message)"

/** What a message without words says in place of them (the attachment labels and the MMS placeholders). */
private val REACTION_WORDLESS = setOf("Photo", "Video", "Audio", "Voice message", "File", "Multimedia message")
private val stickerName = Regex("""sticker\.\w+""")

/** The words a reaction names its message by: the text in quotes, or "an image" for a picture without words. */
internal fun reactionTargetWords(quote: String, media: ReactionMedia?): String =
    media?.let { kind -> REACTION_LANGUAGES[0].media.first { it.second == kind }.first } ?: "“${messageExcerpt(quote, 700)}”"

/**
 * One emoji, by its characters alone, so the phone and the browser (reactionEmoji in reactions.js) always agree
 * about which texts are reactions: pictographs and their joiners.
 */
internal fun isReactionSymbol(value: String): Boolean {
    if (value.isEmpty() || value.length > 64) return false
    var picture = false
    for (code in value.codePoints().toArray()) {
        if (code in 0x1F000..0x1FAFF || code in 0x2190..0x21FF || code in 0x2300..0x23FF || code in 0x2460..0x24FF ||
            code in 0x25A0..0x27BF || code in 0x2900..0x297F || code in 0x2B00..0x2BFF ||
            code in intArrayOf(0xA9, 0xAE, 0x203C, 0x2049, 0x2122, 0x2139, 0x3030, 0x303D, 0x3297, 0x3299, 0x20E3)) picture = true
        else if (!(code == 0x200D || code == 0xFE0F || code == 0xFE0E || code in 0xE0020..0xE007F ||
                code in 0x30..0x39 || code == 0x23 || code == 0x2A)) return false
    }
    return picture
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

@JvmOverloads
internal fun emojiReactionSms(emoji: String, quote: String, ownMessage: Boolean, media: ReactionMedia? = null): String {
    return SmsReaction.entries.firstOrNull { it.symbol == emoji }?.sms(quote, ownMessage, media)
        ?: ("Reacted $emoji to ${reactionTargetWords(quote, media)}" + if (ownMessage) REACTION_OWN else "")
}

/** Readable removal, worded the way iPhones and Google Messages send it, so those apps can fold it too. */
@JvmOverloads
internal fun reactionRemovedSms(emoji: String, quote: String, ownMessage: Boolean, media: ReactionMedia? = null): String {
    val what = SmsReaction.entries.firstOrNull { it.symbol == emoji }?.noun ?: emoji
    return "Removed $what from ${reactionTargetWords(quote, media)}" + if (ownMessage) REACTION_OWN else ""
}

/** Readable change from one reaction to another, so people on other apps know the old one is gone. */
internal fun reactionChangedSms(previous: String, emoji: String, quote: String, ownMessage: Boolean): String =
    "Changed reaction from $previous to $emoji on “${messageExcerpt(quote, 700)}”" + if (ownMessage) " (my message)" else ""

internal enum class ReactionChange { ADD, REMOVE, CHANGE }

/**
 * A reaction written as text: what changed, the emoji, and the message it refers to. That is the [quote]d words or,
 * without a quote, the [media] it names ("Liked an image").
 */
internal data class ReactionText(val change: ReactionChange, val symbol: String, val quote: String?, val ownMessage: Boolean,
                                 val media: ReactionMedia? = null)

private val changedPattern = Regex("""(?s)^Changed reaction from (\S{1,64}) to (\S{1,64}) on (.+)$""")

/** The words between curly or straight quotes, or null. */
private fun unquote(text: String): String? =
    if (text.length >= 3 && text.first() in "“\"" && text.last() in "”\"") text.substring(1, text.length - 1) else null

private fun reactionTrim(text: String) = text.trim(' ', '\t', '\r', '\n')

/**
 * Reads a reaction sent as a text, the way iPhones and other Android apps word it: "Liked “…”" and the five other
 * verbs, "Reacted 😂 to “…”", "Removed a like from “…”", "Removed 😂 from “…”", and the same about "an image",
 * "a video", "an audio message", or "an attachment". Null for any other text. server/reactions.js reads them the
 * same way, and both run app/src/test/resources/reaction-vectors.json.
 */
internal fun parseReactionText(text: String): ReactionText? {
    val trimmed = reactionTrim(text)
    val ownMessage = trimmed.endsWith(REACTION_OWN)
    val wire = trimmed.removeSuffix(REACTION_OWN)
    for (words in REACTION_LANGUAGES) {
        fun target(rest: String, change: ReactionChange, symbol: String): ReactionText? {
            unquote(rest)?.let { return ReactionText(change, symbol, it, ownMessage) }
            return words.media.firstOrNull { it.first == rest }?.let { ReactionText(change, symbol, null, ownMessage, it.second) }
        }
        for ((verb, symbol) in words.verbs) {
            if (wire.startsWith("$verb ")) target(wire.substring(verb.length + 1), ReactionChange.ADD, symbol)?.let { return it }
        }
        for ((frame, change) in listOf(words.reacted to ReactionChange.ADD, words.removed to ReactionChange.REMOVE)) {
            if (!wire.startsWith(frame.first)) continue
            val rest = wire.substring(frame.first.length)
            val at = rest.indexOf(frame.second)
            if (at < 1) continue
            val what = rest.substring(0, at)
            val symbol = (if (change == ReactionChange.REMOVE) words.nouns[what] else null) ?: what.takeIf(::isReactionSymbol) ?: continue
            target(rest.substring(at + frame.second.length), change, symbol)?.let { return it }
        }
    }
    // FluidMessages' own wording for a changed reaction, still sent to chats that aren't texts.
    changedPattern.matchEntire(wire)?.let { match ->
        val emoji = match.groupValues[2]
        if (!isReactionSymbol(emoji)) return null
        return ReactionText(ReactionChange.CHANGE, emoji, unquote(match.groupValues[3]) ?: return null, ownMessage)
    }
    return null
}

/** Curly and straight quotes and apostrophes, line endings, and no-break spaces compare as the same. */
private fun reactionPlain(text: String) = reactionTrim(text.replace("\r\n", "\n").replace('‘', '\'').replace('’', '\'')
    .replace('“', '"').replace('”', '"').replace('\u00a0', ' '))

/** Whether [quote] is this message: all of it, its first 700 characters, or, ending in …, 20 or more of its first characters. */
internal fun reactionQuotes(text: String, quote: String): Boolean {
    val whole = reactionPlain(text)
    val quoted = reactionPlain(quote)
    if (whole == quoted || reactionPlain(messageExcerpt(text, 700)) == quoted) return true
    val cut = if (quoted.endsWith("…")) 1 else if (quoted.endsWith("...")) 3 else return false
    val prefix = quoted.dropLast(cut).trimEnd(' ', '\t', '\r', '\n')
    return prefix.length >= 20 && whole.startsWith(prefix)
}

/** What kind of picture or file a message carries, as reactions name it; [ReactionMedia.ANY] for other files. */
internal fun ChatLine.reactionMedia(): ReactionMedia? {
    val file = attachment ?: return if (photo != null) ReactionMedia.PHOTO else null
    val mime = file.mime.lowercase()
    return when {
        file.kind == AttachmentKind.PHOTO || mime.startsWith("image/") -> ReactionMedia.PHOTO
        file.kind == AttachmentKind.VIDEO || mime.startsWith("video/") -> ReactionMedia.VIDEO
        file.kind == AttachmentKind.AUDIO || file.kind == AttachmentKind.VOICE || mime.startsWith("audio/") -> ReactionMedia.AUDIO
        else -> ReactionMedia.ANY
    }
}

/** The kind of a picture or file sent without words, which a reaction names ("an image") instead of quoting. */
internal fun ChatLine.wordlessMedia(): ReactionMedia? {
    val kind = reactionMedia() ?: return null
    val words = reactionTrim(text)
    return kind.takeIf { words.isEmpty() || words == attachment?.name.orEmpty() || words in REACTION_WORDLESS || stickerName.matches(words) }
}

/**
 * The message a reaction text is about: the most recent one in [visible] (oldest first) that it quotes or names.
 * People react to what others sent, so those are tried first; then anyone's, for someone reacting to their own.
 * "(my message)" from FluidMessages means the sender's own. [reactor] is the sender key of the reaction.
 */
internal fun reactionTarget(parsed: ReactionText, reactor: String?, visible: List<ChatLine>): ChatLine? {
    fun ChatLine.fits() = !event && if (parsed.quote != null) reactionQuotes(text, parsed.quote)
        else reactionMedia().let { it != null && (parsed.media == ReactionMedia.ANY || parsed.media == it) }
    return visible.lastOrNull { (it.senderKey() == reactor) == parsed.ownMessage && it.fits() }
        ?: if (parsed.ownMessage) null else visible.lastOrNull { it.fits() }
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
internal data class DisplayMessage(val line: ChatLine, val bySender: Map<String?, String>, val earlier: List<String> = emptyList()) {
    val reactions: List<String> get() = bySender.values.toList()
}

/** Still sending or failed: keep it visible as its own bubble so its status shows. */
private fun ChatLine.unsettled() = status.startsWith("Sending") || status.startsWith("Couldn’t")

internal fun foldSmsReactions(lines: List<ChatLine>): List<DisplayMessage> {
    val visible = mutableListOf<ChatLine>()
    val reactions = mutableMapOf<String, LinkedHashMap<String?, String>>()
    val earlier = mutableMapOf<String, MutableList<String>>()
    for (line in lines) {
        // "✏️ Edited “old” to: new" replaces the sender's own earlier message.
        val edit = if (line.unsettled() || line.event || line.attachment != null) null else parseEdit(line.text)
        if (edit != null) {
            val index = visible.indexOfLast { it.senderKey() == line.senderKey() && it.attachment == null && editQuote(it.text) == edit.first }
            if (index >= 0) {
                val original = visible[index]
                earlier.getOrPut(original.id) { mutableListOf() }.add(original.text)
                visible[index] = original.copy(text = edit.second)
                continue
            }
        }
        // A picture or file is never folded away, whatever its caption says.
        val parsed = if (line.unsettled() || line.event || line.media != null) null else parseReactionText(line.text)
        if (parsed == null) {
            visible.add(line)
            continue
        }
        val match = line.reactionTarget?.let { id -> visible.lastOrNull { it.id == id } }
            ?: reactionTarget(parsed, line.senderKey(), visible)
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
    return visible.map { DisplayMessage(it, reactions[it.id].orEmpty(), earlier[it.id].orEmpty()) }
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
