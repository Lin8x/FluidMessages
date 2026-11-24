"""End-to-end encrypted Matrix for the companion, using matrix-nio with vodozemac (Olm and Megolm).

The companion becomes one Matrix device of your account. It decrypts incoming messages and encrypts outgoing
ones in encrypted rooms, so they reach the phone as readable history over your paired HTTPS link. Because the
companion holds the keys, the phone labels these rooms "End-to-end encrypted · decrypted on your companion".
New devices of the people you talk to are trusted on first use, the way most bots and bridges behave; verify
the companion's device from another Matrix client to remove "unverified device" warnings for others.

The encryption store (keys and sessions) lives in the data volume, protected with a random pickle key.
"""
import asyncio
import os
from pathlib import Path
import secrets
import threading

try:
    import nio
    from nio.crypto import ENCRYPTION_ENABLED
    from nio.crypto.attachments import decrypt_attachment
except ImportError:
    nio = None
    ENCRYPTION_ENABLED = False


# Matrix call signaling, decrypted in encrypted rooms, for the browser and phone call screens.
CALL_EVENTS = tuple(getattr(nio, name) for name in ("CallInviteEvent", "CallAnswerEvent", "CallCandidatesEvent", "CallHangupEvent")
                    if nio is not None and hasattr(nio, name))
# Topic, name, and membership changes, shown as event lines.
STATE_EVENTS = tuple(getattr(nio, name) for name in ("RoomTopicEvent", "RoomNameEvent", "RoomMemberEvent")
                     if nio is not None and hasattr(nio, name))


def available():
    return nio is not None and ENCRYPTION_ENABLED


def decrypt_file(content, data):
    """Decrypts an encrypted attachment (the "file" object of an m.image, m.file, and so on)."""
    file = content["file"]
    return decrypt_attachment(data, file["key"]["k"], file["hashes"]["sha256"], file["iv"])


class MatrixE2ee:
    def __init__(self, account, store, rooms, on_event, on_invite=None):
        if not available():
            raise RuntimeError("Install matrix-nio[e2e] on the companion to use encrypted Matrix rooms")
        self.account = account
        self.store = Path(store)
        self.rooms = rooms          # callable returning the set of linked room IDs
        self.on_event = on_event    # (room_id, event_source_dict)
        self.on_invite = on_invite  # (room_id, sender, room name)
        self.loop = None
        self.client = None
        self.ready = threading.Event()
        self.stopped = False

    def pickle_key(self):
        path = self.store / "pickle.key"
        if not path.is_file():
            self.store.mkdir(parents=True, exist_ok=True)
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as output:
                output.write(secrets.token_urlsafe(32))
        return path.read_text().strip()

    def run(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        self.loop.run_until_complete(self.main())

    async def main(self):
        self.store.mkdir(parents=True, exist_ok=True)
        config = nio.AsyncClientConfig(store_sync_tokens=True, encryption_enabled=True, pickle_key=self.pickle_key())
        probe = nio.AsyncClient(self.account["homeserver"], self.account["user_id"])
        probe.access_token = self.account["access_token"]
        whoami = await probe.whoami()
        await probe.close()
        if not isinstance(whoami, nio.WhoamiResponse) or not whoami.device_id:
            raise RuntimeError("The Matrix token has no device; sign in again from the phone")
        client = nio.AsyncClient(self.account["homeserver"], self.account["user_id"], whoami.device_id,
                                 str(self.store), config)
        client.restore_login(self.account["user_id"], whoami.device_id, self.account["access_token"])
        self.client = client
        client.add_event_callback(self.event, (nio.RoomMessageText, nio.RoomMessageNotice, nio.RoomMessageEmote,
                                               nio.RoomMessageMedia, nio.RoomEncryptedMedia, nio.ReactionEvent, nio.RedactionEvent,
                                               nio.MegolmEvent, *CALL_EVENTS, *STATE_EVENTS))
        if self.on_invite is not None and hasattr(nio, "InviteMemberEvent"):
            client.add_event_callback(self.invited, nio.InviteMemberEvent)
        if client.should_upload_keys:
            await client.keys_upload()
        self.ready.set()
        try:
            while not self.stopped:
                response = await client.sync(timeout=30_000, full_state=False)
                if isinstance(response, nio.SyncError):
                    await asyncio.sleep(30)
                    continue
                if client.should_upload_keys:
                    await client.keys_upload()
                if client.should_query_keys:
                    await client.keys_query()
                if client.should_claim_keys:
                    await client.keys_claim(client.get_users_for_key_claiming())
        finally:
            self.ready.clear()
            await client.close()

    async def event(self, room, event):
        if room.room_id not in self.rooms() or event.sender == self.client.user_id:
            return
        source = dict(event.source)
        if isinstance(event, nio.MegolmEvent):
            # The sender's keys didn't reach this device (yet). Ask for them and show a placeholder.
            await self.client.request_room_key(event)
            source["type"] = "m.room.message"
            source["content"] = {"msgtype": "m.notice", "body": "Couldn’t decrypt this message yet. It may arrive later."}
        self.on_event(room.room_id, source)

    async def invited(self, room, event):
        """An invitation to you: waits on the Invitations page (chat_features.py)."""
        if getattr(event, "state_key", None) != self.client.user_id or getattr(event, "membership", "") != "invite":
            return
        name = getattr(room, "name", None) or getattr(room, "canonical_alias", None) or ""
        self.on_invite(room.room_id, getattr(event, "sender", ""), name)

    def encrypted(self, room_id):
        room = self.client.rooms.get(room_id) if self.client else None
        return bool(room and room.encrypted)

    def call(self, coroutine, timeout=120):
        if not self.ready.wait(30):
            raise ConnectionError("Matrix isn’t connected yet")
        return asyncio.run_coroutine_threadsafe(coroutine, self.loop).result(timeout)

    def send(self, room_id, event_type, content, transaction):
        async def deliver():
            response = await self.client.room_send(room_id, event_type, content, tx_id=transaction,
                                                   ignore_unverified_devices=True)
            if not isinstance(response, nio.RoomSendResponse):
                raise ConnectionError(getattr(response, "message", "Matrix didn’t accept the message"))
            return response.event_id
        return self.call(deliver())

    def upload(self, room_id, data, mime, name):
        """Uploads a file, encrypting it first in encrypted rooms. Returns the content fields to merge."""
        async def deliver():
            encrypt = self.encrypted(room_id)
            response, keys = await self.client.upload(lambda *_: data, content_type="application/octet-stream" if encrypt else mime,
                                                      filename=name, encrypt=encrypt, filesize=len(data))
            if not isinstance(response, nio.UploadResponse):
                raise ConnectionError("The homeserver didn’t accept the file")
            if encrypt:
                return {"file": {**keys, "url": response.content_uri, "mimetype": mime}}
            return {"url": response.content_uri}
        return self.call(deliver())

    def download(self, mxc):
        async def fetch():
            response = await self.client.download(mxc)
            if not isinstance(response, nio.DownloadResponse):
                raise ConnectionError("Couldn’t download the file")
            return response.body
        return self.call(fetch())

    def stop(self):
        self.stopped = True
