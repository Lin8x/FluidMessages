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
        for (MessageProtocol format : MessageProtocol.values()) if (!format.isText() && format.enabled(prefs)) return true;
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
            if (route != null && address.equals(route.optString("address"))) return route.optString("title");
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
            store.putBatch(messages);
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
        var endpoints = response.optJSONArray("routes");
        String value = endpoints == null ? "[]" : endpoints.toString();
        boolean changed = !value.equals(prefs.getString(ROUTES, "[]"));
        // With no internet accounts on the companion, check back rarely instead of every minute.
        if (endpoints == null || endpoints.length() == 0) nextCheck = SystemClock.elapsedRealtime() + 15 * 60_000;
        prefs.edit().putString(ROUTES, value).apply();
        return changed || messages != null && messages.length() > 0;
    }

    /**
     * Queues a message on the companion. {@code body} is the readable fallback kept in history and sent to
     * protocols without the feature; {@code text} plus {@code relation} let Matrix, XMPP, and Email use
     * native replies and reactions.
     */
    static void send(Context context, String address, MessageProtocol format, String text, String body,
                     MessageRelation relation, Attachment attachment, boolean files) throws Exception {
        format.requireEnabled(context);
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        var payload = new JSONObject().put("address", address)
            .put("protocol", format.name().toLowerCase(Locale.ROOT)).put("body", body).put("text", text)
            .put("request", UUID.randomUUID().toString());
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
        String origin = prefs.getString("server", "");
        String token = Privacy.token(prefs);
        if (!CloudBackup.paired(origin, token)) throw new IOException("Pair FluidMessages Web first.");
        var connection = (HttpsURLConnection) new URL(origin + "/api/protocols/upload").openConnection();
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
        var connection = (HttpsURLConnection) new URL(origin + "/api/protocols/file?id="
            + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8)).openConnection();
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
            if (format.enabled(prefs) && !DirectProtocols.on(prefs, format)) result.put(format.name().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /** Accounts on the companion, with secrets reported only as set or not. */
    static JSONArray accounts(SharedPreferences prefs) throws Exception {
        return send(prefs, "GET", "protocols/accounts", null).optJSONArray("accounts");
    }

    static JSONArray saveAccount(SharedPreferences prefs, String protocol, JSONObject fields) throws Exception {
        return request(prefs, "protocols/accounts", new JSONObject().put("protocol", protocol).put("fields", fields))
            .optJSONArray("accounts");
    }

    static JSONArray deleteAccount(SharedPreferences prefs, String protocol) throws Exception {
        return request(prefs, "protocols/accounts/delete", new JSONObject().put("protocol", protocol)).optJSONArray("accounts");
    }

    /** Links a conversation (a contact's phone number, or a new id) to an address on an internet protocol. */
    static void link(SharedPreferences prefs, String address, MessageProtocol format, String target, String title,
                     boolean group) throws Exception {
        request(prefs, "protocols/routes", new JSONObject().put("address", address)
            .put("protocol", format.name().toLowerCase(Locale.ROOT)).put("target", target).put("title", title).put("group", group));
        nextCheck = 0;
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
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && MessageProtocol.parse(route.optString("protocol")) == format
                    && target.equalsIgnoreCase(route.optString("target"))) return route.optString("address");
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
        var url = new URL(homeserver.replaceAll("/+$", "") + "/_matrix/client/v3/login");
        if (!"https".equals(url.getProtocol())) throw new IOException("Use an https:// homeserver address.");
        var connection = (HttpsURLConnection) url.openConnection();
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

    private static JSONObject request(SharedPreferences prefs, String endpoint, JSONObject payload) throws Exception {
        return send(prefs, "POST", endpoint, payload);
    }

    private static JSONObject send(SharedPreferences prefs, String method, String endpoint, JSONObject payload) throws Exception {
        String origin = prefs.getString("server", "");
        String token = Privacy.token(prefs);
        if (!CloudBackup.paired(origin, token)) throw new IOException("Pair FluidMessages Web first.");
        var connection = (HttpsURLConnection) new URL(origin + "/api/" + endpoint).openConnection();
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
