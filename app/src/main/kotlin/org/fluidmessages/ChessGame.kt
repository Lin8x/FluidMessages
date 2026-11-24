package org.fluidmessages

import kotlin.math.abs

/**
 * A complete chess rules engine for chess cards: legal moves, castling, en passant, promotion, check,
 * checkmate, stalemate, and standard notation (SAN for people, UCI for the move list, FEN for the board).
 * Squares are 0..63 with a1 = 0 and h8 = 63. White pieces are positive: 1 pawn … 6 king.
 */
internal class Chess private constructor(
    val board: IntArray, var whiteToMove: Boolean, private var castling: Int, private var enPassant: Int,
    private var halfMoves: Int, private var fullMoves: Int,
) {
    data class Move(val from: Int, val to: Int, val promotion: Int = 0)

    companion object {
        const val PAWN = 1; const val KNIGHT = 2; const val BISHOP = 3; const val ROOK = 4; const val QUEEN = 5; const val KING = 6

        fun start(): Chess {
            val board = IntArray(64)
            val back = intArrayOf(ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK)
            for (file in 0..7) {
                board[file] = back[file]; board[8 + file] = PAWN
                board[48 + file] = -PAWN; board[56 + file] = -back[file]
            }
            return Chess(board, true, 15, -1, 0, 1)
        }

        /** The position after these UCI moves (e2e4, e7e8q), or null if any of them isn't legal. */
        fun replay(moves: List<String>): Chess? {
            val game = start()
            for (text in moves) {
                val move = game.parseUci(text) ?: return null
                if (move !in game.legalMoves()) return null
                game.play(move)
            }
            return game
        }

        /** A position written in FEN, or null if it can't be read. */
        fun fromFen(fen: String): Chess? = runCatching {
            val parts = fen.trim().split(Regex("\\s+"))
            val board = IntArray(64)
            val rows = parts[0].split("/")
            require(rows.size == 8)
            for ((index, row) in rows.withIndex()) {
                var file = 0
                for (char in row) {
                    if (char.isDigit()) { file += char - '0'; continue }
                    val kind = " pnbrqk".indexOf(char.lowercaseChar())
                    require(kind > 0 && file < 8)
                    board[(7 - index) * 8 + file] = if (char.isUpperCase()) kind else -kind
                    file++
                }
            }
            val rights = parts.getOrElse(2) { "-" }
            var castling = 0
            if ('K' in rights) castling = castling or 1; if ('Q' in rights) castling = castling or 2
            if ('k' in rights) castling = castling or 4; if ('q' in rights) castling = castling or 8
            Chess(board, parts.getOrElse(1) { "w" } == "w", castling, square(parts.getOrElse(3) { "-" }) ?: -1,
                parts.getOrElse(4) { "0" }.toInt(), parts.getOrElse(5) { "1" }.toInt())
        }.getOrNull()

        fun square(name: String): Int? {
            if (name.length != 2) return null
            val file = name[0] - 'a'
            val rank = name[1] - '1'
            return if (file in 0..7 && rank in 0..7) rank * 8 + file else null
        }

        fun name(square: Int) = "${'a' + square % 8}${'1' + square / 8}"
    }

    fun copy() = Chess(board.copyOf(), whiteToMove, castling, enPassant, halfMoves, fullMoves)

    private fun side(piece: Int) = if (piece > 0) 1 else if (piece < 0) -1 else 0

    fun kingSquare(white: Boolean): Int = board.indexOfFirst { it == if (white) KING else -KING }

    /** Whether [square] is attacked by the given side. */
    fun attacked(square: Int, byWhite: Boolean): Boolean {
        val sign = if (byWhite) 1 else -1
        val file = square % 8
        val rank = square / 8
        fun at(f: Int, r: Int) = if (f in 0..7 && r in 0..7) board[r * 8 + f] else 0
        // Pawns attack diagonally forward from their own side.
        val pawnRank = rank - sign
        if (at(file - 1, pawnRank) == sign * PAWN || at(file + 1, pawnRank) == sign * PAWN) return true
        for ((df, dr) in KNIGHT_STEPS) if (at(file + df, rank + dr) == sign * KNIGHT) return true
        for ((df, dr) in KING_STEPS) if (at(file + df, rank + dr) == sign * KING) return true
        for ((df, dr) in STRAIGHT) if (slides(file, rank, df, dr, sign, ROOK)) return true
        for ((df, dr) in DIAGONAL) if (slides(file, rank, df, dr, sign, BISHOP)) return true
        return false
    }

    private fun slides(file: Int, rank: Int, df: Int, dr: Int, sign: Int, kind: Int): Boolean {
        var f = file + df
        var r = rank + dr
        while (f in 0..7 && r in 0..7) {
            val piece = board[r * 8 + f]
            if (piece != 0) return piece == sign * kind || piece == sign * QUEEN
            f += df; r += dr
        }
        return false
    }

    fun inCheck(): Boolean = kingSquare(whiteToMove).let { it >= 0 && attacked(it, !whiteToMove) }

    private fun pseudoMoves(): List<Move> {
        val moves = ArrayList<Move>(48)
        val sign = if (whiteToMove) 1 else -1
        for (from in 0..63) {
            val piece = board[from]
            if (side(piece) != sign) continue
            val file = from % 8
            val rank = from / 8
            fun add(to: Int) {
                if (abs(piece) == PAWN && (to / 8 == 7 || to / 8 == 0)) {
                    for (promotion in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) moves += Move(from, to, promotion)
                } else moves += Move(from, to)
            }
            when (abs(piece)) {
                PAWN -> {
                    val ahead = from + 8 * sign
                    if (ahead in 0..63 && board[ahead] == 0) {
                        add(ahead)
                        val home = if (sign > 0) 1 else 6
                        val twice = ahead + 8 * sign
                        if (rank == home && board[twice] == 0) moves += Move(from, twice)
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val f = file + df
                        val r = rank + sign
                        if (f !in 0..7 || r !in 0..7) continue
                        val to = r * 8 + f
                        if (side(board[to]) == -sign || to == enPassant) add(to)
                    }
                }
                KNIGHT, KING -> {
                    for ((df, dr) in if (abs(piece) == KNIGHT) KNIGHT_STEPS else KING_STEPS) {
                        val f = file + df
                        val r = rank + dr
                        if (f in 0..7 && r in 0..7 && side(board[r * 8 + f]) != sign) moves += Move(from, r * 8 + f)
                    }
                    if (abs(piece) == KING) castlingMoves(from, sign, moves)
                }
                else -> {
                    val directions = when (abs(piece)) { BISHOP -> DIAGONAL; ROOK -> STRAIGHT; else -> STRAIGHT + DIAGONAL }
                    for ((df, dr) in directions) {
                        var f = file + df
                        var r = rank + dr
                        while (f in 0..7 && r in 0..7) {
                            val target = board[r * 8 + f]
                            if (side(target) == sign) break
                            moves += Move(from, r * 8 + f)
                            if (target != 0) break
                            f += df; r += dr
                        }
                    }
                }
            }
        }
        return moves
    }

    private fun castlingMoves(from: Int, sign: Int, moves: MutableList<Move>) {
        val home = if (sign > 0) 4 else 60
        if (from != home || attacked(home, sign < 0)) return
        val kingSide = if (sign > 0) 1 else 4
        val queenSide = if (sign > 0) 2 else 8
        if (castling and kingSide != 0 && board[home + 1] == 0 && board[home + 2] == 0 && board[home + 3] == sign * ROOK &&
            !attacked(home + 1, sign < 0) && !attacked(home + 2, sign < 0)) moves += Move(home, home + 2)
        if (castling and queenSide != 0 && board[home - 1] == 0 && board[home - 2] == 0 && board[home - 3] == 0 &&
            board[home - 4] == sign * ROOK && !attacked(home - 1, sign < 0) && !attacked(home - 2, sign < 0)) moves += Move(home, home - 2)
    }

    fun legalMoves(): List<Move> = pseudoMoves().filter { move ->
        val next = copy()
        next.play(move)
        val king = next.kingSquare(whiteToMove)
        king >= 0 && !next.attacked(king, !whiteToMove)
    }

    fun play(move: Move) {
        val piece = board[move.from]
        val sign = side(piece)
        val captured = board[move.to]
        if (abs(piece) == PAWN && move.to == enPassant && captured == 0) board[move.to - 8 * sign] = 0
        board[move.to] = if (move.promotion != 0) sign * move.promotion else piece
        board[move.from] = 0
        if (abs(piece) == KING && abs(move.to - move.from) == 2) {
            // Castling moves the rook too.
            if (move.to > move.from) { board[move.from + 1] = board[move.from + 3]; board[move.from + 3] = 0 }
            else { board[move.from - 1] = board[move.from - 4]; board[move.from - 4] = 0 }
        }
        if (abs(piece) == KING) castling = castling and (if (sign > 0) 3.inv() else 12.inv())
        for (corner in intArrayOf(move.from, move.to)) castling = castling and when (corner) {
            0 -> 2.inv(); 7 -> 1.inv(); 56 -> 8.inv(); 63 -> 4.inv(); else -> 15
        }
        enPassant = if (abs(piece) == PAWN && abs(move.to - move.from) == 16) (move.from + move.to) / 2 else -1
        halfMoves = if (abs(piece) == PAWN || captured != 0) 0 else halfMoves + 1
        if (!whiteToMove) fullMoves++
        whiteToMove = !whiteToMove
    }

    fun parseUci(text: String): Move? {
        if (text.length !in 4..5) return null
        val from = square(text.substring(0, 2)) ?: return null
        val to = square(text.substring(2, 4)) ?: return null
        val promotion = when (text.getOrNull(4)) { null -> 0; 'q' -> QUEEN; 'r' -> ROOK; 'b' -> BISHOP; 'n' -> KNIGHT; else -> return null }
        return Move(from, to, promotion)
    }

    fun uci(move: Move) = name(move.from) + name(move.to) + when (move.promotion) {
        QUEEN -> "q"; ROOK -> "r"; BISHOP -> "b"; KNIGHT -> "n"; else -> ""
    }

    /** Standard algebraic notation, like Nf3, exd5, O-O, e8=Q+, Qh7#. Call before playing the move. */
    fun san(move: Move): String {
        val piece = abs(board[move.from])
        val capture = board[move.to] != 0 || piece == PAWN && move.to == enPassant
        val text = StringBuilder()
        if (piece == KING && abs(move.to - move.from) == 2) text.append(if (move.to > move.from) "O-O" else "O-O-O")
        else {
            if (piece == PAWN) { if (capture) text.append('a' + move.from % 8) }
            else {
                text.append(" PNBRQK"[piece])
                val rivals = legalMoves().filter { it.to == move.to && it.from != move.from && abs(board[it.from]) == piece }
                if (rivals.isNotEmpty()) {
                    when {
                        rivals.none { it.from % 8 == move.from % 8 } -> text.append('a' + move.from % 8)
                        rivals.none { it.from / 8 == move.from / 8 } -> text.append('1' + move.from / 8)
                        else -> text.append(name(move.from))
                    }
                }
            }
            if (capture) text.append('x')
            text.append(name(move.to))
            if (move.promotion != 0) text.append('=').append(" PNBRQK"[move.promotion])
        }
        val after = copy()
        after.play(move)
        if (after.inCheck()) text.append(if (after.legalMoves().isEmpty()) '#' else '+')
        return text.toString()
    }

    enum class Outcome { PLAYING, CHECKMATE, STALEMATE, DRAW }

    fun outcome(): Outcome = when {
        legalMoves().isNotEmpty() -> if (halfMoves >= 100 || insufficient()) Outcome.DRAW else Outcome.PLAYING
        inCheck() -> Outcome.CHECKMATE
        else -> Outcome.STALEMATE
    }

    private fun insufficient(): Boolean {
        val pieces = board.filter { it != 0 && abs(it) != KING }
        return pieces.isEmpty() || pieces.size == 1 && abs(pieces[0]) in setOf(KNIGHT, BISHOP)
    }

    /** Forsyth–Edwards Notation, the standard way to write a position. */
    fun fen(): String {
        val rows = (7 downTo 0).joinToString("/") { rank ->
            val row = StringBuilder()
            var empty = 0
            for (file in 0..7) {
                val piece = board[rank * 8 + file]
                if (piece == 0) { empty++; continue }
                if (empty > 0) { row.append(empty); empty = 0 }
                val letter = " pnbrqk"[abs(piece)]
                row.append(if (piece > 0) letter.uppercaseChar() else letter)
            }
            if (empty > 0) row.append(empty)
            row.toString()
        }
        val rights = buildString {
            if (castling and 1 != 0) append('K'); if (castling and 2 != 0) append('Q')
            if (castling and 4 != 0) append('k'); if (castling and 8 != 0) append('q')
        }.ifEmpty { "-" }
        return "$rows ${if (whiteToMove) "w" else "b"} $rights ${if (enPassant >= 0) name(enPassant) else "-"} $halfMoves $fullMoves"
    }
}

private val KNIGHT_STEPS = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
private val KING_STEPS = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
private val STRAIGHT = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
private val DIAGONAL = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
