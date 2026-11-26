package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

final class GatewayClient {
    static final String ROUTES = "gatewayRoutes";
    private static long nextCheck;
    private static final int LIMIT = 7 * 1024 * 1024;

    /** Links from the companion plus links for protocols connected from this phone. */
    private static JSONArray routes(SharedPreferences prefs) {
        JSONArray result;
        try { result = new JSONArray(prefs.getString(ROUTES, "[]")); }
        catch (Exception ignored) { result = new JSONArray(); }
        var direct = DirectProtocols.routes(prefs);
        for (int i = 0; i < direct.length(); i++) {
            var route = direct.optJSONObject(i);
            if (route != null && DirectProtocols.on(prefs, MessageProtocol.parse(route.optString("protocol")))) result.put(route);
        }
        return result;
    }

    static boolean hasRoutes(SharedPreferences prefs) { return routes(prefs).length() > 0; }

    /** Every linked address, on the phone or the server, for the Phone tab's contact list. */
    static List<JSONObject> allRoutes(SharedPreferences prefs) {
        var items = routes(prefs);
        var result = new ArrayList<JSONObject>();
        for (int i = 0; i < items.length(); i++) if (items.optJSONObject(i) != null) result.add(items.optJSONObject(i));
        return result;
    }

    /** Internet protocols connect automatically once paired; turning every one of them off stops the checks. */
    static boolean wanted(SharedPreferences prefs) {
        for (MessageProtocol format : MessageProtocol.values()) if (!format.isText() && format != MessageProtocol.NEARBY && format.enabled(prefs)) return true;
        return false;
    }

