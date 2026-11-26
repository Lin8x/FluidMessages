package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.json.JSONObject;

/**
 * This phone as one OMEMO device of your XMPP account, for XMPP connected from the phone (DirectXmpp). Uses
 * OmemoCore for the cryptography and the same rules as FluidMessages Web (OmemoRules): one-to-one chats are
 * locked when the other person has OMEMO devices, trust is blind before verification, and used prekeys are
 * replaced and republished.
 *
 * Keys and sessions are stored with the Android Keystore key that protects the phone's account details
 * (DirectProtocols.seal), in their own preferences file, which backups never include: a restored phone becomes a
 * new OMEMO device, as other apps do. Nothing here logs keys or text.
 */
final class PhoneOmemo {
    interface Network {
        /** Publishes [payloadXml] as the item "current" of your own PEP [node], readable by anyone. */
        void publish(String node, String payloadXml) throws Exception;
        /** The payload of [bareJid]'s PEP [node], or null when there's none. */
        StandardExtensionElement fetch(String bareJid, String node) throws Exception;
        /** Sends a key transport message (no text) to [bareJid]. */
        void send(String bareJid, StandardExtensionElement encrypted) throws Exception;
    }

    interface Notices { void notice(String jid, String text); }

    static final class Unavailable extends Exception { Unavailable() { super("They don't use OMEMO"); } }
    static final class NotTrusted extends Exception {
        NotTrusted() { super("Not sent: none of their OMEMO devices is trusted yet. Check them in this chat's Security section, then send again."); }
    }

    static final class Opened {
        String text, error = "", trust = "";
        int device;
    }

    static final class Sealed {
        StandardExtensionElement element;
        int devices, trusted, failed;
    }

    static final String FALLBACK = "This message is locked with OMEMO, and your app can't open it.";
    private static final long TTL = 10 * 60_000L, ROTATE = 7L * 24 * 60 * 60_000L;
    private static final String FILE = "fluid-omemo";

    private final Context context;
    private final String own;
    private final Network network;
    private final Notices notices;
    private final Map<String, Long> checked = new HashMap<>();
    private OmemoCore.OwnKeys keys;
    private int deviceId;
    /** jid → device id → {"ik": base64 Curve25519 key, "trust": name, "active": bool} */
    private JSONObject devices = new JSONObject();
    /** "jid|device" → session JSON */
    private JSONObject sessions = new JSONObject();

    PhoneOmemo(Context context, String ownJid, Network network, Notices notices) {
        this.context = context.getApplicationContext();
        this.own = ownJid.toLowerCase(Locale.ROOT);
        this.network = network;
        this.notices = notices;
    }

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    int deviceId() { return deviceId; }

    // ----- Storage -----

    private void load() throws Exception {
        String sealed = prefs(context).getString("state." + own, "");
        if (sealed.isEmpty()) return;
        var state = new JSONObject(DirectProtocols.open(sealed));
        deviceId = state.getInt("device");
        keys = new OmemoCore.OwnKeys();
        keys.identity = new OmemoCore.Identity(OmemoCore.unb64(state.getString("seed")));
        keys.registrationId = state.getInt("reg");
        var spk = state.getJSONObject("spk");
        keys.signedPreKeyId = spk.getInt("id");
        keys.signedPreKeyPriv = OmemoCore.unb64(spk.getString("priv"));
        keys.signedPreKeySignature = OmemoCore.unb64(spk.getString("sig"));
        keys.signedPreKeyTime = spk.getLong("time");
        var old = state.optJSONObject("old_spk");
        if (old != null) {
            keys.oldSignedPreKeyId = old.getInt("id");
            keys.oldSignedPreKeyPriv = OmemoCore.unb64(old.getString("priv"));
        }
        var pre = state.getJSONObject("prekeys");
        for (var it = pre.keys(); it.hasNext(); ) {
            String id = it.next();
            keys.preKeys.put(Integer.parseInt(id), OmemoCore.unb64(pre.getString(id)));
        }
        keys.nextPreKeyId = state.optInt("next_pk", 1);
        devices = state.optJSONObject("devices") == null ? new JSONObject() : state.getJSONObject("devices");
        sessions = state.optJSONObject("sessions") == null ? new JSONObject() : state.getJSONObject("sessions");
    }

