package org.fluidmessages;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.math.ec.rfc7748.X25519;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Legacy OMEMO (XEP-0384 version 0.3, eu.siacs.conversations.axolotl) for the phone's own XMPP connection.
 *
 * Written for FluidMessages (Apache-2.0) from public specifications only: XEP-0384 0.3; Signal's X3DH and Double
 * Ratchet specifications (signal.org/docs); the XEdDSA specification; RFC 5869 (HKDF), RFC 7748 (X25519), and
 * RFC 8032 (Ed25519). No GPL or AGPL OMEMO or Signal code was read. The message layout (version byte 0x33,
 * protocol buffer fields, an 8-byte MAC over sender identity || receiver identity || message) is the same as
 * FluidMessages Web's (server/omemo_legacy.py), and both were checked against messages from an independent
 * implementation (app/src/test/resources/omemo).
 *
 * Primitives: X25519 and Ed25519 from Bouncy Castle (MIT-style license), HMAC-SHA-256, AES-CBC, and AES-GCM from
 * the platform. The ratchet follows the Double Ratchet specification's section 3 pseudocode, with its MAX_SKIP
 * limit, and works on a copy so a message that fails to open never changes the session. Never log keys or text.
 */
final class OmemoCore {
    private OmemoCore() {}

    static final String NAMESPACE = "eu.siacs.conversations.axolotl";
    static final String DEVICE_LIST_NODE = NAMESPACE + ".devicelist";
    static final String BUNDLES_NODE = NAMESPACE + ".bundles:";
    static final int VERSION = 0x33;
    static final int MAC_LENGTH = 8;
    /** Skipped message keys accepted in one message, and kept per session (oldest dropped first). */
    static final int MAX_SKIP = 1000;
    /** Replaced only by tests, to make their output repeatable. */
    static SecureRandom RANDOM = new SecureRandom();
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));

    static final class OmemoException extends Exception {
        OmemoException(String message) { super(message); }
    }

    // ----- Primitives -----

    static byte[] random(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    static byte[] x25519Public(byte[] priv) {
        byte[] pub = new byte[32];
        X25519.generatePublicKey(priv, 0, pub, 0);
        return pub;
    }

    /** X25519 (RFC 7748). Refuses an all-zero result, which a malicious key could force. */
    static byte[] dh(byte[] priv, byte[] pub) throws OmemoException {
        if (pub == null || pub.length != 32) throw new OmemoException("bad key");
        byte[] out = new byte[32];
        if (!X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) throw new OmemoException("bad key");
        return out;
    }

    static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] part : parts) mac.update(part);
            return mac.doFinal();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    /** HKDF-SHA-256 (RFC 5869). A null salt is 32 zero bytes. */
    static byte[] hkdf(byte[] salt, byte[] input, byte[] info, int length) {
        byte[] prk = hmac(salt == null ? new byte[32] : salt, input);
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        int filled = 0;
        for (int counter = 1; filled < length; counter++) {
            block = hmac(prk, block, info, new byte[] {(byte) counter});
            int take = Math.min(block.length, length - filled);
            System.arraycopy(block, 0, out, filled, take);
            filled += take;
        }
        return out;
    }

    static byte[] aesCbc(int mode, byte[] key, byte[] iv, byte[] data) throws OmemoException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException error) {
            throw new OmemoException("couldn't open");
        }
    }

    static byte[] aesGcm(int mode, byte[] key, byte[] iv, byte[] data) throws OmemoException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException error) {
            throw new OmemoException("couldn't open");
        }
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    // ----- Keys -----

    /** Encode(PK) of X3DH: the Curve25519 type byte 0x05, then the key. */
    static byte[] encodeKey(byte[] pub) { return concat(new byte[] {5}, pub); }

    static byte[] decodeKey(byte[] data) throws OmemoException {
        if (data != null && data.length == 33 && data[0] == 5) return Arrays.copyOfRange(data, 1, 33);
        if (data != null && data.length == 32) return data.clone();
        throw new OmemoException("unknown key encoding");
    }

    /**
     * An identity key pair. The secret is an Ed25519 seed (RFC 8032) whose public point has sign bit 0, so an
     * ordinary Ed25519 signature is also a valid XEdDSA signature for the matching Curve25519 key (XEdDSA section
     * 2.3: the verifier always takes sign bit 0). The X25519 private key is the same clamped scalar.
     */
    static final class Identity {
        final byte[] seed, priv, pub;

        Identity(byte[] seed) {
            this.seed = seed.clone();
            byte[] hash = sha512(seed);
            this.priv = Arrays.copyOf(hash, 32);
            this.priv[0] &= (byte) 248;
            this.priv[31] &= 127;
            this.priv[31] |= 64;
            this.pub = x25519Public(priv);
        }

        /** An identity known only by its X25519 private key (for tests); it can't sign. */
        private Identity(byte[] seed, byte[] priv) {
            this.seed = seed;
            this.priv = priv.clone();
            this.pub = x25519Public(priv);
        }

        static Identity fromPrivate(byte[] priv) { return new Identity(null, priv); }

        static Identity generate() {
            while (true) {
                byte[] seed = random(32);
                byte[] edPub = new byte[32];
                Ed25519.generatePublicKey(seed, 0, edPub, 0);
                if ((edPub[31] & 0x80) == 0) return new Identity(seed);
            }
        }

        byte[] sign(byte[] message) {
            byte[] signature = new byte[64];
            Ed25519.sign(seed, 0, message, 0, message.length, signature, 0);
            return signature;
        }
    }

    static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    /** XEdDSA verify (section 2.6): the Edwards key for Montgomery u is y = (u - 1) / (u + 1), sign bit 0. */
    static boolean xeddsaVerify(byte[] curvePub, byte[] message, byte[] signature) {
        if (curvePub == null || curvePub.length != 32 || signature == null || signature.length != 64) return false;
        byte[] le = curvePub.clone();
        le[31] &= 127;
        BigInteger u = new BigInteger(1, reverse(le));
        if (u.compareTo(P) >= 0) return false;
        BigInteger denominator = u.add(BigInteger.ONE).mod(P);
        if (denominator.signum() == 0) return false;
        BigInteger y = u.subtract(BigInteger.ONE).mod(P).multiply(denominator.modInverse(P)).mod(P);
        byte[] edPub = reverse(toFixed(y));
        byte[] sig = signature.clone();
        // Older signers stored the Edwards sign bit in the signature's last byte; accept that form too.
        if ((sig[63] & 0x80) != 0) {
            sig[63] &= 127;
            edPub[31] |= (byte) 0x80;
        }
        try {
            return Ed25519.verify(sig, 0, edPub, 0, message, 0, message.length);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static byte[] toFixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] reverse(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = data[data.length - 1 - i];
        return out;
    }

    // ----- Protocol buffers (varints and length-delimited fields only) -----

    static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    static void field(ByteArrayOutputStream out, int number, long value) {
        varint(out, (long) number << 3);
        varint(out, value);
    }

    static void field(ByteArrayOutputStream out, int number, byte[] value) {
        varint(out, ((long) number << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    /** Fields by number: Long for varints, byte[] for bytes. The last one wins. */
    static Map<Integer, Object> parse(byte[] data, int from, int to) throws OmemoException {
        Map<Integer, Object> fields = new HashMap<>();
        int[] at = {from};
        while (at[0] < to) {
            long tag = readVarint(data, at, to);
            int number = (int) (tag >>> 3), wire = (int) (tag & 7);
            if (wire == 0) {
                fields.put(number, readVarint(data, at, to));
            } else if (wire == 2) {
                long length = readVarint(data, at, to);
                if (length < 0 || at[0] + length > to) throw new OmemoException("truncated");
                fields.put(number, Arrays.copyOfRange(data, at[0], at[0] + (int) length));
                at[0] += (int) length;
            } else {
                throw new OmemoException("unsupported field");
            }
        }
        return fields;
    }

    private static long readVarint(byte[] data, int[] at, int to) throws OmemoException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (at[0] >= to) throw new OmemoException("truncated");
            int b = data[at[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new OmemoException("bad varint");
    }

    static long number(Map<Integer, Object> fields, int key) throws OmemoException {
        Object value = fields.get(key);
        if (value == null) return 0;
        if (!(value instanceof Long)) throw new OmemoException("bad field");
        return (Long) value;
    }

    static byte[] bytes(Map<Integer, Object> fields, int key) throws OmemoException {
        Object value = fields.get(key);
        if (!(value instanceof byte[])) throw new OmemoException("missing field");
        return (byte[]) value;
    }

    // ----- Double Ratchet (specification section 3) -----

    /** One device's session. Kept as JSON in the encrypted account store; copied before each decryption. */
    static final class Session {
        byte[] rootKey, dhsPriv, dhsPub, dhr, cks, ckr;
        int ns, nr, pn;
        final LinkedHashMap<String, byte[]> skipped = new LinkedHashMap<>();
        byte[] localIdentity, remoteIdentity;   // Curve25519 public keys
        boolean initiator, confirmed;
        // The key exchange, sent with every message until the other side answers (initiator), or the one this
        // session came from (responder), to recognize repeats.
        int preKeyId, signedPreKeyId;
        byte[] baseKey;

        Session copy() {
            Session s = new Session();
            s.rootKey = rootKey; s.dhsPriv = dhsPriv; s.dhsPub = dhsPub; s.dhr = dhr; s.cks = cks; s.ckr = ckr;
            s.ns = ns; s.nr = nr; s.pn = pn;
            s.skipped.putAll(skipped);  // Keys are replaced, never changed in place, so sharing the arrays is safe.
            s.localIdentity = localIdentity; s.remoteIdentity = remoteIdentity;
            s.initiator = initiator; s.confirmed = confirmed;
            s.preKeyId = preKeyId; s.signedPreKeyId = signedPreKeyId; s.baseKey = baseKey;
            return s;
        }

        JSONObject toJson() throws org.json.JSONException {
            JSONObject json = new JSONObject();
            put(json, "rk", rootKey); put(json, "dhsPriv", dhsPriv); put(json, "dhsPub", dhsPub); put(json, "dhr", dhr);
            put(json, "cks", cks); put(json, "ckr", ckr); put(json, "li", localIdentity); put(json, "ri", remoteIdentity);
            put(json, "base", baseKey);
            json.put("ns", ns).put("nr", nr).put("pn", pn).put("initiator", initiator).put("confirmed", confirmed)
                .put("pk", preKeyId).put("spk", signedPreKeyId);
            JSONArray list = new JSONArray();
            for (Map.Entry<String, byte[]> entry : skipped.entrySet()) {
                list.put(new JSONArray().put(entry.getKey()).put(b64(entry.getValue())));
            }
            return json.put("skipped", list);
        }

        static Session fromJson(JSONObject json) throws org.json.JSONException {
            Session s = new Session();
            s.rootKey = get(json, "rk"); s.dhsPriv = get(json, "dhsPriv"); s.dhsPub = get(json, "dhsPub"); s.dhr = get(json, "dhr");
            s.cks = get(json, "cks"); s.ckr = get(json, "ckr"); s.localIdentity = get(json, "li"); s.remoteIdentity = get(json, "ri");
            s.baseKey = get(json, "base");
            s.ns = json.getInt("ns"); s.nr = json.getInt("nr"); s.pn = json.getInt("pn");
            s.initiator = json.getBoolean("initiator"); s.confirmed = json.getBoolean("confirmed");
            s.preKeyId = json.getInt("pk"); s.signedPreKeyId = json.getInt("spk");
            JSONArray list = json.optJSONArray("skipped");
            for (int i = 0; list != null && i < list.length(); i++) {
                s.skipped.put(list.getJSONArray(i).getString(0), unb64(list.getJSONArray(i).getString(1)));
            }
            return s;
        }

        private static void put(JSONObject json, String key, byte[] value) throws org.json.JSONException {
            if (value != null) json.put(key, b64(value));
        }

        private static byte[] get(JSONObject json, String key) {
            return json.has(key) ? unb64(json.optString(key)) : null;
        }
    }

    /** KDF_RK (section 5.2): HKDF with the root key as salt, info "WhisperRatchet". Returns {root key, chain key}. */
    static byte[][] kdfRk(byte[] rootKey, byte[] dhOut) {
        byte[] out = hkdf(rootKey, dhOut, "WhisperRatchet".getBytes(), 64);
        return new byte[][] {Arrays.copyOf(out, 32), Arrays.copyOfRange(out, 32, 64)};
    }

    /** KDF_CK (section 5.2): message key HMAC(ck, 0x01), next chain key HMAC(ck, 0x02). Returns {chain, message}. */
    static byte[][] kdfCk(byte[] chainKey) {
        return new byte[][] {hmac(chainKey, new byte[] {2}), hmac(chainKey, new byte[] {1})};
    }

    /** The message keys (section 5.2): HKDF, zero salt, info "WhisperMessageKeys": AES-256 key, HMAC key, IV. */
    static byte[][] messageKeys(byte[] messageKey) {
        byte[] out = hkdf(null, messageKey, "WhisperMessageKeys".getBytes(), 80);
        return new byte[][] {Arrays.copyOf(out, 32), Arrays.copyOfRange(out, 32, 64), Arrays.copyOfRange(out, 64, 80)};
    }

    /** RatchetEncrypt (section 3.4), producing a serialized Signal message: version, fields 1-4, then the MAC. */
    static byte[] encrypt(Session s, byte[] plaintext) throws OmemoException {
        if (s.cks == null) throw new OmemoException("no sending chain yet");
        byte[][] step = kdfCk(s.cks);
        s.cks = step[0];
        byte[][] keys = messageKeys(step[1]);
        byte[] ciphertext = aesCbc(Cipher.ENCRYPT_MODE, keys[0], keys[2], plaintext);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(VERSION);
        field(body, 1, encodeKey(s.dhsPub));
        field(body, 2, s.ns);
        field(body, 3, s.pn);
        field(body, 4, ciphertext);
        s.ns++;
        byte[] serialized = body.toByteArray();
        byte[] mac = Arrays.copyOf(hmac(keys[1], encodeKey(s.localIdentity), encodeKey(s.remoteIdentity), serialized), MAC_LENGTH);
        return concat(serialized, mac);
    }

    /** RatchetDecrypt (section 3.5) on a copy of the session; returns the new session state and the plaintext. */
    static Object[] decrypt(Session original, byte[] message) throws OmemoException {
        if (message.length < 1 + MAC_LENGTH || ((message[0] & 0xFF) >> 4) != 3) throw new OmemoException("version");
        Map<Integer, Object> fields = parse(message, 1, message.length - MAC_LENGTH);
        byte[] ratchet = decodeKey(bytes(fields, 1));
        long n = number(fields, 2), pn = number(fields, 3);
        byte[] ciphertext = bytes(fields, 4);
        if (n < 0 || n > Integer.MAX_VALUE || pn < 0 || pn > Integer.MAX_VALUE) throw new OmemoException("counter");
        Session s = original.copy();
        byte[] messageKey = s.skipped.remove(hex(ratchet) + ":" + n);
        if (messageKey == null) {
            if (s.dhr == null || !Arrays.equals(ratchet, s.dhr)) {
                skip(s, (int) pn);
                // DHRatchet
                s.pn = s.ns;
                s.ns = 0;
                s.nr = 0;
                s.dhr = ratchet;
                byte[][] receiving = kdfRk(s.rootKey, dh(s.dhsPriv, s.dhr));
                s.rootKey = receiving[0];
                s.ckr = receiving[1];
                s.dhsPriv = random(32);
                s.dhsPub = x25519Public(s.dhsPriv);
                byte[][] sending = kdfRk(s.rootKey, dh(s.dhsPriv, s.dhr));
                s.rootKey = sending[0];
                s.cks = sending[1];
            }
            skip(s, (int) n);
            byte[][] step = kdfCk(s.ckr);
            s.ckr = step[0];
            messageKey = step[1];
            s.nr++;
        }
        byte[][] keys = messageKeys(messageKey);
        byte[] expected = Arrays.copyOf(hmac(keys[1], encodeKey(s.remoteIdentity), encodeKey(s.localIdentity),
            Arrays.copyOf(message, message.length - MAC_LENGTH)), MAC_LENGTH);
        if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(message, message.length - MAC_LENGTH, message.length))) {
            throw new OmemoException("couldn't open");
        }
        byte[] plaintext = aesCbc(Cipher.DECRYPT_MODE, keys[0], keys[2], ciphertext);
        s.confirmed = true;
        return new Object[] {s, plaintext};
    }

    /** SkipMessageKeys (section 3.5), with MAX_SKIP per message and per session. */
    private static void skip(Session s, int until) throws OmemoException {
        if (s.ckr == null) return;
        if (until - s.nr > MAX_SKIP) throw new OmemoException("too many skipped messages");
        while (s.nr < until) {
            byte[][] step = kdfCk(s.ckr);
            s.ckr = step[0];
            s.skipped.put(hex(s.dhr) + ":" + s.nr, step[1]);
            s.nr++;
        }
        Iterator<String> oldest = s.skipped.keySet().iterator();
        while (s.skipped.size() > MAX_SKIP && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    // ----- X3DH (sections 3.3 and 3.4) -----

    /** A device's published keys. */
    static final class Bundle {
        byte[] identity, signedPreKey, signature;
        int signedPreKeyId;
        final LinkedHashMap<Integer, byte[]> preKeys = new LinkedHashMap<>();
    }

    /** Our own keys: identity, the signed prekey (and the one before it), and one-time prekeys by id. */
    static final class OwnKeys {
        Identity identity;
        int signedPreKeyId, oldSignedPreKeyId;
        byte[] signedPreKeyPriv, signedPreKeySignature, oldSignedPreKeyPriv;
        long signedPreKeyTime;
        final LinkedHashMap<Integer, byte[]> preKeys = new LinkedHashMap<>();
        int nextPreKeyId = 1;
        int registrationId;

        static OwnKeys create() {
            OwnKeys keys = new OwnKeys();
            keys.identity = Identity.generate();
            keys.registrationId = 1 + RANDOM.nextInt(16380);
            keys.rotateSignedPreKey();
            keys.refill(100);
            return keys;
        }

        void rotateSignedPreKey() {
            oldSignedPreKeyId = signedPreKeyId;
            oldSignedPreKeyPriv = signedPreKeyPriv;
            signedPreKeyId = signedPreKeyId % 0xFFFFFF + 1;
            signedPreKeyPriv = random(32);
            signedPreKeySignature = identity.sign(encodeKey(x25519Public(signedPreKeyPriv)));
            signedPreKeyTime = System.currentTimeMillis();
        }

        /** Tops the one-time prekeys back up to [count] (XEP-0384 0.3: 100, and at least 20). */
        void refill(int count) {
            while (preKeys.size() < count) {
                while (preKeys.containsKey(nextPreKeyId)) nextPreKeyId = nextPreKeyId % 0xFFFFFF + 1;
                preKeys.put(nextPreKeyId, random(32));
                nextPreKeyId = nextPreKeyId % 0xFFFFFF + 1;
            }
        }

        Bundle bundle() {
            Bundle bundle = new Bundle();
            bundle.identity = identity.pub;
            bundle.signedPreKeyId = signedPreKeyId;
            bundle.signedPreKey = x25519Public(signedPreKeyPriv);
            bundle.signature = signedPreKeySignature;
            for (Map.Entry<Integer, byte[]> entry : preKeys.entrySet()) bundle.preKeys.put(entry.getKey(), x25519Public(entry.getValue()));
            return bundle;
        }

        byte[] signedPreKey(int id) {
            if (id == signedPreKeyId) return signedPreKeyPriv;
            if (id == oldSignedPreKeyId && oldSignedPreKeyPriv != null) return oldSignedPreKeyPriv;
            return null;
        }
    }

    static byte[] sharedSecret(byte[]... dhs) {
        byte[] input = new byte[32];
        Arrays.fill(input, (byte) 0xFF);
        return hkdf(null, concat(input, concat(dhs)), "WhisperText".getBytes(), 32);
    }

    /** Starts a session with a device from its bundle (active X3DH, then the Double Ratchet's RatchetInitAlice). */
    static Session start(OwnKeys own, Bundle bundle) throws OmemoException {
        if (!xeddsaVerify(bundle.identity, encodeKey(bundle.signedPreKey), bundle.signature)) {
            throw new OmemoException("The device's signed prekey isn't signed by its identity key");
        }
        if (bundle.preKeys.isEmpty()) throw new OmemoException("The device has no prekeys left");
        List<Integer> ids = new ArrayList<>(bundle.preKeys.keySet());
        int preKeyId = ids.get(RANDOM.nextInt(ids.size()));
        byte[] ephemeral = random(32);
        byte[] secret = sharedSecret(dh(own.identity.priv, bundle.signedPreKey), dh(ephemeral, bundle.identity),
            dh(ephemeral, bundle.signedPreKey), dh(ephemeral, bundle.preKeys.get(preKeyId)));
        Session s = new Session();
        s.initiator = true;
        s.localIdentity = own.identity.pub;
        s.remoteIdentity = bundle.identity;
        s.preKeyId = preKeyId;
        s.signedPreKeyId = bundle.signedPreKeyId;
        s.baseKey = x25519Public(ephemeral);
        s.dhsPriv = random(32);
        s.dhsPub = x25519Public(s.dhsPriv);
        s.dhr = bundle.signedPreKey;
        byte[][] sending = kdfRk(secret, dh(s.dhsPriv, s.dhr));
        s.rootKey = sending[0];
        s.cks = sending[1];
        return s;
    }

    /** A key exchange message: version byte, then the prekey ids, base and identity keys, and the first message. */
    static byte[] keyExchange(Session s, int registrationId, byte[] message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(VERSION);
        field(out, 5, registrationId);
        field(out, 1, s.preKeyId);
        field(out, 6, s.signedPreKeyId);
        field(out, 2, encodeKey(s.baseKey));
        field(out, 3, encodeKey(s.localIdentity));
        field(out, 4, message);
        return out.toByteArray();
    }

    /** The parts of a received key exchange. */
    static final class KeyExchange {
        int preKeyId, signedPreKeyId;
        byte[] baseKey, identity, message;
    }

    static KeyExchange parseKeyExchange(byte[] data) throws OmemoException {
        if (data.length < 2 || ((data[0] & 0xFF) >> 4) != 3) throw new OmemoException("version");
        Map<Integer, Object> fields = parse(data, 1, data.length);
        KeyExchange exchange = new KeyExchange();
        exchange.preKeyId = (int) number(fields, 1);
        exchange.signedPreKeyId = (int) number(fields, 6);
        exchange.baseKey = decodeKey(bytes(fields, 2));
        exchange.identity = decodeKey(bytes(fields, 3));
        exchange.message = bytes(fields, 4);
        return exchange;
    }

    /** Whether a key exchange repeats the one a session came from (the sender keeps sending it until we answer). */
    static boolean sameExchange(Session s, KeyExchange exchange) {
        return s != null && !s.initiator && s.baseKey != null && Arrays.equals(s.baseKey, exchange.baseKey)
            && Arrays.equals(s.remoteIdentity, exchange.identity) && s.preKeyId == exchange.preKeyId;
    }

    /** Answers a key exchange (passive X3DH, then RatchetInitBob). The caller deletes the used prekey. */
    static Session accept(OwnKeys own, KeyExchange exchange) throws OmemoException {
        byte[] signed = own.signedPreKey(exchange.signedPreKeyId);
        byte[] preKey = own.preKeys.get(exchange.preKeyId);
        if (signed == null || preKey == null) throw new OmemoException("The key exchange uses a prekey this device no longer has");
        byte[] secret = sharedSecret(dh(signed, exchange.identity), dh(own.identity.priv, exchange.baseKey),
            dh(signed, exchange.baseKey), dh(preKey, exchange.baseKey));
        Session s = new Session();
        s.initiator = false;
        s.localIdentity = own.identity.pub;
        s.remoteIdentity = exchange.identity;
        s.preKeyId = exchange.preKeyId;
        s.signedPreKeyId = exchange.signedPreKeyId;
        s.baseKey = exchange.baseKey;
        s.dhsPriv = signed;
        s.dhsPub = x25519Public(signed);
        s.rootKey = secret;
        return s;
    }

    // ----- Payloads (XEP-0384 0.3 section 4.5): AES-128-GCM; the key and tag travel in each device's message. -----

    /** Returns {ciphertext without tag, iv, key || tag}. */
    static byte[][] sealPayload(byte[] plaintext) throws OmemoException {
        byte[] key = random(16), iv = random(12);
        byte[] sealed = aesGcm(Cipher.ENCRYPT_MODE, key, iv, plaintext);
        int split = sealed.length - 16;
        return new byte[][] {Arrays.copyOf(sealed, split), iv, concat(key, Arrays.copyOfRange(sealed, split, sealed.length))};
    }

    /** Opens a payload with key || tag (32 bytes), or a 16-byte key with the tag at the end of the payload. */
    static byte[] openPayload(byte[] keyMaterial, byte[] iv, byte[] payload) throws OmemoException {
        if (keyMaterial.length >= 32) {
            return aesGcm(Cipher.DECRYPT_MODE, Arrays.copyOf(keyMaterial, 16), iv, concat(payload, Arrays.copyOfRange(keyMaterial, 16, 32)));
        }
        if (keyMaterial.length == 16) return aesGcm(Cipher.DECRYPT_MODE, keyMaterial, iv, payload);
        throw new OmemoException("unexpected key length");
    }

    // ----- Small helpers -----

    static String b64(byte[] data) { return Base64.getEncoder().encodeToString(data); }

    static byte[] unb64(String text) {
        return Base64.getMimeDecoder().decode(text == null ? "" : text.trim());
    }

    static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format("%02x", b & 0xFF));
        return out.toString();
    }
}
