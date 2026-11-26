"""OMEMO for FluidMessages Web's XMPP connection: this install becomes one OMEMO device of your XMPP account.

It uses python-omemo (MIT) for device lists, sessions, prekey refills, and heartbeats, with our own legacy OMEMO
backend (omemo_legacy.py, written from the public specs), so chats work with Conversations, Monocles, Gajim, Dino,
Siskin, Monal, and other apps that speak eu.siacs.conversations.axolotl. Trust is "blind trust before
verification" (omemo_trust.py).

Keys and sessions live in the data volume, in a file encrypted with AES-256-GCM under a random key kept in a
private file next to it (mode 600), the way the encrypted Matrix store keeps its pickle key. They are never put in
backups: a restored or new install creates a new OMEMO device, as other apps do. Nothing here logs keys or text.
"""
import asyncio
import base64
import contextvars
import json
import logging
import os
from pathlib import Path
import secrets
import time

try:
    import omemo
    from omemo.storage import Just, Nothing
    import omemo_legacy as legacy
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # The companion runs without OMEMO; XMPP chats then show as encrypted in transit only.
    omemo = None

import omemo_trust as rules

# python-omemo's own log lines name accounts and devices; keep them out of the companion's output.
logging.getLogger("omemo").setLevel(logging.CRITICAL + 1)

# True while a message is being encrypted: devices that aren't trusted are then left out (see trust_level).
_SENDING = contextvars.ContextVar("omemo_sending", default=False)
DEVICE_LIST_TTL = 10 * 60  # Seconds between device list checks for a contact before sending.


def available():
    return omemo is not None


class OmemoUnavailable(Exception):
    """The contact has no OMEMO device, so a message can only go without OMEMO."""


class OmemoNotTrusted(Exception):
    """The contact has OMEMO devices, but none is trusted yet. Nothing is sent without OMEMO in that case."""


