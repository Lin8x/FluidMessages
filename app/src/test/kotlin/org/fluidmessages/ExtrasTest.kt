package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edits, effects, random picks, rock paper scissors, contact cards, and game links. */
class ExtrasTest {
    private var next = 0
    private fun mine(text: String) = ChatLine("m${next++}", text, "", true, "", "")
    private fun theirs(text: String) = ChatLine("t${next++}", text, "", false, "", "")

    @Test fun editsReplaceOnlyTheSendersOwnMessage() {
        val original = mine("Coffee at ten?")
        val folded = foldSmsReactions(listOf(original, theirs("Sure"), mine(editText("Coffee at ten?", "Coffee at eleven?"))))
        assertEquals(listOf("Coffee at eleven?", "Sure"), folded.map { it.line.text })
        assertEquals(listOf("Coffee at ten?"), folded[0].earlier)
        // Someone else can't edit your message.
        val spoof = foldSmsReactions(listOf(mine("Coffee at ten?"), theirs(editText("Coffee at ten?", "Never mind"))))
        assertEquals("Coffee at ten?", spoof[0].line.text)
        assertEquals(2, spoof.size)
    }

    @Test fun effectsRoundTrip() {
        val sent = effectText("Happy birthday!", MessageEffect.CONFETTI)
        assertEquals("Happy birthday!" to MessageEffect.CONFETTI, parseEffect(sent))
        assertTrue(effectText("psst", MessageEffect.INVISIBLE_INK).contains("hidden until tapped"))
        assertNull(parseEffect("(sent with Confetti 🎉)"))
        assertNull(parseEffect("I was sent with love"))
    }

    @Test fun randomResultsParse() {
        repeat(50) {
            val dice = parseRandom(rollText(3, 6)) as RandomResult.Dice
            assertEquals(3, dice.rolls.size); assertTrue(dice.rolls.all { it in 1..6 })
            val pick = parseRandom(pickText(listOf("Pizza", "Tacos", "Sushi"))) as RandomResult.Pick
            assertTrue(pick.choice in pick.options)
        }
        assertTrue(parseRandom(coinText()) is RandomResult.Coin)
        assertNull(parseRandom("🎲 Rolled 2d6: 7 + 1 = 8"))
    }

    private fun rpsLines(id: String, secret: String, sealed: Hand, revealed: Hand): List<ChatLine> {
        val title = "✊✋✌️ Rock paper scissors"
        return listOf(
            mine("$title · I've locked in my pick.\nfm-rps:$id:seal:${seal(id, sealed, secret)}"),
            theirs("Paper!"),
            mine("$title · I picked ${revealed.word}.\nfm-rps:$id:open:${revealed.word}:$secret"),
        )
    }

    @Test fun rockPaperScissorsChecksTheSeal() {
        val honest = rpsLines("abc123", "0123456789abcdef0123456789abcdef", Hand.ROCK, Hand.ROCK)
        val views = trackRps(honest)
        assertEquals(Hand.PAPER, views.getValue(honest[1].id).answer)
        assertTrue(views.getValue(honest[1].id).reply)
        val end = views.getValue(honest[2].id)
        assertTrue(end.honest); assertTrue(end.latest); assertEquals(Hand.ROCK, end.revealed)
        // Changing the pick after seeing theirs is caught.
        val cheat = rpsLines("abc124", "0123456789abcdef0123456789abcdef", Hand.ROCK, Hand.SCISSORS)
        assertFalse(trackRps(cheat).getValue(cheat[2].id).honest)
    }

    @Test fun contactCardsAndGameLinks() {
        val card = parseVCard("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Maya Chen\r\nTEL;TYPE=CELL:+1 555 0100\r\nEMAIL:maya@example.org\r\nEND:VCARD\r\n")!!
        assertEquals("Maya Chen", card.name)
        assertEquals(listOf("+1 555 0100"), card.phones)
        assertEquals(listOf("maya@example.org"), card.emails)
        val link = parseGameLink(gameLinkText("Chess", "Lichess", "https://lichess.org/abcd1234", "You play black."))!!
        assertEquals("Lichess", link.site); assertEquals("https://lichess.org/abcd1234", link.url)
    }

    @Test fun formattingAndSpoilers() {
        val white = androidx.compose.ui.graphics.Color.White
        val text = formatMessage("*Big* news: ||we won|| and see https://example.org/a_b_c", white, emptySet()) {}
        assertEquals("Big news: ██ ███ and see https://example.org/a_b_c", text.text)
        assertEquals("Big news: we won and see https://example.org/a_b_c",
            formatMessage("*Big* news: ||we won|| and see https://example.org/a_b_c", white, setOf(0)) {}.text)
        assertFalse(hasFormatting("2*3*4 and snake_case_name"))
        assertTrue(hasFormatting("this is _really_ ~not~ `code`"))
        assertEquals("Secret: ▒▒▒", plainPreview("Secret: ||the answer||"))
    }
}
