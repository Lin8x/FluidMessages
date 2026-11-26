"""Legacy OMEMO (XEP-0384 version 0.3, namespace eu.siacs.conversations.axolotl) for python-omemo.

Written for FluidMessages (Apache-2.0) from public specifications only:
- XEP-0384 version 0.3 (device list and bundle nodes, the <encrypted> element, AES-128-GCM payloads whose
  16-byte key and 16-byte tag are encrypted per device, prekey="true" key exchanges, key transport messages);
- Signal's X3DH specification (shared secret: 32 0xFF bytes, then DH1..DH4, HKDF-SHA-256 with a zero salt; the
  associated data is Encode(IK_A) || Encode(IK_B); Encode adds the 0x05 Curve25519 type byte);
- Signal's Double Ratchet specification (KDF_RK is HKDF-SHA-256 with the root key as salt; KDF_CK is HMAC-SHA-256
  with the constants 0x01 for the message key and 0x02 for the next chain key; the message keys are expanded with
  HKDF into a 32-byte AES-256-CBC key, a 32-byte HMAC-SHA-256 key, and a 16-byte IV, as in its section 5.2);
- the XEdDSA specification (signatures on the signed prekey by the Curve25519 identity key);
- RFC 5869 (HKDF), RFC 7748 (X25519), RFC 8032 (Ed25519).
The wire layout of the Signal messages (a version byte 0x33, then protocol buffer fields, then an 8-byte truncated
MAC over sender identity || receiver identity || message) was confirmed only by black-box tests against another
implementation; no GPL or AGPL source code was read. The info strings are "WhisperText" (X3DH), "WhisperRatchet"
(root chain), and "WhisperMessageKeys" (message keys).

The ratchet and key agreement run in the MIT libraries DoubleRatchet and X3DH (Syndace); signing in XEdDSA (MIT).
This module only configures them and adds the message format, so the cryptographic steps aren't hand-written.
Never log keys or message text here.
"""
from __future__ import annotations

import base64
import hmac
import hashlib
import secrets
import xml.etree.ElementTree as ET
from typing import Dict, Optional, Tuple

import doubleratchet
from doubleratchet.recommended import aead_aes_hmac, diffie_hellman_ratchet_curve25519, HashFunction, kdf_hkdf, kdf_separate_hmacs
from doubleratchet.recommended.crypto_provider_impl import CryptoProviderImpl
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import x3dh
import x3dh.identity_key_pair
import xeddsa
from omemo.backend import Backend, DecryptionFailed, KeyExchangeFailed, TooManySkippedMessageKeys
from omemo.bundle import Bundle
from omemo.identity_key_pair import IdentityKeyPair, IdentityKeyPairSeed
from omemo.message import Content, EncryptedKeyMaterial, KeyExchange, Message, PlainKeyMaterial
from omemo.session import Initiation, Session
from omemo.storage import Storage
from omemo.types import DeviceList

NAMESPACE = "eu.siacs.conversations.axolotl"
DEVICE_LIST_NODE = NAMESPACE + ".devicelist"
BUNDLES_NODE = NAMESPACE + ".bundles:"
VERSION_BYTE = 0x33          # Message format version 3, as the first byte of every Signal message.
MAC_LENGTH = 8               # The MAC on each Signal message is HMAC-SHA-256 truncated to 8 bytes.
CURVE_TYPE = b"\x05"         # Encode(PK): the Curve25519 type byte in front of the 32-byte key.
MAX_PRE_KEY_ID = 0xFFFFFF    # Prekey ids stay below 2^24, the range other clients accept.
FALLBACK_BODY = "This message is locked with OMEMO, and your app can't open it."


def bundle_node(device_id: int) -> str:
    return BUNDLES_NODE + str(int(device_id))


# ---------------------------------------------------------------------------------------------------------------
# Protocol buffers, by hand: varints (wire type 0) and length-delimited bytes (wire type 2) are all these messages use.

