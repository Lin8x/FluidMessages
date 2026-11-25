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
        /** The Reconnect automatically switch changed while connected. */
        default void autoReconnectChanged() {}
        /** Checks for new messages once, for background jobs and protocols without an open connection. */
        default void poll(Context context) throws Exception {}
        /** Prepares for poll() without starting a connection. */
        default void load(Context context, JSONObject account) {}
        /** Sends and returns fields to keep on the message record, such as the server's message id. */
        JSONObject send(Context context, JSONObject route, JSONObject message, MediaSanitizer.Prepared file) throws Exception;
        /** Whether this protocol needs an always-open connection to receive messages. */
        boolean persistent();
        /** Runs a slash command typed in one of this protocol's chats (ChatCommands); returns {"notice", "open"}. */
        default JSONObject command(Context context, JSONObject route, ChatCommand command) throws Exception {
            throw new IllegalStateException("/" + command.getName() + " works through FluidMessages Web for this service.");
        }
        /** The Reconnect button: tries again at once, outside the hourly limit. */
        default void reconnectNow() {}
        /** A chat was opened: a channel that isn't kept joined joins again. */
        default void opened(JSONObject route) {}
        /** Invites or removes someone in a group. Needs the right permission in that room or channel. */
        default void changeMember(Context context, JSONObject route, boolean add, String member) throws Exception {
            throw new UnsupportedOperationException("This protocol can't change group members");
        }
        /** Takes back one of your sent messages for everyone, where the protocol allows it. */
        default void retract(Context context, JSONObject route, JSONObject record) throws Exception {}
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
        if (previous != null) for (String secret : new String[]{"imap_password", "smtp_password", "password", "sasl_password", "nickserv_password", "access_token"}) {
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
        prefs(context).edit().remove("direct.account." + protocol.name()).remove(key(protocol)).remove(key(protocol) + ".always")
            .remove(key(protocol) + ".reconnect").apply();
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
            String service = protocol.name().toLowerCase(Locale.ROOT);
            if (route != null && protocol == MessageProtocol.parse(route.optString("protocol"))
                    && AccountKeys.targetKey(service, target).equals(AccountKeys.targetKey(service, route.optString("target")))) return route;
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
            String service = protocol.name().toLowerCase(Locale.ROOT);
            if (protocol == MessageProtocol.parse(route.optString("protocol"))
                    && AccountKeys.targetKey(service, target).equals(AccountKeys.targetKey(service, route.optString("target")))) {
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

    /**
     * A chat the network opened (someone new messaged you, or the server chat), added without restarting the
     * connection. Returns its link, or the one already there.
     */
    static synchronized JSONObject linkQuietly(Context context, MessageProtocol protocol, String target, String title,
                                              boolean group, JSONObject extra) throws Exception {
        var existing = routeForTarget(context, protocol, target);
        if (existing != null) return existing;
        var prefs = prefs(context);
        var items = routes(prefs);
        var route = new JSONObject().put("address", protocol.name().toLowerCase(Locale.ROOT) + "-"
                + Integer.toHexString(target.toLowerCase(Locale.ROOT).hashCode()) + Long.toHexString(System.nanoTime() & 0xfff))
            .put("protocol", protocol.name().toLowerCase(Locale.ROOT)).put("target", target).put("title", title)
            .put("group", group).put("direct", true);
        if (extra != null) for (var keys = extra.keys(); keys.hasNext(); ) { String key = keys.next(); route.put(key, extra.get(key)); }
        items.put(route);
        prefs.edit().putString(ROUTES, items.toString()).apply();
        // The inbox shows the new chat now, before its first message.
        MmsTransport.changed(context);
        return route;
    }

    /**
     * "connected", "connecting", "reconnecting" (retry_at), "disconnected" (after the hourly limit, a refusal in "error",
     * or with "manual" while Reconnect automatically is off), or "off" while this phone isn't connecting at all.
     */
    static JSONObject status(SharedPreferences prefs, MessageProtocol protocol) {
        try { return new JSONObject(prefs.getString("direct.status." + protocol.name(), "{}")); }
        catch (Exception error) { return new JSONObject(); }
    }

    private static JSONObject statusOff() {
        try { return new JSONObject().put("state", "off"); } catch (Exception error) { return new JSONObject(); }
    }

    static void saveStatus(Context context, MessageProtocol protocol, JSONObject status) {
        var before = status(prefs(context), protocol);
        prefs(context).edit().putString("direct.status." + protocol.name(), status.toString()).apply();
        // The inbox's plug follows: it reloads when the connection's mark changes (not on every retry time).
        if (!IrcRules.connectionState(before.optString("state"), before.optBoolean("manual"))
                .equals(IrcRules.connectionState(status.optString("state"), status.optBoolean("manual")))) {
            try { MmsTransport.changed(context); } catch (Exception ignored) {}
        }
    }

    /**
     * FluidMessages Web already connects to this IRC network as the same nick: the phone uses that connection instead
     * of a second one, which the network would rename to nick_ and could count as reconnect flooding. "Connect anyway"
     * (direct.IRC.anyway) overrides it.
     */
    static boolean webHasSameAccount(SharedPreferences prefs, MessageProtocol protocol, JSONObject account) {
        if (protocol != MessageProtocol.IRC || account == null || prefs.getString("server", "").isEmpty()) return false;
        String id = "irc:" + account.optString("nick") + "@" + account.optString("server").toLowerCase(Locale.ROOT);
        try {
            var accounts = new JSONArray(prefs.getString("gatewayAccounts", "[]"));
            for (int i = 0; i < accounts.length(); i++) if (id.equals(accounts.optJSONObject(i).optString("id"))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    static boolean deferredToWeb(SharedPreferences prefs, MessageProtocol protocol, JSONObject account) {
        return webHasSameAccount(prefs, protocol, account) && !prefs.getBoolean("direct." + protocol.name() + ".anyway", false);
    }

    static JSONObject command(Context context, String address, MessageProtocol format, ChatCommand command) throws Exception {
        var route = route(prefs(context), address, format);
        if (route == null) throw new IllegalStateException("Link this chat first");
        DirectClient client;
        synchronized (DirectProtocols.class) { client = clients.get(format); }
        if (client == null) throw new IllegalStateException(format.label + " isn’t connected. Open FluidMessages and try again.");
        return client.command(context, route, command);
    }

    // IRC channels on the phone's own connection: the Join and Browse pages (ServersScreen) work without FluidMessages Web.

    private static DirectIrc irc() {
        synchronized (DirectProtocols.class) { return clients.get(MessageProtocol.IRC) instanceof DirectIrc client ? client : null; }
    }

    /** Whether the phone's own IRC connection is signed in and can join or list channels right now. */
    static boolean ircReady() {
        var client = irc();
        return client != null && client.ready();
    }

    private static DirectIrc ircOrThrow(Context context) {
        var client = irc();
        if (client != null) return client;
        var account = account(context, MessageProtocol.IRC);
        String server = account == null ? "" : account.optString("server");
        String name = ServerDirectory.INSTANCE.ircName(server);
        throw new IllegalStateException("You’re not connected to " + (name != null ? name : server.isEmpty() ? "IRC" : server) + " yet. Connect, then try again.");
    }

    /** Joins a channel by name (with or without #) and returns its chat's link. */
    static JSONObject ircJoin(Context context, String channel) throws Exception {
        return ircJoin(context, channel, "");
    }

    /** Joins with the channel's password ([key]), for a channel that asked for one (IrcJoinRefused code 475). */
    static JSONObject ircJoin(Context context, String channel, String key) throws Exception {
        return ircOrThrow(context).join(context.getApplicationContext(), channel, key == null ? "" : key.trim());
    }

    /** The network's public channels matching [search], busiest first, capped at [limit]. */
    static List<IrcChannelRow> ircList(Context context, String search, int limit) throws Exception {
        return ircOrThrow(context).list(search, limit);
    }

    /**
     * The NickServ card on the phone's own IRC connection: sends IDENTIFY, and with [save] keeps the password sealed
     * on the account like its other secrets, without reconnecting. The password is never logged or put in a chat.
     */
    static void ircIdentify(Context context, String password, boolean save) throws Exception {
        ircOrThrow(context).identifyNow(password, save);
        if (!save) return;
        synchronized (DirectProtocols.class) {
            var account = account(context, MessageProtocol.IRC);
            if (account == null) return;
            account.put("nickserv_password", password);
            String sealed = account.toString();
            prefs(context).edit().putString("direct.account." + MessageProtocol.IRC.name(), seal(sealed)).apply();
            // The running connection already has it, so the next restart() check doesn't reconnect for it.
            var client = irc();
            if (client != null) client.resign(sealed);
        }
    }

    static void reconnect(Context context, MessageProtocol format) {
        DirectClient client;
        synchronized (DirectProtocols.class) { client = clients.get(format); }
        if (client != null) client.reconnectNow();
        else restart(context);
    }

    static void opened(Context context, String address, MessageProtocol format) {
        var route = route(prefs(context), address, format);
        DirectClient client;
        synchronized (DirectProtocols.class) { client = clients.get(format); }
        if (route != null && client != null) client.opened(route);
    }

    /**
     * Reconnect automatically (default on) for this phone's account on [protocol]. Off: it doesn't connect when the app
     * opens or after a drop, only when you tap Reconnect; its chats stay in the list.
     */
    static boolean autoReconnect(SharedPreferences prefs, MessageProtocol protocol) {
        return prefs.getBoolean(key(protocol) + ".reconnect", true);
    }

    static void setAutoReconnect(Context context, MessageProtocol protocol, boolean on) {
        prefs(context).edit().putBoolean(key(protocol) + ".reconnect", on).apply();
        DirectClient client;
        synchronized (DirectProtocols.class) { client = clients.get(protocol); }
        if (client != null) client.autoReconnectChanged();
        // Turned back on: connect now rather than waiting for the next Reconnect.
        if (on) reconnect(context, protocol);
    }

    /**
     * Disconnect, from a chat's or server's Connection section, the inbox, or the Connections page: turns Reconnect
     * automatically off, so nothing connects again until Connect or Reconnect (also when the app opens next), and
     * closes the connection now. A fresh client then waits for Reconnect; the chats stay in the list.
     */
    static void disconnect(Context context, MessageProtocol protocol) {
        var app = context.getApplicationContext();
        prefs(app).edit().putBoolean(key(protocol) + ".reconnect", false).apply();
        synchronized (DirectProtocols.class) {
            var existing = clients.remove(protocol);
            if (existing != null) existing.stop();
            var account = account(app, protocol);
            try {
                saveStatus(app, protocol, new JSONObject().put("state", "disconnected").put("manual", true).put("retry_at", 0)
                    .put("error", "").put("network", account == null ? "" : account.optString(protocol == MessageProtocol.IRC ? "server" : "homeserver")));
            } catch (Exception ignored) {}
            restart(app);
        }
    }

    /** Remove channel: takes a channel or group chat out of the list; an open IRC connection parts it at once. */
    static void removeChannel(Context context, String address, MessageProtocol protocol) {
        // Matrix and XMPP: leave the room on the server too, when connected (IRC parts it in syncChannels below).
        if (protocol != MessageProtocol.IRC && route(prefs(context), address, protocol) != null) {
            try { command(context, address, protocol, new ChatCommand("part", "", java.util.List.of())); } catch (Exception ignored) {}
        }
        if (route(prefs(context), address, protocol) != null) unlink(context, address, protocol);
        prefs(context).edit().remove("keepJoined." + address).apply();
        restart(context);
        MmsTransport.changed(context);
    }

    /** Keep joined (default on): rejoined right after reconnecting. Off: rejoined when you open the channel. */
    static boolean keepJoined(SharedPreferences prefs, String address) {
        return prefs.getBoolean("keepJoined." + address, true);
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
            // A protocol turned off for messages stays connected when it's still allowed for calls.
            var account = on(prefs, protocol) && (protocol.enabled(prefs) || Calling.keepsConnected(prefs, protocol)) ? account(app, protocol) : null;
            var existing = clients.get(protocol);
            boolean always = account != null && stayConnected(prefs, protocol);
            // Never a second IRC connection beside FluidMessages Web's with the same nick.
            boolean wanted = account != null && (foreground || always) && !deferredToWeb(prefs, protocol, account);
            service |= always && wanted;
            // IRC follows channel changes on its open connection, so a new channel or direct chat never reconnects.
            String signature = account == null ? "" : protocol == MessageProtocol.IRC ? account.toString() : account.toString() + routes(prefs);
            if (existing instanceof DirectIrc irc && wanted && signature.equals(existing.toString())) irc.syncChannels();
            if (existing != null && (!wanted || !signature.equals(existing.toString()))) {
                existing.stop();
                clients.remove(protocol);
                existing = null;
                // Not connecting from this phone now: the inbox shows no connection mark rather than an old one.
                if (!wanted) saveStatus(app, protocol, statusOff());
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
        ProtocolService.update(app, service || SipAgent.alwaysOn(prefs));
        if (!clients.isEmpty()) Outbox.schedule(app);
    }

    /** One background check for protocols that don't keep a connection open (from the periodic job). */
    static void poll(Context context) {
        var app = context.getApplicationContext();
        var prefs = prefs(app);
        for (MessageProtocol protocol : new MessageProtocol[]{MessageProtocol.MATRIX, MessageProtocol.EMAIL}) {
            if (!on(prefs, protocol) || !protocol.enabled(prefs)) continue;
            // Reconnect automatically off: no checks in the background either, until you tap Reconnect.
            if (protocol == MessageProtocol.MATRIX && !autoReconnect(prefs, protocol)) continue;
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
            if (record.optInt("type") == 1 && !record.optBoolean("event")) {
                if (Mutes.isMuted(prefs(context), record.getString("address"))) return;  // Muted: stored and unread, but quiet.
                if (HiddenChats.rowHidden(prefs(context), record)) SmsDelivery.notifyHidden(context);
                else SmsDelivery.notify(context, "New " + MessageProtocol.parse(record.getString("protocol")).label
                    + " message", record.getString("address"));
            }
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
        send(context, address, format, text, body, relation, attachment, false);
    }

    /** [literal]: typed with a leading //, so "/me" and the like go out as written instead of as an action. */
    static void send(Context context, String address, MessageProtocol format, String text, String body,
                     MessageRelation relation, Attachment attachment, boolean literal) throws Exception {
        format.requireEnabled(context);
        var prefs = prefs(context);
        var route = route(prefs, address, format);
        if (route == null) throw new IllegalStateException("Link a " + format.label + " address to this conversation first.");
        DirectClient client;
        synchronized (DirectProtocols.class) { client = clients.get(format); }
        String id = "direct-" + UUID.randomUUID();
        var message = record(route, id, body, true, System.currentTimeMillis(), "").put("status", "Sending…").put("text", text);
        if (literal) message.put("literal", true);
        int disappear = Disappearing.seconds(prefs, address);
        if (disappear > 0) message.put("expire_seconds", disappear);
        var store = ProtocolStore.get(context);
        if (relation != null) {
            var target = relation.getTargetId() == null ? null : store.find(relation.getTargetId());
            var json = relation.toJson();
            if (target != null && address.equals(target.optString("address"))) {
                for (String field : new String[]{"event_id", "mail_id", "mail_subject", "xmpp_id", "irc_msgid", "author"}) {
                    if (target.has(field)) json.put(field, target.get(field));
                }
                if (relation.getKind() == RelationKind.REACTION) {
                    message.put("reaction_target", target.getString("id"));
                    if (relation.getRevises()) {
                        String earlier = previousReactionEvent(store, address, target.getString("id"));
                        if (!earlier.isEmpty()) json.put("previous_event", earlier);
                    }
                }
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
            if (client == null) { queue(context, message); return; }
            deliver(context, client, route, message, file);
        } catch (java.io.IOException error) {
            // A weak or missing connection: keep it and send it when the network is back, instead of failing.
            queue(context, message);
        } catch (Exception error) {
            message.put("status", "Couldn’t send · " + (error.getMessage() == null ? "Try again" : error.getMessage()));
            store.put(message);
            throw error;
        } finally {
            if (file != null) file.delete();
            MmsTransport.changed(context);
        }
    }

    private static void deliver(Context context, DirectClient client, JSONObject route, JSONObject message,
                                MediaSanitizer.Prepared file) throws Exception {
        var updates = client.send(context, route, message, file);
        for (var keys = updates.keys(); keys.hasNext(); ) {
            String field = keys.next();
            message.put(field, updates.get(field));
        }
        message.remove("queued");
        message.put("status", "Sent");
        ProtocolStore.get(context).put(message);
    }

    static final String QUEUED = "Queued · sends when you’re back online";

    private static void queue(Context context, JSONObject message) throws Exception {
        message.put("queued", true).put("status", QUEUED);
        ProtocolStore.get(context).put(message);
        Outbox.schedule(context);
    }

    /** Sends messages that were queued while offline, oldest first. True when nothing is left waiting. */
    static boolean flush(Context context) {
        var app = context.getApplicationContext();
        var prefs = prefs(app);
        var store = ProtocolStore.get(app);
        boolean done = true;
        try {
            var waiting = new java.util.ArrayList<JSONObject>();
            for (var row : store.read()) if (row.optBoolean("queued")) waiting.add(new JSONObject(row.toString()));
            waiting.sort(java.util.Comparator.comparingLong(row -> row.optLong("date")));
            for (var message : waiting) {
                var format = MessageProtocol.parse(message.optString("protocol"));
                var route = route(prefs, message.optString("address"), format);
                DirectClient client;
                synchronized (DirectProtocols.class) { client = clients.get(format); }
                if (route == null || !format.enabled(prefs)) {
                    message.remove("queued");
                    store.put(message.put("status", "Couldn’t send · " + format.label + " is no longer set up"));
                    continue;
                }
                if (client == null) { done = false; continue; }
                MediaSanitizer.Prepared file = null;
                try {
                    var attachment = Attachment.fromJson(message.optJSONObject("attachment"), store.file(message));
                    if (attachment != null && attachment.isFile()) file = MediaSanitizer.prepare(app, attachment, MediaSanitizer.MAX_FILE);
                    deliver(app, client, route, message, file);
                } catch (java.io.IOException offline) {
                    done = false;
                    int tries = message.optInt("tries") + 1;
                    if (tries >= 30) {
                        message.remove("queued");
                        message.put("status", "Couldn’t send · " + (offline.getMessage() == null ? "no connection" : offline.getMessage()));
                    }
                    store.put(message.put("tries", tries));
                    break;  // Still offline; try again later rather than hammering the server.
                } catch (Exception error) {
                    message.remove("queued");
                    store.put(message.put("status", "Couldn’t send · " + (error.getMessage() == null ? "Try again" : error.getMessage())));
                } finally { if (file != null) file.delete(); }
            }
        } catch (Exception ignored) { done = false; }
        finally { MmsTransport.changed(app); }
        return done;
    }

    /** The phone's own XMPP connection, for XMPP calls; null when XMPP isn't connected from this phone. */
    static DirectXmpp xmpp() {
        synchronized (DirectProtocols.class) { return clients.get(MessageProtocol.XMPP) instanceof DirectXmpp client ? client : null; }
    }

    /** The phone's own Matrix connection, for calls in chats it links directly; null otherwise. */
    static DirectMatrix matrix() {
        synchronized (DirectProtocols.class) { return clients.get(MessageProtocol.MATRIX) instanceof DirectMatrix client ? client : null; }
    }

    static void changeMember(Context context, String address, MessageProtocol format, boolean add, String member) throws Exception {
        var route = route(prefs(context), address, format);
        if (route == null) throw new IllegalStateException("Link this group first");
        DirectClient client;
        synchronized (DirectProtocols.class) { client = clients.get(format); }
        if (client == null) throw new IllegalStateException(format.label + " isn’t connected. Open FluidMessages and try again.");
        client.changeMember(context, route, add, member.trim());
    }

    /** Takes back a sent message on Matrix or XMPP when it disappears. Companion-linked chats are handled there. */
    static void retract(Context context, JSONObject record) throws Exception {
        var format = MessageProtocol.parse(record.optString("protocol"));
        var route = route(prefs(context), record.optString("address"), format);
        if (route == null) return;
        DirectClient client;
        synchronized (DirectProtocols.class) { client = clients.get(format); }
        if (client != null) client.retract(context, route, record);
    }

    /** The event of your latest reaction on a message, which a removal or change takes back; "" if it's gone. */
    static String previousReactionEvent(ProtocolStore store, String address, String target) throws Exception {
        for (JSONObject item : store.read()) {
            if (!address.equals(item.optString("address")) || item.optInt("type") != 2
                    || !target.equals(item.optString("reaction_target"))) continue;
            var earlier = item.optJSONObject("relation");
            if (earlier != null && earlier.optBoolean("removed")) return "";
            return item.optString("status").equals("Sent") ? item.optString("event_id") : "";
        }
        return "";
    }

    static String unavailable(SharedPreferences prefs, String address, MessageProtocol format) {
        if (route(prefs, address, format) == null) return "Link a " + format.label + " address to this conversation in its details.";
        try {
            var account = (JSONObject) null;
            String sealed = prefs.getString("direct.account." + format.name(), "");
            if (!sealed.isEmpty()) account = new JSONObject(open(sealed));
            if (deferredToWeb(prefs, format, account)) {
                return "FluidMessages Web is already connected to " + account.optString("server") + " as " + account.optString("nick")
                    + ", so this phone doesn’t open a second connection with the same nick. Turn off Connect from this phone for IRC to use FluidMessages Web’s connection.";
            }
        } catch (Exception ignored) {}
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
