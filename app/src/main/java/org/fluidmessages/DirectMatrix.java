package org.fluidmessages;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Matrix from the phone over the client-server API: linked rooms only, with native replies, reactions, and
 * media. Matrix is checked with a long poll while FluidMessages is open and on the background schedule
 * otherwise. End-to-end encrypted rooms need the companion, which holds the encryption keys; here they show a
 * placeholder and sending is declined with that reason.
 */
final class DirectMatrix implements DirectProtocols.DirectClient {
    private static final long LIMIT = MediaSanitizer.MAX_FILE;
    private final String signature;
    private final Map<String, Boolean> encrypted = new HashMap<>();
    private volatile boolean stopped;
    private Thread thread;
    private Context context;
    private JSONObject account;

    DirectMatrix(String signature) { this.signature = signature; }

    @Override public String toString() { return signature; }
    @Override public boolean persistent() { return false; }

    @Override public void load(Context context, JSONObject account) {
        this.context = context;
        this.account = account;
    }

    @Override public void start(Context context, JSONObject account) {
        load(context, account);
        thread = new Thread(() -> {
            long delay = 5_000;
            while (!stopped) {
                try {
                    sync(30_000);
                    delay = 5_000;
                } catch (Exception error) {
                    try { Thread.sleep(delay); } catch (InterruptedException interrupted) { return; }
                    delay = Math.min(delay * 2, 300_000);
                }
            }
        }, "fluid-direct-matrix");
        thread.start();
    }

    @Override public void stop() {
        stopped = true;
        if (thread != null) thread.interrupt();
    }

    @Override public void poll(Context context) throws Exception { sync(0); }

