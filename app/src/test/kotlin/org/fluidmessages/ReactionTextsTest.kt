package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reactions that arrive as a text from iPhones and other Android apps ("Liked “See you at 7”"), and the texts
 * FluidMessages sends for its own. The cases in reaction-vectors.json are the ones FluidMessages Web runs too
 * (scripts/test-local reactions_test), so a synced chat folds the same way on both.
 */
class ReactionTextsTest {
    private val vectors = Json(javaClass.getResource("/reaction-vectors.json")!!.readText()).read() as Map<*, *>
    private fun list(key: String) = vectors[key] as List<*>

    private fun line(id: String, from: String, body: String, media: String? = null, status: String = "", target: String? = null) =
        ChatLine(id, body, "", from == "me", status, "", from.takeUnless { it == "me" }, target, attachment = when (media) {
            null, "" -> null
            "photo" -> Attachment(AttachmentKind.PHOTO, null, "Photo", "image/jpeg")
            "video" -> Attachment(AttachmentKind.VIDEO, null, "clip.mp4", "video/mp4")
            "voice" -> Attachment(AttachmentKind.VOICE, null, "Voice message", "audio/mp4")
            "audio" -> Attachment(AttachmentKind.AUDIO, null, "song.mp3", "audio/mpeg")
            else -> Attachment(AttachmentKind.FILE, null, "notes.pdf", "application/pdf")
        })

    @Test fun readsEverySharedForm() {
        assertTrue(list("parse").size > 100)
        for (case in list("parse")) {
            case as Map<*, *>
            val text = case["text"] as String
            val got = parseReactionText(text)
            val want = case["expect"] as Map<*, *>?
            if (want == null) { assertNull(text, got); continue }
            assertNotNull(text, got)
            assertEquals(text, want["change"], got!!.change.name.lowercase())
            assertEquals(text, want["emoji"], got.symbol)
            assertEquals(text, want["quote"], got.quote)
            assertEquals(text, want["media"], got.media?.name?.lowercase())
            assertEquals(text, want["own"], got.ownMessage)
        }
    }

    @Test fun agreesWithTheBrowserAboutEmoji() {
        for (case in list("emoji")) {
            case as List<*>
            assertEquals(case[0] as String, case[1], isReactionSymbol(case[0] as String))
        }
    }

    @Test fun foldsTheSharedChats() {
        assertTrue(list("fold").size > 60)
        for (case in list("fold")) {
            case as Map<*, *>
            val lines = (case["messages"] as List<*>).map {
                it as Map<*, *>
                line(it["id"] as String, it["from"] as String, it["body"] as String, it["media"] as String?,
                    it["status"] as String? ?: "", it["target"] as String?)
            }
            val folded = foldSmsReactions(lines)
            val got = folded.map { shown -> listOf(shown.line.id, shown.bySender.map { (who, emoji) -> listOf(who ?: "me", emoji) }) }
            val want = (case["shown"] as List<*>).map { it as Map<*, *>; listOf(it["id"], it["reactions"]) }
            assertEquals(case["name"] as String, want, got)
            // Never dropped: a text that isn't a reaction always keeps its bubble.
            for (line in lines) if (parseReactionText(line.text) == null) assertTrue(line.text, folded.any { it.line.id == line.id })
        }
    }

    @Test fun namesAMessageWithoutWordsByWhatItIs() {
        for (case in list("words")) {
            case as List<*>
            val body = case[0] as String
            val target = line("a", "Sam", body, case[1] as String).let { made ->
                made.attachment?.let { made.copy(attachment = it.copy(name = case[2] as String)) } ?: made
            }
            assertEquals(body, case[3], reactionTargetWords(target.text, target.wordlessMedia()))
        }
    }

