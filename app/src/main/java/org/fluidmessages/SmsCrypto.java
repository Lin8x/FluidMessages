package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.PhoneNumberUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.json.JSONObject;

/**
 * Encrypted texting over plain SMS and MMS, for people who both use FluidMessages.
 *
 * <p>Both people enter the same key. The text that crosses the carrier, and the copy in Android's shared SMS
 * store, is {@code FM1:} followed by Base64 of: a 4-byte key id, a 12-byte nonce, and AES-256-GCM ciphertext. The
 * AES key is PBKDF2-HMAC-SHA256 of the shared key, salt {@code FluidMessages SMS 1}, 100,000 rounds. Inside is a
 * version byte (1), a 4-byte disappear-after time in seconds (0 for never), then the UTF-8 text. Everyone else
 * sees the scrambled form, and the Base64 alphabet keeps it in the carrier's 7-bit character set.
 */
final class SmsCrypto {
    static final String PREFIX = "FM1:";
    /** UTF-8 bytes; the encoded result still fits in Android's 1,600-character text limit. */
    static final int MAX_TEXT = 1000;
    private static final byte[] SALT = "FluidMessages SMS 1".getBytes(StandardCharsets.US_ASCII);
    private static final Map<String, SecretKey> derived = new ConcurrentHashMap<>();

    record Opened(String text, int expireSeconds) {}

    private SmsCrypto() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    // Settings

    /** Protocols FluidMessages can encrypt itself: texts, group texts, and internet chats sent from the phone. */
    static final List<MessageProtocol> PROTOCOLS = List.of(MessageProtocol.SMS, MessageProtocol.MMS, MessageProtocol.MATRIX,
        MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC);

    /** The default for every chat on this protocol. Versions before per-protocol settings had one switch for texts. */
    static boolean globalOn(SharedPreferences prefs, MessageProtocol protocol) {
        var family = protocol == MessageProtocol.RCS ? MessageProtocol.SMS : protocol;
        boolean legacy = (family == MessageProtocol.SMS || family == MessageProtocol.MMS) && prefs.getBoolean("smsEncryptAll", false);
        return prefs.getBoolean("fluidEncrypt." + family.name(), legacy);
    }

    static void setGlobal(SharedPreferences prefs, MessageProtocol protocol, boolean on) {
        prefs.edit().putBoolean("fluidEncrypt." + protocol.name(), on).remove("smsEncryptAll").apply();
    }

    static boolean anyGlobal(SharedPreferences prefs) {
        for (var protocol : PROTOCOLS) if (globalOn(prefs, protocol)) return true;
        return false;
    }

    static String defaultKey(SharedPreferences prefs) { return Privacy.open(prefs, prefs.getString("smsKey.default", "")); }

    static void setDefaultKey(SharedPreferences prefs, String key) {
        prefs.edit().putString("smsKey.default", key.isEmpty() ? "" : Privacy.seal(prefs, key)).apply();
    }

    private static JSONObject chatKeys(SharedPreferences prefs) {
        try { return new JSONObject(Privacy.open(prefs, prefs.getString("smsKey.chats", "{}"))); }
        catch (Exception error) { return new JSONObject(); }
    }

    /** The key saved for this chat, matching phone numbers however they're written. */
    static String chatKey(SharedPreferences prefs, String address) {
        var keys = chatKeys(prefs);
        if (keys.has(address)) return keys.optString(address);
        for (var names = keys.keys(); names.hasNext();) {
            String other = names.next();
            if (SmsStore.validRecipient(other) && SmsStore.validRecipient(address) && PhoneNumberUtils.compare(other, address)) {
                return keys.optString(other);
            }
        }
        return "";
    }

    static void setChatKey(SharedPreferences prefs, String address, String key) throws Exception {
        var keys = chatKeys(prefs);
        if (key.isEmpty()) keys.remove(address); else keys.put(address, key);
        prefs.edit().putString("smsKey.chats", Privacy.seal(prefs, keys.toString())).apply();
    }

    /** "on", "off", or "" to follow the global setting. */
    static String chatChoice(SharedPreferences prefs, String address) {
        String exact = prefs.getString("smsEncrypt." + address, null);
        if (exact != null) return exact;
        // The same number written another way (+1 415…, (415) …) is the same chat.
        if (SmsStore.validRecipient(address)) {
            for (var entry : prefs.getAll().entrySet()) {
                if (!entry.getKey().startsWith("smsEncrypt.") || !(entry.getValue() instanceof String value)) continue;
                String other = entry.getKey().substring("smsEncrypt.".length());
                if (SmsStore.validRecipient(other) && PhoneNumberUtils.compare(other, address)) return value;
            }
        }
        return "";
    }

    static void setChatChoice(SharedPreferences prefs, String address, String choice) {
        if (choice.isEmpty()) prefs.edit().remove("smsEncrypt." + address).apply();
        else prefs.edit().putString("smsEncrypt." + address, choice).apply();
    }