    /** Saved before anything is sent, so a ratchet step is never reused after a restart. */
    private void save() throws Exception {
        var pre = new JSONObject();
        for (var entry : keys.preKeys.entrySet()) pre.put(Integer.toString(entry.getKey()), OmemoCore.b64(entry.getValue()));
        var state = new JSONObject().put("device", deviceId).put("seed", OmemoCore.b64(keys.identity.seed))
            .put("reg", keys.registrationId).put("next_pk", keys.nextPreKeyId).put("prekeys", pre)
            .put("spk", new JSONObject().put("id", keys.signedPreKeyId).put("priv", OmemoCore.b64(keys.signedPreKeyPriv))
                .put("sig", OmemoCore.b64(keys.signedPreKeySignature)).put("time", keys.signedPreKeyTime))
            .put("devices", devices).put("sessions", sessions);
        if (keys.oldSignedPreKeyPriv != null) {
            state.put("old_spk", new JSONObject().put("id", keys.oldSignedPreKeyId).put("priv", OmemoCore.b64(keys.oldSignedPreKeyPriv)));
        }
        prefs(context).edit().putString("state." + own, DirectProtocols.seal(state.toString())).commit();
    }

    private OmemoCore.Session session(String jid, int device) {
        var json = sessions.optJSONObject(jid + "|" + device);
        if (json == null) return null;
        try { return OmemoCore.Session.fromJson(json); } catch (Exception error) { return null; }
    }

    private void putSession(String jid, int device, OmemoCore.Session session) throws Exception {
        sessions.put(jid + "|" + device, session.toJson());
    }

    private JSONObject deviceInfo(String jid, int device) throws Exception {
        var forJid = devices.optJSONObject(jid);
        if (forJid == null) { forJid = new JSONObject(); devices.put(jid, forJid); }
        var info = forJid.optJSONObject(Integer.toString(device));
        if (info == null) {
            info = new JSONObject().put("trust", OmemoRules.UNDECIDED).put("active", true);
            forJid.put(Integer.toString(device), info);
        }
        return info;
    }

    // ----- Start -----

    synchronized void start() throws Exception {
        load();
        boolean fresh = keys == null;
        if (fresh) {
            keys = OmemoCore.OwnKeys.create();
            List<Integer> taken = activeDevices(own, true);
            do { deviceId = 1 + OmemoCore.RANDOM.nextInt(Integer.MAX_VALUE - 1); } while (taken.contains(deviceId));
        }
        if (System.currentTimeMillis() - keys.signedPreKeyTime > ROTATE) keys.rotateSignedPreKey();
        save();
        network.publish(OmemoCore.BUNDLES_NODE + deviceId, bundleXml());
        refresh(own, true);
        save();
    }

    private String bundleXml() {
        var bundle = keys.bundle();
        var xml = new StringBuilder("<bundle xmlns='" + OmemoCore.NAMESPACE + "'>");
        xml.append("<signedPreKeyPublic signedPreKeyId='").append(bundle.signedPreKeyId).append("'>")
            .append(OmemoCore.b64(OmemoCore.encodeKey(bundle.signedPreKey))).append("</signedPreKeyPublic>");
        xml.append("<signedPreKeySignature>").append(OmemoCore.b64(bundle.signature)).append("</signedPreKeySignature>");
        xml.append("<identityKey>").append(OmemoCore.b64(OmemoCore.encodeKey(bundle.identity))).append("</identityKey><prekeys>");
        for (var entry : bundle.preKeys.entrySet()) {
            xml.append("<preKeyPublic preKeyId='").append(entry.getKey()).append("'>")
                .append(OmemoCore.b64(OmemoCore.encodeKey(entry.getValue()))).append("</preKeyPublic>");
        }
        return xml.append("</prekeys></bundle>").toString();
    }

    private static String deviceListXml(List<Integer> ids) {
        var xml = new StringBuilder("<list xmlns='" + OmemoCore.NAMESPACE + "'>");
        for (int id : ids) xml.append("<device id='").append(id).append("'/>");
        return xml.append("</list>").toString();
    }

