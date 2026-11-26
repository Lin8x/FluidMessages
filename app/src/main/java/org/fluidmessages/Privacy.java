package org.fluidmessages;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Privacy on the phone itself.
 *
 * <p>Stored messages and secrets are sealed with a data key that only this install's Android Keystore can
 * unwrap. Hidden chats live in a separate vault: anything moved there is encrypted to a vault public key, so
 * new hidden messages can be stored while the vault is closed, but only the vault code opens them again.
 * Chats and protocols can also carry their own codes, checked against a slow salted hash.
 */
final class Privacy {
    private static final String WRAP_KEY = "fluid-at-rest";
    private static final String SEALED = "e1:";
    private static final int ITERATIONS = 120_000;
    private static final SecureRandom random = new SecureRandom();
    private static SecretKey dataKey;
    private static PrivateKey vaultKey;
    /** Chats and protocols opened with their code since the app last went to the background. */
    private static final Set<String> opened = Collections.synchronizedSet(new HashSet<>());

    private Privacy() {}

    // At-rest sealing

    private static synchronized SecretKey dataKey(SharedPreferences prefs) throws Exception {
        if (dataKey != null) return dataKey;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey wrap;
        if (store.getKey(WRAP_KEY, null) instanceof SecretKey existing) wrap = existing;
        else {
            var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(WRAP_KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            wrap = generator.generateKey();
        }
        String wrapped = prefs.getString("atRestKey", "");
        if (!wrapped.isEmpty()) {
            byte[] blob = Base64.decode(wrapped, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrap, new GCMParameterSpec(128, blob, 0, 12));
            dataKey = new SecretKeySpec(cipher.doFinal(blob, 12, blob.length - 12), "AES");
            return dataKey;
        }
        // The data key stays in software so a full inbox opens quickly; only its wrapped copy is stored.
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrap);
        prefs.edit().putString("atRestKey", Base64.encodeToString(concat(cipher.getIV(), cipher.doFinal(raw)), Base64.NO_WRAP)).commit();
        dataKey = new SecretKeySpec(raw, "AES");
        return dataKey;
    }

    static boolean sealed(String value) { return value != null && value.startsWith(SEALED); }

    static String seal(SharedPreferences prefs, String value) {
        try { return SEALED + Base64.encodeToString(aesSeal(dataKey(prefs), value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP); }
        catch (Exception error) { throw new IllegalStateException("Could not encrypt stored data", error); }
    }

    /** Opens a sealed value; values written before encryption was added pass through unchanged. */
    static String open(SharedPreferences prefs, String value) {
        if (!sealed(value)) return value;
        try {
            return new String(aesOpen(dataKey(prefs), Base64.decode(value.substring(SEALED.length()), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("Could not decrypt stored data", error); }
    }

    static String token(SharedPreferences prefs) {
        String value = prefs.getString("token", "");
        try { return open(prefs, value == null ? "" : value); } catch (Exception error) { return ""; }
    }

    /** Seals secrets saved by versions that stored them in the clear. */
    static void migrate(SharedPreferences prefs) {
        String token = prefs.getString("token", "");
        if (token != null && !token.isEmpty() && !sealed(token)) prefs.edit().putString("token", seal(prefs, token)).apply();
    }

    // Hidden chats

    static boolean vaultExists(SharedPreferences prefs) { return !prefs.getString("vault.public", "").isEmpty(); }
    static boolean vaultOpen() { return vaultKey != null; }
    static synchronized void closeVault() { vaultKey = null; }

    /** Creates the vault, or changes its code when it is open. Existing hidden messages stay readable. */
    static synchronized void setVaultCode(SharedPreferences prefs, String code) throws Exception {
        PrivateKey privateKey;
        String publicKey;
        if (vaultExists(prefs)) {
            if (vaultKey == null) throw new IllegalStateException("Open the private folder first");
            privateKey = vaultKey;
            publicKey = prefs.getString("vault.public", "");
        } else {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = generator.generateKeyPair();
            privateKey = pair.getPrivate();
            publicKey = Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP);
        }
        byte[] salt = randomBytes(16);
        byte[] sealedKey = aesSeal(stretch(code, salt), privateKey.getEncoded());
        prefs.edit().putString("vault.public", publicKey).putString("vault.salt", b64(salt))
            .putString("vault.private", b64(sealedKey)).commit();
        vaultKey = privateKey;
    }

    /** Tries a code against the vault. Codes shorter than four characters are never checked. */
    static synchronized boolean openVault(SharedPreferences prefs, String code) {
        if (!vaultExists(prefs) || code == null || code.length() < 4) return false;
        try {
            byte[] raw = aesOpen(stretch(code, unb64(prefs.getString("vault.salt", ""))), unb64(prefs.getString("vault.private", "")));
            vaultKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(raw));
            return true;
        } catch (Exception wrongCode) { return false; }
    }

    /** Whether this is the private folder passcode, without opening or closing anything. */
    static boolean checkVaultCode(SharedPreferences prefs, String code) {
        if (!vaultExists(prefs) || code == null || code.length() < 4) return false;
        try {
            aesOpen(stretch(code, unb64(prefs.getString("vault.salt", ""))), unb64(prefs.getString("vault.private", "")));
            return true;
        } catch (Exception wrongCode) { return false; }
    }

    // Unlocking hidden chats with a fingerprint, face, or the phone's screen lock

    private static final String DEVICE_KEY = "fluid-vault-device";

    /** A keystore key that only works for a few seconds after you unlock with your fingerprint, face, or screen lock. */
    private static SecretKey deviceKey(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(DEVICE_KEY, null) instanceof SecretKey existing) return existing;
        if (!create) throw new IllegalStateException("Turn on quick unlock again in Private folder");
        var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(DEVICE_KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(15, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build());
        return generator.generateKey();
    }

    static boolean deviceUnlockOn(SharedPreferences prefs) { return !prefs.getString("vault.device", "").isEmpty(); }

    /** Call right after the person unlocks with their fingerprint, face, or screen lock, with hidden chats open. */
    static synchronized void enableDeviceUnlock(SharedPreferences prefs) throws Exception {
        if (vaultKey == null) throw new IllegalStateException("Open the private folder first");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey(true));
        prefs.edit().putString("vault.device", b64(concat(cipher.getIV(), cipher.doFinal(vaultKey.getEncoded())))).commit();
    }

    static void disableDeviceUnlock(SharedPreferences prefs) {
        prefs.edit().remove("vault.device").apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(DEVICE_KEY);
        } catch (Exception ignored) {}
    }