    static List<MessageProtocol> formats(SharedPreferences prefs, String identity) {
        var formats = new ArrayList<MessageProtocol>();
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && identity.equals(route.optString("address"))) {
                formats.add(MessageProtocol.parse(route.optString("protocol")));
            }
        }
        return formats;
    }

    static String title(SharedPreferences prefs, String address) {
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route == null || !address.equals(route.optString("address"))) continue;
            // An IRC network's server chat reads "Libera.Chat · network messages", not just the server's address.
            return route.optBoolean("server_chat") ? IrcIntro.serverChatTitle(route.optString("title")) : route.optString("title");
        }
        return "";
    }

    static List<GroupMember> members(SharedPreferences prefs, String address) {
        var items = routes(prefs);
        var members = new LinkedHashMap<String, GroupMember>();
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route == null || !address.equals(route.optString("address"))) continue;
            var people = route.optJSONArray("members");
            if (people == null) continue;
            for (int index = 0; index < people.length(); index++) {
                String person = people.optString(index);
                if (!person.isBlank()) members.putIfAbsent(person, new GroupMember(person));
            }
        }
        return List.copyOf(members.values());
    }

    static String identity(SharedPreferences prefs, String address, ContactDirectory contacts) {
        if (!prefs.getBoolean("combineProtocols", false) || !SmsStore.validRecipient(address)) return address;
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            String other = route == null ? "" : route.optString("address");
            if (SmsStore.validRecipient(other) && contacts.key(address).equals(contacts.key(other))) return other;
        }
        return address;
    }

    static String unavailable(SharedPreferences prefs, String number, MessageProtocol format) {
        if (!formats(prefs, number).contains(format) ||
                !CloudBackup.paired(prefs.getString("server", ""), Privacy.token(prefs))) {
            return "Connect " + format.label + " for this contact on FluidMessages Web.";
        }
        return "";
    }

    /** Tells the companion these messages were read here. Off the main thread; failures retry on the next refresh. */
    static void markRead(SharedPreferences prefs, List<String> ids) {
        if (ids.isEmpty() || !CloudBackup.paired(prefs.getString("server", ""), Privacy.token(prefs))) return;
        try { request(prefs, "protocols/read", new JSONObject().put("ids", new JSONArray(ids))); } catch (Exception ignored) {}
    }

    static synchronized boolean refresh(Context context, SharedPreferences prefs) throws Exception {
        if (!CloudBackup.paired(prefs.getString("server", ""), Privacy.token(prefs))
                || !wanted(prefs) || SystemClock.elapsedRealtime() < nextCheck) return false;
        nextCheck = SystemClock.elapsedRealtime() + 60_000;
        var response = request(prefs, "protocols", new JSONObject().put("enabled", enabled(prefs)));
        JSONArray messages = response.optJSONArray("messages");
        if (messages != null) {
            var store = ProtocolStore.get(context);
            // A message read on this phone stays read, even if the companion hasn't heard yet; tell it again.
            var readHere = new JSONArray();
            for (int i = 0; i < messages.length(); i++) {
                var message = messages.optJSONObject(i);
                if (message == null || message.optInt("read", 1) != 0) continue;
                var local = store.find(message.optString("id"));
                if (local != null && local.optInt("read", 0) == 1) {
                    message.put("read", 1);
                    readHere.put(message.getString("id"));
                }
            }
            // A message whose time is up here was deleted here; a companion that hasn't caught up can't bring it back.
            var fresh = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < messages.length(); i++) {
                var message = messages.optJSONObject(i);
                if (message != null && !Disappearing.expired(prefs, message, message.optString("address"), now)) fresh.put(message);
            }
            messages = fresh;
            // New incoming messages relayed by the companion (Matrix, XMPP, Email, IRC) notify like a text does.
            // A long backlog, such as the first sync after pairing, only notifies for the last day.
            var arrived = new ArrayList<JSONObject>();
            for (int i = 0; i < messages.length(); i++) {
                var message = messages.optJSONObject(i);
                if (message != null && IncomingAlerts.shouldNotify(message.optInt("type"), message.optInt("read", 1) == 0,
                        message.optBoolean("event"), message.optLong("date"), now)
                        && store.find(message.optString("id")) == null) arrived.add(message);
            }
            store.putBatch(messages);
            for (var message : IncomingAlerts.newestPerChat(arrived)) {
                // Muted chats (for now, or by their server) stay quiet; SmsDelivery.notify checks the rest too.
                if (Mutes.isMuted(prefs, message.optString("address"))) continue;
                if (HiddenChats.rowHidden(prefs, message)) SmsDelivery.notifyHidden(context);
                else SmsDelivery.notify(context, "New " + MessageProtocol.parse(message.optString("protocol")).label + " message",
                    message.optString("address"));
            }
            if (readHere.length() > 0) {
                try { request(prefs, "protocols/read", new JSONObject().put("ids", readHere)); } catch (Exception ignored) {}
            }
            // Follow the Battery and sync choice for fetching received attachments without a tap.
            if (SyncPolicy.autoDownload(context)) for (int i = 0; i < messages.length(); i++) {
                var message = messages.optJSONObject(i);
                var attached = message == null ? null : message.optJSONObject("attachment");
                if (attached == null || message.optInt("type") != 1 || store.file(message) != null
                        || attached.optLong("size") > MediaSanitizer.MAX_FILE) continue;
                try { download(context, message); } catch (Exception ignored) {}
            }
        }
        var accounts = response.optJSONArray("accounts");
        prefs.edit().putString("gatewayAccounts", accounts == null ? "[]" : accounts.toString()).apply();
        var channels = response.optJSONObject("channels");
        prefs.edit().putString("gatewayChannels", channels == null ? "{}" : channels.toString()).apply();
        // Invitations waiting on the Invitations page, and each IRC network's connection for the Reconnect bar.
        var invites = response.optJSONArray("invites");
        var connections = response.optJSONObject("connections");
        // The inbox's plugs follow a server connecting, dropping, or being turned off.
        boolean marks = !connectionMarks(prefs.getString("gatewayConnections", "{}")).equals(connectionMarks(connections == null ? "{}" : connections.toString()));
        // Each connection's Tor on FluidMessages Web (TorChat): the inbox's onion, and chat details' switch. Older
        // servers send none, and their chats say to update FluidMessages Web.
        var tor = response.optJSONObject("tor");
        String torValue = tor == null ? "{}" : tor.toString();
        marks = marks || !torRoutes(prefs.getString("gatewayTor", "{}")).equals(torRoutes(torValue));
        prefs.edit().putString("gatewayInvites", invites == null ? "[]" : invites.toString())
            .putString("gatewayConnections", connections == null ? "{}" : connections.toString())
            .putString("gatewayTor", torValue).apply();
        var endpoints = response.optJSONArray("routes");
        String value = endpoints == null ? "[]" : endpoints.toString();
        boolean changed = !value.equals(prefs.getString(ROUTES, "[]"));
        // With no internet accounts on the companion, check back rarely instead of every minute.
        if (endpoints == null || endpoints.length() == 0) nextCheck = SystemClock.elapsedRealtime() + 15 * 60_000;
        prefs.edit().putString(ROUTES, value).apply();
        return changed || marks || messages != null && messages.length() > 0;
    }

    /** Each account's Tor route on FluidMessages Web ("tor", "wait", "direct"), to tell whether the onions changed. */
    private static Map<String, String> torRoutes(String tor) {
        var routes = new HashMap<String, String>();
        try {
            var all = new JSONObject(tor == null ? "{}" : tor);
            for (var names = all.keys(); names.hasNext(); ) {
                String id = names.next();
                var entry = all.optJSONObject(id);
                if (entry != null) routes.put(id, entry.optString("route"));
            }
        } catch (Exception ignored) {}
        return routes;
    }

    /** Each server's inbox mark (IrcRules.connectionState), to tell whether a sync changed any. */
    private static Map<String, String> connectionMarks(String connections) {
        var marks = new HashMap<String, String>();
        try {
            var all = new JSONObject(connections == null ? "{}" : connections);
            for (var names = all.keys(); names.hasNext(); ) {
                String id = names.next();
                var status = all.optJSONObject(id);
                if (status != null) marks.put(id, IrcRules.connectionState(status.optString("state"), status.optBoolean("manual")));
            }
        } catch (Exception ignored) {}
        return marks;
    }

    /**
     * Queues a message on the companion. {@code body} is the readable fallback kept in history and sent to
     * protocols without the feature; {@code text} plus {@code relation} let Matrix, XMPP, and Email use
     * native replies and reactions.
     */
    static void send(Context context, String address, MessageProtocol format, String text, String body,
                     MessageRelation relation, Attachment attachment, boolean files) throws Exception {
        send(context, address, format, text, body, relation, attachment, files, false);
    }

    /** [literal]: typed with a leading //, so a line starting with / is sent as written, never read as a command. */
    static void send(Context context, String address, MessageProtocol format, String text, String body,
                     MessageRelation relation, Attachment attachment, boolean files, boolean literal) throws Exception {
        format.requireEnabled(context);
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        var payload = new JSONObject().put("address", address)
            .put("protocol", format.name().toLowerCase(Locale.ROOT)).put("body", body).put("text", text)
            .put("request", UUID.randomUUID().toString());
        if (literal) payload.put("literal", true);
        if (relation != null) payload.put("relation", relation.toJson());
        MediaSanitizer.Prepared file = null;
        try {
            if (attachment != null) {
                if (!files) throw new IOException(format.label + " can’t send files.");
                // The cleaned copy goes to the companion first; the message then refers to that upload.
                file = MediaSanitizer.prepare(context, attachment, MediaSanitizer.MAX_FILE);
                String upload = upload(prefs, file);
                payload.put("attachment", attachment.toJson().put("name", file.name()).put("mime", file.mime())
                    .put("size", file.file().length()).put("upload", upload));
            }
            var result = request(prefs, "protocols/send", payload);
            var message = result.optJSONObject("message");
            if (message != null) {
                if (file != null && message.optJSONObject("attachment") != null) {
                    try (var input = Files.newInputStream(file.file().toPath())) {
                        ProtocolStore.get(context).putFile(message.getString("id"), input, MediaSanitizer.MAX_FILE);
                    }
                }
                ProtocolStore.get(context).put(message);
            }
        } finally { if (file != null) file.delete(); }
    }

    /** Streams a prepared file to the companion and returns its upload id. */
    private static String upload(SharedPreferences prefs, MediaSanitizer.Prepared file) throws Exception {
        NetworkGate.requireInternet();
        String origin = prefs.getString("server", "");
        String token = Privacy.token(prefs);
        if (!CloudBackup.paired(origin, token)) throw new IOException("Pair FluidMessages Web first.");
        var connection = CompanionTls.open(prefs, origin + "/api/protocols/upload");
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(120_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", file.mime());
            connection.setRequestProperty("X-Fluid-Name", java.net.URLEncoder.encode(file.name(), StandardCharsets.UTF_8));
            connection.setFixedLengthStreamingMode(file.file().length());
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream(); var input = Files.newInputStream(file.file().toPath())) {
                input.transferTo(output);
            }
            int code = connection.getResponseCode();
            try (var input = code == 200 ? connection.getInputStream() : connection.getErrorStream()) {
                if (input == null) throw new IOException("Update FluidMessages Web to send files.");
                var reply = new JSONObject(new String(input.readNBytes(16 * 1024), StandardCharsets.UTF_8));
                if (code != 200) throw new IOException(reply.optString("error", "Could not upload the file."));
                return reply.getString("upload");
            }
        } finally { connection.disconnect(); }
    }

    /** Downloads a received attachment from the companion (or the phone's own Matrix connection) into local storage. */
    static void download(Context context, JSONObject message) throws Exception {
        var attached = message.optJSONObject("attachment");
        if (message.optBoolean("direct") && attached != null && !attached.optString("mxc").isEmpty()) {
            var account = DirectProtocols.account(context, MessageProtocol.MATRIX);
            if (account == null) throw new IOException("Add your Matrix account to download this file.");
            var client = new DirectMatrix("");
            client.load(context, account);
            client.download(message.getString("id"), attached.getString("mxc"));
            return;
        }
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        String origin = prefs.getString("server", "");
        String token = Privacy.token(prefs);
        String id = message.getString("id");
        if (!CloudBackup.paired(origin, token)) throw new IOException("Pair FluidMessages Web first.");
        NetworkGate.requireInternet();
        var connection = CompanionTls.open(prefs, origin + "/api/protocols/file?id="
            + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8));
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(120_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (connection.getResponseCode() != 200) throw new IOException("The attachment is no longer on FluidMessages Web.");
            try (var input = connection.getInputStream()) { ProtocolStore.get(context).putFile(id, input, MediaSanitizer.MAX_FILE); }
        } finally { connection.disconnect(); }
    }

    /**
     * Protocols the companion should handle. A protocol connected from this phone is left out, so the companion
     * pauses that account and each message arrives once.
     */
    static JSONArray enabled(SharedPreferences prefs) {
        var result = new JSONArray();
        for (MessageProtocol format : MessageProtocol.values()) {
            if (format.enabled(prefs) && format != MessageProtocol.NEARBY && !DirectProtocols.on(prefs, format)) result.put(format.name().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /** Accounts on the companion, with secrets reported only as set or not. */
    static JSONArray accounts(SharedPreferences prefs) throws Exception {
        return send(prefs, "GET", "protocols/accounts", null).optJSONArray("accounts");
    }

    static JSONArray saveAccount(SharedPreferences prefs, String protocol, JSONObject fields) throws Exception {
        return saveAccount(prefs, protocol, fields, "", false);
    }

    /** Saves an account on FluidMessages Web: [id] edits that one, [add] adds another, neither edits the first. */
    static JSONArray saveAccount(SharedPreferences prefs, String protocol, JSONObject fields, String id, boolean add) throws Exception {
        var payload = new JSONObject().put("protocol", protocol).put("fields", fields);
        if (id != null && !id.isEmpty()) payload.put("id", id);
        else if (add) payload.put("add", true);
        nextCheck = 0;
        return request(prefs, "protocols/accounts", payload).optJSONArray("accounts");
    }

    static JSONArray deleteAccount(SharedPreferences prefs, String protocol) throws Exception {
        return deleteAccount(prefs, protocol, "");
    }

    static JSONArray deleteAccount(SharedPreferences prefs, String protocol, String id) throws Exception {
        var payload = new JSONObject().put("protocol", protocol);
        if (id != null && !id.isEmpty()) payload.put("id", id);
        nextCheck = 0;
        return request(prefs, "protocols/accounts/delete", payload).optJSONArray("accounts");
    }

    // Several accounts per service, and servers (IRC networks, Matrix homeservers, XMPP accounts)

    /** The companion's accounts from its last sync: {protocol, source, id, name}. Older companions send no id. */
    static List<JSONObject> accountList(SharedPreferences prefs) {
        var result = new ArrayList<JSONObject>();
        try {
            var accounts = new JSONArray(prefs.getString("gatewayAccounts", "[]"));
            for (int i = 0; i < accounts.length(); i++) if (accounts.optJSONObject(i) != null) result.add(accounts.optJSONObject(i));
        } catch (Exception ignored) {}
        return result;
    }

    static List<JSONObject> accountsFor(SharedPreferences prefs, String service) {
        var result = new ArrayList<JSONObject>();
        for (var account : accountList(prefs)) if (service.equals(account.optString("protocol")) && !account.optString("id").isEmpty()) result.add(account);
        return result;
    }

    /** The account a conversation goes through on [format], or "". */
    static String accountOf(SharedPreferences prefs, String address, MessageProtocol format) {
        for (var link : links(prefs, address)) {
            if (MessageProtocol.parse(link.optString("protocol")) == format) return link.optString("account");
        }
        return "";
    }

    /** "from alice@example.org" for a chat's header and details when its service has more than one account. */
    static String via(SharedPreferences prefs, String address, MessageProtocol format) {
        var from = fromAccount(prefs, address, format);
        return from == null ? "" : "from " + from.getShown();
    }

    /**
     * The accounts New message can send from on [format], from the companion's last sync, with the one used most
     * recently marked. Empty while this phone connects that service itself (one account).
     */
    static List<SendAccount> sendAccounts(SharedPreferences prefs, MessageProtocol format) {
        var result = new ArrayList<SendAccount>();
        if (DirectProtocols.on(prefs, format)) return result;
        for (var account : accountsFor(prefs, format.name().toLowerCase(Locale.ROOT))) {
            result.add(new SendAccount(account.optString("id"), account.optString("name"), account.optBoolean("recent")));
        }
        return result;
    }

    /**
     * The account a chat sends from when its service has more than one (FromAccount.chatAccount): its link's, or the
     * first account for links from before multiple accounts. Null with one account, or for a chat without a link.
     */
    static SendAccount fromAccount(SharedPreferences prefs, String address, MessageProtocol format) {
        for (var link : links(prefs, address)) {
            if (MessageProtocol.parse(link.optString("protocol")) != format) continue;
            return FromAccount.INSTANCE.chatAccount(link.optString("account"), sendAccounts(prefs, format));
        }
        return null;
    }

    /** The inbox row's small account hint (FromAccount.inboxHint), or "". */
    static String inboxAccountHint(SharedPreferences prefs, String address, MessageProtocol format) {
        for (var link : links(prefs, address)) {
            if (MessageProtocol.parse(link.optString("protocol")) != format) continue;
            return FromAccount.INSTANCE.inboxHint(link.optString("account"), sendAccounts(prefs, format));
        }
        return "";
    }

    /** The server (IRC network, Matrix or XMPP account) a conversation belongs to, or "". */
    static String serverOf(SharedPreferences prefs, String address) {
        for (var link : links(prefs, address)) {
            String account = link.optString("account");
            if (AccountKeys.INSTANCE.getServerServices().contains(AccountKeys.INSTANCE.service(account))) return account;
        }
        return "";
    }

    /** Whether a conversation is a channel, room, or group chat on its server. */
    static boolean groupChat(SharedPreferences prefs, String address) {
        for (var link : links(prefs, address)) if (link.optBoolean("group")) return true;
        return false;
    }

    /** Servers for Messages → Communities: accounts on IRC, Matrix, and XMPP. */
    static List<JSONObject> servers(SharedPreferences prefs) {
        var result = new ArrayList<JSONObject>();
        for (var account : accountList(prefs)) {
            if (!account.optString("id").isEmpty() && AccountKeys.INSTANCE.getServerServices().contains(account.optString("protocol"))) result.add(account);
        }
        return result;
    }

    /** A server's directory: IRC LIST, the Matrix room directory, or XMPP group chat services and their rooms. */
    static JSONObject browse(SharedPreferences prefs, String account, String search, String service) throws Exception {
        var payload = new JSONObject().put("account", account).put("search", search).put("limit", 100);
        if (service != null && !service.isEmpty()) payload.put("service", service);
        return request(prefs, "protocols/browse", payload);
    }

    /** The Matrix Spaces an account has joined, with their rooms. */
    static JSONArray spaces(SharedPreferences prefs, String account) throws Exception {
        return request(prefs, "protocols/servers/spaces", new JSONObject().put("account", account)).optJSONArray("spaces");
    }

    /** Joins a channel or room on a server; returns its link. */
    static JSONObject join(SharedPreferences prefs, String account, String target, String title) throws Exception {
        return join(prefs, account, target, title, "");
    }

    /**
     * Joins with the channel's password ([key], "" for none). An IRC channel that said no comes back as
     * IrcJoinRefused, with the reason in plain words and the network's reply number (475 asks for a password).
     */
    static JSONObject join(SharedPreferences prefs, String account, String target, String title, String key) throws Exception {
        var body = new JSONObject().put("account", account).put("target", target).put("title", title == null ? "" : title);
        if (key != null && !key.isEmpty()) body.put("key", key);
        var result = request(prefs, "protocols/join", body);
        nextCheck = 0;
        var refused = result.optJSONObject("refused");
        if (refused != null) throw new IrcJoinRefused(refused.optString("text", "Couldn’t join."), refused.optString("code"));
        return result.optJSONObject("route");
    }

    /** Stops joining [first] before an IRC channel on FluidMessages Web (the channel's details). */
    static void forgetJoinFirst(SharedPreferences prefs, String address, String first) throws Exception {
        request(prefs, "protocols/joinfirst", new JSONObject().put("address", address).put("protocol", "irc").put("remove", first));
        nextCheck = 0;
    }

    /** Runs a slash command typed in a chat on FluidMessages Web: {"notice", "open": {"address", "protocol"}}. */
    static JSONObject command(SharedPreferences prefs, String address, MessageProtocol format, String text) throws Exception {
        var result = request(prefs, "protocols/command", new JSONObject().put("address", address)
            .put("protocol", format.name().toLowerCase(Locale.ROOT)).put("text", text).put("request", UUID.randomUUID().toString()));
        nextCheck = 0;
        return result;
    }

    /** Accepts or declines an invitation or contact request waiting on FluidMessages Web. */
    static JSONObject answerInvite(SharedPreferences prefs, String id, boolean accept) throws Exception {
        var result = request(prefs, "protocols/invites", new JSONObject().put("id", id).put("accept", accept));
        prefs.edit().putString("gatewayInvites", result.optJSONArray("invites") == null ? "[]" : result.optJSONArray("invites").toString()).apply();
        nextCheck = 0;
        return result;
    }

    /**
     * The NickServ card in an IRC chat on FluidMessages Web: it sends IDENTIFY over its connection (never as a chat
     * message) and with [save] keeps the password on that account. Returns {"ok", "saved", "notice"}.
     */
    static JSONObject identify(SharedPreferences prefs, String address, String password, boolean save) throws Exception {
        return request(prefs, "protocols/identify", new JSONObject().put("address", address).put("protocol", "irc")
            .put("password", password).put("save", save));
    }

    /** The Reconnect button for an IRC network connected from FluidMessages Web. */
    static void reconnect(SharedPreferences prefs, String account) throws Exception {
        request(prefs, "protocols/reconnect", new JSONObject().put("account", account));
        nextCheck = 0;
    }

    /** A server's Reconnect automatically setting on FluidMessages Web (IRC networks, XMPP and Matrix accounts). */
    static void autoReconnect(SharedPreferences prefs, String account, boolean on) throws Exception {
        request(prefs, "protocols/autoreconnect", new JSONObject().put("account", account).put("on", on));
        nextCheck = 0;
    }

    /** Disconnect a server on FluidMessages Web until Connect: Reconnect automatically goes off there too. */
    static void disconnect(SharedPreferences prefs, String account) throws Exception {
        request(prefs, "protocols/disconnect", new JSONObject().put("account", account));
        nextCheck = 0;
    }

    /** An IRC channel's Keep joined setting on FluidMessages Web. */
    static void keepJoined(SharedPreferences prefs, String address, boolean keep) throws Exception {
        request(prefs, "protocols/keep", new JSONObject().put("address", address).put("protocol", "irc").put("keep", keep));
        nextCheck = 0;
    }

    /** Opening a channel that isn't kept joined rejoins it. */
    static void opened(SharedPreferences prefs, String address) {
        try { request(prefs, "protocols/open", new JSONObject().put("address", address).put("protocol", "irc")); }
        catch (Exception ignored) {}
    }

    /** The companion's link for a chat, or null. */
    static JSONObject routeOf(SharedPreferences prefs, String address, MessageProtocol format) {
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && address.equals(route.optString("address")) && format == MessageProtocol.parse(route.optString("protocol"))) return route;
        }
        return null;
    }

    /** Leaves a channel or room on its server. Its earlier messages stay. */
    static void leave(SharedPreferences prefs, String address, MessageProtocol format) throws Exception {
        request(prefs, "protocols/leave", new JSONObject().put("address", address).put("protocol", format.name().toLowerCase(Locale.ROOT)));
        nextCheck = 0;
    }

    /** Links a conversation (a contact's phone number, or a new id) to an address on an internet protocol. */
    static void link(SharedPreferences prefs, String address, MessageProtocol format, String target, String title,
                     boolean group) throws Exception {
        link(prefs, address, format, target, title, group, "");
    }

    /**
     * Links through [account], or the service's most recently used one when it's "". The companion keeps a contact's
     * second account in a chat of its own; the returned address is that chat's.
     */
    static String link(SharedPreferences prefs, String address, MessageProtocol format, String target, String title,
                       boolean group, String account) throws Exception {
        var payload = new JSONObject().put("address", address)
            .put("protocol", format.name().toLowerCase(Locale.ROOT)).put("target", target).put("title", title).put("group", group);
        if (account != null && !account.isEmpty()) payload.put("account", account);
        var route = request(prefs, "protocols/routes", payload).optJSONObject("route");
        nextCheck = 0;
        return route == null ? address : route.optString("address", address);
    }

    /** Every chat linked to Matrix, on the phone or the companion: [address, title] pairs, for the phone's call list. */
    static List<String[]> matrixChats(SharedPreferences prefs) {
        var result = new ArrayList<String[]>();
        var seen = new java.util.HashSet<String>();
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route == null || MessageProtocol.parse(route.optString("protocol")) != MessageProtocol.MATRIX) continue;
            String address = route.optString("address");
            if (seen.add(address)) result.add(new String[]{address, route.optString("title", address)});
        }
        return result;
    }

    /** Call signaling relayed by the companion for Matrix rooms it links. */
    static void callSend(SharedPreferences prefs, String address, String type, JSONObject content) throws Exception {
        request(prefs, "calls/send", new JSONObject().put("address", address).put("protocol", "matrix").put("type", type).put("content", content));
    }

    static JSONObject calls(SharedPreferences prefs, long since) throws Exception {
        return send(prefs, "GET", "calls?since=" + since, null);
    }

    static JSONArray iceServers(SharedPreferences prefs) {
        try { return send(prefs, "GET", "calls/ice", null).optJSONArray("iceServers"); }
        catch (Exception error) { return new JSONArray(); }
    }

    /** Invites or removes someone in a companion-linked group: a Matrix room, XMPP room, or IRC channel. */
    static void changeMember(SharedPreferences prefs, String address, MessageProtocol format, boolean add, String member) throws Exception {
        request(prefs, "protocols/members", new JSONObject().put("address", address)
            .put("protocol", format.name().toLowerCase(Locale.ROOT)).put("action", add ? "add" : "remove").put("member", member));
        nextCheck = 0;
    }

    static void unlink(SharedPreferences prefs, String address, MessageProtocol format) throws Exception {
        request(prefs, "protocols/routes/delete", new JSONObject().put("address", address)
            .put("protocol", format.name().toLowerCase(Locale.ROOT)));
        nextCheck = 0;
    }

    /** An XMPP chat's OMEMO devices on FluidMessages Web, for its Encryption (OMEMO) page. */
    static JSONObject omemo(SharedPreferences prefs, String address) throws Exception {
        return request(prefs, "protocols/omemo", new JSONObject().put("address", address).put("protocol", "xmpp"));
    }

    static JSONObject omemoTrust(SharedPreferences prefs, String address, String jid, int device, String trust) throws Exception {
        return request(prefs, "protocols/omemo/trust", new JSONObject().put("address", address).put("protocol", "xmpp")
            .put("jid", jid).put("device", device).put("trust", trust));
    }

    static JSONObject omemoLink(SharedPreferences prefs, String address, String uri) throws Exception {
        return request(prefs, "protocols/omemo/trust", new JSONObject().put("address", address).put("protocol", "xmpp").put("uri", uri));
    }

    /** Whether the companion reports this conversation's room as end-to-end encrypted. */
    static boolean encrypted(SharedPreferences prefs, String address, MessageProtocol format) {
        for (var link : links(prefs, address)) {
            if (MessageProtocol.parse(link.optString("protocol")) == format && link.optBoolean("encrypted")) return true;
        }
        return false;
    }

    /** Whether the companion reported an account for this protocol on its last sync. */
    static boolean hasAccount(SharedPreferences prefs, MessageProtocol format) {
        try {
            var accounts = new JSONArray(prefs.getString("gatewayAccounts", "[]"));
            for (int i = 0; i < accounts.length(); i++) {
                if (format.name().toLowerCase(Locale.ROOT).equals(accounts.optJSONObject(i).optString("protocol"))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** The conversation already linked to this address, if any. */
    static String routeForTarget(SharedPreferences prefs, MessageProtocol format, String target) {
        return routeForTarget(prefs, format, target, "");
    }

    /** The chat with [target] through [account] ("" for any), counting older links as the first account's. */
    static String routeForTarget(SharedPreferences prefs, MessageProtocol format, String target, String account) {
        var accounts = account.isEmpty() ? List.<SendAccount>of() : sendAccounts(prefs, format);
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            String service = format.name().toLowerCase(Locale.ROOT);
            if (route != null && MessageProtocol.parse(route.optString("protocol")) == format
                    && AccountKeys.targetKey(service, target).equals(AccountKeys.targetKey(service, route.optString("target")))) {
                if (!account.isEmpty()) {
                    var owner = FromAccount.INSTANCE.chatAccount(route.optString("account"), accounts);
                    if (owner != null && !owner.getId().equals(account)) continue;
                }
                return route.optString("address");
            }
        }
        return null;
    }

    /** The addresses linked to a conversation, for contact details. */
    static List<JSONObject> links(SharedPreferences prefs, String address) {
        var result = new ArrayList<JSONObject>();
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && address.equals(route.optString("address"))) result.add(route);
        }
        return result;
    }

    /** IRC channel topic and members from the companion, keyed by conversation address. */
    static JSONObject channel(SharedPreferences prefs, String address) {
        try { return new JSONObject(prefs.getString("gatewayChannels", "{}")).optJSONObject(address); }
        catch (Exception error) { return null; }
    }

    /**
     * Signs in to a Matrix homeserver with a password and returns an access token. The password goes only
     * to the homeserver; the companion keeps just the token.
     */
    static String matrixLogin(String homeserver, String user, String password) throws Exception {
        return matrixLogin(homeserver, user, password, "FluidMessages Web");
    }

    static String matrixLogin(String homeserver, String user, String password, String device) throws Exception {
        NetworkGate.requireInternet();
        var url = new URL(homeserver.replaceAll("/+$", "") + "/_matrix/client/v3/login");
        if (!"https".equals(url.getProtocol())) throw new IOException("Use an https:// homeserver address.");
        var connection = (HttpsURLConnection) TorRoute.open(url);
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] data = new JSONObject().put("type", "m.login.password")
                .put("identifier", new JSONObject().put("type", "m.id.user").put("user", user))
                .put("password", password).put("initial_device_display_name", device)
                .toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream()) { output.write(data); }
            int code = connection.getResponseCode();
            try (var input = code == 200 ? connection.getInputStream() : connection.getErrorStream()) {
                var reply = new JSONObject(new String(input == null ? new byte[0] : input.readNBytes(64 * 1024), StandardCharsets.UTF_8));
                if (code != 200) throw new IOException(reply.optString("error", "The homeserver didn’t accept that sign-in."));
                return reply.getString("access_token");
            }
        } finally { connection.disconnect(); }
    }

    /**
     * Contacts made in FluidMessages Web (WebContacts), or null from a server too old to keep them, which answers
     * "Unknown endpoint". They're on their own endpoint rather than in the settings merge, so older phones never see them.
     */
    static JSONObject contacts(SharedPreferences prefs) throws Exception {
        try { return send(prefs, "GET", "contacts", null); }
        catch (IOException error) {
            if (String.valueOf(error.getMessage()).contains("Unknown endpoint")) return null;
            throw error;
        }
    }

    private static JSONObject request(SharedPreferences prefs, String endpoint, JSONObject payload) throws Exception {
        return send(prefs, "POST", endpoint, payload);
    }

    /** Posts to FluidMessages Web, for actions such as answering a message request (MessageRequests). */
    static JSONObject post(SharedPreferences prefs, String endpoint, JSONObject payload) throws Exception {
        return request(prefs, endpoint, payload);
    }

    private static JSONObject send(SharedPreferences prefs, String method, String endpoint, JSONObject payload) throws Exception {
        NetworkGate.requireInternet();
        String origin = prefs.getString("server", "");
        String token = Privacy.token(prefs);
        if (!CloudBackup.paired(origin, token)) throw new IOException("Pair FluidMessages Web first.");
        // Every FluidMessages Web request opens through CompanionTls, pinned when a certificate fingerprint was saved.
        var connection = CompanionTls.open(prefs, origin + "/api/" + endpoint);
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (payload != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                connection.setDoOutput(true);
                try (var output = connection.getOutputStream()) { output.write(data); }
            }
            int code = connection.getResponseCode();
            try (var input = code == 200 ? connection.getInputStream() : connection.getErrorStream()) {
                if (input == null) throw new IOException("Update and reconnect FluidMessages Web.");
                byte[] bytes = input.readNBytes(LIMIT + 1);
                if (bytes.length > LIMIT) throw new IOException("The protocol snapshot is too large.");
                var reply = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (code != 200) throw new IOException(reply.optString("error", "Could not connect the message protocol."));
                return reply;
            }
        } finally { connection.disconnect(); }
    }
}