    /** The device ids in [jid]'s published list (fetched now when [fetch]), or the ones known. */
    private List<Integer> activeDevices(String jid, boolean fetch) throws Exception {
        List<Integer> ids = new ArrayList<>();
        if (fetch) {
            var list = network.fetch(jid, OmemoCore.DEVICE_LIST_NODE);
            if (list != null) {
                for (var device : list.getElements("device")) {
                    try {
                        int id = Integer.parseInt(device.getAttributeValue("id"));
                        if (id >= 1) ids.add(id);
                    } catch (NumberFormatException ignored) {}
                }
            }
            return ids;
        }
        var forJid = devices.optJSONObject(jid);
        if (forJid != null) {
            for (var it = forJid.keys(); it.hasNext(); ) {
                String id = it.next();
                if (forJid.getJSONObject(id).optBoolean("active", true)) ids.add(Integer.parseInt(id));
            }
        }
        return ids;
    }

    /** Fetches a device list (at most every 10 minutes unless [force]) and records new and gone devices. */
    private void refresh(String jid, boolean force) throws Exception {
        Long last = checked.get(jid);
        if (!force && last != null && System.currentTimeMillis() - last < TTL) return;
        List<Integer> listed;
        try { listed = activeDevices(jid, true); } catch (Exception error) { return; }
        checked.put(jid, System.currentTimeMillis());
        if (jid.equals(own) && !listed.contains(deviceId)) {
            List<Integer> mine = new ArrayList<>(listed);
            mine.add(deviceId);
            network.publish(OmemoCore.DEVICE_LIST_NODE, deviceListXml(mine));
            listed = mine;
        }
        var forJid = devices.optJSONObject(jid);
        if (forJid != null) {
            for (var it = forJid.keys(); it.hasNext(); ) {
                String id = it.next();
                forJid.getJSONObject(id).put("active", listed.contains(Integer.parseInt(id)));
            }
        }
        for (int id : listed) deviceInfo(jid, id).put("active", true);
    }

    // ----- Trust -----

    /** BTBV: a device seen for the first time is trusted unless you verified another of that person's devices. */
    private String trust(String jid, int device) throws Exception {
        var info = deviceInfo(jid, device);
        String name = info.optString("trust", OmemoRules.UNDECIDED);
        if (!name.equals(OmemoRules.UNDECIDED)) return name;
        List<String> others = new ArrayList<>();
        var forJid = devices.optJSONObject(jid);
        for (var it = forJid.keys(); it.hasNext(); ) {
            String id = it.next();
            if (!id.equals(Integer.toString(device))) others.add(forJid.getJSONObject(id).optString("trust"));
        }
        name = OmemoRules.firstUse(others);
        info.put("trust", name);
        if (name.equals(OmemoRules.UNCONFIRMED) && !jid.equals(own) && notices != null) {
            notices.notice(jid, jid + " has a new device. Messages aren't locked for it until you OK it in Security.");
        }
        return name;
    }

    synchronized void setTrust(String jid, int device, String name) throws Exception {
        if (!name.equals(OmemoRules.VERIFIED) && !name.equals(OmemoRules.BLIND) && !name.equals(OmemoRules.DISTRUSTED)) {
            throw new IllegalArgumentException("Unknown trust choice");
        }
        deviceInfo(jid.toLowerCase(Locale.ROOT), device).put("trust", name);
        save();
    }

    /** A device's identity key, fetched from its bundle the first time. A different key than before is a key change. */
    private OmemoCore.Bundle bundle(String jid, int device) throws Exception {
        var element = network.fetch(jid, OmemoCore.BUNDLES_NODE + device);
        if (element == null) throw new OmemoCore.OmemoException("no bundle");
        var bundle = new OmemoCore.Bundle();
        var spk = element.getFirstElement("signedPreKeyPublic");
        bundle.signedPreKeyId = Integer.parseInt(spk.getAttributeValue("signedPreKeyId"));
        bundle.signedPreKey = OmemoCore.decodeKey(OmemoCore.unb64(spk.getText()));
        bundle.signature = OmemoCore.unb64(element.getFirstElement("signedPreKeySignature").getText());
        bundle.identity = OmemoCore.decodeKey(OmemoCore.unb64(element.getFirstElement("identityKey").getText()));
        var prekeys = element.getFirstElement("prekeys");
        for (var key : prekeys == null ? List.<StandardExtensionElement>of() : prekeys.getElements("preKeyPublic")) {
            try { bundle.preKeys.put(Integer.parseInt(key.getAttributeValue("preKeyId")), OmemoCore.decodeKey(OmemoCore.unb64(key.getText()))); }
            catch (Exception ignored) {}
        }
        noteIdentity(jid, device, bundle.identity);
        return bundle;
    }

