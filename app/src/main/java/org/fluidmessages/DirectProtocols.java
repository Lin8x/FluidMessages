package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Internet protocols connected from this phone instead of the companion.
 *
 * Each protocol is off until the person turns on "Connect from this phone" and accepts the battery notice.
 * Account details are encrypted with an Android Keystore key and never leave the phone (or enter backups).
 * Links are stored on the phone, and messages go into the same history as companion messages, so the
 * conversation screens, replies, reactions, and attachments work the same way.
 *
 * Battery: Email and Matrix check on the Battery and sync schedule. IRC and XMPP need an open connection;
 * by default it stays open only while FluidMessages is on screen. "Stay connected" keeps it open in a
 * foreground service with an ongoing notification, which uses noticeably more battery.
 */
final class DirectProtocols {
    static final String ROUTES = "directRoutes";
    private static final String KEY = "fluid-direct-accounts";
    private static final Map<MessageProtocol, DirectClient> clients = new EnumMap<>(MessageProtocol.class);
    private static boolean foreground;

    interface DirectClient {
        void start(Context context, JSONObject account);
        void stop();
        /** Checks for new messages once, for background jobs and protocols without an open connection. */
        default void poll(Context context) throws Exception {}
        /** Prepares for poll() without starting a connection. */
        default void load(Context context, JSONObject account) {}
        /** Sends and returns fields to keep on the message record, such as the server's message id. */
        JSONObject send(Context context, JSONObject route, JSONObject message, MediaSanitizer.Prepared file) throws Exception;
        /** Whether this protocol needs an always-open connection to receive messages. */
        boolean persistent();
    }

    private DirectProtocols() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    static String key(MessageProtocol protocol) { return "direct." + protocol.name(); }

    /** Connected from this phone: turned on, with the battery notice accepted. */
    static boolean on(SharedPreferences prefs, MessageProtocol protocol) {
        return prefs.getBoolean(key(protocol), false) && prefs.contains("direct.account." + protocol.name());
    }

    static boolean anyOn(SharedPreferences prefs) {
        for (MessageProtocol protocol : MessageProtocol.values()) if (on(prefs, protocol)) return true;
        return false;
    }

    static boolean stayConnected(SharedPreferences prefs, MessageProtocol protocol) {
        return prefs.getBoolean(key(protocol) + ".always", false);
    }

    // Accounts

    static void saveAccount(Context context, MessageProtocol protocol, JSONObject account, boolean always) throws Exception {
        var prefs = prefs(context);
        var previous = account(context, protocol);
        // Leaving a password blank keeps the saved one, as on the companion.
        if (previous != null) for (String secret : new String[]{"imap_password", "smtp_password", "password", "sasl_password", "access_token"}) {
            if (account.optString(secret).isEmpty() && !previous.optString(secret).isEmpty()) account.put(secret, previous.getString(secret));
        }
        prefs.edit().putString("direct.account." + protocol.name(), seal(account.toString()))
            .putBoolean(key(protocol), true).putBoolean(key(protocol) + ".always", always).apply();
        if (protocol == MessageProtocol.IRC) {
            // Channels on the IRC account appear in the inbox without a separate link, as on the companion.
            var channels = account.optJSONArray("channels");
            for (int i = 0; channels != null && i < channels.length(); i++) {
                String channel = channels.optString(i);
                if (routeForTarget(context, protocol, channel) == null) {
                    link(context, "irc-" + Integer.toHexString(channel.toLowerCase(Locale.ROOT).hashCode()), protocol, channel, channel, true);
                }
            }
        }
        restart(context);
    }

    static JSONObject account(Context context, MessageProtocol protocol) {
        String sealed = prefs(context).getString("direct.account." + protocol.name(), "");
        if (sealed.isEmpty()) return null;
        try { return new JSONObject(open(sealed)); } catch (Exception error) { return null; }
    }

    static void removeAccount(Context context, MessageProtocol protocol) {
        prefs(context).edit().remove("direct.account." + protocol.name()).remove(key(protocol)).remove(key(protocol) + ".always").apply();
        restart(context);
    }

    // Links

    static JSONArray routes(SharedPreferences prefs) {
        try { return new JSONArray(prefs.getString(ROUTES, "[]")); } catch (Exception error) { return new JSONArray(); }
    }