def _varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("negative varint")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _read_varint(data: bytes, offset: int) -> Tuple[int, int]:
    value = shift = 0
    while True:
        if offset >= len(data) or shift > 63:
            raise ValueError("truncated varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7


def proto_int(number: int, value: int) -> bytes:
    return _varint(number << 3) + _varint(value)


def proto_bytes(number: int, value: bytes) -> bytes:
    return _varint(number << 3 | 2) + _varint(len(value)) + value


def proto_parse(data: bytes) -> Dict[int, object]:
    """Fields by number (the last one wins, as protocol buffers specify). Unknown wire types are an error."""
    fields: Dict[int, object] = {}
    offset = 0
    while offset < len(data):
        tag, offset = _read_varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if wire == 0:
            fields[number], offset = _read_varint(data, offset)
        elif wire == 2:
            length, offset = _read_varint(data, offset)
            if offset + length > len(data):
                raise ValueError("truncated field")
            fields[number] = data[offset:offset + length]
            offset += length
        else:
            raise ValueError("unsupported wire type")
    return fields


def encode_key(pub: bytes) -> bytes:
    """A Curve25519 public key as Signal serializes it: the type byte, then the 32-byte key."""
    if len(pub) != 32:
        raise ValueError("Curve25519 keys are 32 bytes")
    return CURVE_TYPE + pub


def decode_key(data: object) -> bytes:
    if not isinstance(data, bytes):
        raise ValueError("missing key")
    if len(data) == 33 and data[:1] == CURVE_TYPE:
        return data[1:]
    if len(data) == 32:
        return data  # Some clients leave out the type byte.
    raise ValueError("unknown key encoding")


def encode_signal_message(ratchet_pub: bytes, counter: int, previous_counter: int, ciphertext: bytes) -> bytes:
    """The authenticated part of a Signal message (without its MAC): version byte, then fields 1-4."""
    return bytes([VERSION_BYTE]) + proto_bytes(1, encode_key(ratchet_pub)) + proto_int(2, counter) \
        + proto_int(3, previous_counter) + proto_bytes(4, ciphertext)


def decode_signal_message(data: bytes) -> Tuple[bytes, int, int, bytes]:
    """Returns (ratchet key, counter, previous counter, ciphertext) of a whole message, MAC included."""
    if len(data) < 1 + MAC_LENGTH or data[0] >> 4 != 3:
        raise ValueError("unsupported message version")
    fields = proto_parse(data[1:-MAC_LENGTH])
    ciphertext = fields.get(4)
    if not isinstance(ciphertext, bytes):
        raise ValueError("missing ciphertext")
    return decode_key(fields.get(1)), int(fields.get(2, 0)), int(fields.get(3, 0)), ciphertext


def encode_pre_key_message(registration_id: int, pre_key_id: int, signed_pre_key_id: int, base_key: bytes,
                           identity_key: bytes, message: bytes) -> bytes:
    """A key exchange: version byte, then the prekey ids, the sender's ephemeral (base) and identity keys, and the
    first Signal message."""
    return bytes([VERSION_BYTE]) + proto_int(5, registration_id) + proto_int(1, pre_key_id) \
        + proto_int(6, signed_pre_key_id) + proto_bytes(2, encode_key(base_key)) \
        + proto_bytes(3, encode_key(identity_key)) + proto_bytes(4, message)


def decode_pre_key_message(data: bytes) -> Tuple[int, int, bytes, bytes, bytes]:
    """Returns (prekey id, signed prekey id, base key, identity key, inner Signal message)."""
    if len(data) < 2 or data[0] >> 4 != 3:
        raise ValueError("unsupported message version")
    fields = proto_parse(data[1:])
    message = fields.get(4)
    if not isinstance(message, bytes) or 1 not in fields or 6 not in fields:
        raise ValueError("incomplete key exchange")
    return int(fields[1]), int(fields[6]), decode_key(fields.get(2)), decode_key(fields.get(3)), message


# ---------------------------------------------------------------------------------------------------------------
# Double Ratchet configuration

class RootChainKDFImpl(kdf_hkdf.KDF):
    """KDF_RK (Double Ratchet section 5.2): HKDF-SHA-256, salt = root key, input = DH output."""
    @staticmethod
    def _get_hash_function() -> HashFunction:
        return HashFunction.SHA_256

    @staticmethod
    def _get_info() -> bytes:
        return b"WhisperRatchet"


class MessageChainKDFImpl(kdf_separate_hmacs.KDF):
    """KDF_CK (Double Ratchet section 5.2): HMAC-SHA-256 of the chain key with 0x02 (next chain key) and 0x01
    (message key), in the order DoubleRatchet splits them."""
    @staticmethod
    def _get_hash_function() -> HashFunction:
        return HashFunction.SHA_256


MESSAGE_CHAIN_CONSTANT = b"\x02\x01"
HEADER_LENGTH = 32 + 4 + 4
IDENTITY_PAIR_LENGTH = 33 * 2


def _pack_header(header: doubleratchet.Header) -> bytes:
    return header.ratchet_pub + header.sending_chain_length.to_bytes(4, "big") \
        + header.previous_sending_chain_length.to_bytes(4, "big")


def _unpack(associated_data: bytes) -> Tuple[bytes, doubleratchet.Header]:
    if len(associated_data) != IDENTITY_PAIR_LENGTH + HEADER_LENGTH:
        raise doubleratchet.DecryptionFailedException("unexpected associated data")
    identities, packed = associated_data[:IDENTITY_PAIR_LENGTH], associated_data[IDENTITY_PAIR_LENGTH:]
    # Header fields: ratchet key, previous sending chain length, sending chain length (the message counter).
    return identities, doubleratchet.Header(packed[:32], int.from_bytes(packed[36:40], "big"),
                                            int.from_bytes(packed[32:36], "big"))


class AEADImpl(aead_aes_hmac.AEAD):
    """ENCRYPT/DECRYPT (Double Ratchet section 5.2, "recommended cryptographic algorithms"): HKDF-SHA-256 of the
    message key (zero salt, info "WhisperMessageKeys") into an AES-256-CBC key, an HMAC key, and the IV. The MAC
    covers sender identity || receiver identity || the serialized message and is truncated to 8 bytes."""
    @staticmethod
    def _get_hash_function() -> HashFunction:
        return HashFunction.SHA_256

    @staticmethod
    def _get_info() -> bytes:
        return b"WhisperMessageKeys"

    @staticmethod
    async def _keys(key: bytes) -> Tuple[bytes, bytes, bytes]:
        out = await CryptoProviderImpl.hkdf_derive(hash_function=HashFunction.SHA_256, length=80, salt=b"\x00" * 32,
                                                   info=b"WhisperMessageKeys", key_material=key)
        return out[:32], out[32:64], out[64:]

    @classmethod
    async def encrypt(cls, plaintext: bytes, key: bytes, associated_data: bytes) -> bytes:
        identities, header = _unpack(associated_data)
        cipher_key, mac_key, iv = await cls._keys(key)
        ciphertext = await CryptoProviderImpl.aes_cbc_encrypt(cipher_key, iv, plaintext)
        body = encode_signal_message(header.ratchet_pub, header.sending_chain_length,
                                     header.previous_sending_chain_length, ciphertext)
        mac = (await CryptoProviderImpl.hmac_calculate(mac_key, HashFunction.SHA_256, identities + body))[:MAC_LENGTH]
        return body + mac

    @classmethod
    async def decrypt(cls, ciphertext: bytes, key: bytes, associated_data: bytes) -> bytes:
        identities, header = _unpack(associated_data)
        cipher_key, mac_key, iv = await cls._keys(key)
        body, mac = ciphertext[:-MAC_LENGTH], ciphertext[-MAC_LENGTH:]
        expected = (await CryptoProviderImpl.hmac_calculate(mac_key, HashFunction.SHA_256, identities + body))[:MAC_LENGTH]
        if not hmac.compare_digest(expected, mac):
            raise doubleratchet.aead.AuthenticationFailedException("MAC mismatch")
        try:
            ratchet_pub, counter, previous, inner = decode_signal_message(ciphertext)
        except ValueError as error:
            raise doubleratchet.DecryptionFailedException() from error
        if doubleratchet.Header(ratchet_pub, previous, counter) != header:
            raise doubleratchet.aead.AuthenticationFailedException("header mismatch")
        return await CryptoProviderImpl.aes_cbc_decrypt(cipher_key, iv, inner)


class DoubleRatchetImpl(doubleratchet.DoubleRatchet):
    @staticmethod
    def _build_associated_data(associated_data: bytes, header: doubleratchet.Header) -> bytes:
        return associated_data + _pack_header(header)


class StateImpl(x3dh.BaseState):
    """X3DH (section 2 and 3): Curve25519 identity keys, SHA-256, info "WhisperText"."""
    INFO = b"WhisperText"

    @staticmethod
    def _encode_public_key(key_format: x3dh.IdentityKeyFormat, pub: bytes) -> bytes:
        return encode_key(pub)


def _ratchet(**extra):
    return dict(diffie_hellman_ratchet_class=diffie_hellman_ratchet_curve25519.DiffieHellmanRatchet,
                root_chain_kdf=RootChainKDFImpl, message_chain_kdf=MessageChainKDFImpl,
                message_chain_constant=MESSAGE_CHAIN_CONSTANT, aead=AEADImpl, **extra)


def ed_identity(curve_pub: bytes) -> bytes:
    """python-omemo names devices by Ed25519 identity keys; legacy OMEMO sends Curve25519 ones (sign bit 0)."""
    return xeddsa.curve25519_pub_to_ed25519_pub(curve_pub, False)


def curve_identity(ed_pub: bytes) -> bytes:
    return xeddsa.ed25519_pub_to_curve25519_pub(ed_pub)


# ---------------------------------------------------------------------------------------------------------------
# python-omemo types

class BundleImpl(Bundle):
    def __init__(self, bare_jid: str, device_id: int, bundle: x3dh.Bundle, signed_pre_key_id: int,
                 pre_key_ids: Dict[bytes, int]) -> None:
        self.__bare_jid, self.__device_id, self.__bundle = bare_jid, device_id, bundle
        self.signed_pre_key_id = signed_pre_key_id
        self.pre_key_ids = dict(pre_key_ids)

    namespace = property(lambda self: NAMESPACE)
    bare_jid = property(lambda self: self.__bare_jid)
    device_id = property(lambda self: self.__device_id)
    bundle = property(lambda self: self.__bundle)

    @property
    def identity_key(self) -> bytes:
        return ed_identity(self.__bundle.identity_key)

    def __eq__(self, other: object) -> bool:
        return isinstance(other, BundleImpl) and (other.bare_jid, other.device_id, other.bundle, other.signed_pre_key_id,
                                                  other.pre_key_ids) == (self.bare_jid, self.device_id, self.bundle,
                                                                         self.signed_pre_key_id, self.pre_key_ids)

    def __hash__(self) -> int:
        return hash((self.bare_jid, self.device_id, self.bundle, self.signed_pre_key_id, frozenset(self.pre_key_ids.items())))


class ContentImpl(Content):
    """The <payload> (AES-128-GCM ciphertext without its tag) and the <iv>. A key transport has no payload."""
    def __init__(self, ciphertext: Optional[bytes], iv: bytes) -> None:
        self.ciphertext, self.iv = ciphertext, iv

    @property
    def empty(self) -> bool:
        return self.ciphertext is None


class PlainKeyMaterialImpl(PlainKeyMaterial):
    """What each device's Signal session carries: the 16-byte AES key, then the 16-byte GCM tag."""
    def __init__(self, data: bytes) -> None:
        self.data = data


class EncryptedKeyMaterialImpl(EncryptedKeyMaterial):
    def __init__(self, bare_jid: str, device_id: int, encrypted_message: doubleratchet.EncryptedMessage) -> None:
        self.__bare_jid, self.__device_id = bare_jid, device_id
        self.encrypted_message = encrypted_message

    bare_jid = property(lambda self: self.__bare_jid)
    device_id = property(lambda self: self.__device_id)

    def serialize(self) -> bytes:
        return self.encrypted_message.ciphertext

    @staticmethod
    def parse(data: bytes, bare_jid: str, device_id: int) -> "EncryptedKeyMaterialImpl":
        ratchet_pub, counter, previous, _ = decode_signal_message(data)
        return EncryptedKeyMaterialImpl(bare_jid, device_id,
                                        doubleratchet.EncryptedMessage(doubleratchet.Header(ratchet_pub, previous, counter), data))


class KeyExchangeImpl(KeyExchange):
    """X3DH header plus prekey ids. Keys are Curve25519 (32 bytes); a parsed one lacks the prekey values until the
    receiver looks them up by id."""
    def __init__(self, header: x3dh.Header, signed_pre_key_id: int, pre_key_id: int) -> None:
        self.header, self.signed_pre_key_id, self.pre_key_id = header, signed_pre_key_id, pre_key_id

    @property
    def identity_key(self) -> bytes:
        return ed_identity(self.header.identity_key)

    def builds_same_session(self, other: KeyExchange) -> bool:
        return isinstance(other, KeyExchangeImpl) and other.header.identity_key == self.header.identity_key \
            and other.header.ephemeral_key == self.header.ephemeral_key \
            and other.signed_pre_key_id == self.signed_pre_key_id and other.pre_key_id == self.pre_key_id

    def is_network_instance(self) -> bool:
        return self.header.signed_pre_key == b"" and self.header.pre_key == b""

    def serialize(self, message: bytes, registration_id: int) -> bytes:
        return encode_pre_key_message(registration_id, self.pre_key_id, self.signed_pre_key_id,
                                      self.header.ephemeral_key, self.header.identity_key, message)

    @staticmethod
    def parse(data: bytes) -> Tuple["KeyExchangeImpl", bytes]:
        pre_key_id, signed_pre_key_id, base_key, identity_key, message = decode_pre_key_message(data)
        return KeyExchangeImpl(x3dh.Header(identity_key, base_key, b"", b""), signed_pre_key_id, pre_key_id), message


class SessionImpl(Session):
    def __init__(self, bare_jid: str, device_id: int, initiation: Initiation, key_exchange: KeyExchangeImpl,
                 associated_data: bytes, double_ratchet: DoubleRatchetImpl, confirmed: bool = False) -> None:
        self.__bare_jid, self.__device_id, self.__initiation = bare_jid, device_id, initiation
        self.__key_exchange, self.associated_data, self.double_ratchet = key_exchange, associated_data, double_ratchet
        self.__confirmed = confirmed

    namespace = property(lambda self: NAMESPACE)
    bare_jid = property(lambda self: self.__bare_jid)
    device_id = property(lambda self: self.__device_id)
    initiation = property(lambda self: self.__initiation)
    confirmed = property(lambda self: self.__confirmed)
    key_exchange = property(lambda self: self.__key_exchange)
    receiving_chain_length = property(lambda self: self.double_ratchet.receiving_chain_length)
    sending_chain_length = property(lambda self: self.double_ratchet.sending_chain_length)

    def confirm(self) -> None:
        self.__confirmed = True

    # X3DH gives AD = Encode(IK_initiator) || Encode(IK_responder). Each MAC covers the sender's identity first.
    @property
    def sending_ad(self) -> bytes:
        return self.associated_data if self.initiation is Initiation.ACTIVE else _swap(self.associated_data)

    @property
    def receiving_ad(self) -> bytes:
        return self.associated_data if self.initiation is Initiation.PASSIVE else _swap(self.associated_data)


def _swap(identities: bytes) -> bytes:
    return identities[33:66] + identities[:33]


class LegacyOmemo(Backend):
    """The python-omemo backend for eu.siacs.conversations.axolotl."""
    def __init__(self, storage: Storage, max_num_per_session_skipped_keys: int = 1000,
                 max_num_per_message_skipped_keys: Optional[int] = None) -> None:
        super().__init__(max_num_per_session_skipped_keys, max_num_per_message_skipped_keys)
        self.__storage = storage
        self.__state: Optional[StateImpl] = None

    namespace = property(lambda self: NAMESPACE)
    supports_labels = property(lambda self: False)

    def __key(self, *parts: object) -> str:
        return "/" + "/".join([NAMESPACE, *map(str, parts)])

    async def __get_state(self) -> StateImpl:
        if self.__state is not None:
            return self.__state
        stored = (await self.__storage.load(self.__key("x3dh"))).maybe(None)
        if isinstance(stored, dict):
            state, _ = StateImpl.from_json(stored, x3dh.IdentityKeyFormat.CURVE_25519, x3dh.HashFunction.SHA_256, StateImpl.INFO)
        else:
            pair = await IdentityKeyPair.get(self.__storage)
            secret = x3dh.identity_key_pair.IdentityKeyPairSeed(pair.seed) if isinstance(pair, IdentityKeyPairSeed) \
                else x3dh.identity_key_pair.IdentityKeyPairPriv(pair.as_priv().priv)
            state = StateImpl.create(x3dh.IdentityKeyFormat.CURVE_25519, x3dh.HashFunction.SHA_256, StateImpl.INFO, secret)
            await self.__save_state(state)
        self.__state = state
        await self.__number_keys(state)
        return state

    async def __save_state(self, state: StateImpl) -> None:
        await self.__storage.store(self.__key("x3dh"), state.json)

    async def __ids(self, name: str) -> Dict[bytes, int]:
        stored = (await self.__storage.load(self.__key(name))).maybe({})
        return {base64.b64decode(key): int(value) for key, value in (stored or {}).items()}

    async def __store_ids(self, name: str, ids: Dict[bytes, int]) -> None:
        await self.__storage.store(self.__key(name), {base64.b64encode(key).decode(): value for key, value in ids.items()})

    async def __number_keys(self, state: StateImpl) -> None:
        """Gives each signed prekey and prekey a small numeric id, kept while the key exists."""
        signed = await self.__ids("signed_pre_key_ids")
        current = {state.bundle.signed_pre_key} | ({state.old_signed_pre_key} if state.old_signed_pre_key else set())
        changed = False
        for key in current - set(signed):
            signed[key] = max(signed.values(), default=0) % MAX_PRE_KEY_ID + 1
            changed = True
        for key in set(signed) - current:
            del signed[key]
            changed = True
        if changed:
            await self.__store_ids("signed_pre_key_ids", signed)
        pre = await self.__ids("pre_key_ids")
        current = set(state.bundle.pre_keys) | set(state.hidden_pre_keys)
        changed = False
        next_id = (await self.__storage.load_primitive(self.__key("next_pre_key_id"), int)).maybe(1)
        for key in sorted(current - set(pre)):
            used = set(pre.values())
            while next_id in used:
                next_id = next_id % MAX_PRE_KEY_ID + 1
            pre[key] = next_id
            next_id = next_id % MAX_PRE_KEY_ID + 1
            changed = True
        for key in set(pre) - current:
            del pre[key]
            changed = True
        if changed:
            await self.__store_ids("pre_key_ids", pre)
            await self.__storage.store(self.__key("next_pre_key_id"), next_id)

    async def __changed(self, state: StateImpl) -> None:
        await self.__save_state(state)
        await self.__number_keys(state)

    async def registration_id(self) -> int:
        """Signal's registration id, sent in key exchanges; other OMEMO clients don't use it."""
        value = (await self.__storage.load_primitive(self.__key("registration_id"), int)).maybe(None)
        if value is None:
            value = secrets.randbelow(16380) + 1
            await self.__storage.store(self.__key("registration_id"), value)
        return value

    # Sessions

    async def load_session(self, bare_jid: str, device_id: int) -> Optional[SessionImpl]:
        stored = (await self.__storage.load(self.__key("sessions", bare_jid, device_id))).maybe(None)
        if not isinstance(stored, dict):
            return None
        try:
            ratchet = DoubleRatchetImpl.from_json(stored["ratchet"], dos_protection_threshold=self.max_num_per_message_skipped_keys,
                                                  max_num_skipped_message_keys=self.max_num_per_session_skipped_keys, **_ratchet())
        except Exception:
            return None
        def raw(name):
            return base64.b64decode(stored[name]) if stored.get(name) is not None else None
        header = x3dh.Header(raw("identity_key"), raw("ephemeral_key"), raw("signed_pre_key") or b"", raw("pre_key"))
        return SessionImpl(bare_jid, device_id, Initiation(stored["initiation"]),
                           KeyExchangeImpl(header, stored["signed_pre_key_id"], stored["pre_key_id"]),
                           raw("associated_data"), ratchet, stored["confirmed"])

    async def store_session(self, session: Session) -> None:
        assert isinstance(session, SessionImpl)
        header = session.key_exchange.header
        def b64(value):
            return None if value is None else base64.b64encode(value).decode()
        await self.__storage.store(self.__key("sessions", session.bare_jid, session.device_id), {
            "initiation": session.initiation.value, "confirmed": session.confirmed,
            "identity_key": b64(header.identity_key), "ephemeral_key": b64(header.ephemeral_key),
            "signed_pre_key": b64(header.signed_pre_key), "pre_key": b64(header.pre_key),
            "signed_pre_key_id": session.key_exchange.signed_pre_key_id, "pre_key_id": session.key_exchange.pre_key_id,
            "associated_data": b64(session.associated_data), "ratchet": session.double_ratchet.json})
        jids = set((await self.__storage.load_list(self.__key("bare_jids"), str)).maybe([]))
        if session.bare_jid not in jids:
            await self.__storage.store(self.__key("bare_jids"), sorted(jids | {session.bare_jid}))
        devices = set((await self.__storage.load_list(self.__key("devices", session.bare_jid), int)).maybe([]))
        if session.device_id not in devices:
            await self.__storage.store(self.__key("devices", session.bare_jid), sorted(devices | {session.device_id}))

    async def build_session_active(self, bare_jid: str, device_id: int, bundle: Bundle,
                                   plain_key_material: PlainKeyMaterial) -> Tuple[SessionImpl, EncryptedKeyMaterialImpl]:
        assert isinstance(bundle, BundleImpl) and isinstance(plain_key_material, PlainKeyMaterialImpl)
        state = await self.__get_state()
        try:
            shared_secret, associated_data, header = await state.get_shared_secret_active(bundle.bundle)
        except x3dh.KeyAgreementException as error:
            raise KeyExchangeFailed() from error
        ratchet, message = await DoubleRatchetImpl.encrypt_initial_message(
            dos_protection_threshold=self.max_num_per_message_skipped_keys,
            max_num_skipped_message_keys=self.max_num_per_session_skipped_keys, shared_secret=shared_secret,
            recipient_ratchet_pub=bundle.bundle.signed_pre_key, message=plain_key_material.data,
            associated_data=associated_data, **_ratchet())
        exchange = KeyExchangeImpl(header, bundle.signed_pre_key_id, bundle.pre_key_ids[header.pre_key])
        session = SessionImpl(bare_jid, device_id, Initiation.ACTIVE, exchange, associated_data, ratchet)
        return session, EncryptedKeyMaterialImpl(bare_jid, device_id, message)

    async def build_session_passive(self, bare_jid: str, device_id: int, key_exchange: KeyExchange,
                                    encrypted_key_material: EncryptedKeyMaterial) -> Tuple[SessionImpl, PlainKeyMaterialImpl]:
        assert isinstance(key_exchange, KeyExchangeImpl) and isinstance(encrypted_key_material, EncryptedKeyMaterialImpl)
        state = await self.__get_state()
        if key_exchange.is_network_instance():
            signed = {value: key for key, value in (await self.__ids("signed_pre_key_ids")).items()}
            pre = {value: key for key, value in (await self.__ids("pre_key_ids")).items()}
            if key_exchange.signed_pre_key_id not in signed or key_exchange.pre_key_id not in pre:
                raise KeyExchangeFailed("The key exchange uses a prekey this device no longer has.")
            key_exchange = KeyExchangeImpl(x3dh.Header(key_exchange.header.identity_key, key_exchange.header.ephemeral_key,
                                                       signed[key_exchange.signed_pre_key_id], pre[key_exchange.pre_key_id]),
                                           key_exchange.signed_pre_key_id, key_exchange.pre_key_id)
        try:
            shared_secret, associated_data, signed_pre_key = await state.get_shared_secret_passive(key_exchange.header)
        except x3dh.KeyAgreementException as error:
            raise KeyExchangeFailed() from error
        try:
            ratchet, data = await DoubleRatchetImpl.decrypt_initial_message(
                dos_protection_threshold=self.max_num_per_message_skipped_keys,
                max_num_skipped_message_keys=self.max_num_per_session_skipped_keys, shared_secret=shared_secret,
                own_ratchet_priv=signed_pre_key.priv, message=encrypted_key_material.encrypted_message,
                associated_data=associated_data, **_ratchet())
        except Exception as error:
            raise DecryptionFailed("The first message of the key exchange couldn't be opened.") from error
        session = SessionImpl(bare_jid, device_id, Initiation.PASSIVE, key_exchange, associated_data, ratchet)
        return session, PlainKeyMaterialImpl(data)

    # Payloads (XEP-0384 0.3 section 4.5): AES-128-GCM with a fresh key and 12-byte IV.

    async def encrypt_plaintext(self, plaintext: bytes) -> Tuple[ContentImpl, PlainKeyMaterialImpl]:
        key, iv = secrets.token_bytes(16), secrets.token_bytes(12)
        sealed = AESGCM(key).encrypt(iv, plaintext, None)
        return ContentImpl(sealed[:-16], iv), PlainKeyMaterialImpl(key + sealed[-16:])

    async def encrypt_empty(self) -> Tuple[ContentImpl, PlainKeyMaterialImpl]:
        key, iv = secrets.token_bytes(16), secrets.token_bytes(12)
        return ContentImpl(None, iv), PlainKeyMaterialImpl(key + AESGCM(key).encrypt(iv, b"", None))

    async def encrypt_key_material(self, session: Session, plain_key_material: PlainKeyMaterial) -> EncryptedKeyMaterialImpl:
        assert isinstance(session, SessionImpl) and isinstance(plain_key_material, PlainKeyMaterialImpl)
        message = await session.double_ratchet.encrypt_message(plain_key_material.data, session.sending_ad)
        return EncryptedKeyMaterialImpl(session.bare_jid, session.device_id, message)

    async def decrypt_plaintext(self, content: Content, plain_key_material: PlainKeyMaterial) -> bytes:
        assert isinstance(content, ContentImpl) and isinstance(plain_key_material, PlainKeyMaterialImpl)
        return open_payload(plain_key_material.data, content.iv, content.ciphertext or b"")

    async def decrypt_key_material(self, session: Session, encrypted_key_material: EncryptedKeyMaterial) -> PlainKeyMaterialImpl:
        assert isinstance(session, SessionImpl) and isinstance(encrypted_key_material, EncryptedKeyMaterialImpl)
        try:
            data = await session.double_ratchet.decrypt_message(encrypted_key_material.encrypted_message, session.receiving_ad)
        except doubleratchet.DoSProtectionException as error:
            raise TooManySkippedMessageKeys() from error
        except Exception as error:
            raise DecryptionFailed("The key couldn't be opened.") from error
        session.confirm()
        return PlainKeyMaterialImpl(data)

    # Prekeys

    async def signed_pre_key_age(self) -> int:
        return (await self.__get_state()).signed_pre_key_age()

    async def rotate_signed_pre_key(self) -> None:
        state = await self.__get_state()
        state.rotate_signed_pre_key()
        await self.__changed(state)

    async def hide_pre_key(self, session: Session) -> bool:
        assert isinstance(session, SessionImpl)
        state = await self.__get_state()
        hidden = state.hide_pre_key(session.key_exchange.header.pre_key)
        await self.__save_state(state)
        return hidden

    async def delete_pre_key(self, session: Session) -> bool:
        assert isinstance(session, SessionImpl)
        state = await self.__get_state()
        deleted = state.delete_pre_key(session.key_exchange.header.pre_key)
        await self.__changed(state)
        return deleted

    async def delete_hidden_pre_keys(self) -> None:
        state = await self.__get_state()
        state.delete_hidden_pre_keys()
        await self.__changed(state)

    async def get_num_visible_pre_keys(self) -> int:
        return (await self.__get_state()).get_num_visible_pre_keys()

    async def generate_pre_keys(self, num_pre_keys: int) -> None:
        state = await self.__get_state()
        state.generate_pre_keys(num_pre_keys)
        await self.__changed(state)

    async def get_bundle(self, bare_jid: str, device_id: int) -> BundleImpl:
        state = await self.__get_state()
        bundle = state.bundle
        signed = await self.__ids("signed_pre_key_ids")
        pre = await self.__ids("pre_key_ids")
        return BundleImpl(bare_jid, device_id, bundle, signed[bundle.signed_pre_key],
                          {key: pre[key] for key in bundle.pre_keys if key in pre})

    async def sign_own_label(self, label: str) -> bytes:
        raise NotImplementedError("Legacy OMEMO has no device labels")

    async def verify_label_signature(self, label: str, signature: bytes, identity_key: bytes) -> bool:
        return False

    async def purge(self) -> None:
        for bare_jid in (await self.__storage.load_list(self.__key("bare_jids"), str)).maybe([]):
            await self.purge_bare_jid(bare_jid)
        await self.__storage.delete(self.__key("bare_jids"))
        for name in ("x3dh", "signed_pre_key_ids", "pre_key_ids", "next_pre_key_id", "registration_id"):
            await self.__storage.delete(self.__key(name))
        self.__state = None

    async def purge_bare_jid(self, bare_jid: str) -> None:
        for device_id in (await self.__storage.load_list(self.__key("devices", bare_jid), int)).maybe([]):
            await self.__storage.delete(self.__key("sessions", bare_jid, device_id))
        await self.__storage.delete(self.__key("devices", bare_jid))


def open_payload(key_material: bytes, iv: bytes, payload: bytes) -> bytes:
    """Opens an AES-128-GCM payload. The key material is key || tag (32 bytes); older clients sent the 16-byte key
    alone and put the tag at the end of the payload."""
    from cryptography.exceptions import InvalidTag
    if len(key_material) >= 32:
        key, sealed = key_material[:16], payload + key_material[16:32]
    elif len(key_material) == 16:
        key, sealed = key_material, payload
    else:
        raise DecryptionFailed("Unexpected key length")
    try:
        return AESGCM(key).decrypt(iv, sealed, None)
    except (InvalidTag, ValueError) as error:
        raise DecryptionFailed("The message couldn't be opened.") from error


# ---------------------------------------------------------------------------------------------------------------
# XML (XEP-0384 0.3 sections 4.3 and 4.5)

def _q(name: str) -> str:
    return "{%s}%s" % (NAMESPACE, name)


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def _unb64(text: Optional[str]) -> bytes:
    if not text:
        raise ValueError("missing value")
    return base64.b64decode("".join(text.split()), validate=True)


def serialize_device_list(device_list: DeviceList) -> ET.Element:
    element = ET.Element(_q("list"))
    for device_id in sorted(device_list):
        ET.SubElement(element, _q("device"), {"id": str(device_id)})
    return element


def parse_device_list(element: ET.Element) -> DeviceList:
    devices: DeviceList = {}
    for device in element.iter(_q("device")):
        try:
            device_id = int(device.get("id", ""))
        except ValueError:
            continue
        if 1 <= device_id <= 2 ** 31 - 1:
            devices[device_id] = None
    return devices


def serialize_bundle(bundle: BundleImpl) -> ET.Element:
    element = ET.Element(_q("bundle"))
    spk = ET.SubElement(element, _q("signedPreKeyPublic"), {"signedPreKeyId": str(bundle.signed_pre_key_id)})
    spk.text = _b64(encode_key(bundle.bundle.signed_pre_key))
    ET.SubElement(element, _q("signedPreKeySignature")).text = _b64(bundle.bundle.signed_pre_key_sig)
    ET.SubElement(element, _q("identityKey")).text = _b64(encode_key(bundle.bundle.identity_key))
    prekeys = ET.SubElement(element, _q("prekeys"))
    for key, key_id in sorted(bundle.pre_key_ids.items(), key=lambda item: item[1]):
        ET.SubElement(prekeys, _q("preKeyPublic"), {"preKeyId": str(key_id)}).text = _b64(encode_key(key))
    return element


def parse_bundle(element: ET.Element, bare_jid: str, device_id: int) -> BundleImpl:
    spk = element.find(_q("signedPreKeyPublic"))
    if spk is None:
        raise ValueError("bundle without a signed prekey")
    pre_key_ids: Dict[bytes, int] = {}
    for prekey in element.iter(_q("preKeyPublic")):
        try:
            pre_key_ids[decode_key(_unb64(prekey.text))] = int(prekey.get("preKeyId", ""))
        except ValueError:
            continue
    signature = _unb64(element.findtext(_q("signedPreKeySignature")))
    identity = decode_key(_unb64(element.findtext(_q("identityKey"))))
    return BundleImpl(bare_jid, device_id, x3dh.Bundle(identity, decode_key(_unb64(spk.text)), signature,
                                                       frozenset(pre_key_ids)),
                      int(spk.get("signedPreKeyId", "")), pre_key_ids)


async def serialize_message(message: Message, backend: LegacyOmemo) -> ET.Element:
    """The <encrypted> element. Key exchanges (prekey="true") carry a PreKeySignalMessage."""
    assert isinstance(message.content, ContentImpl)
    registration_id = await backend.registration_id()
    encrypted = ET.Element(_q("encrypted"))
    header = ET.SubElement(encrypted, _q("header"), {"sid": str(message.device_id)})
    for material, exchange in sorted(message.keys, key=lambda item: item[0].device_id):
        assert isinstance(material, EncryptedKeyMaterialImpl)
        if exchange is None:
            ET.SubElement(header, _q("key"), {"rid": str(material.device_id)}).text = _b64(material.serialize())
        else:
            assert isinstance(exchange, KeyExchangeImpl)
            ET.SubElement(header, _q("key"), {"rid": str(material.device_id), "prekey": "true"}).text = \
                _b64(exchange.serialize(material.serialize(), registration_id))
    ET.SubElement(header, _q("iv")).text = _b64(message.content.iv)
    if message.content.ciphertext is not None:
        ET.SubElement(encrypted, _q("payload")).text = _b64(message.content.ciphertext)
    return encrypted


def parse_message(element: ET.Element, sender_bare_jid: str, own_bare_jid: str, own_device_id: int) -> Message:
    """Reads an <encrypted> element. Only the key for this device is parsed (legacy OMEMO names recipients by
    device id alone); the message's keys are empty when there's none for us."""
    header = element.find(_q("header"))
    if header is None:
        raise ValueError("no header")
    sender_device = int(header.get("sid", ""))
    iv = _unb64(header.findtext(_q("iv")))
    payload = element.findtext(_q("payload"))
    content = ContentImpl(None if payload is None else _unb64(payload), iv)
    keys = set()
    for key in header.iter(_q("key")):
        if key.get("rid") != str(own_device_id):
            continue
        data = _unb64(key.text)
        if key.get("prekey") in ("true", "1"):
            exchange, inner = KeyExchangeImpl.parse(data)
            keys.add((EncryptedKeyMaterialImpl.parse(inner, own_bare_jid, own_device_id), exchange))
        else:
            keys.add((EncryptedKeyMaterialImpl.parse(data, own_bare_jid, own_device_id), None))
        break
    return Message(NAMESPACE, sender_bare_jid, sender_device, content, frozenset(keys))


def recipient_device_ids(element: ET.Element):
    header = element.find(_q("header"))
    return set() if header is None else {key.get("rid") for key in header.iter(_q("key"))}
