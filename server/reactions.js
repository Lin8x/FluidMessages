'use strict';
// Reactions that arrive as a text ("Liked “See you at 7”"), the way iPhones and other Android apps send them when
// the other side has no native reactions. The phone reads them with the same rules (MessageInteractions.kt), and
// both sides run the cases in app/src/test/resources/reaction-vectors.json, so a synced chat folds the same way.

// One entry per language. English is the only one so far: add a language by adding its own entry here and in
// REACTION_LANGUAGES on the phone, with wording taken from real messages.
const REACTION_WORDS = [{
  verbs: [['Liked', '👍'], ['Loved', '❤️'], ['Disliked', '👎'], ['Laughed at', '😆'], ['Emphasized', '‼️'], ['Questioned', '❓']],
  reacted: ['Reacted ', ' to '],
  removed: ['Removed ', ' from '],
  nouns: {'a like': '👍', 'a heart': '❤️', 'a dislike': '👎', 'a laugh': '😆', 'an exclamation': '‼️', 'a question mark': '❓'},
  // A reaction to a message without words names what it was. The first phrase of a kind is the one sent.
  media: [['an image', 'photo'], ['a photo', 'photo'], ['a picture', 'photo'], ['a video', 'video'], ['a movie', 'video'],
    ['an audio message', 'audio'], ['a voice message', 'audio'], ['an attachment', 'any'], ['a file', 'any']],
}];
const REACTION_OWN = ' (my message)';
/** What a message without words says in place of them (the phone's attachment labels and the MMS placeholders). */
const REACTION_WORDLESS = ['Photo', 'Video', 'Audio', 'Voice message', 'File', 'Multimedia message'];

const reactionTrim = (text) => text.replace(/^[ \t\r\n]+|[ \t\r\n]+$/g, '');

function reactionExcerpt(value, limit) {
  const chars = Array.from(value);
  return chars.length > limit ? chars.slice(0, limit).join('') + '…' : value;
}

/** One emoji, by its characters alone, so the phone and the browser always agree: pictographs and their joiners. */
function reactionEmoji(value) {
  if (typeof value !== 'string' || !value || value.length > 64) return false;
  let picture = false;
  for (const char of value) {
    const code = char.codePointAt(0);
    if ((code >= 0x1F000 && code <= 0x1FAFF) || (code >= 0x2190 && code <= 0x21FF) || (code >= 0x2300 && code <= 0x23FF)
      || (code >= 0x2460 && code <= 0x24FF) || (code >= 0x25A0 && code <= 0x27BF) || (code >= 0x2900 && code <= 0x297F)
      || (code >= 0x2B00 && code <= 0x2BFF) || [0xA9, 0xAE, 0x203C, 0x2049, 0x2122, 0x2139, 0x3030, 0x303D, 0x3297, 0x3299, 0x20E3].includes(code)) {
      picture = true;
    } else if (!(code === 0x200D || code === 0xFE0F || code === 0xFE0E || (code >= 0xE0020 && code <= 0xE007F)
      || (code >= 0x30 && code <= 0x39) || code === 0x23 || code === 0x2A)) return false;
  }
  return picture;
}

const reactionQuoted = (text) => text.length >= 3 && '“"'.includes(text[0]) && '”"'.includes(text[text.length - 1]);

/**
 * Reads a reaction text: {change: 'add' | 'remove' | 'change', emoji, quote, media, own}, or null for any other text.
 * [quote] is the quoted words; without a quote, [media] is the kind of message it names ('photo', 'video', 'audio', 'any').
 */
function parseReaction(body) {
  if (typeof body !== 'string') return null;
  let wire = reactionTrim(body);
  const own = wire.endsWith(REACTION_OWN);
  if (own) wire = wire.slice(0, -REACTION_OWN.length);
  for (const words of REACTION_WORDS) {
    const target = (rest, change, emoji) => {
      if (reactionQuoted(rest)) return {change, emoji, quote: rest.slice(1, -1), media: null, own};
      const named = words.media.find(([phrase]) => phrase === rest);
      return named ? {change, emoji, quote: null, media: named[1], own} : null;
    };
    for (const [verb, emoji] of words.verbs) {
      const found = wire.startsWith(verb + ' ') && target(wire.slice(verb.length + 1), 'add', emoji);
      if (found) return found;
    }
    for (const [[start, middle], change] of [[words.reacted, 'add'], [words.removed, 'remove']]) {
      if (!wire.startsWith(start)) continue;
      const rest = wire.slice(start.length), at = rest.indexOf(middle);
      if (at < 1) continue;
      const what = rest.slice(0, at);
      const emoji = (change === 'remove' && words.nouns[what]) || (reactionEmoji(what) ? what : null);
      const found = emoji && target(rest.slice(at + middle.length), change, emoji);
      if (found) return found;
    }
  }
  // FluidMessages' own wording for a changed reaction, still sent to chats that aren't texts.
  const changed = /^Changed reaction from (\S{1,64}) to (\S{1,64}) on ([\s\S]+)$/.exec(wire);
  if (changed && reactionEmoji(changed[2]) && reactionQuoted(changed[3])) {
    return {change: 'change', emoji: changed[2], quote: changed[3].slice(1, -1), media: null, own};
  }
  return null;
}

