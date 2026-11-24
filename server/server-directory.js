'use strict';
// Well-known public servers offered in Add server, one list per service. Keep it in step with the phone's
// ServerDirectory.kt. Only add servers that are public and long-running. Picking one only fills in the sign-in form;
// nothing connects until Save. IRC entries are TLS hosts; FluidMessages never connects to IRC without TLS.

const SERVER_DIRECTORY = {
  irc: [
    {name: 'Libera.Chat', host: 'irc.libera.chat', port: 6697, tls: true, about: 'Free and open-source software projects and their communities'},
    {name: 'OFTC', host: 'irc.oftc.net', port: 6697, tls: true, about: 'Debian, Tor, and other open-source projects'},
    {name: 'EFnet', host: 'irc.efnet.org', port: 6697, tls: true, about: 'One of the oldest IRC networks, with general chat of every kind'},
    {name: 'IRCnet', host: 'ssl.ircnet.ovh', port: 6697, tls: true, about: 'A long-running network, popular in Europe'},
    {name: 'Undernet', host: 'irc.undernet.org', port: 6697, tls: true, about: 'A large general-purpose network with help and social channels'},
    {name: 'DALnet', host: 'irc.dal.net', port: 6697, tls: true, about: 'Social chat, games, and help channels'},
    {name: 'QuakeNet', host: 'irc.quakenet.org', port: 6667, tls: false, about: 'Gaming communities and esports'},
    {name: 'Rizon', host: 'irc.rizon.net', port: 6697, tls: true, about: 'Anime, gaming, and file-sharing communities'},
    {name: 'hackint', host: 'irc.hackint.org', port: 6697, tls: true, about: 'Hacker spaces and security communities'},
    {name: 'Snoonet', host: 'irc.snoonet.org', port: 6697, tls: true, about: 'Chat rooms for online communities and forums'},
    {name: 'GIMPNet', host: 'irc.gimp.org', port: 6697, tls: true, about: 'GIMP, GNOME, and related desktop projects'},
    {name: 'EsperNet', host: 'irc.esper.net', port: 6697, tls: true, about: 'Minecraft and other gaming and open-source projects'},
    {name: 'tilde.chat', host: 'irc.tilde.chat', port: 6697, tls: true, about: 'Small shared-computer (tilde) communities'},
  ],
  matrix: [
    {name: 'matrix.org', homeserver: 'https://matrix-client.matrix.org', about: 'The largest public homeserver, run by the Matrix.org Foundation'},
    {name: 'envs.net', homeserver: 'https://matrix.envs.net', about: 'A community homeserver for the envs.net shared-computer community'},
    {name: 'tchncs.de', homeserver: 'https://matrix.tchncs.de', about: 'A long-running free homeserver from Germany, open to everyone'},
    {name: 'mozilla.org', homeserver: 'https://mozilla.modular.im', about: 'Mozilla’s community homeserver. Sign in with a Mozilla account; its public rooms are open to everyone'},
    {name: 'catgirl.cloud', homeserver: 'https://matrix.catgirl.cloud', about: 'A free community homeserver with open sign-up'},
    {name: 'g24.at', homeserver: 'https://matrix.g24.at', about: 'A long-running free homeserver from Austria'},
    {name: 'nope.chat', homeserver: 'https://nope.chat', about: 'A free homeserver with open sign-up'},
    {name: 'gnulinux.club', homeserver: 'https://matrix.gnulinux.club', about: 'A free homeserver for Linux and free-software users'},
  ],
  xmpp: [
    {name: 'jabber.org', domain: 'jabber.org', about: 'The original XMPP service, running since 1999. Free sign-up'},
    {name: 'conversations.im', domain: 'conversations.im', about: 'From the makers of the Conversations app. Account creation is paid after a free trial'},
    {name: 'yax.im', domain: 'yax.im', about: 'A free service you can sign up for from any XMPP app'},
    {name: 'xmpp.jp', domain: 'xmpp.jp', about: 'A free service from Japan, open to everyone'},
    {name: 'jabber.fr', domain: 'jabber.fr', about: 'A free French-language community service'},
    {name: 'chapril.org', domain: 'chapril.org', about: 'A free service run by the April free-software association'},
    {name: 'nixnet.services', domain: 'nixnet.services', about: 'A free privacy-focused service'},
    {name: 'magicbroccoli.de', domain: 'magicbroccoli.de', about: 'A free community service from Germany'},
  ],
};

/** The address a directory entry fills in: the IRC host, the Matrix homeserver, or the XMPP domain. */
function directoryAddress(entry) {
  return entry.host || entry.homeserver || entry.domain || '';
}

/** Entries whose name, address, or description contain every word of [query], case and accents ignored. */
function directorySearch(list, query) {
  const fold = (text) => String(text || '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const words = fold(query).split(/\s+/).filter(Boolean);
  if (!words.length) return list.slice();
  return list.filter((entry) => {
    const text = fold(entry.name + ' ' + directoryAddress(entry) + ' ' + entry.about);
    return words.every((word) => text.includes(word));
  });
}

/** Rows found on a server (channels or rooms) matching every word of [query], for filtering as you type. */
function roomSearch(rooms, query) {
  const fold = (text) => String(text || '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const words = fold(query).split(/\s+/).filter(Boolean);
  if (!words.length) return rooms.slice();
  return rooms.filter((room) => {
    const text = fold([room.name, room.target, room.alias, room.topic].join(' '));
    return words.every((word) => text.includes(word));
  });
}

if (typeof module !== 'undefined') module.exports = {SERVER_DIRECTORY, directorySearch, directoryAddress, roomSearch};