    /** Call right after the person unlocks with their fingerprint, face, or screen lock. */
    static synchronized boolean openVaultWithDevice(SharedPreferences prefs) {
        try {
            byte[] blob = unb64(prefs.getString("vault.device", ""));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deviceKey(false), new GCMParameterSpec(128, blob, 0, 12));
            byte[] raw = cipher.doFinal(blob, 12, blob.length - 12);
            vaultKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(raw));
            return true;
        } catch (Exception error) { return false; }
    }

    // Decoy mode: only decoy chats and the chats you allow show, until you type your hidden chats passcode.

    static boolean decoyMode(SharedPreferences prefs) { return prefs.getBoolean("decoyMode", false); }

    static void setDecoyMode(SharedPreferences prefs, boolean on) { prefs.edit().putBoolean("decoyMode", on).commit(); }

    static Set<String> decoyAllowed(SharedPreferences prefs) { return sealedSet(prefs, "decoy.allowed"); }

    static void setDecoyAllowed(SharedPreferences prefs, Set<String> addresses) {
        prefs.edit().putString("decoy.allowed", seal(prefs, new JSONArray(addresses).toString())).apply();
    }

    /** Removes the vault after its hidden chats have been restored. */
    static synchronized void removeVault(SharedPreferences prefs) {
        disableDeviceUnlock(prefs);
        prefs.edit().remove("vault.public").remove("vault.salt").remove("vault.private").remove("hidden.addresses")
            .remove("hidden.protocols").remove("vault.inSettings").apply();
        vaultKey = null;
    }

    /** Encrypts to the vault's public key, so it works while the vault is closed. */
    static String vaultSeal(SharedPreferences prefs, String value) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(prefs.getString("vault.public", ""))));
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var ephemeral = generator.generateKeyPair();
        byte[] key = agree(ephemeral.getPrivate(), publicKey);
        byte[] ephemeralKey = ephemeral.getPublic().getEncoded();
        byte[] body = aesSeal(new SecretKeySpec(key, "AES"), value.getBytes(StandardCharsets.UTF_8));
        return b64(ByteBuffer.allocate(2 + ephemeralKey.length + body.length).putShort((short) ephemeralKey.length)
            .put(ephemeralKey).put(body).array());
    }

    static String vaultOpen(String sealed) throws Exception {
        PrivateKey key = vaultKey;
        if (key == null) throw new IllegalStateException("The private folder is closed");
        var buffer = ByteBuffer.wrap(unb64(sealed));
        byte[] ephemeralKey = new byte[buffer.getShort()];
        buffer.get(ephemeralKey);
        byte[] body = new byte[buffer.remaining()];
        buffer.get(body);
        PublicKey ephemeral = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(ephemeralKey));
        return new String(aesOpen(new SecretKeySpec(agree(key, ephemeral), "AES"), body), StandardCharsets.UTF_8);
    }

    private static byte[] agree(PrivateKey own, PublicKey other) throws Exception {
        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(own);
        agreement.doPhase(other, true);
        return MessageDigest.getInstance("SHA-256").digest(agreement.generateSecret());
    }

    static Set<String> hiddenAddresses(SharedPreferences prefs) { return sealedSet(prefs, "hidden.addresses"); }
    static Set<String> hiddenProtocols(SharedPreferences prefs) { return sealedSet(prefs, "hidden.protocols"); }

    static void setHidden(SharedPreferences prefs, String key, String value, boolean hidden) {
        var values = sealedSet(prefs, key);
        if (hidden) values.add(value); else values.remove(value);
        prefs.edit().putString(key, seal(prefs, new JSONArray(values).toString())).apply();
    }

    /** Opened sets by key, reused while the stored value is unchanged: the inbox asks once per message. */
    private static final Map<String, Object[]> openedSets = new java.util.concurrent.ConcurrentHashMap<>();

    private static Set<String> sealedSet(SharedPreferences prefs, String key) {
        String stored = prefs.getString(key, "");
        if (stored == null || stored.isEmpty()) return new LinkedHashSet<>();
        Object[] cached = openedSets.get(key);
        @SuppressWarnings("unchecked")
        Set<String> known = cached != null && stored.equals(cached[0]) ? (Set<String>) cached[1] : null;
        if (known != null) return new LinkedHashSet<>(known);
        var values = new LinkedHashSet<String>();
        try {
            var array = new JSONArray(open(prefs, stored));
            for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
            openedSets.put(key, new Object[]{stored, java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values))});
        } catch (Exception ignored) {}
        return values;
    }

    /** True when a conversation or its protocol is hidden and the vault is closed. */
    static boolean hides(SharedPreferences prefs, String address, MessageProtocol protocol) {
        if (vaultKey != null || !vaultExists(prefs)) return false;
        return hiddenAddresses(prefs).contains(address) || hiddenProtocols(prefs).contains(protocol.family().name());
    }

    static boolean hidden(SharedPreferences prefs, String address, MessageProtocol protocol) {
        return vaultExists(prefs) && (hiddenAddresses(prefs).contains(address) || hiddenProtocols(prefs).contains(protocol.family().name()));
    }

    // Decoy conversations

    static final String DECOY = "decoy:";

    /** Made-up conversations shown in the inbox while hidden chats are closed. They are never sent. */
    static List<JSONObject> decoys(SharedPreferences prefs) {
        var rows = new ArrayList<JSONObject>();
        String stored = prefs.getString("decoys", "");
        if (stored == null || stored.isEmpty()) return rows;
        try {
            var array = new JSONArray(open(prefs, stored));
            for (int i = 0; i < array.length(); i++) rows.add(array.getJSONObject(i));
        } catch (Exception ignored) {}
        return rows;
    }

    static void saveDecoys(SharedPreferences prefs, List<JSONObject> rows) {
        prefs.edit().putString("decoys", seal(prefs, new JSONArray(rows).toString())).apply();
    }

    static void addDecoy(SharedPreferences prefs, String name, String body, boolean outgoing, long date) throws Exception {
        var rows = decoys(prefs);
        rows.add(new JSONObject().put("id", "decoy-" + Long.toHexString(random.nextLong())).put("address", DECOY + name)
            .put("body", body).put("date", date).put("type", outgoing ? 2 : 1).put("read", 1).put("protocol", "sms")
            .put("status", outgoing ? "Sent" : ""));
        saveDecoys(prefs, rows);
    }

    static void removeDecoyChat(SharedPreferences prefs, String name) {
        var rows = decoys(prefs);
        rows.removeIf(row -> row.optString("address").equals(DECOY + name));
        saveDecoys(prefs, rows);
    }

    // Chat and protocol codes

    static String chatScope(String address) { return "chat:" + address; }
    static String protocolScope(MessageProtocol protocol) { return "protocol:" + protocol.family().name(); }

    static boolean hasCode(SharedPreferences prefs, String scope) { return !prefs.getString("code." + scope, "").isEmpty(); }

    /** Kept beside the codes (and with them through a settings reset): a lock that opens with the phone's screen lock. */
    private static final String SCREEN_LOCK = "code.device.";

    /** Whether a chat or kind lock asks for the phone's PIN, pattern, password, fingerprint, or face instead of a code. */
    static boolean usesScreenLock(SharedPreferences prefs, String scope) { return prefs.getBoolean(SCREEN_LOCK + scope, false); }

    /** Whether a chat or kind is locked at all, with a passcode or with the phone's screen lock. */
    static boolean hasLock(SharedPreferences prefs, String scope) { return hasCode(prefs, scope) || usesScreenLock(prefs, scope); }

    /** Locks a chat or kind with the phone's screen lock, replacing any passcode. Call after the phone unlock succeeded. */
    static void setScreenLock(SharedPreferences prefs, String scope) {
        prefs.edit().remove("code." + scope).putBoolean(SCREEN_LOCK + scope, true).apply();
        opened.add(scope);
    }

    /** Turns a chat or kind lock off, whichever kind it was. */
    static void removeLock(SharedPreferences prefs, String scope) {
        prefs.edit().remove("code." + scope).remove(SCREEN_LOCK + scope).apply();
        opened.remove(scope);
    }

    /** Opens a lock after the phone's screen lock was confirmed. */
    static void markOpened(String scope) { opened.add(scope); }

    static Set<String> openedScopes() {
        synchronized (opened) { return new HashSet<>(opened); }
    }

    /** Closes every opened lock not in [keep] (ChatLocks.stillOpen). True when something closed. */
    static boolean keepOpen(Set<String> keep) { return opened.retainAll(keep); }

    static void setCode(SharedPreferences prefs, String scope, String code) throws Exception {
        if (code == null || code.isEmpty()) {
            prefs.edit().remove("code." + scope).remove(SCREEN_LOCK + scope).apply();
            opened.remove(scope);
            return;
        }
        byte[] salt = randomBytes(16);
        prefs.edit().putString("code." + scope, b64(salt) + "." + b64(stretch(code, salt).getEncoded()))
            .remove(SCREEN_LOCK + scope).apply();
        opened.add(scope);
    }

    static boolean checkCode(SharedPreferences prefs, String scope, String code) {
        String[] stored = prefs.getString("code." + scope, "").split("\\.", 2);
        if (stored.length != 2 || code == null) return false;
        try {
            boolean match = MessageDigest.isEqual(stretch(code, unb64(stored[0])).getEncoded(), unb64(stored[1]));
            if (match) opened.add(scope);
            return match;
        } catch (Exception error) { return false; }
    }

    /** The codes a conversation still needs before it opens: its own, then its protocol's. */
    static List<String> lockedScopes(SharedPreferences prefs, String address, MessageProtocol protocol) {
        var result = new ArrayList<String>();
        for (String scope : List.of(chatScope(address), protocolScope(protocol))) {
            if (hasLock(prefs, scope) && !opened.contains(scope)) result.add(scope);
        }
        return result;
    }

    /** The inbox line for a locked chat, or null when it isn't locked right now. */
    static String lockedPreview(SharedPreferences prefs, String address, MessageProtocol protocol) {
        var scopes = lockedScopes(prefs, address, protocol);
        if (scopes.isEmpty()) return null;
        return usesScreenLock(prefs, scopes.get(0)) ? "Locked · unlock to read" : "Locked · enter the passcode to read";
    }

    static boolean locked(SharedPreferences prefs, String address, MessageProtocol protocol) {
        return !lockedScopes(prefs, address, protocol).isEmpty();
    }

    /** Called when the app leaves the screen: hidden chats close and chat codes are asked for again. */
    static void forget() {
        opened.clear();
        closeVault();
    }

    // Primitives

    static SecretKey stretch(String code, byte[] salt) throws Exception { return stretch(code, salt, ITERATIONS); }

    static SecretKey stretch(String code, byte[] salt, int iterations) throws Exception {
        var spec = new PBEKeySpec(code.toCharArray(), salt, iterations, 256);
        return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
    }

    static byte[] aesSeal(SecretKey key, byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = randomBytes(12);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return concat(iv, cipher.doFinal(plain));
    }

    static byte[] aesOpen(SecretKey key, byte[] sealed) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
        return cipher.doFinal(sealed, 12, sealed.length - 12);
    }

    static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    static String b64(byte[] bytes) { return Base64.encodeToString(bytes, Base64.NO_WRAP); }
    static byte[] unb64(String value) { return Base64.decode(value, Base64.NO_WRAP); }
}
