"""Slash commands typed in a chat's message box, for IRC, Matrix, and XMPP.

The same table is in the phone (ChatCommands.kt) and the browser (chat-commands.js), so all three accept the same
commands and give the same errors. Commands never reach SMS or email as text: there, a line that starts with a known
command asks you to type // to send it as written. Unknown commands get a friendly error instead of being sent.
"""

CHAT_PROTOCOLS = ("irc", "matrix", "xmpp")

# name: (usage, what it does, protocols)
COMMANDS = {
    "me": ("/me waves", "Send an action, shown as “* you waves”", ("irc", "matrix", "xmpp")),
    "msg": ("/msg nick message", "Open a direct chat and send a message", ("irc", "matrix", "xmpp")),
    "query": ("/query nick", "Open a direct chat", ("irc", "matrix", "xmpp")),
    "join": ("/join #channel", "Join a channel or room", ("irc", "matrix", "xmpp")),
    "part": ("/part [reason]", "Leave this channel or room", ("irc", "matrix", "xmpp")),
    "nick": ("/nick newname", "Change your nickname (display name on Matrix)", ("irc", "matrix", "xmpp")),
    "topic": ("/topic [new topic]", "Show or change the topic", ("irc", "matrix", "xmpp")),
    "invite": ("/invite nick [#channel]", "Invite someone here", ("irc", "matrix", "xmpp")),
    "whois": ("/whois nick", "Look someone up", ("irc",)),
    "away": ("/away [message]", "Set or clear your away message", ("irc",)),
    "kick": ("/kick nick [reason]", "Remove someone (needs operator rights)", ("irc",)),
    "mode": ("/mode [target] +o nick", "Change channel or user modes", ("irc",)),
    "notice": ("/notice target message", "Send a notice", ("irc",)),
    "quote": ("/quote RAW LINE", "Send a raw IRC line (advanced)", ("irc",)),
    "help": ("/help", "List the commands", ("irc", "matrix", "xmpp")),
}
ALIASES = {"leave": "part", "raw": "quote", "j": "join", "q": "query", "back": "away", "action": "me"}
# Commands that need words after them.
NEEDS = {"me": 1, "msg": 2, "query": 1, "join": 1, "nick": 1, "invite": 1, "whois": 1, "kick": 1, "mode": 1,
         "notice": 2, "quote": 1}


class CommandError(ValueError):
    pass


def literal(text):
    """'//' at the start sends the rest as written: "//shrug" sends "/shrug"."""
    return isinstance(text, str) and text.startswith("//")


def looks_like_command(text):
    return isinstance(text, str) and text.startswith("/") and not literal(text) and len(text) > 1 \
        and not text[1].isspace() and "\n" not in text.split(" ", 1)[0]


def known(name):
    name = name.lower()
    name = ALIASES.get(name, name)
    return name if name in COMMANDS else None


def available(protocol):
    return [name for name, (_, _, protocols) in COMMANDS.items() if protocol in protocols]


def help_text(protocol):
    lines = [COMMANDS[name][0] + " — " + COMMANDS[name][1] for name in available(protocol)]
    return "Commands: " + "; ".join(lines) + ". Start with // to send a line that begins with /."


def parse(protocol, text):
    """Returns {"name", "args", "argv"} for a command line, or None for ordinary text. Raises CommandError for an
    unknown command, a command this service doesn't have, missing words, or any command outside IRC, Matrix, XMPP."""
    if not looks_like_command(text):
        return None
    word, _, rest = text[1:].partition(" ")
    name = known(word)
    if protocol not in CHAT_PROTOCOLS:
        if name is None:
            return None  # "/s" or "/shrug" in a text message is just text.
        raise CommandError("Commands work in IRC, Matrix, and XMPP chats. Start with // to send “" + text[:40] + "” as text.")
    if name is None:
        raise CommandError("Unknown command /" + word[:30] + ". Type /help for the list, or start with // to send it as text.")
    if protocol not in COMMANDS[name][2]:
        raise CommandError("/" + name + " isn’t available on " + {"irc": "IRC", "matrix": "Matrix", "xmpp": "XMPP"}[protocol] + ".")
    rest = rest.strip()
    if "\r" in rest or "\n" in rest:
        raise CommandError("Commands fit on one line")
    argv = rest.split()
    if len(argv) < NEEDS.get(name, 0):
        raise CommandError("Usage: " + COMMANDS[name][0])
    if word.lower() == "back":
        rest, argv = "", []
    return {"name": name, "args": rest, "argv": argv}
