package org.fluidmessages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Games in a chat, like the ones people play over iMessage, but plain text underneath so they work with anyone:
 *
 *   🎮 Tic-tac-toe · I played square 5. Your turn!
 *   1️⃣2️⃣3️⃣ / 4️⃣❌6️⃣ / 7️⃣8️⃣9️⃣
 *   Reply with a number to play there. You're ⭕.
 *   fm-game:ttt:k3j9x2:f:5
 *
 * People with FluidMessages see a board they can tap. People on other apps see the board drawn in text and answer
 * with just their move ("e5", "Nf3", "7"); that reply counts as their move, and the next card carries it on. The
 * last line keeps every move so far, so any message rebuilds the game. Cards that rewrite earlier moves, play for the
 * wrong side, or keep going after the game ended are shown as not fitting the game and change nothing.
 */

internal enum class GameKind(val key: String, val title: String, val icon: String, val maxMoves: Int) {
    CHESS("chess", "Chess", "♟️", 600), TIC_TAC_TOE("ttt", "Tic-tac-toe", "❌", 9), FOUR("four", "Four in a row", "🔴", 42);

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
}

internal enum class GameResult { PLAYING, FIRST_WINS, SECOND_WINS, DRAW }

/** A position in a game. Positions never change; [play] gives the next one. */
internal sealed class GamePosition {
    abstract val firstToMove: Boolean
    abstract val result: GameResult
    abstract fun play(move: String): GamePosition?
    /** How a move reads in a sentence, like "e4", "square 5", or "column 3". Call on the position before it. */
    abstract fun describe(move: String): String
    /** A plain reply from someone on another app, turned into a move, or null if it isn't one. */
    abstract fun readReply(text: String): String?
    /** The board in text, for people without FluidMessages, from the side of [forFirst]. */
    abstract fun textBoard(forFirst: Boolean): String
    abstract fun howToReply(forFirst: Boolean): String
    open val note: String? get() = null

    companion object {
        fun start(kind: GameKind): GamePosition = when (kind) {
            GameKind.CHESS -> ChessPosition(Chess.start())
            GameKind.TIC_TAC_TOE -> GridPosition(3, 3, 3, false, IntArray(9))
            GameKind.FOUR -> GridPosition(7, 6, 4, true, IntArray(42))
        }

        fun replay(kind: GameKind, moves: List<String>): GamePosition? {
            if (moves.size > kind.maxMoves) return null
            var position: GamePosition = start(kind)
            for (move in moves) position = position.play(move) ?: return null
            return position
        }
    }
}

private val looksLikeMove = Regex("(?i)^(?:[kqrbn]?[a-h]?[1-8]?x?[a-h][1-8]=?[qrbn]?|[a-h][1-8][a-h][1-8][qrbn]?|[o0][o0][o0]?)$")

internal class ChessPosition(val game: Chess) : GamePosition() {
    override val firstToMove get() = game.whiteToMove
    override val result: GameResult by lazy {
        when (game.outcome()) {
            Chess.Outcome.PLAYING -> GameResult.PLAYING
            Chess.Outcome.CHECKMATE -> if (game.whiteToMove) GameResult.SECOND_WINS else GameResult.FIRST_WINS
            else -> GameResult.DRAW
        }
    }
    override val note: String? get() = when {
        result == GameResult.PLAYING && game.inCheck() -> "Check!"
        result == GameResult.DRAW && game.legalMoves().isEmpty() -> "Stalemate"
        else -> null
    }

    override fun play(move: String): GamePosition? {
        val parsed = game.parseUci(move) ?: return null
        if (parsed !in game.legalMoves()) return null
        return ChessPosition(game.copy().apply { play(parsed) })
    }

    override fun describe(move: String) = game.parseUci(move)?.let { game.san(it) } ?: move

