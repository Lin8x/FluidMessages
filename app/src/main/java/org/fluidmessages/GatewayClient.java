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
                !CloudBackup.paired(prefs.getString("server", ""), prefs.getString("token", ""))) {
            return "Connect " + format.label + " for this contact on the web companion.";
        }
        return "";
    }

    static synchronized boolean refresh(Context context, SharedPreferences prefs) throws Exception {
        if (!CloudBackup.paired(prefs.getString("server", ""), prefs.getString("token", ""))
                || !wanted(prefs) || SystemClock.elapsedRealtime() < nextCheck) return false;
        nextCheck = SystemClock.elapsedRealtime() + 60_000;
        var response = request(prefs, "protocols", new JSONObject().put("enabled", enabled(prefs)));
        JSONArray messages = response.optJSONArray("messages");
        if (messages != null) {
            var store = ProtocolStore.get(context);
            store.putBatch(messages);
            // Follow the Battery and sync choice for fetching received attachments without a tap.
            if (SyncPolicy.autoDownload(context)) for (int i = 0; i < messages.length(); i++) {
                var message = messages.optJSONObject(i);
                var attached = message == null ? null : message.optJSONObject("attachment");
                if (attached == null || message.optInt("type") != 1 || store.file(message) != null
                        || attached.optLong("size") > MediaSanitizer.MAX_FILE) continue;
                try { download(context, message); } catch (Exception ignored) {}
            }
        }
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
        String token = prefs.getString("token", "");
        if (!CloudBackup.paired(origin, token)) throw new IOException("Pair the HTTPS companion first.");
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
                if (input == null) throw new IOException("Update your web companion to send files.");
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
        String token = prefs.getString("token", "");
        String id = message.getString("id");
        if (!CloudBackup.paired(origin, token)) throw new IOException("Pair the HTTPS companion first.");
        var connection = (HttpsURLConnection) new URL(origin + "/api/protocols/file?id="
            + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8)).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(120_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (connection.getResponseCode() != 200) throw new IOException("The attachment is no longer on the companion.");
            try (var input = connection.getInputStream()) { ProtocolStore.get(context).putFile(id, input, MediaSanitizer.MAX_FILE); }
        } finally { connection.disconnect(); }
    }

    static JSONArray enabled(SharedPreferences prefs) {
        var result = new JSONArray();
        for (MessageProtocol format : MessageProtocol.values()) {
            if (format.enabled(prefs)) result.put(format.name().toLowerCase(Locale.ROOT));
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
        return matrixLogin(homeserver, user, password, "FluidMessages companion");
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
        String token = prefs.getString("token", "");
        if (!CloudBackup.paired(origin, token)) throw new IOException("Pair the HTTPS companion first.");
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
                if (input == null) throw new IOException("Update and reconnect your web companion.");
                byte[] bytes = input.readNBytes(LIMIT + 1);
                if (bytes.length > LIMIT) throw new IOException("The protocol snapshot is too large.");
                var reply = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (code != 200) throw new IOException(reply.optString("error", "Could not connect the message protocol."));
                return reply;
            }
        } finally { connection.disconnect(); }
    }
}
