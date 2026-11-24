package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Games played between FluidMessages and people on other apps who answer in plain text. */
class GamesTest {
    private var next = 0
    private fun mine(text: String) = ChatLine("m${next++}", text, "", true, "", "")
    private fun theirs(text: String) = ChatLine("t${next++}", text, "", false, "", "")

    @Test fun chessRepliesInAnyCommonWriting() {
        val start = GamePosition.start(GameKind.CHESS)
        assertEquals("e2e4", start.readReply("e4"))
        assertEquals("e2e4", start.readReply("E4"))
        assertEquals("g1f3", start.readReply("Nf3"))
        assertEquals("g1f3", start.readReply("nf3"))
        assertEquals("e2e4", start.readReply("e2-e4"))
        assertNull(start.readReply("e5"))
        assertNull(start.readReply("ok see you"))
        assertNull(start.readReply("Ke2"))
        // Castling once the way is clear.
        var position: GamePosition = start
        for (move in listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6")) position = position.play(move)!!
        assertEquals("e1g1", position.readReply("O-O"))
        assertEquals("e1g1", position.readReply("0-0"))
        assertEquals("f3e5", position.readReply("Nxe5"))
    }

    @Test fun ticTacToeAndFourInARow() {
        var ttt = GamePosition.start(GameKind.TIC_TAC_TOE)
        for (move in listOf("1", "4", "2", "5")) ttt = ttt.play(move)!!
        assertNull(ttt.play("1"))
        assertEquals(null, ttt.readReply("4"))
        ttt = ttt.play(ttt.readReply("3")!!)!!
        assertEquals(GameResult.FIRST_WINS, ttt.result)
        assertNull(ttt.play("9"))

        var four = GamePosition.start(GameKind.FOUR)
        for (move in listOf("1", "2", "1", "2", "1", "2")) four = four.play(move)!!
        assertEquals("1", four.readReply("column 1"))
        four = four.play("1")!!
        assertEquals(GameResult.FIRST_WINS, four.result)
        assertNull(GamePosition.replay(GameKind.FOUR, List(7) { "3" }))
    }

    @Test fun plainRepliesCountAsMoves() {
        val start = newGameText(GameKind.TIC_TAC_TOE, true)
        val id = parseGame(start)!!.id
        val first = gameText(GameKind.TIC_TAC_TOE, id, true, listOf("5"), "I played the middle. Your turn!")
        val lines = listOf(mine(start), mine(first), theirs("1"), mine(gameText(GameKind.TIC_TAC_TOE, id, true, listOf("5", "1", "9"), "x")))
        val moved = mutableSetOf<String>()
        val games = trackGames(lines, moved)
        assertEquals(setOf(lines[2].id), moved)
        assertTrue(games.getValue(lines[2].id).reply)
        val last = games.getValue(lines[3].id)
        assertTrue(last.latest)
        assertFalse(last.rejected)
        assertEquals(listOf("5", "1", "9"), last.moves)
        assertFalse(last.myTurn)
    }

    @Test fun cardsThatRewriteTheGameAreSkipped() {
        val start = newGameText(GameKind.CHESS, false)
        val id = parseGame(start)!!.id
        val lines = listOf(
            mine(start),
            theirs(gameText(GameKind.CHESS, id, true, listOf("e2e4"), "e4")),
            // Rewriting their first move.
            theirs(gameText(GameKind.CHESS, id, true, listOf("d2d4", "d7d5", "c2c4"), "cheat")),
            // Moving for my side.
            theirs(gameText(GameKind.CHESS, id, true, listOf("e2e4", "e7e5"), "cheat")),
        )
        val games = trackGames(lines)
        assertFalse(games.getValue(lines[1].id).rejected)
        assertTrue(games.getValue(lines[2].id).rejected)
        assertTrue(games.getValue(lines[3].id).rejected)
        assertTrue(games.getValue(lines[1].id).latest)
        assertTrue(games.getValue(lines[1].id).myTurn)
    }

    @Test fun numbersAfterANewerPollAreVotes() {
        val start = newGameText(GameKind.TIC_TAC_TOE, false)
        val poll = "📊 Poll: Lunch?\n1. Pizza\n2. Tacos\nReply with a number to vote · poll abcdef12"
        val moved = mutableSetOf<String>()
        trackGames(listOf(mine(start), mine(poll), theirs("1")), moved)
        assertTrue(moved.isEmpty())
    }

    @Test fun oldChessCardsStillWork() {
        val legacy = "♟️ Chess · I played e4\nSee the board: https://lichess.org/analysis/standard/x\nfm-chess:abc123:w:e2e4"
        val game = assertNotNull(parseGame(legacy)).let { parseGame(legacy)!! }
        assertEquals(GameKind.CHESS, game.kind)
        assertEquals(listOf("e2e4"), game.moves)
        assertNull(parseGame("🎮 Chess · x\nfm-game:nope:abc123:f:"))
    }
}