    override fun readReply(text: String): String? {
        val raw = text.trim().trimEnd('!', '?', '.', '+', '#').replace(" ", "").replace("-", "")
        // Most short messages aren't moves; only ones shaped like a move are checked against the board.
        if (raw.length > 8 || !looksLikeMove.matches(raw)) return null
        val legal = game.legalMoves()
        // Castling, however it's written.
        val castle = raw.uppercase().replace('0', 'O')
        if (castle == "OO" || castle == "OOO") {
            return legal.firstOrNull { abs(game.board[it.from]) == Chess.KING && abs(it.to - it.from) == 2 && (it.to > it.from) == (castle == "OO") }
                ?.let(game::uci)
        }
        // e2e4 or e7e8q.
        val lower = raw.lowercase()
        game.parseUci(lower)?.let { move -> if (move in legal) return lower }
        game.parseUci(lower + "q")?.let { move -> if (move in legal && lower.length == 4) return lower + "q" }
        // Standard notation, forgiving about capital letters, "x", "=", and check marks.
        fun key(san: String) = san.replace("x", "").replace("=", "").replace("+", "").replace("#", "")
        val candidates = linkedSetOf(raw, raw.replaceFirstChar { it.uppercaseChar() },
            raw.dropLast(1) + raw.last().uppercaseChar(), raw.replaceFirstChar { it.uppercaseChar() }.let { it.dropLast(1) + it.last().uppercaseChar() }, lower)
        val notation = legal.associateWith { key(game.san(it)) }
        for (candidate in candidates) {
            val matches = notation.filterValues { it == key(candidate) }.keys
            if (matches.size == 1) return game.uci(matches.first())
        }
        return null
    }

    override fun textBoard(forFirst: Boolean): String {
        val ranks = if (forFirst) (7 downTo 0) else (0..7)
        val files = if (forFirst) (0..7) else (7 downTo 0)
        val rows = ranks.map { rank ->
            "${rank + 1} " + files.joinToString(" ") { file ->
                val piece = game.board[rank * 8 + file]
                if (piece == 0) "." else " pnbrqk"[abs(piece)].let { if (piece > 0) it.uppercaseChar() else it }.toString()
            }
        }
        return (rows + ("  " + files.joinToString(" ") { ('a' + it).toString() })).joinToString("\n")
    }

    override fun howToReply(forFirst: Boolean) =
        "Reply with your move, like ${if (forFirst) "e4 or Nf3" else "e5 or Nf6"}. You're ${if (forFirst) "white (capital letters)" else "black (small letters)"}."
}

/** Tic-tac-toe ([drop] false) and four in a row ([drop] true): a grid and a line of [goal] to win. */
internal class GridPosition(val columns: Int, val rows: Int, private val goal: Int, val drop: Boolean, val cells: IntArray) : GamePosition() {
    private val count get() = cells.count { it != 0 }
    override val firstToMove get() = count % 2 == 0
    val winningLine: List<Int> by lazy { findLine() }
    override val result: GameResult get() = when {
        winningLine.isNotEmpty() -> if (cells[winningLine[0]] == 1) GameResult.FIRST_WINS else GameResult.SECOND_WINS
        count == cells.size -> GameResult.DRAW
        else -> GameResult.PLAYING
    }

    /** Where a move lands: the square itself, or for four in a row, the lowest open spot in the column. */
    fun target(move: String): Int? {
        val number = move.toIntOrNull() ?: return null
        if (move != number.toString()) return null
        return if (drop) {
            if (number !in 1..columns) null else (0 until rows).map { row -> row * columns + number - 1 }.firstOrNull { cells[it] == 0 }
        } else if (number in 1..cells.size && cells[number - 1] == 0) number - 1 else null
    }

    override fun play(move: String): GamePosition? {
        if (result != GameResult.PLAYING) return null
        val at = target(move) ?: return null
        return GridPosition(columns, rows, goal, drop, cells.copyOf().also { it[at] = if (firstToMove) 1 else 2 })
    }

    override fun describe(move: String) = if (drop) "column $move" else when (move) {
        "5" -> "the middle"; "1" -> "the top left"; "3" -> "the top right"; "7" -> "the bottom left"; "9" -> "the bottom right"
        else -> "square $move"
    }

    override fun readReply(text: String): String? {
        val match = (if (drop) Regex("""(?i)^\s*(?:col(?:umn)?\s*)?(\d)\s*[.!]?\s*$""") else Regex("""^\s*(\d)\s*[.!]?\s*$""")).find(text) ?: return null
        val move = match.groupValues[1]
        return if (target(move) != null) move else null
    }

