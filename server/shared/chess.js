'use strict';
// The chess rules behind chess cards, the same as the Android app's (ChessGame.kt): legal moves, castling,
// en passant, promotion, check, checkmate, stalemate, SAN for people, UCI for the move list, and FEN.
// Squares are 0..63 with a1 = 0. White pieces are positive: 1 pawn … 6 king.
const FluidChess = (() => {
  const PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
  const KNIGHT_STEPS = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];
  const KING_STEPS = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];
  const STRAIGHT = [[1, 0], [-1, 0], [0, 1], [0, -1]];
  const DIAGONAL = [[1, 1], [1, -1], [-1, 1], [-1, -1]];
  const sign = (piece) => Math.sign(piece);
  const name = (square) => 'abcdefgh'[square % 8] + (Math.floor(square / 8) + 1);
  const square = (text) => {
    if (!/^[a-h][1-8]$/.test(text)) return null;
    return (text.charCodeAt(1) - 49) * 8 + (text.charCodeAt(0) - 97);
  };

  class Game {
    constructor(board, white, castling, enPassant, half, full) {
      Object.assign(this, {board, white, castling, enPassant, half, full});
    }

    static start() {
      const board = new Array(64).fill(0);
      const back = [ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK];
      for (let file = 0; file < 8; file++) {
        board[file] = back[file]; board[8 + file] = PAWN; board[48 + file] = -PAWN; board[56 + file] = -back[file];
      }
      return new Game(board, true, 15, -1, 0, 1);
    }

    static replay(moves) {
      const game = Game.start();
      for (const text of moves) {
        const move = game.parse(text);
        if (!move || !game.legal().some((m) => m.from === move.from && m.to === move.to && m.promotion === move.promotion)) return null;
        game.play(move);
      }
      return game;
    }

    copy() { return new Game(this.board.slice(), this.white, this.castling, this.enPassant, this.half, this.full); }

    king(white) { return this.board.indexOf(white ? KING : -KING); }

    attacked(target, byWhite) {
      const s = byWhite ? 1 : -1;
      const file = target % 8, rank = Math.floor(target / 8);
      const at = (f, r) => (f >= 0 && f < 8 && r >= 0 && r < 8 ? this.board[r * 8 + f] : 0);
      if (at(file - 1, rank - s) === s * PAWN || at(file + 1, rank - s) === s * PAWN) return true;
      if (KNIGHT_STEPS.some(([df, dr]) => at(file + df, rank + dr) === s * KNIGHT)) return true;
      if (KING_STEPS.some(([df, dr]) => at(file + df, rank + dr) === s * KING)) return true;
      const slides = (directions, kind) => directions.some(([df, dr]) => {
        let f = file + df, r = rank + dr;
        while (f >= 0 && f < 8 && r >= 0 && r < 8) {
          const piece = this.board[r * 8 + f];
          if (piece) return piece === s * kind || piece === s * QUEEN;
          f += df; r += dr;
        }
        return false;
      });
      return slides(STRAIGHT, ROOK) || slides(DIAGONAL, BISHOP);
    }

    check() { const k = this.king(this.white); return k >= 0 && this.attacked(k, !this.white); }

    pseudo() {
      const moves = [];
      const s = this.white ? 1 : -1;
      for (let from = 0; from < 64; from++) {
        const piece = this.board[from];
        if (sign(piece) !== s) continue;
        const file = from % 8, rank = Math.floor(from / 8);
        const add = (to) => {
          if (Math.abs(piece) === PAWN && (Math.floor(to / 8) === 7 || Math.floor(to / 8) === 0)) {
            for (const promotion of [QUEEN, ROOK, BISHOP, KNIGHT]) moves.push({from, to, promotion});
          } else moves.push({from, to, promotion: 0});
        };
        const kind = Math.abs(piece);
        if (kind === PAWN) {
          const ahead = from + 8 * s;
          if (this.board[ahead] === 0) {
            add(ahead);
            if (rank === (s > 0 ? 1 : 6) && this.board[ahead + 8 * s] === 0) moves.push({from, to: ahead + 8 * s, promotion: 0});
          }
          for (const df of [-1, 1]) {
            const f = file + df, r = rank + s;
            if (f < 0 || f > 7 || r < 0 || r > 7) continue;
            const to = r * 8 + f;
            if (sign(this.board[to]) === -s || to === this.enPassant) add(to);
          }
        } else if (kind === KNIGHT || kind === KING) {
          for (const [df, dr] of kind === KNIGHT ? KNIGHT_STEPS : KING_STEPS) {
            const f = file + df, r = rank + dr;
            if (f >= 0 && f < 8 && r >= 0 && r < 8 && sign(this.board[r * 8 + f]) !== s) moves.push({from, to: r * 8 + f, promotion: 0});
          }
          if (kind === KING) this.castles(from, s, moves);
        } else {
          const directions = kind === BISHOP ? DIAGONAL : kind === ROOK ? STRAIGHT : STRAIGHT.concat(DIAGONAL);
          for (const [df, dr] of directions) {
            let f = file + df, r = rank + dr;
            while (f >= 0 && f < 8 && r >= 0 && r < 8) {
              const target = this.board[r * 8 + f];
              if (sign(target) === s) break;
              moves.push({from, to: r * 8 + f, promotion: 0});
              if (target) break;
              f += df; r += dr;
            }
          }
        }
      }
      return moves;
    }

    castles(from, s, moves) {
      const home = s > 0 ? 4 : 60;
      if (from !== home || this.attacked(home, s < 0)) return;
      const b = this.board;
      if (this.castling & (s > 0 ? 1 : 4) && !b[home + 1] && !b[home + 2] && b[home + 3] === s * ROOK
        && !this.attacked(home + 1, s < 0) && !this.attacked(home + 2, s < 0)) moves.push({from: home, to: home + 2, promotion: 0});
      if (this.castling & (s > 0 ? 2 : 8) && !b[home - 1] && !b[home - 2] && !b[home - 3] && b[home - 4] === s * ROOK
        && !this.attacked(home - 1, s < 0) && !this.attacked(home - 2, s < 0)) moves.push({from: home, to: home - 2, promotion: 0});
    }

    legal() {
      return this.pseudo().filter((move) => {
        const next = this.copy();
        next.play(move);
        const k = next.king(this.white);
        return k >= 0 && !next.attacked(k, !this.white);
      });
    }

    play(move) {
      const b = this.board;
      const piece = b[move.from], s = sign(piece), captured = b[move.to];
      if (Math.abs(piece) === PAWN && move.to === this.enPassant && !captured) b[move.to - 8 * s] = 0;
      b[move.to] = move.promotion ? s * move.promotion : piece;
      b[move.from] = 0;
      if (Math.abs(piece) === KING && Math.abs(move.to - move.from) === 2) {
        if (move.to > move.from) { b[move.from + 1] = b[move.from + 3]; b[move.from + 3] = 0; }
        else { b[move.from - 1] = b[move.from - 4]; b[move.from - 4] = 0; }
      }
      if (Math.abs(piece) === KING) this.castling &= s > 0 ? ~3 : ~12;
      for (const corner of [move.from, move.to]) this.castling &= {0: ~2, 7: ~1, 56: ~8, 63: ~4}[corner] ?? 15;
      this.enPassant = Math.abs(piece) === PAWN && Math.abs(move.to - move.from) === 16 ? (move.from + move.to) / 2 : -1;
      this.half = Math.abs(piece) === PAWN || captured ? 0 : this.half + 1;
      if (!this.white) this.full++;
      this.white = !this.white;
    }

    parse(text) {
      if (!/^[a-h][1-8][a-h][1-8][qrbn]?$/.test(text)) return null;
      return {from: square(text.slice(0, 2)), to: square(text.slice(2, 4)), promotion: ' pnbrqk'.indexOf(text[4] || ' ') > 0 ? ' pnbrqk'.indexOf(text[4]) : 0};
    }

    uci(move) { return name(move.from) + name(move.to) + (move.promotion ? ' pnbrqk'[move.promotion] : ''); }

    san(move) {
      const piece = Math.abs(this.board[move.from]);
      const capture = this.board[move.to] !== 0 || (piece === PAWN && move.to === this.enPassant);
      let text = '';
      if (piece === KING && Math.abs(move.to - move.from) === 2) text = move.to > move.from ? 'O-O' : 'O-O-O';
      else {
        if (piece === PAWN) { if (capture) text += 'abcdefgh'[move.from % 8]; }
        else {
          text += ' PNBRQK'[piece];
          const rivals = this.legal().filter((m) => m.to === move.to && m.from !== move.from && Math.abs(this.board[m.from]) === piece);
          if (rivals.length) {
            if (!rivals.some((m) => m.from % 8 === move.from % 8)) text += 'abcdefgh'[move.from % 8];
            else if (!rivals.some((m) => Math.floor(m.from / 8) === Math.floor(move.from / 8))) text += Math.floor(move.from / 8) + 1;
            else text += name(move.from);
          }
        }
        if (capture) text += 'x';
        text += name(move.to);
        if (move.promotion) text += '=' + ' PNBRQK'[move.promotion];
      }
      const after = this.copy();
      after.play(move);
      if (after.check()) text += after.legal().length ? '+' : '#';
      return text;
    }

    outcome() {
      if (this.legal().length) {
        const pieces = this.board.filter((p) => p && Math.abs(p) !== KING);
        const bare = !pieces.length || (pieces.length === 1 && [KNIGHT, BISHOP].includes(Math.abs(pieces[0])));
        return this.half >= 100 || bare ? 'draw' : 'playing';
      }
      return this.check() ? 'checkmate' : 'stalemate';
    }

    fen() {
      const rows = [];
      for (let rank = 7; rank >= 0; rank--) {
        let row = '', empty = 0;
        for (let file = 0; file < 8; file++) {
          const piece = this.board[rank * 8 + file];
          if (!piece) { empty++; continue; }
          if (empty) { row += empty; empty = 0; }
          const letter = ' pnbrqk'[Math.abs(piece)];
          row += piece > 0 ? letter.toUpperCase() : letter;
        }
        rows.push(row + (empty || ''));
      }
      const rights = (this.castling & 1 ? 'K' : '') + (this.castling & 2 ? 'Q' : '') + (this.castling & 4 ? 'k' : '') + (this.castling & 8 ? 'q' : '');
      return rows.join('/') + ' ' + (this.white ? 'w' : 'b') + ' ' + (rights || '-') + ' '
        + (this.enPassant >= 0 ? name(this.enPassant) : '-') + ' ' + this.half + ' ' + this.full;
    }
  }

  return {Game, PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING, name, square};
})();
if (typeof module !== 'undefined') module.exports = FluidChess;