if omemo is not None:
    class MemoryStorage(omemo.Storage):
        """Keeps everything in a dict: for tests, and as the in-memory side of EncryptedFileStorage."""
        def __init__(self):
            super().__init__(disable_cache=True)
            self.data = {}

        async def _load(self, key):
            return Just(self.data[key]) if key in self.data else Nothing()

        async def _store(self, key, value):
            self.data[key] = value
            self.changed()

        async def _delete(self, key):
            if self.data.pop(key, None) is not None:
                self.changed()

        def changed(self):
            pass

    class EncryptedFileStorage(MemoryStorage):
        """The store as one JSON document, encrypted with AES-256-GCM. Written before anything is sent, so a
        ratchet step is never reused after a restart."""
        def __init__(self, directory):
            super().__init__()
            self.directory = Path(directory)
            self.directory.mkdir(parents=True, exist_ok=True)
            os.chmod(self.directory, 0o700)
            self.key = self.load_key()
            self.path = self.directory / "omemo.json.enc"
            if self.path.is_file():
                blob = self.path.read_bytes()
                self.data = json.loads(AESGCM(self.key).decrypt(blob[:12], blob[12:], b"fluidmessages-omemo"))

        def load_key(self):
            path = self.directory / "omemo.key"
            if not path.is_file():
                descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "w") as output:
                    output.write(base64.b64encode(secrets.token_bytes(32)).decode())
            return base64.b64decode(path.read_text().strip())

        def changed(self):
            nonce = secrets.token_bytes(12)
            blob = nonce + AESGCM(self.key).encrypt(nonce, json.dumps(self.data, separators=(",", ":")).encode(),
                                                     b"fluidmessages-omemo")
            temporary = self.path.with_suffix(".tmp")
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(blob)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.path)

    class Manager(omemo.SessionManager):
        """python-omemo's session manager, with XMPP done through [network] (see Omemo below)."""
        network = None
        backend = None
        on_trust = None

        async def _upload_bundle(self, bundle):
            try:
                await self.network.publish(legacy.bundle_node(bundle.device_id), legacy.serialize_bundle(bundle))
            except Exception as error:
                raise omemo.BundleUploadFailed() from error

        async def _download_bundle(self, namespace, bare_jid, device_id):
            try:
                element = await self.network.fetch(bare_jid, legacy.bundle_node(device_id))
            except Exception as error:
                raise omemo.BundleDownloadFailed() from error
            if element is None:
                raise omemo.BundleNotFound()
            try:
                return legacy.parse_bundle(element, bare_jid, device_id)
            except (ValueError, TypeError) as error:
                raise omemo.BundleDownloadFailed() from error

        async def _delete_bundle(self, namespace, device_id):
            try:
                await self.network.delete(legacy.bundle_node(device_id))
            except Exception as error:
                raise omemo.BundleDeletionFailed() from error

        async def _upload_device_list(self, namespace, device_list):
            try:
                await self.network.publish(legacy.DEVICE_LIST_NODE, legacy.serialize_device_list(device_list))
            except Exception as error:
                raise omemo.DeviceListUploadFailed() from error

        async def _download_device_list(self, namespace, bare_jid):
            try:
                element = await self.network.fetch(bare_jid, legacy.DEVICE_LIST_NODE)
            except Exception as error:
                raise omemo.DeviceListDownloadFailed() from error
            return {} if element is None else legacy.parse_device_list(element)

        async def _evaluate_custom_trust_level(self, device):
            name = device.trust_level_name
            if name not in rules.NAMES or name == rules.UNDECIDED:
                others = [other.trust_level_name for other in await self.get_device_information(device.bare_jid)
                          if other.device_id != device.device_id]
                name = rules.first_use(others)
                await self.set_trust(device.bare_jid, device.identity_key, name)
                if name == rules.UNCONFIRMED and self.on_trust:
                    self.on_trust(device.bare_jid, device.device_id)
            if rules.trusted(name):
                return omemo.TrustLevel.TRUSTED
            # Leave untrusted devices out of messages you send; still open what they send (and mark it).
            return omemo.TrustLevel.DISTRUSTED if _SENDING.get() else omemo.TrustLevel.UNDECIDED

        async def _make_trust_decision(self, undecided, identifier):
            return None  # Not reached: while sending, every device is trusted or left out.

        async def _send_message(self, message, bare_jid):
            try:
                await self.network.send(bare_jid, await legacy.serialize_message(message, self.backend))
            except Exception as error:
                raise omemo.MessageSendingFailed() from error


