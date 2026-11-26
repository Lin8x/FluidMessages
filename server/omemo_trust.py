"""OMEMO rules that need no cryptography library: trust, fingerprints, xmpp: links, and the lock color.

Trust follows "blind trust before verification" (BTBV), as Conversations does: while you haven't verified any
device of a contact, their new devices are trusted on sight. Once you verify one, later new devices wait for your
OK before messages are locked for them. Your own other devices follow the same rule.

The phone has the same rules in OmemoRules.kt; keep the two in step.
"""
from urllib.parse import quote, unquote

# Trust names, as stored per identity key.
UNDECIDED = "undecided"    # Seen but not decided yet; BTBV decides on first use.
BLIND = "blind"            # Trusted on sight (no device of this contact was verified then).
VERIFIED = "verified"      # You compared the fingerprint and marked it verified.
UNCONFIRMED = "unconfirmed"  # A new device that appeared after you verified one: waits for your OK.
DISTRUSTED = "distrusted"  # You turned it off: messages aren't locked for it, and its messages are marked.
NAMES = (UNDECIDED, BLIND, VERIFIED, UNCONFIRMED, DISTRUSTED)


def first_use(names_of_contact):
    """The trust a newly seen device gets, given the trust names of that contact's other devices."""
    return UNCONFIRMED if VERIFIED in set(names_of_contact) else BLIND


def trusted(name):
    """Messages are locked for these devices."""
    return name in (BLIND, VERIFIED)


def fingerprint_groups(hex_key):
    """A 32-byte key as 8 groups of 8 hex characters, the way other OMEMO apps show it."""
    text = "".join(ch for ch in str(hex_key).lower() if ch in "0123456789abcdef")
    if len(text) == 66 and text.startswith("05"):
        text = text[2:]  # The Curve25519 type byte isn't part of the fingerprint.
    return [text[index:index + 8] for index in range(0, len(text), 8)]


def fingerprint_text(hex_key):
    return " ".join(fingerprint_groups(hex_key))


def same_fingerprint(a, b):
    return "".join(fingerprint_groups(a)) == "".join(fingerprint_groups(b)) != ""


def xmpp_uri(jid, devices):
    """xmpp:user@host?omemo-sid-<id>=<fingerprint>;… (the format Conversations shows as a QR code).
    devices: [(device_id, hex fingerprint)]."""
    parts = ["omemo-sid-%d=%s" % (int(device_id), "".join(fingerprint_groups(fingerprint)))
             for device_id, fingerprint in devices]
    return "xmpp:" + quote(jid, safe="@.-_") + ("?" + ";".join(parts) if parts else "")


def parse_xmpp_uri(uri):
    """Returns (bare JID, {device id: fingerprint}) from an xmpp: link, or None when it isn't one.
    Accepts both ; and & between parts, and an action such as ?message;omemo-sid-…"""
    text = str(uri or "").strip()
    if not text.lower().startswith("xmpp:"):
        return None
    rest = text[5:].lstrip("/")
    jid, _, query = rest.partition("?")
    jid = unquote(jid).split("/", 1)[0].strip().lower()
    if "@" not in jid or " " in jid:
        return None
    devices = {}
    for part in query.replace("&", ";").split(";"):
        key, _, value = part.partition("=")
        if key.lower().startswith("omemo-sid-"):
            try:
                device_id = int(key[10:])
            except ValueError:
                continue
            fingerprint = "".join(fingerprint_groups(value))
            if 1 <= device_id <= 2 ** 31 - 1 and len(fingerprint) == 64:
                devices[device_id] = fingerprint
    return jid, devices


def verify_from_uri(uri, jid, known):
    """Which devices to mark verified after scanning [uri] for [jid]. known: {device id: fingerprint} for that
    contact. Returns (device ids to verify, device ids whose fingerprint doesn't match)."""
    parsed = parse_xmpp_uri(uri)
    if not parsed or parsed[0] != str(jid).lower():
        return [], []
    matches, mismatches = [], []
    for device_id, fingerprint in parsed[1].items():
        if device_id in known:
            (matches if same_fingerprint(known[device_id], fingerprint) else mismatches).append(device_id)
    return sorted(matches), sorted(mismatches)


def lock(state):
    """The lock for an XMPP chat from its OMEMO state:
    {"available": OMEMO works on this side, "devices": number of the contact's devices with OMEMO,
     "trusted": how many of them messages are locked for, "failed": devices the last message couldn't reach,
     "plain": the last message went without OMEMO, "group": a group chat, "group_ok": members-only and not anonymous}.
    Returns (color, words)."""
    state = state or {}
    if not state.get("available"):
        return "yellow", "Encrypted in transit. OMEMO isn't set up here, so your XMPP server can read it."
    if state.get("group") and not state.get("group_ok"):
        return "yellow", "Encrypted in transit. This group isn't locked: OMEMO needs a members-only group where members can see each other's addresses."
    if not state.get("devices"):
        return "yellow", "Encrypted in transit. They don't use OMEMO yet, so your XMPP server can read it."
    if state.get("plain"):
        return "yellow", "Your last message wasn't locked with OMEMO."
    if not state.get("trusted"):
        return "yellow", "Encrypted in transit. None of their devices is trusted yet; check them in Security."
    if state.get("failed"):
        return "yellow", "Locked with OMEMO, but it couldn't reach every trusted device."
    return "green", "Locked with OMEMO: only you and the people you're talking to can read it."