    /** Records a device's identity key; returns false (after a notice) when it changed. */
    private boolean noteIdentity(String jid, int device, byte[] identity) throws Exception {
        var info = deviceInfo(jid, device);
        String known = info.optString("ik");
        String now = OmemoCore.b64(identity);
        if (known.isEmpty()) { info.put("ik", now); return true; }
        if (known.equals(now)) return true;
        // The key changed (for example the app was reinstalled): decided again like a new device; a verified
        // device waits for your OK.
        boolean wasVerified = OmemoRules.VERIFIED.equals(info.optString("trust"));
        info.put("ik", now).put("trust", wasVerified ? OmemoRules.UNCONFIRMED : OmemoRules.UNDECIDED);
        sessions.remove(jid + "|" + device);
        if (notices != null && !jid.equals(own)) {
            notices.notice(jid, jid + "'s security key changed, for example after reinstalling their app. Check it in Security if you didn't expect this.");
        }
        return false;
    }

    // ----- Sending -----

    /** Whether every recipient has an OMEMO device. */
    synchronized boolean canLock(Set<String> recipients) throws Exception {
        for (String jid : recipients) {
            refresh(jid.toLowerCase(Locale.ROOT), false);
            if (activeDevices(jid.toLowerCase(Locale.ROOT), false).isEmpty()) return false;
        }
        save();
        return !recipients.isEmpty();
    }

    synchronized Sealed encrypt(Set<String> recipients, byte[] plaintext) throws Exception {
        Map<String, List<Integer>> targets = new LinkedHashMap<>();
        var sealed = new Sealed();
        for (String raw : recipients) {
            String jid = raw.toLowerCase(Locale.ROOT);
            refresh(jid, false);
            List<Integer> active = activeDevices(jid, false);
            if (active.isEmpty()) throw new Unavailable();
            List<Integer> trustedIds = new ArrayList<>();
            for (int id : active) if (OmemoRules.trusted(trust(jid, id))) trustedIds.add(id);
            if (trustedIds.isEmpty()) { save(); throw new NotTrusted(); }
            sealed.devices += active.size();
            sealed.trusted += trustedIds.size();
            targets.put(jid, trustedIds);
        }
        refresh(own, false);
        List<Integer> mine = new ArrayList<>();
        for (int id : activeDevices(own, false)) if (id != deviceId && OmemoRules.trusted(trust(own, id))) mine.add(id);
        targets.put(own, mine);
        byte[][] payload = OmemoCore.sealPayload(plaintext);
        var header = StandardExtensionElement.builder("header", OmemoCore.NAMESPACE).addAttribute("sid", Integer.toString(deviceId));
        for (var entry : targets.entrySet()) {
            for (int id : entry.getValue()) {
                try {
                    header.addElement(keyFor(entry.getKey(), id, payload[2]));
                } catch (Exception error) {
                    if (!entry.getKey().equals(own)) sealed.failed++;
                }
            }
        }
        header.addElement(StandardExtensionElement.builder("iv", OmemoCore.NAMESPACE).setText(OmemoCore.b64(payload[1])).build());
        var encrypted = StandardExtensionElement.builder("encrypted", OmemoCore.NAMESPACE).addElement(header.build());
        if (plaintext.length > 0 || payload[0].length > 0) {
            encrypted.addElement(StandardExtensionElement.builder("payload", OmemoCore.NAMESPACE).setText(OmemoCore.b64(payload[0])).build());
        }
        sealed.element = encrypted.build();
        save();
        return sealed;
    }