    /** A chat's own choice always wins; with none, the protocol's default applies. */
    static boolean wanted(SharedPreferences prefs, String address, MessageProtocol protocol) {
        String choice = chatChoice(prefs, address);
        if (choice.equals("on")) return true;
        if (choice.equals("off")) return false;
        return globalOn(prefs, protocol);
    }

    /** The key used to send in this chat: its own, else the default key. */
    static String sendingKey(SharedPreferences prefs, String address) {
        String own = chatKey(prefs, address);
        return own.isEmpty() ? defaultKey(prefs) : own;
    }

    static boolean active(SharedPreferences prefs, String address, MessageProtocol protocol) {
        return wanted(prefs, address, protocol) && !sendingKey(prefs, address).isEmpty();
    }

    // Sending and reading

    /** The text to hand the carrier: scrambled when this chat is encrypted, otherwise unchanged. */
    static String outgoing(Context context, String conversation, MessageProtocol protocol, String text) throws Exception {
        var prefs = prefs(context);
        if (text == null || text.isBlank() || text.startsWith(PREFIX)) return text;
        boolean wantedHere = wanted(prefs, conversation, protocol);
        if (!wantedHere) return text;
        String key = sendingKey(prefs, conversation);
        if (key.isEmpty()) throw new IllegalStateException("Add a text encryption key for this chat in its details, or turn encryption off");
        return seal(key, text, Disappearing.seconds(prefs, conversation));
    }

    static String seal(String sharedKey, String text, int expireSeconds) throws Exception {
        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_TEXT) throw new IllegalArgumentException("Encrypted texts can be up to " + MAX_TEXT + " characters");
        SecretKey key = derive(sharedKey);
        byte[] inner = ByteBuffer.allocate(5 + plain.length).put((byte) 1).putInt(Math.max(0, expireSeconds)).put(plain).array();
        return PREFIX + Privacy.b64(Privacy.concat(keyId(key), Privacy.aesSeal(key, inner)));
    }

    /** Opens a scrambled text with any key that could apply to it, or returns null. */
    static Opened open(SharedPreferences prefs, String body, List<String> addresses) {
        if (body == null || !body.startsWith(PREFIX)) return null;
        byte[] blob;
        try { blob = Privacy.unb64(body.substring(PREFIX.length()).trim()); }
        catch (IllegalArgumentException error) { return null; }
        if (blob.length < 4 + 12 + 16 + 5) return null;
        var candidates = new ArrayList<String>();
        for (String address : addresses) {
            String own = chatKey(prefs, address);
            if (!own.isEmpty()) candidates.add(own);
        }
        String fallback = defaultKey(prefs);
        if (!fallback.isEmpty()) candidates.add(fallback);
        for (String candidate : candidates) {
            try {
                SecretKey key = derive(candidate);
                if (!java.security.MessageDigest.isEqual(keyId(key), java.util.Arrays.copyOf(blob, 4))) continue;
                byte[] inner = Privacy.aesOpen(key, java.util.Arrays.copyOfRange(blob, 4, blob.length));
                var buffer = ByteBuffer.wrap(inner);
                if (buffer.get() != 1) continue;
                int expire = buffer.getInt();
                return new Opened(new String(inner, 5, inner.length - 5, StandardCharsets.UTF_8), expire);
            } catch (Exception wrongKey) { /* try the next key */ }
        }
        return null;
    }

    /**
     * Replaces a stored scrambled text with what it says, for showing in the app or the companion. Texts this
     * phone can't open say so instead of showing the scrambled form.
     */
    static void reveal(SharedPreferences prefs, JSONObject row) {
        String body = row.optString("body");
        if (!body.startsWith(PREFIX)) return;
        try {
            var addresses = new ArrayList<String>();
            addresses.add(row.optString("address"));
            if (!row.optString("author").isEmpty()) addresses.add(row.optString("author"));
            var opened = open(prefs, body, addresses);
            row.put("encrypted_text", true);
            if (opened == null) {
                row.put("body", "🔒 Encrypted message. Add the key you share with this person in the chat's Security section to read it.");
                return;
            }
            row.put("body", opened.text());
            if (opened.expireSeconds() > 0) row.put("expires", row.optLong("date") + opened.expireSeconds() * 1000L);
        } catch (Exception ignored) {}
    }

    private static SecretKey derive(String sharedKey) throws Exception {
        var cached = derived.get(sharedKey);
        if (cached != null) return cached;
        var key = Privacy.stretch(sharedKey, SALT, 100_000);
        derived.put(sharedKey, key);
        return key;
    }

    private static byte[] keyId(SecretKey key) throws Exception {
        return java.util.Arrays.copyOf(Privacy.hmac(key.getEncoded(), "FluidMessages key id"), 4);
    }

    /** A random key to share in person: 20 letters and digits in groups of four. */
    static String newKey() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        byte[] bytes = Privacy.randomBytes(20);
        var result = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && i % 4 == 0) result.append('-');
            result.append(alphabet.charAt((bytes[i] & 0xff) % alphabet.length()));
        }
        return result.toString();
    }
}