    private fun findLine(): List<Int> {
        for (row in 0 until rows) for (column in 0 until columns) {
            val mark = cells[row * columns + column]
            if (mark == 0) continue
            for ((dc, dr) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
                val line = (0 until goal).map { step -> column + dc * step to row + dr * step }
                if (line.all { (c, r) -> c in 0 until columns && r in 0 until rows && cells[r * columns + c] == mark }) {
                    return line.map { (c, r) -> r * columns + c }
                }
            }
        }
        return emptyList()
    }

    fun mark(player: Int) = if (drop) (if (player == 1) "🔴" else "🟡") else (if (player == 1) "❌" else "⭕")

    override fun textBoard(forFirst: Boolean): String = if (drop) {
        val header = (1..columns).joinToString("") { keycap(it) }
        (listOf(header) + (rows - 1 downTo 0).map { row ->
            (0 until columns).joinToString("") { column -> cells[row * columns + column].let { if (it == 0) "⚪" else mark(it) } }
        }).joinToString("\n")
    } else (0 until rows).joinToString("\n") { row ->
        (0 until columns).joinToString("") { column -> (row * columns + column).let { if (cells[it] == 0) keycap(it + 1) else mark(cells[it]) } }
    }

    override fun howToReply(forFirst: Boolean) = if (drop) "Reply with a column number (1–$columns) to drop your piece. You're ${mark(if (forFirst) 1 else 2)}."
        else "Reply with a number to play there. You're ${mark(if (forFirst) 1 else 2)}."

    private fun keycap(number: Int) = "$number️⃣"
}

/** One game message: which game, who sent it as which side, and every move so far. */
internal data class GameMessage(val kind: GameKind, val id: String, val senderFirst: Boolean, val moves: List<String>, val resigned: Boolean)

private val gamePattern = Regex("""fm-game:([a-z]{2,8}):([a-z0-9]{6,12}):([fs]):([a-h0-9qrbn,]{0,3600})(:resign)?\s*$""")
private val legacyChess = Regex("""fm-chess:([a-z0-9]{6,12}):([wb]):([a-h1-8qrbn,]{0,3600})(:resign)?""")

internal fun parseGame(text: String): GameMessage? {
    if (text.length > 6000) return null
    if (text.startsWith("🎮")) {
        val match = gamePattern.find(text) ?: return null
        val kind = GameKind.of(match.groupValues[1]) ?: return null
        val moves = match.groupValues[4].split(',').filter { it.isNotEmpty() }
        if (moves.size > kind.maxMoves) return null
        return GameMessage(kind, match.groupValues[2], match.groupValues[3] == "f", moves, match.groupValues[5].isNotEmpty())
    }
    // Chess cards from before games had their own menu.
    if (text.startsWith("♟")) {
        val match = legacyChess.find(text) ?: return null
        val moves = match.groupValues[3].split(',').filter { it.isNotEmpty() }
        if (moves.size > GameKind.CHESS.maxMoves) return null
        return GameMessage(GameKind.CHESS, match.groupValues[1], match.groupValues[2] == "w", moves, match.groupValues[4].isNotEmpty())
    }
    return null
}

internal fun gameText(kind: GameKind, id: String, senderFirst: Boolean, moves: List<String>, summary: String, resign: Boolean = false): String {
    val position = GamePosition.replay(kind, moves) ?: GamePosition.start(kind)
    val them = !senderFirst
    val reply = if (!resign && position.result == GameResult.PLAYING && position.firstToMove == them) "\n" + position.howToReply(them) else ""
    return "🎮 ${kind.title} · $summary\n${position.textBoard(them)}$reply\n" +
        "fm-game:${kind.key}:$id:${if (senderFirst) "f" else "s"}:${moves.joinToString(",")}${if (resign) ":resign" else ""}"
}

