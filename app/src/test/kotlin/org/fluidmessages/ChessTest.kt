package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Move counts ("perft") from well-known positions; any rules mistake changes these numbers. */
class ChessTest {
    private fun perft(game: Chess, depth: Int): Long {
        if (depth == 0) return 1
        var total = 0L
        for (move in game.legalMoves()) {
            val next = game.copy()
            next.play(move)
            total += perft(next, depth - 1)
        }
        return total
    }

    @Test fun startPosition() {
        val game = Chess.start()
        assertEquals(20, perft(game, 1))
        assertEquals(400, perft(game, 2))
        assertEquals(8902, perft(game, 3))
        assertEquals(197281, perft(game, 4))
    }

    // "Kiwipete": castling, en passant, promotions, and pins all at once.
    @Test fun kiwipete() {
        val game = assertNotNullGame("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48, perft(game, 1))
        assertEquals(2039, perft(game, 2))
        assertEquals(97862, perft(game, 3))
    }

    @Test fun position3() {
        val game = assertNotNullGame("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14, perft(game, 1))
        assertEquals(191, perft(game, 2))
        assertEquals(2812, perft(game, 3))
        assertEquals(43238, perft(game, 4))
    }

    @Test fun position4() {
        val game = assertNotNullGame("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6, perft(game, 1))
        assertEquals(264, perft(game, 2))
        assertEquals(9467, perft(game, 3))
    }

    @Test fun notationAndMate() {
        val game = Chess.start()
        val names = mutableListOf<String>()
        for (uci in listOf("f2f3", "e7e5", "g2g4", "d8h4")) {
            val move = game.parseUci(uci)!!
            names += game.san(move)
            game.play(move)
        }
        assertEquals(listOf("f3", "e5", "g4", "Qh4#"), names)
        assertEquals(Chess.Outcome.CHECKMATE, game.outcome())
        assertEquals("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3", game.fen())
        assertNotNull(Chess.replay(listOf("e2e4", "e7e5", "g1f3")))
    }

    private fun assertNotNullGame(fen: String): Chess = Chess.fromFen(fen).also { assertNotNull(it) }!!
}