/** Curly and straight quotes and apostrophes, line endings, and no-break spaces compare as the same. */
const reactionPlain = (text) => reactionTrim(String(text || '').replace(/\r\n/g, '\n').replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/ /g, ' '));

/** Whether [quote] is this message: all of it, its first 700 characters, or, ending in …, 20 or more of its first characters. */
function reactionQuotes(text, quote) {
  if (typeof text !== 'string') return false;
  const whole = reactionPlain(text), quoted = reactionPlain(quote);
  if (whole === quoted || reactionPlain(reactionExcerpt(text, 700)) === quoted) return true;
  const cut = quoted.endsWith('…') ? 1 : quoted.endsWith('...') ? 3 : 0;
  const prefix = cut ? quoted.slice(0, -cut).replace(/[ \t\r\n]+$/, '') : '';
  return prefix.length >= 20 && whole.startsWith(prefix);
}

/** 'photo', 'video', 'audio', or 'file' for a message with a picture or a file; null for words alone. */
function reactionMedia(message) {
  const file = message.attachment;
  if (file) {
    const kind = String(file.kind || '').toLowerCase(), mime = String(file.mime || '').toLowerCase();
    if (kind === 'photo' || mime.startsWith('image/')) return 'photo';
    if (kind === 'video' || mime.startsWith('video/')) return 'video';
    if (kind === 'audio' || kind === 'voice' || mime.startsWith('audio/')) return 'audio';
    return 'file';
  }
  return message.photo ? 'photo' : null;
}

/** The kind of a picture or file sent without words, which a reaction names ("an image") instead of quoting. */
function reactionWordless(message) {
  const kind = reactionMedia(message), body = reactionTrim(message.body || '');
  const wordless = !body || body === (message.attachment?.name || '') || REACTION_WORDLESS.includes(body) || /^sticker\.\w+$/.test(body);
  return kind && wordless ? kind : null;
}

/** The words a reaction to [message] names it by: its text in quotes, or "an image" for a picture without words. */
function reactionTargetWords(message) {
  const kind = reactionWordless(message);
  const named = kind && REACTION_WORDS[0].media.find(([, covers]) => covers === (kind === 'file' ? 'any' : kind));
  return named ? named[0] : '“' + reactionExcerpt(message.body || '', 700) + '”';
}

/**
 * The message a reaction text is about: the most recent one in [shown] (oldest first) that it quotes or names.
 * People react to what others sent, so those are tried first; then anyone's, for someone reacting to their own.
 * "(my message)" from FluidMessages means the sender's own. [sender] gives a message's sender key.
 */
function reactionTarget(parsed, reactor, shown, sender) {
  const fits = (row) => !row.event && (parsed.quote !== null ? reactionQuotes(row.body, parsed.quote)
    : !!reactionMedia(row) && (parsed.media === 'any' || parsed.media === reactionMedia(row)));
  let fallback = null;
  for (let index = shown.length - 1; index >= 0; index--) {
    const row = shown[index];
    if (!fits(row)) continue;
    if ((sender(row) === reactor) === parsed.own) return row;
    if (!parsed.own && !fallback) fallback = row;
  }
  return fallback;
}

/** A reaction text as a sentence about [who], for notifications: "Sam loved “See you at 7”". Null for other texts. */
function reactionNotice(who, body) {
  if (!who || !parseReaction(body)) return null;
  let wire = reactionTrim(body);
  if (wire.endsWith(REACTION_OWN)) wire = wire.slice(0, -REACTION_OWN.length);
  return who + ' ' + wire[0].toLowerCase() + wire.slice(1);
}

if (typeof module !== 'undefined') {
  module.exports = {parseReaction, reactionEmoji, reactionQuotes, reactionMedia, reactionWordless, reactionTargetWords, reactionTarget, reactionNotice};
}