    static JSONObject route(SharedPreferences prefs, String address, MessageProtocol protocol) {
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && address.equals(route.optString("address"))
                    && protocol == MessageProtocol.parse(route.optString("protocol"))) return route;
        }
        return null;
    }

    static JSONObject routeForTarget(Context context, MessageProtocol protocol, String target) {
        var items = routes(prefs(context));
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && protocol == MessageProtocol.parse(route.optString("protocol"))
                    && target.equalsIgnoreCase(route.optString("target"))) return route;
        }
        return null;
    }

    static void link(Context context, String address, MessageProtocol protocol, String target, String title, boolean group) throws Exception {
        target = target.trim();
        boolean valid = switch (protocol) {
            case EMAIL, XMPP -> target.matches("[^@\\s<>]{1,64}@[A-Za-z0-9.-]{1,253}");
            case MATRIX -> target.matches("[@!][^\\s:]{1,255}:[A-Za-z0-9.:-]{1,255}");
            case IRC -> target.matches("[#&][^\\s,\\x07]{1,49}|[A-Za-z\\[\\]\\\\`_^{|}][A-Za-z0-9\\[\\]\\\\`_^{|}-]{0,31}");
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Enter a valid " + protocol.label + " address");
        if (protocol == MessageProtocol.MATRIX && target.startsWith("@")) target = DirectMatrix.directRoom(context, target);
        var prefs = prefs(context);
        var items = routes(prefs);
        var kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route == null || address.equals(route.optString("address")) && protocol == MessageProtocol.parse(route.optString("protocol"))) continue;
            if (protocol == MessageProtocol.parse(route.optString("protocol")) && target.equalsIgnoreCase(route.optString("target"))) {
                throw new IllegalArgumentException("That address already belongs to another conversation");
            }
            kept.put(route);
        }
        kept.put(new JSONObject().put("address", address).put("protocol", protocol.name().toLowerCase(Locale.ROOT))
            .put("target", protocol == MessageProtocol.EMAIL || protocol == MessageProtocol.XMPP ? target.toLowerCase(Locale.ROOT) : target)
            .put("title", title).put("group", group || protocol == MessageProtocol.IRC && target.matches("[#&].*"))
            .put("direct", true));
        prefs.edit().putString(ROUTES, kept.toString()).apply();
        restart(context);
    }

    static void unlink(Context context, String address, MessageProtocol protocol) {
        var prefs = prefs(context);
        var items = routes(prefs);
        var kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && !(address.equals(route.optString("address")) && protocol == MessageProtocol.parse(route.optString("protocol")))) kept.put(route);
        }
        prefs.edit().putString(ROUTES, kept.toString()).apply();
    }

    static List<String> targets(Context context, MessageProtocol protocol, boolean groups) {
        var result = new ArrayList<String>();
        var items = routes(prefs(context));
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && protocol == MessageProtocol.parse(route.optString("protocol")) && route.optBoolean("group") == groups) {
                result.add(route.optString("target"));
            }
        }
        return result;
    }

    // Lifecycle

    /** Called as FluidMessages comes on screen or leaves it. */
    static synchronized void foreground(Context context, boolean visible) {
        foreground = visible;
        restart(context);
    }

    /** Starts or stops each client to match its settings, and the service for "Stay connected". */
    static synchronized void restart(Context context) {
        var app = context.getApplicationContext();
        var prefs = prefs(app);
        boolean service = false;
        for (MessageProtocol protocol : new MessageProtocol[]{MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC}) {
            var account = on(prefs, protocol) && protocol.enabled(prefs) ? account(app, protocol) : null;
            var existing = clients.get(protocol);
            boolean always = account != null && stayConnected(prefs, protocol);
            boolean wanted = account != null && (foreground || always);
            service |= always;
            String signature = account == null ? "" : account.toString() + routes(prefs);
            if (existing != null && (!wanted || !signature.equals(existing.toString()))) {
                existing.stop();
                clients.remove(protocol);
                existing = null;
            }
            if (wanted && existing == null) {
                DirectClient client = switch (protocol) {
                    case MATRIX -> new DirectMatrix(signature);
                    case XMPP -> new DirectXmpp(signature);
                    case EMAIL -> new DirectEmail(signature);
                    default -> new DirectIrc(signature);
                };
                clients.put(protocol, client);
                client.start(app, account);
            }
        }
        ProtocolService.update(app, service);
    }

    /** One background check for protocols that don't keep a connection open (from the periodic job). */
    static void poll(Context context) {
        var app = context.getApplicationContext();
        var prefs = prefs(app);
        for (MessageProtocol protocol : new MessageProtocol[]{MessageProtocol.MATRIX, MessageProtocol.EMAIL}) {
            if (!on(prefs, protocol) || !protocol.enabled(prefs)) continue;
            var account = account(app, protocol);
            if (account == null) continue;
            DirectClient client;
            synchronized (DirectProtocols.class) { client = clients.get(protocol); }
            try {
                if (client != null) client.poll(app);
                else {
                    DirectClient once = protocol == MessageProtocol.MATRIX ? new DirectMatrix("") : new DirectEmail("");
                    once.load(app, account);
                    once.poll(app);
                }
            } catch (Exception ignored) {}
        }
    }

    // Messages

    static JSONObject record(JSONObject route, String id, String body, boolean outgoing, long date, String author) throws Exception {
        return new JSONObject().put("id", id).put("address", route.getString("address")).put("protocol", route.getString("protocol"))
            .put("title", route.optString("title")).put("body", body).put("date", date).put("type", outgoing ? 2 : 1)
            .put("read", outgoing ? 1 : 0).put("status", "").put("author", author).put("direct", true);
    }

    /** Stores an incoming message unless it's already known, and notifies like an SMS. */
    static void incoming(Context context, JSONObject record) {
        try {
            var store = ProtocolStore.get(context);
            if (store.find(record.getString("id")) != null) return;
            store.put(record);
            MmsTransport.changed(context);
            if (record.optInt("type") == 1 && !record.optBoolean("event")) SmsDelivery.notify(context, "New " +
                MessageProtocol.parse(record.getString("protocol")).label + " message", record.getString("address"));
        } catch (Exception ignored) {}
    }

    static JSONObject findBy(Context context, String address, String field, String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            for (JSONObject item : ProtocolStore.get(context).read()) {
                if (address.equals(item.optString("address")) && value.equals(item.optString(field))) return item;
            }
        } catch (Exception ignored) {}
        return null;
    }

    static void send(Context context, String address, MessageProtocol format, String text, String body,
                     MessageRelation relation, Attachment attachment) throws Exception {
        format.requireEnabled(context);
        var prefs = prefs(context);
        var route = route(prefs, address, format);
        if (route == null) throw new IllegalStateException("Link a " + format.label + " address to this conversation first.");
        DirectClient client;
        synchronized (DirectProtocols.class) { client = clients.get(format); }
        if (client == null) throw new IllegalStateException(format.label + " isn’t connected. Open FluidMessages and try again.");
        String id = "direct-" + UUID.randomUUID();
        var message = record(route, id, body, true, System.currentTimeMillis(), "").put("status", "Sending…").put("text", text);
        var store = ProtocolStore.get(context);
        if (relation != null) {
            var target = relation.getTargetId() == null ? null : store.find(relation.getTargetId());
            var json = relation.toJson();
            if (target != null && address.equals(target.optString("address"))) {
                for (String field : new String[]{"event_id", "mail_id", "mail_subject", "xmpp_id", "irc_msgid", "author"}) {
                    if (target.has(field)) json.put(field, target.get(field));
                }
                if (relation.getKind() == RelationKind.REACTION) message.put("reaction_target", target.getString("id"));
                if (relation.getKind() == RelationKind.REPLY) message.put("reply_target", target.getString("id"));
            }
            message.put("relation", json);
        }
        MediaSanitizer.Prepared file = null;
        try {
            if (attachment != null) {
                file = MediaSanitizer.prepare(context, attachment, MediaSanitizer.MAX_FILE);
                message.put("attachment", attachment.toJson().put("name", file.name()).put("mime", file.mime()).put("size", file.file().length()));
                try (var input = java.nio.file.Files.newInputStream(file.file().toPath())) { store.putFile(id, input, MediaSanitizer.MAX_FILE); }
            }
            store.put(message);
            var updates = client.send(context, route, message, file);
            for (var keys = updates.keys(); keys.hasNext(); ) {
                String field = keys.next();
                message.put(field, updates.get(field));
            }
            message.put("status", "Sent");
            store.put(message);
        } catch (Exception error) {
            message.put("status", "Couldn’t send · " + (error.getMessage() == null ? "Try again" : error.getMessage()));
            store.put(message);
            throw error;
        } finally {
            if (file != null) file.delete();
            MmsTransport.changed(context);
        }
    }

    static String unavailable(SharedPreferences prefs, String address, MessageProtocol format) {
        if (route(prefs, address, format) == null) return "Link a " + format.label + " address to this conversation in its details.";
        return "";
    }

    // Keystore

    private static SecretKey keystoreKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(KEY, null) instanceof SecretKey existing) return existing;
        var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }

    private static String seal(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
            + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String open(String sealed) throws Exception {
        String[] parts = sealed.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
