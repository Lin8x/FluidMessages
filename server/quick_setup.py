"""Only send encrypted messages (Settings → Privacy and security, and Quick setup in quick-setup.js).

Off unless turned on. While it's on, FluidMessages Web refuses to send in a chat that can't be end-to-end encrypted
right now, with the same words the composer shows (quick-setup.js quickRefusal). Receiving and reading never change.
Texts from the browser are never encrypted (the FluidMessages key stays on the phone), so they wait for the phone.
"""

PREF = "encryptedOnly"
START = "Only send encrypted messages is on. "
REASONS = {
    "irc": "IRC can’t encrypt messages, so sending here is off.",
    "email": "Email isn’t encrypted end to end, so sending here is off.",
    "matrix": "This Matrix room isn’t encrypted, so sending here is off. A room admin can turn on encryption.",
    "xmpp": "This XMPP chat isn’t encrypted with OMEMO right now, so sending here is off. Check OMEMO in this chat’s details.",
}
TEXTS = ("Texts sent from FluidMessages Web aren’t encrypted, so sending here is off. Send it from your phone with "
         "FluidMessages encryption on.")


def refusal(on, protocol, encrypted):
    """Why sending is off, or "" when it may send."""
    if not on or encrypted:
        return ""
    return START + REASONS.get(protocol, TEXTS)


def check(prefs, protocol, encrypted):
    """Raises ValueError with the reason when [prefs] has the switch on and this chat isn't encrypted."""
    reason = refusal(prefs.get(PREF) is True, protocol, encrypted)
    if reason:
        raise ValueError(reason)