    private Map<String, JSONObject> rooms() {
        var rooms = new HashMap<String, JSONObject>();
        var items = DirectProtocols.routes(DirectProtocols.prefs(context));
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && MessageProtocol.parse(route.optString("protocol")) == MessageProtocol.MATRIX) rooms.put(route.optString("target"), route);
        }
        return rooms;
    }

    private void sync(int timeout) throws Exception {
        var rooms = rooms();
        if (rooms.isEmpty()) {
            if (timeout > 0) Thread.sleep(30_000);
            return;
        }
        var prefs = DirectProtocols.prefs(context);
        var filter = new JSONObject().put("presence", new JSONObject().put("types", new JSONArray()))
            .put("room", new JSONObject().put("rooms", new JSONArray(rooms.keySet())).put("timeline", new JSONObject()
                .put("limit", 30).put("types", new JSONArray().put("m.room.message").put("m.room.encrypted").put("m.reaction"))));
        String since = prefs.getString("direct.matrix.since", "");
        String query = "sync?timeout=" + timeout + "&filter=" + URLEncoder.encode(filter.toString(), StandardCharsets.UTF_8)
            + (since.isEmpty() ? "" : "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8));
        var response = json("GET", query, null, timeout + 20_000);
        var joined = response.optJSONObject("rooms") == null ? null : response.getJSONObject("rooms").optJSONObject("join");
        if (joined != null) for (var keys = joined.keys(); keys.hasNext(); ) {
            String room = keys.next();
            var route = rooms.get(room);
            var timeline = joined.getJSONObject(room).optJSONObject("timeline");
            var events = timeline == null ? null : timeline.optJSONArray("events");
            for (int i = 0; route != null && events != null && i < events.length(); i++) accept(route, events.getJSONObject(i));
        }
        String next = response.optString("next_batch");
        if (!next.isEmpty()) prefs.edit().putString("direct.matrix.since", next).apply();
    }

    private void accept(JSONObject route, JSONObject event) throws Exception {
        if (account.optString("user_id").equals(event.optString("sender")) || !event.has("event_id")) return;
        String address = route.getString("address");
        String id = "matrix-" + hash(event.getString("event_id"));
        long date = event.optLong("origin_server_ts", System.currentTimeMillis());
        String author = event.optString("sender");
        var content = event.optJSONObject("content");
        if (content == null) return;
        var relates = content.optJSONObject("m.relates_to");
        JSONObject record;
        switch (event.optString("type")) {
            case "m.reaction" -> {
                if (relates == null || !"m.annotation".equals(relates.optString("rel_type"))) return;
                var target = DirectProtocols.findBy(context, address, "event_id", relates.optString("event_id"));
                if (target == null) return;
                record = DirectProtocols.record(route, id, MessageInteractionsKt.emojiReactionSms(relates.optString("key"),
                    target.optString("body"), false), false, date, author).put("reaction_target", target.getString("id"));
            }
            case "m.room.encrypted" -> record = DirectProtocols.record(route, id,
                "Encrypted message. Connect Matrix through your companion to read encrypted rooms.", false, date, author);
            case "m.room.message" -> {
                String type = content.optString("msgtype");
                String body = content.optString("body");
                if (type.equals("m.image") || type.equals("m.video") || type.equals("m.audio") || type.equals("m.file")) {
                    String url = content.optString("url");
                    if (url.isEmpty()) {
                        record = DirectProtocols.record(route, id, "Encrypted file. Connect Matrix through your companion to open it.", false, date, author);
                        break;
                    }
                    var info = content.optJSONObject("info");
                    String mime = info == null ? "application/octet-stream" : info.optString("mimetype", "application/octet-stream");
                    String name = MediaSanitizer.safeName(content.optString("filename", body));
                    var kind = content.has("org.matrix.msc3245.voice") ? AttachmentKind.VOICE : Attachment.kindFor(mime);
                    record = DirectProtocols.record(route, id, body.equals(name) ? name : body, false, date, author);
                    record.put("attachment", new Attachment(kind, null, name, mime, info == null ? -1 : info.optLong("size", -1),
                        info == null ? 0 : info.optLong("duration")).toJson().put("mxc", url));
                    if (SyncPolicy.autoDownload(context)) download(id, url);
                } else if (type.equals("m.text") || type.equals("m.notice") || type.equals("m.emote")) {
                    var reply = relates == null ? null : relates.optJSONObject("m.in_reply_to");
                    var target = reply == null ? null : DirectProtocols.findBy(context, address, "event_id", reply.optString("event_id"));
                    if (target != null) {
                        // Older clients prepend "> <@user> quote" lines; the quote card replaces them.
                        var lines = body.split("\n", -1);
                        int start = 0;
                        while (start < lines.length && lines[start].startsWith(">")) start++;
                        String rest = String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length)).strip();
                        body = "Reply to “" + MessageInteractionsKt.messageExcerpt(target.optString("body"), 240) + "”\n\n" + (rest.isEmpty() ? body : rest);
                    }
                    record = DirectProtocols.record(route, id, type.equals("m.emote") ? "/me " + body : body, false, date, author);
                    if (target != null) record.put("reply_target", target.getString("id"));
                } else return;
            }
            default -> { return; }
        }
        DirectProtocols.incoming(context, record.put("event_id", event.getString("event_id")));
    }

    /** Downloads a received file through authenticated media, falling back for older homeservers. */
    void download(String id, String mxc) throws Exception {
        var match = java.util.regex.Pattern.compile("mxc://([A-Za-z0-9.:-]{1,255})/([A-Za-z0-9_-]{1,255})").matcher(mxc);
        if (!match.matches()) throw new IOException("Invalid media address");
        String path = URLEncoder.encode(match.group(1), StandardCharsets.UTF_8) + "/" + URLEncoder.encode(match.group(2), StandardCharsets.UTF_8);
        try (var input = open("GET", "/_matrix/client/v1/media/download/" + path, null, null, 120_000)) {
            ProtocolStore.get(context).putFile(id, input, LIMIT);
        } catch (IOException error) {
            try (var input = open("GET", "/_matrix/media/v3/download/" + path, null, null, 120_000)) {
                ProtocolStore.get(context).putFile(id, input, LIMIT);
            }
        }
    }

    @Override public JSONObject send(Context context, JSONObject route, JSONObject message, MediaSanitizer.Prepared file) throws Exception {
        String room = route.getString("target");
        String encodedRoom = URLEncoder.encode(room, StandardCharsets.UTF_8);
        if (isEncrypted(room)) throw new IllegalStateException("This room is end-to-end encrypted. Connect Matrix through your companion to send here.");
        var relation = message.optJSONObject("relation");
        String event = relation == null ? "" : relation.optString("event_id");
        String transaction = URLEncoder.encode(message.getString("id"), StandardCharsets.UTF_8);
        if (relation != null && "reaction".equals(relation.optString("kind")) && !event.isEmpty()) {
            var content = new JSONObject().put("m.relates_to", new JSONObject().put("rel_type", "m.annotation")
                .put("event_id", event).put("key", relation.optString("emoji")));
            return new JSONObject().put("event_id", json("PUT", "rooms/" + encodedRoom + "/send/m.reaction/" + transaction,
                content, 30_000).getString("event_id"));
        }
        JSONObject content;
        if (file != null) {
            var attachment = message.getJSONObject("attachment");
            String uri;
            try (var input = java.nio.file.Files.newInputStream(file.file().toPath())) {
                uri = new JSONObject(new String(read(open("POST", "/_matrix/media/v3/upload?filename="
                    + URLEncoder.encode(file.name(), StandardCharsets.UTF_8), input, file.mime(), 120_000)), StandardCharsets.UTF_8))
                    .getString("content_uri");
            }
            String msgtype = switch (attachment.optString("kind")) {
                case "photo" -> "m.image";
                case "video" -> "m.video";
                case "audio", "voice" -> "m.audio";
                default -> "m.file";
            };
            var info = new JSONObject().put("mimetype", file.mime()).put("size", file.file().length());
            if (attachment.optLong("duration") > 0) info.put("duration", attachment.optLong("duration"));
            String caption = message.optString("text");
            content = new JSONObject().put("msgtype", msgtype).put("body", caption.isEmpty() ? file.name() : caption)
                .put("filename", file.name()).put("url", uri).put("info", info);
            if ("voice".equals(attachment.optString("kind"))) content.put("org.matrix.msc3245.voice", new JSONObject())
                .put("org.matrix.msc1767.audio", new JSONObject().put("duration", attachment.optLong("duration")));
        } else if (relation != null && "reply".equals(relation.optString("kind")) && !event.isEmpty()) {
            content = new JSONObject().put("msgtype", "m.text").put("body", message.optString("text", message.getString("body")))
                .put("m.relates_to", new JSONObject().put("m.in_reply_to", new JSONObject().put("event_id", event)));
        } else {
            content = new JSONObject().put("msgtype", "m.text").put("body", message.getString("body"));
        }
        return new JSONObject().put("event_id", json("PUT", "rooms/" + encodedRoom + "/send/m.room.message/" + transaction,
            content, 30_000).getString("event_id"));
    }

    private boolean isEncrypted(String room) throws Exception {
        Boolean known = encrypted.get(room);
        if (known != null) return known;
        boolean value;
        try {
            json("GET", "rooms/" + URLEncoder.encode(room, StandardCharsets.UTF_8) + "/state/m.room.encryption", null, 20_000);
            value = true;
        } catch (NotFound missing) { value = false; }
        encrypted.put(room, value);
        return value;
    }

    /** Finds the direct room with a Matrix user, or creates one, like "Start chat" in other clients. */
    static String directRoom(Context context, String user) throws Exception {
        var account = DirectProtocols.account(context, MessageProtocol.MATRIX);
        if (account == null) throw new IllegalStateException("Add your Matrix account first.");
        var client = new DirectMatrix("");
        client.load(context, account);
        String me = URLEncoder.encode(account.getString("user_id"), StandardCharsets.UTF_8);
        JSONObject direct;
        try { direct = client.json("GET", "user/" + me + "/account_data/m.direct", null, 20_000); }
        catch (NotFound missing) { direct = new JSONObject(); }
        var rooms = direct.optJSONArray(user);
        if (rooms != null && rooms.length() > 0) return rooms.getString(0);
        String room = client.json("POST", "createRoom", new JSONObject().put("is_direct", true)
            .put("invite", new JSONArray().put(user)).put("preset", "trusted_private_chat"), 30_000).getString("room_id");
        direct.put(user, (rooms == null ? new JSONArray() : rooms).put(room));
        client.json("PUT", "user/" + me + "/account_data/m.direct", direct, 20_000);
        return room;
    }

    private static final class NotFound extends IOException { NotFound() { super("Not found"); } }

    private JSONObject json(String method, String path, JSONObject body, int timeout) throws Exception {
        byte[] data = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        try (var input = open(method, "/_matrix/client/v3/" + path, data == null ? null : new java.io.ByteArrayInputStream(data),
                data == null ? null : "application/json", timeout)) {
            return new JSONObject(new String(read(input), StandardCharsets.UTF_8));
        }
    }

    private static byte[] read(InputStream input) throws IOException {
        byte[] data = input.readNBytes((int) LIMIT + 1);
        if (data.length > LIMIT) throw new IOException("Matrix response too large");
        return data;
    }

    private InputStream open(String method, String path, InputStream body, String type, int timeout) throws Exception {
        var url = new URL(account.getString("homeserver").replaceAll("/+$", "") + path);
        if (!"https".equals(url.getProtocol())) throw new IOException("Matrix needs an https:// homeserver");
        var connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + account.getString("access_token"));
        if (body != null) {
            connection.setRequestProperty("Content-Type", type);
            connection.setChunkedStreamingMode(64 * 1024);
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream()) { body.transferTo(output); }
        }
        int code = connection.getResponseCode();
        if (code == 404) { connection.disconnect(); throw new NotFound(); }
        if (code != 200) {
            String reason = "";
            try (var error = connection.getErrorStream()) {
                if (error != null) reason = new JSONObject(new String(error.readNBytes(16 * 1024), StandardCharsets.UTF_8)).optString("error");
            } catch (Exception ignored) {}
            connection.disconnect();
            throw new IOException(reason.isEmpty() ? "Matrix returned HTTP " + code : reason);
        }
        return connection.getInputStream();
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return hex.toString();
        } catch (Exception error) { return Integer.toHexString(value.hashCode()); }
    }
}