    @Test fun sendsTheStandardWording() {
        // What goes out when you react to a text from someone without FluidMessages.
        val quote = "See you at 7"
        assertEquals("Liked “See you at 7”", emojiReactionSms("👍", quote, false))
        assertEquals("Loved “See you at 7”", emojiReactionSms("❤️", quote, false))
        assertEquals("Disliked “See you at 7”", emojiReactionSms("👎", quote, false))
        assertEquals("Laughed at “See you at 7”", emojiReactionSms("😆", quote, false))
        assertEquals("Emphasized “See you at 7”", emojiReactionSms("‼️", quote, false))
        assertEquals("Questioned “See you at 7”", emojiReactionSms("❓", quote, false))
        assertEquals("Reacted 😂 to “See you at 7”", emojiReactionSms("😂", quote, false))
        assertEquals("Removed a like from “See you at 7”", reactionRemovedSms("👍", quote, false))
        assertEquals("Removed a heart from “See you at 7”", reactionRemovedSms("❤️", quote, false))
        assertEquals("Removed a dislike from “See you at 7”", reactionRemovedSms("👎", quote, false))
        assertEquals("Removed a laugh from “See you at 7”", reactionRemovedSms("😆", quote, false))
        assertEquals("Removed an exclamation from “See you at 7”", reactionRemovedSms("‼️", quote, false))
        assertEquals("Removed a question mark from “See you at 7”", reactionRemovedSms("❓", quote, false))
        assertEquals("Removed 😂 from “See you at 7”", reactionRemovedSms("😂", quote, false))
        assertEquals("Loved an image", emojiReactionSms("❤️", "Photo", false, ReactionMedia.PHOTO))
        assertEquals("Reacted 😂 to a video", emojiReactionSms("😂", "Video", false, ReactionMedia.VIDEO))
        assertEquals("Removed a heart from an audio message", reactionRemovedSms("❤️", "Voice message", false, ReactionMedia.AUDIO))
        assertEquals("Liked an attachment", emojiReactionSms("👍", "notes.pdf", false, ReactionMedia.ANY))
        // Everything sent is read back as the same reaction.
        for (reaction in SmsReaction.entries) {
            assertEquals(ReactionText(ReactionChange.ADD, reaction.symbol, quote, false), parseReactionText(reaction.sms(quote, false)))
            assertEquals(ReactionText(ReactionChange.REMOVE, reaction.symbol, quote, true),
                parseReactionText(reactionRemovedSms(reaction.symbol, quote, true)))
            for (media in ReactionMedia.entries) assertEquals(ReactionText(ReactionChange.ADD, reaction.symbol, null, false, media),
                parseReactionText(reaction.sms("", false, media)))
        }
    }

    @Test fun textsGetTheStandardWordingForAChangeToo() {
        val target = line("a", "Sam", "See you at 7")
        val change = MessageRelation(RelationKind.REACTION, target.id, target.text, "Sam", "❤️", false, previous = "👍")
        val message = OutgoingMessage("+12025550123", listOf("+12025550123"), "", relation = change)
        for (protocol in listOf(MessageProtocol.SMS, MessageProtocol.MMS, MessageProtocol.RCS)) {
            assertEquals(protocol.name, "Loved “See you at 7”", message.forTransport(protocol).fallbackBody())
        }
        // Other chats keep FluidMessages' own wording; Matrix and XMPP change it natively.
        assertEquals("Changed reaction from 👍 to ❤️ on “See you at 7”", message.forTransport(MessageProtocol.EMAIL).text)
        assertEquals(change, message.forTransport(MessageProtocol.MATRIX).relation)
        val removal = message.copy(relation = change.copy(emoji = "👍", previous = "", removed = true))
        assertEquals("Removed a like from “See you at 7”", removal.forTransport(MessageProtocol.SMS).text)
        val emoji = message.copy(relation = change.copy(emoji = "😂", previous = ""))
        assertEquals("Reacted 😂 to “See you at 7”", emoji.forTransport(MessageProtocol.SMS).fallbackBody())
        val photo = MessageRelation.reaction(line("p", "Sam", "Photo", "photo"), "👍", false)
        assertEquals("Liked an image", message.copy(relation = photo).forTransport(MessageProtocol.SMS).fallbackBody())
    }

    /** A small JSON reader: the unit tests have no JSON library, and the vectors use plain objects, lists, and strings. */
    private class Json(private val text: String) {
        private var at = 0
        fun read(): Any? {
            skip()
            return when (val char = text[at]) {
                '{' -> buildMap {
                    at++; skip()
                    while (text[at] != '}') {
                        val key = read() as String
                        skip(); at++
                        put(key, read())
                        skip(); if (text[at] == ',') at++
                        skip()
                    }
                    at++
                }
                '[' -> buildList {
                    at++; skip()
                    while (text[at] != ']') {
                        add(read())
                        skip(); if (text[at] == ',') at++
                        skip()
                    }
                    at++
                }
                '"' -> buildString {
                    at++
                    while (text[at] != '"') {
                        if (text[at] == '\\') {
                            at++
                            when (text[at]) {
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                'u' -> { append(text.substring(at + 1, at + 5).toInt(16).toChar()); at += 4 }
                                else -> append(text[at])
                            }
                        } else append(text[at])
                        at++
                    }
                    at++
                }
                else -> {
                    val word = text.substring(at).takeWhile { it.isLetter() }
                    at += word.length
                    when (word) { "true" -> true; "false" -> false; "null" -> null; else -> error("Unexpected $char at $at") }
                }
            }
        }
        private fun skip() { while (at < text.length && text[at].isWhitespace()) at++ }
    }
}