    /** The <key> for one device: a key exchange until that device has answered, then normal messages. */
    private StandardExtensionElement keyFor(String jid, int device, byte[] keyMaterial) throws Exception {
        var session = session(jid, device);
        if (session == null) {
            var bundle = bundle(jid, device);
            // The key may have changed just now; then the device waits for your OK like a new one.
            if (!OmemoRules.trusted(trust(jid, device))) throw new OmemoCore.OmemoException("needs your OK");
            session = OmemoCore.start(keys, bundle);
        }
        byte[] message = OmemoCore.encrypt(session, keyMaterial);
        var key = StandardExtensionElement.builder("key", OmemoCore.NAMESPACE).addAttribute("rid", Integer.toString(device));
        if (session.initiator && !session.confirmed) {
            key.addAttribute("prekey", "true");
            message = OmemoCore.keyExchange(session, keys.registrationId, message);
        }
        putSession(jid, device, session);
        return key.setText(OmemoCore.b64(message)).build();
    }

    // ----- Receiving -----

    synchronized Opened decrypt(String sender, StandardExtensionElement encrypted) {
        var result = new Opened();
        sender = sender.toLowerCase(Locale.ROOT);
        try {
            var header = encrypted.getFirstElement("header");
            result.device = Integer.parseInt(header.getAttributeValue("sid"));
            StandardExtensionElement mine = null;
            for (var key : header.getElements("key")) if (Integer.toString(deviceId).equals(key.getAttributeValue("rid"))) mine = key;
            if (mine == null) { result.error = "not_for_us"; return result; }
            byte[] data = OmemoCore.unb64(mine.getText());
            byte[] iv = OmemoCore.unb64(header.getFirstElement("iv").getText());
            var payloadElement = encrypted.getFirstElement("payload");
            boolean prekey = "true".equals(mine.getAttributeValue("prekey")) || "1".equals(mine.getAttributeValue("prekey"));
            var existing = session(sender, result.device);
            OmemoCore.Session session;
            byte[] inner;
            OmemoCore.KeyExchange exchange = null;
            if (prekey) {
                exchange = OmemoCore.parseKeyExchange(data);
                inner = exchange.message;
                if (OmemoCore.sameExchange(existing, exchange)) {
                    session = existing;
                    exchange = null;  // Already answered; the sender hasn't seen our answer yet.
                } else {
                    noteIdentity(sender, result.device, exchange.identity);
                    session = OmemoCore.accept(keys, exchange);
                }
            } else {
                if (existing == null) { result.error = "failed"; return result; }
                session = existing;
                inner = data;
            }
            Object[] opened = OmemoCore.decrypt(session, inner);
            session = (OmemoCore.Session) opened[0];
            byte[] keyMaterial = (byte[]) opened[1];
            String text = payloadElement == null ? null
                : new String(OmemoCore.openPayload(keyMaterial, iv, OmemoCore.unb64(payloadElement.getText())), java.nio.charset.StandardCharsets.UTF_8);
            putSession(sender, result.device, session);
            if (exchange != null) {
                // The prekey is used up: delete it, make a new one, and republish (XEP-0384 0.3 section 5).
                keys.preKeys.remove(exchange.preKeyId);
                keys.refill(100);
            }
            deviceInfo(sender, result.device);
            if (devices.getJSONObject(sender).getJSONObject(Integer.toString(result.device)).optString("ik").isEmpty()) {
                deviceInfo(sender, result.device).put("ik", OmemoCore.b64(session.remoteIdentity));
            }
            result.trust = trust(sender, result.device);
            save();
            if (exchange != null) {
                try { network.publish(OmemoCore.BUNDLES_NODE + deviceId, bundleXml()); } catch (Exception ignored) {}
            }
            // Answer a key exchange, and after many messages without one from us, so the session keeps moving.
            if (exchange != null || session.nr > 53) heartbeat(sender, result.device);
            result.text = text;
        } catch (Exception error) {
            result.error = "failed";
        }
        return result;
    }

    private void heartbeat(String jid, int device) {
        try {
            byte[][] payload = OmemoCore.sealPayload(new byte[0]);
            var header = StandardExtensionElement.builder("header", OmemoCore.NAMESPACE).addAttribute("sid", Integer.toString(deviceId));
            header.addElement(keyFor(jid, device, payload[2]));
            header.addElement(StandardExtensionElement.builder("iv", OmemoCore.NAMESPACE).setText(OmemoCore.b64(payload[1])).build());
            save();
            network.send(jid, StandardExtensionElement.builder("encrypted", OmemoCore.NAMESPACE).addElement(header.build()).build());
        } catch (Exception ignored) {}
    }

    // ----- What the Security page shows -----