internal fun newGameText(kind: GameKind, senderFirst: Boolean): String = gameText(kind, newId(), senderFirst, emptyList(), when (kind) {
    GameKind.CHESS -> if (senderFirst) "Let's play chess! I'm white, so I go first." else "Let's play chess! I'm black, so you go first."
    else -> if (senderFirst) "Let's play! I'll go first." else "Let's play! You go first."
})

/** The sentence a move is sent with. */
internal fun moveSummary(before: GamePosition, move: String, after: GamePosition): String {
    val played = when (before) {
        is ChessPosition -> "I played ${before.describe(move)}"
        is GridPosition -> if (before.drop) "I dropped in ${before.describe(move)}" else "I played ${before.describe(move)}"
    }
    return when (after.result) {
        GameResult.FIRST_WINS, GameResult.SECOND_WINS -> when (after) {
            is ChessPosition -> "$played. Checkmate!"
            is GridPosition -> "$played. ${if (after.drop) "Four" else "Three"} in a row, I win!"
        }
        GameResult.DRAW -> "$played. It's a draw!"
        GameResult.PLAYING -> "$played. ${after.note?.let { "$it " } ?: ""}Your turn!"
    }
}

/** How one chat message fits into its game. */
internal data class GameView(
    val kind: GameKind, val id: String, val moves: List<String>, val position: GamePosition, val mineFirst: Boolean,
    /** Who resigned: true for you, false for them, null for nobody. */
    val resignedByMe: Boolean?,
    val latest: Boolean,
    /** The move this message made, and whether you made it. */
    val move: String?, val byMe: Boolean, val moveText: String?,
    /** A plain reply from someone on another app that counted as a move. */
    val reply: Boolean,
    /** A card that doesn't fit the game so far, so it changed nothing. */
    val rejected: Boolean,
) {
    val over get() = resignedByMe != null || position.result != GameResult.PLAYING
    val myTurn get() = latest && !over && position.firstToMove == mineFirst
}

private class GameTrack(val kind: GameKind, val id: String, var moves: List<String>, var position: GamePosition, val mineFirst: Boolean,
                        var resignedByMe: Boolean?, var lastLine: String, var lastIndex: Int)

/** Follows every game through a chat, plain replies included. [consumed] gets the lines that counted as moves. */
internal fun trackGames(lines: List<ChatLine>, consumed: MutableSet<String>? = null): Map<String, GameView> {
    val games = linkedMapOf<String, GameTrack>()
    val views = mutableMapOf<String, GameView>()
    var lastPoll = -1
    // Rebuilding a game from a card is the costly part; a flood of long made-up games can't stall the chat.
    var budget = 4000
    fun view(track: GameTrack, line: ChatLine, move: String?, moveText: String?, reply: Boolean, rejected: Boolean) = GameView(track.kind, track.id,
        track.moves, track.position, track.mineFirst, track.resignedByMe, false, move, line.outgoing, moveText, reply, rejected)
    for ((index, line) in lines.withIndex()) {
        if (line.attachment != null) continue
        if (parsePoll(line.text) != null) { lastPoll = index; continue }
        val message = parseGame(line.text)
        if (message != null) {
            val senderFirst = message.senderFirst
            val known = games[message.id]
            if (known == null || known.kind != message.kind) {
                if (known != null || message.moves.size > budget) continue
                budget -= message.moves.size
                val position = GamePosition.replay(message.kind, message.moves) ?: continue
                val mineFirst = if (line.outgoing) senderFirst else !senderFirst
                val track = GameTrack(message.kind, message.id, message.moves, position, mineFirst,
                    if (message.resigned) line.outgoing else null, line.id, index)
                games[message.id] = track
                views[line.id] = view(track, line, message.moves.lastOrNull(), null, false, false)
                continue
            }
            // A card must play for the side of whoever sent it, keep every earlier move, and add at most the moves
            // since (a plain reply this phone missed, then the sender's own).
            val senderIsMine = line.outgoing
            val sideOk = (if (senderIsMine) known.mineFirst else !known.mineFirst) == senderFirst
            val extra = message.moves.size - known.moves.size
            val keepsHistory = extra >= 0 && message.moves.subList(0, known.moves.size) == known.moves
            var next: GamePosition? = known.position
            var lastBy: Boolean? = null
            if (sideOk && keepsHistory && extra <= 2 && known.resignedByMe == null && (extra == 0 || known.position.result == GameResult.PLAYING)) {
                for (move in message.moves.drop(known.moves.size)) {
                    val position = next ?: break
                    lastBy = position.firstToMove == senderFirst
                    next = position.play(move)
                }
            } else next = null
            val fits = next != null && (extra == 0 && message.resigned || extra > 0 && lastBy == true)
            if (!fits) { views[line.id] = view(known, line, null, null, false, true); continue }
            val before = known.position
            val moveText = if (extra == 1) before.describe(message.moves.last()) else null
            known.moves = message.moves; known.position = next!!
            if (message.resigned) known.resignedByMe = senderIsMine
            known.lastLine = line.id; known.lastIndex = index
            views[line.id] = view(known, line, message.moves.lastOrNull().takeIf { extra > 0 }, moveText, false, false)
            continue
        }
        // A plain reply: a move for the newest game waiting on that person, if it reads as one.
        if (line.text.length > 24) continue
        val waiting = games.values.filter { it.resignedByMe == null && it.position.result == GameResult.PLAYING &&
            it.position.firstToMove == (if (line.outgoing) it.mineFirst else !it.mineFirst) }.maxByOrNull { it.lastIndex } ?: continue
        if (waiting.kind != GameKind.CHESS && lastPoll > waiting.lastIndex) continue // A number after a newer poll is a vote.
        val move = waiting.position.readReply(line.text) ?: continue
        val before = waiting.position
        val after = before.play(move) ?: continue
        waiting.moves = waiting.moves + move; waiting.position = after
        waiting.lastLine = line.id; waiting.lastIndex = index
        consumed?.add(line.id)
        views[line.id] = view(waiting, line, move, before.describe(move), true, false)
    }
    val latest = games.values.associate { it.lastLine to it.id }
    return views.mapValues { (lineId, view) -> if (latest[lineId] == view.id && !view.rejected) view.copy(latest = true) else view }
}