class Omemo:
    """One OMEMO device for [own_jid]. [network] provides, as coroutines:
    publish(node, element), fetch(bare_jid, node) → element or None, delete(node), and send(bare_jid, element)
    for key transport and heartbeat messages. [on_notice](kind, bare_jid, data) reports trust and key changes."""

    def __init__(self, own_jid, network, storage=None, directory=None, on_notice=None, max_skipped=1000):
        if not available():
            raise RuntimeError("Install omemo, doubleratchet, x3dh, and xeddsa on the companion to use OMEMO")
        self.own_jid = own_jid.lower()
        self.network = network
        self.storage = storage if storage is not None else EncryptedFileStorage(directory)
        self.on_notice = on_notice or (lambda *args: None)
        self.manager = None
        self.backend = None
        self.lock = asyncio.Lock()
        self.checked = {}  # bare JID → when its device list was last fetched
        # Skipped message keys kept per session, and accepted in one message: bounds memory use.
        self.max_skipped = max_skipped
        self.own_device = 0

    async def start(self):
        self.backend = legacy.LegacyOmemo(self.storage, self.max_skipped)
        notice = self.on_notice
        # A class per device, since python-omemo talks to the network while it creates the manager.
        bound = type("BoundManager", (Manager,), {
            "network": self.network, "backend": self.backend,
            "on_trust": staticmethod(lambda jid, device: notice("new_device", jid, {"device": device}))})
        self.manager = await bound.create([self.backend], self.storage, self.own_jid, None, rules.UNDECIDED,
                                          pre_key_refill_threshold=99)
        self.checked[self.own_jid] = time.time()
        self.own_device = (await self.storage.load_primitive("/own_device_id", int)).from_just()
        return self

    @property
    def device_id(self):
        return self.own_device

    def history_start(self):
        self.manager.before_history_sync()

    async def history_done(self):
        async with self.lock:
            await self.manager.after_history_sync()

    async def device_list(self, bare_jid, element):
        """A device list arrived by PEP notification."""
        async with self.lock:
            await self.manager.update_device_list(legacy.NAMESPACE, bare_jid.lower(), legacy.parse_device_list(element))
            self.checked[bare_jid.lower()] = time.time()

    async def refresh(self, bare_jid, force=False):
        bare_jid = bare_jid.lower()
        if force or time.time() - self.checked.get(bare_jid, 0) > DEVICE_LIST_TTL:
            try:
                await self.manager.refresh_device_list(legacy.NAMESPACE, bare_jid)
                self.checked[bare_jid] = time.time()
            except omemo.DeviceListDownloadFailed:
                pass

    async def can_lock(self, recipients):
        """Whether every recipient has at least one OMEMO device (a message to them can be locked)."""
        async with self.lock:
            for jid in recipients:
                await self.refresh(jid)
                if not any(dict(device.active).get(legacy.NAMESPACE)
                           for device in await self.manager.get_device_information(jid.lower())):
                    return False
            return bool(recipients)

    async def has_devices(self, bare_jid):
        async with self.lock:
            await self.refresh(bare_jid)
            return any(dict(device.active).get(legacy.NAMESPACE) for device in await self.manager.get_device_information(bare_jid.lower()))

    async def encrypt(self, recipients, text):
        """Returns (the <encrypted> element, report). report: {"devices", "trusted", "failed"} for the recipients.
        Raises OmemoUnavailable when a recipient has no OMEMO device, and OmemoNotTrusted when none is trusted."""
        recipients = frozenset(jid.lower() for jid in recipients)
        async with self.lock:
            for jid in recipients | {self.own_jid}:
                await self.refresh(jid)
            report = await self._report(recipients)
            if any(not report["per_jid"].get(jid) for jid in recipients):
                raise OmemoUnavailable()
            token = _SENDING.set(True)
            try:
                messages, errors = await self.manager.encrypt(recipients, {legacy.NAMESPACE: text.encode("utf-8")})
            except omemo.NoEligibleDevices as error:
                raise OmemoNotTrusted() from error
            finally:
                _SENDING.reset(token)
            message = next(iter(messages))
            report["failed"] = len([error for error in errors if error.bare_jid in recipients])
            return await legacy.serialize_message(message, self.backend), report

    async def _report(self, recipients):
        per_jid, devices, trusted = {}, 0, 0
        for jid in recipients:
            active = [device for device in await self.manager.get_device_information(jid)
                      if dict(device.active).get(legacy.NAMESPACE)]
            per_jid[jid] = len(active)
            devices += len(active)
            trusted += len([device for device in active if rules.trusted(device.trust_level_name)
                            or device.trust_level_name == rules.UNDECIDED and rules.first_use(
                                [other.trust_level_name for other in active]) == rules.BLIND])
        return {"devices": devices, "trusted": trusted, "failed": 0, "per_jid": per_jid}

    async def state(self, recipients):
        """The lock inputs for a chat (see omemo_trust.lock)."""
        async with self.lock:
            for jid in recipients:
                await self.refresh(jid)
            report = await self._report(frozenset(jid.lower() for jid in recipients))
            report.pop("per_jid")
            return {"available": True, **report}

    async def decrypt(self, sender_jid, element, archived=False):
        """Returns {"text": str or None (None for a key transport or heartbeat), "device": id, "trust": name,
        "error": "" or "not_for_us" / "failed" / "unknown_device" / "key_changed"}."""
        sender_jid = sender_jid.lower()
        async with self.lock:
            try:
                message = legacy.parse_message(element, sender_jid, self.own_jid, self.device_id)
            except (ValueError, TypeError):
                return {"text": None, "device": 0, "trust": "", "error": "failed"}
            if not message.keys:
                return {"text": None, "device": message.device_id, "trust": "", "error": "not_for_us"}
            for attempt in (0, 1):
                try:
                    plaintext, device, _ = await self.manager.decrypt(message)
                except omemo.PublicDataInconsistency:
                    if attempt or archived:
                        return {"text": None, "device": message.device_id, "trust": "", "error": "key_changed"}
                    await self._forget_identity(sender_jid, message.device_id)
                    continue
                except omemo.SenderNotFound:
                    return {"text": None, "device": message.device_id, "trust": "", "error": "unknown_device"}
                except (omemo.OMEMOException, ValueError, TypeError, KeyError):
                    return {"text": None, "device": message.device_id, "trust": "", "error": "failed"}
                text = None
                if plaintext is not None:
                    try:
                        text = plaintext.decode("utf-8")
                    except UnicodeDecodeError:
                        return {"text": None, "device": message.device_id, "trust": "", "error": "failed"}
                trust = next((other.trust_level_name for other in await self.manager.get_device_information(sender_jid)
                              if other.device_id == device.device_id), device.trust_level_name)
                return {"text": text, "device": device.device_id, "trust": trust, "error": "", "changed": bool(attempt)}

    async def _forget_identity(self, bare_jid, device_id):
        """A device sent a new key exchange with a different identity key: its key changed (for example after the
        app was reinstalled). Forget the old key, so the new one is fetched and decided on like a new device; a
        device you had verified waits for your OK again."""
        stored = (await self.storage.load_bytes(f"/devices/{bare_jid}/{device_id}/identity_key")).maybe(None)
        was = (await self.storage.load_primitive(
            f"/trust/{bare_jid}/{base64.urlsafe_b64encode(stored).decode('ascii')}", str)).maybe(None) if stored else None
        await self.storage.delete(f"/devices/{bare_jid}/{device_id}/identity_key")
        self.on_notice("key_changed", bare_jid, {"device": device_id})
        if was == rules.VERIFIED:
            for device in await self.manager.get_device_information(bare_jid):
                if device.device_id == device_id:
                    await self.manager.set_trust(bare_jid, device.identity_key, rules.UNCONFIRMED)

    async def devices(self, bare_jid):
        """The devices for a Security list: [{"id", "fingerprint" (groups of 8), "trust", "own", "active"}].
        For your own JID this includes this device first."""
        bare_jid = bare_jid.lower()
        async with self.lock:
            await self.refresh(bare_jid)
            output = []
            for device in sorted(await self.manager.get_device_information(bare_jid), key=lambda d: d.device_id):
                this = bare_jid == self.own_jid and device.device_id == self.device_id
                output.append({"id": device.device_id, "this": this, "active": bool(dict(device.active).get(legacy.NAMESPACE)),
                               "fingerprint": rules.fingerprint_groups(legacy.curve_identity(device.identity_key).hex()),
                               "trust": rules.VERIFIED if this else device.trust_level_name})
            output.sort(key=lambda item: (not item["this"], item["id"]))
            return output

    async def set_trust(self, bare_jid, device_id, name):
        if name not in (rules.VERIFIED, rules.BLIND, rules.DISTRUSTED):
            raise ValueError("Unknown trust choice")
        bare_jid = bare_jid.lower()
        async with self.lock:
            for device in await self.manager.get_device_information(bare_jid):
                if device.device_id == int(device_id):
                    await self.manager.set_trust(bare_jid, device.identity_key, name)
                    return True
        return False

    async def own_uri(self):
        devices = await self.devices(self.own_jid)
        return rules.xmpp_uri(self.own_jid, [(d["id"], "".join(d["fingerprint"])) for d in devices if d["this"]])