    /** [{"id", "fingerprint", "trust", "this"}] for [jid]'s devices (your own include this phone first). */
    synchronized List<JSONObject> devices(String jid) throws Exception {
        jid = jid.toLowerCase(Locale.ROOT);
        refresh(jid, false);
        List<JSONObject> out = new ArrayList<>();
        if (jid.equals(own)) {
            out.add(new JSONObject().put("id", deviceId).put("this", true).put("trust", OmemoRules.VERIFIED)
                .put("fingerprint", OmemoRules.fingerprintText(OmemoCore.hex(keys.identity.pub))));
        }
        for (int id : activeDevices(jid, false)) {
            if (jid.equals(own) && id == deviceId) continue;
            var info = deviceInfo(jid, id);
            if (info.optString("ik").isEmpty()) {
                try { bundle(jid, id); } catch (Exception ignored) {}
            }
            String ik = info.optString("ik");
            out.add(new JSONObject().put("id", id).put("this", false).put("trust", trust(jid, id))
                .put("fingerprint", ik.isEmpty() ? "" : OmemoRules.fingerprintText(OmemoCore.hex(OmemoCore.unb64(ik)))));
        }
        save();
        return out;
    }

    synchronized OmemoRules.LockState lockState(String jid) throws Exception {
        jid = jid.toLowerCase(Locale.ROOT);
        refresh(jid, false);
        int total = 0, trusted = 0;
        for (int id : activeDevices(jid, false)) {
            total++;
            if (OmemoRules.trusted(trust(jid, id))) trusted++;
        }
        save();
        var lock = prefs(context).getString("lock." + jid, "");
        return new OmemoRules.LockState(true, total, trusted, lock.equals("failed") ? 1 : 0, lock.equals("plain"), false, false);
    }

    /** The Encryption (OMEMO) page's data, in the same shape as FluidMessages Web's /api/protocols/omemo. */
    synchronized JSONObject page(String jid) throws Exception {
        jid = jid.toLowerCase(Locale.ROOT);
        var lock = OmemoRules.lock(lockState(jid));
        return new JSONObject().put("available", true).put("color", lock.getFirst()).put("words", lock.getSecond())
            .put("people", new org.json.JSONArray().put(new JSONObject().put("jid", jid).put("devices", new org.json.JSONArray(devices(jid)))))
            .put("own", new JSONObject().put("jid", own).put("devices", new org.json.JSONArray(devices(own))))
            .put("uri", ownUri());
    }

    /** Verifies the devices an xmpp: link names when their fingerprints match. */
    synchronized JSONObject verifyLink(String chat, String uri) throws Exception {
        var parsed = OmemoRules.parseXmppUri(uri);
        if (parsed == null) throw new IllegalArgumentException("That isn't an xmpp: link with OMEMO fingerprints");
        String jid = parsed.getFirst();
        int verified = 0, mismatched = 0;
        if (jid.equals(chat.toLowerCase(Locale.ROOT)) || jid.equals(own)) {
            Map<Integer, String> known = new HashMap<>();
            for (var device : devices(jid)) known.put(device.getInt("id"), device.optString("fingerprint"));
            var result = OmemoRules.verifyFromUri(uri, jid, known);
            for (int id : result.getFirst()) deviceInfo(jid, id).put("trust", OmemoRules.VERIFIED);
            verified = result.getFirst().size();
            mismatched = result.getSecond().size();
            save();
        }
        return page(chat).put("checked", new JSONObject().put("verified", verified).put("mismatched", mismatched));
    }

    String ownUri() {
        return OmemoRules.xmppUri(own, List.of(new kotlin.Pair<>(deviceId, OmemoCore.hex(keys.identity.pub))));
    }

    /** What the last message in a chat with [jid] did: "locked", "failed" (some trusted device missed it), or "plain". */
    static void rememberLock(Context context, String jid, String value) {
        prefs(context).edit().putString("lock." + jid.toLowerCase(Locale.ROOT), value).apply();
    }

    /** Whether the chat with [jid] is locked with OMEMO for all trusted devices, as its lock last showed. */
    static boolean locked(Context context, String jid) {
        return "locked".equals(prefs(context).getString("lock." + jid.toLowerCase(Locale.ROOT), ""));
    }
}