// The cards

private val pieceGlyph = mapOf(1 to "♟", 2 to "♞", 3 to "♝", 4 to "♜", 5 to "♛", 6 to "♚")

@Composable
internal fun ChessBoard(game: Chess, flipped: Boolean, square: Dp, selected: Int?, targets: Set<Int>, last: Pair<Int, Int>?,
                        tap: ((Int) -> Unit)?) {
    val light = Color(0xFFEDE6D6)
    val dark = Color(0xFF8DA37A)
    Column(Modifier.clip(RoundedCornerShape(8.dp))) {
        for (row in 0..7) Row {
            for (column in 0..7) {
                val rank = if (flipped) row else 7 - row
                val file = if (flipped) 7 - column else column
                val index = rank * 8 + file
                val base = if ((rank + file) % 2 == 0) dark else light
                val tint = when {
                    index == selected -> Color(0xFFF6D365)
                    last != null && (index == last.first || index == last.second) -> base.copy(red = (base.red + .25f).coerceAtMost(1f))
                    else -> base
                }
                Box(Modifier.size(square).background(tint).then(if (tap != null) Modifier.clickable(onClickLabel = Chess.name(index)) { tap(index) } else Modifier),
                    contentAlignment = Alignment.Center) {
                    val piece = game.board[index]
                    if (piece != 0) Text(pieceGlyph[abs(piece)] + "︎", fontSize = (square.value * .72f).sp,
                        textAlign = TextAlign.Center, style = TextStyle(color = if (piece > 0) Color.White else Color(0xFF1E1E1E),
                            shadow = Shadow(if (piece > 0) Color.Black else Color.White.copy(alpha = .4f), Offset(0f, 0f), 3f)))
                    if (index in targets) Box(Modifier.size(square * .3f).background(Color.Black.copy(alpha = .25f), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun GridBoard(position: GridPosition, cell: Dp, pending: Int?, tap: ((String) -> Unit)?) {
    val line = position.winningLine.toSet()
    val frame = if (position.drop) Color(0xFF2F5BD3) else Color(0xFF2A2A2E)
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(frame).padding(if (position.drop) 4.dp else 2.dp),
        verticalArrangement = Arrangement.spacedBy(if (position.drop) 3.dp else 2.dp)) {
        for (row in 0 until position.rows) Row(horizontalArrangement = Arrangement.spacedBy(if (position.drop) 3.dp else 2.dp)) {
            for (column in 0 until position.columns) {
                // Four in a row is drawn with the bottom row last.
                val index = if (position.drop) (position.rows - 1 - row) * position.columns + column else row * position.columns + column
                val mark = if (index == pending) (if (position.firstToMove) 1 else 2) else position.cells[index]
                val move = if (position.drop) "${column + 1}" else "${index + 1}"
                val canTap = tap != null && position.target(move) != null
                val shape = if (position.drop) CircleShape else RoundedCornerShape(4.dp)
                Box(Modifier.size(cell).clip(shape)
                    .background(if (position.drop) Color(0xFFF2F2F7) else Color(0xFFF7F7FA))
                    .then(if (index in line) Modifier.border(3.dp, Color(0xFF34C759), shape) else Modifier)
                    .then(if (canTap) Modifier.clickable(onClickLabel = position.describe(move)) { tap?.invoke(move) } else Modifier),
                    contentAlignment = Alignment.Center) {
                    if (mark != 0) {
                        if (position.drop) Box(Modifier.size(cell * .86f).background(if (mark == 1) Color(0xFFFF3B30) else Color(0xFFFFCC00), CircleShape)
                            .then(if (index == pending) Modifier.border(2.dp, Color.Black.copy(alpha = .4f), CircleShape) else Modifier))
                        else Text(if (mark == 1) "✕" else "◯", color = if (mark == 1) Color(0xFFFF3B30) else Color(0xFF007AFF),
                            fontSize = (cell.value * .6f).sp, fontWeight = FontWeight.Bold,
                            modifier = if (index == pending) Modifier.background(Color(0x22000000), CircleShape) else Modifier)
                    }
                }
            }
        }
    }
}

private fun status(view: GameView): String {
    view.resignedByMe?.let { return if (it) "You resigned" else "They resigned · you win! 🎉" }
    val mine = if (view.mineFirst) GameResult.FIRST_WINS else GameResult.SECOND_WINS
    return when (view.position.result) {
        GameResult.PLAYING -> when {
            !view.latest -> "Move ${view.moves.size}"
            view.myTurn -> (view.position.note?.let { "$it " } ?: "") + "Your move"
            else -> "Their move"
        }
        GameResult.DRAW -> view.position.note?.let { "$it · it's a draw" } ?: "It's a draw"
        mine -> "You win! 🎉"
        else -> "They win"
    }
}

private fun lastChessMove(view: GameView): Pair<Int, Int>? {
    val game = (view.position as? ChessPosition)?.game ?: return null
    val move = view.moves.lastOrNull() ?: return null
    return game.parseUci(move)?.let { it.from to it.to }
}

@Composable
internal fun GameCard(view: GameView, foreground: Color, respond: ((String) -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(view.kind.title.uppercase(), color = foreground.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (view.rejected) {
            Text("This message doesn't fit the game so far, so it was skipped.", color = foreground, fontSize = 14.sp)
            return@Column
        }
        view.moveText?.let { Text(if (view.byMe) "You played $it" else "They played $it", color = foreground, fontSize = 14.sp) }
        Box(Modifier.clickable(onClickLabel = "Open the game") { open = true }) {
            when (val position = view.position) {
                is ChessPosition -> ChessBoard(position.game, !view.mineFirst, 21.dp, null, emptySet(), lastChessMove(view), null)
                is GridPosition -> GridBoard(position, if (position.drop) 24.dp else 42.dp, null, null)
            }
        }
        Text(status(view), color = foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (view.myTurn && respond != null) FilledTonalButton(onClick = { open = true }) { Text("Make your move") }
        else TextButton(onClick = { open = true }) { Text(if (view.latest && view.over && respond != null) "Play again" else "View game", color = foreground) }
    }
    if (open) GameDialog(view, respond.takeIf { view.latest }, { open = false }) { text -> respond?.invoke(text); open = false }
}

@Composable
private fun GameDialog(view: GameView, respond: ((String) -> Unit)?, dismiss: () -> Unit, send: (String) -> Unit) {
    val playing = respond != null && view.myTurn
    var pending by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        // A full page, so the board is as big as the screen allows and Back closes it.
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                val width = maxWidth
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (playing) "Your move" else view.kind.title, style = MaterialTheme.typography.titleMedium)
                    when (val position = view.position) {
                        is ChessPosition -> ChessMoveBoard(position, view, playing, minOf((width - 2.dp) / 8, 46.dp), pending) { pending = it }
                        is GridPosition -> {
                            val cell = minOf((width - 30.dp) / position.columns, if (position.drop) 44.dp else 80.dp)
                            GridBoard(position, cell, pending?.let(position::target), if (playing) { move -> pending = move } else null)
                        }
                    }
                    if (!playing) Text(status(view), fontWeight = FontWeight.SemiBold)
                    val move = pending
                    if (move != null) {
                        val after = view.position.play(move)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pending = null }) { Text("Undo") }
                            Button(enabled = after != null, onClick = {
                                after ?: return@Button
                                send(gameText(view.kind, view.id, view.mineFirst, view.moves + move, moveSummary(view.position, move, after)))
                            }) { Text("Send") }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (playing) TextButton(onClick = { send(gameText(view.kind, view.id, view.mineFirst, view.moves, "I resign. Good game!", resign = true)) }) {
                            Text("Resign", color = MaterialTheme.colorScheme.error)
                        }
                        if (respond != null && view.over) TextButton(onClick = { send(newGameText(view.kind, !view.mineFirst)) }) { Text("Play again") }
                        TextButton(onClick = dismiss) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChessMoveBoard(position: ChessPosition, view: GameView, playing: Boolean, square: Dp, pending: String?, choose: (String?) -> Unit) {
    val start = position.game
    var selected by remember { mutableStateOf<Int?>(null) }
    var promotionFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val legal = remember(start) { if (playing) start.legalMoves() else emptyList() }
    val pendingMove = pending?.let(start::parseUci)
    val shown = remember(pending) { pendingMove?.let { start.copy().apply { play(it) } } ?: start }
    val targets = selected?.let { from -> legal.filter { it.from == from }.map { it.to }.toSet() }.orEmpty()
    ChessBoard(shown, !view.mineFirst, square, selected, if (pending == null) targets else emptySet(),
        pendingMove?.let { it.from to it.to } ?: lastChessMove(view), if (playing && pending == null) { index ->
            val piece = start.board[index]
            val from = selected
            when {
                from != null && index in targets -> {
                    val options = legal.filter { it.from == from && it.to == index }
                    if (options.size > 1) promotionFor = from to index else choose(start.uci(options.first()))
                    selected = null
                }
                piece != 0 && (piece > 0) == view.mineFirst -> selected = index
                else -> selected = null
            }
        } else null)
    promotionFor?.let { (from, to) ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in listOf(Chess.QUEEN, Chess.ROOK, Chess.BISHOP, Chess.KNIGHT)) OutlinedButton(onClick = {
                choose(start.uci(Chess.Move(from, to, kind))); promotionFor = null
            }) { Text(pieceGlyph[kind] + "︎", fontSize = 20.sp) }
        }
    }
    if (pendingMove != null) Text("Send ${start.san(pendingMove)}?", fontSize = 14.sp)
}

/** The Games menu: pick a game, then where to play (right here, or a site for people without FluidMessages), then sides. */
@Composable
internal fun GamesDialog(dismiss: () -> Unit, post: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var kind by remember { mutableStateOf<GameKind?>(null) }
    var rps by remember { mutableStateOf(false) }
    var site by remember { mutableStateOf<GameSite?>(null) }
    var here by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val chosen = kind
    val chosenSite = site
    fun open(url: String) = runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    fun back() { when { here || chosenSite != null -> { here = false; site = null; link = ""; problem = null }; chosen != null || rps -> { kind = null; rps = false }; else -> dismiss() } }
    FormPage(onDismissRequest = dismiss, title = { Text(when { rps -> "Rock paper scissors"; chosenSite != null -> "${chosen?.title} on ${chosenSite.name}"; else -> chosen?.title ?: "Games" }) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun Choice(icon: String, title: String, detail: String, pick: () -> Unit) = Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = pick).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(icon, fontSize = 22.sp)
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                rps -> {
                    Text("Pick your hand. It's sealed until they choose, so neither of you can change your mind.", fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (hand in Hand.entries) OutlinedButton(onClick = { post(startRps(context, hand)); dismiss() }) { Text(hand.emoji + " " + hand.title, fontSize = 13.sp) }
                    }
                    Text("People on other apps reply with rock, paper, or scissors, and see your pick after they answer.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                chosen == null -> {
                    Text("Take turns right in this chat. People on other apps see the board as text and reply with their move.", fontSize = 14.sp)
                    for (game in GameKind.entries) Choice(game.icon, game.title, when (game) {
                        GameKind.CHESS -> "The full rules, here or on Lichess or Chess.com"
                        GameKind.TIC_TAC_TOE -> "Three in a row wins"
                        GameKind.FOUR -> "Drop pieces; four in a line wins"
                    }) { kind = game }
                    Choice("✊", "Rock paper scissors", "Sealed picks, so it's fair") { rps = true }
                }
                here -> Text(if (chosen == GameKind.CHESS) "White moves first." else "Who makes the first move?")
                chosenSite != null && chosenSite.automatic -> {
                    Text(chosenSite.detail, fontSize = 14.sp)
                    Text("Which side do you want?", fontSize = 14.sp)
                    if (working) Text("Making the game…", fontSize = 13.sp)
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
                chosenSite != null -> {
                    Text("1. Open ${chosenSite.name} and start a game with a friend.\n2. Copy the invite link it gives you.\n3. Paste it here and send it.", fontSize = 14.sp)
                    if (chosenSite.detail.contains("account")) Text("${chosenSite.name} needs a free account for both of you.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { open(chosenSite.url) }) { Text("Open ${chosenSite.name}") }
                    androidx.compose.material3.OutlinedTextField(link, { link = it.trim().take(500) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Invite link") }, placeholder = { Text("https://…") })
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
                else -> {
                    Text("Where do you want to play?", fontSize = 14.sp)
                    Choice("💬", "Here in this chat", "Works with anyone. Other apps get the board as text.") { here = true }
                    for (option in gameSites(chosen.title)) Choice("🌐", option.name, option.detail) { site = option }
                }
            }
        }
    }, confirmButton = {
        val game = chosen
        when {
            game != null && here -> Row {
                TextButton(onClick = { post(newGameText(game, false)); dismiss() }) { Text(if (game == GameKind.CHESS) "Play black" else "They go first") }
                TextButton(onClick = { post(newGameText(game, true)); dismiss() }) { Text(if (game == GameKind.CHESS) "Play white" else "I go first") }
            }
            game != null && chosenSite != null && chosenSite.automatic -> Row {
                for ((label, white) in listOf("Play black" to false, "Play white" to true)) TextButton(enabled = !working, onClick = {
                    working = true; problem = null
                    scope.launch {
                        val urls = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { lichessGame(white) }
                        working = false
                        if (urls == null) { problem = "Lichess didn't answer. Check your connection and try again."; return@launch }
                        open(urls.first)
                        post(gameLinkText("Chess", "Lichess", urls.second, "You play ${if (white) "black" else "white"}. Open the link to play in your browser, no account needed."))
                        dismiss()
                    }
                }) { Text(label) }
            }
            game != null && chosenSite != null -> TextButton(onClick = {
                val ok = Regex("""^https://[A-Za-z0-9.-]+\.[A-Za-z]{2,}(/\S*)?$""").matches(link)
                if (!ok) problem = "Paste the game's https:// link."
                else { post(gameLinkText(game.title, chosenSite.name, link, "Open the link to play in your browser.")); dismiss() }
            }) { Text("Send") }
        }
    }, dismissButton = { TextButton(onClick = ::back) { Text(if (chosen != null || rps) "Back" else "Cancel") } })
}
