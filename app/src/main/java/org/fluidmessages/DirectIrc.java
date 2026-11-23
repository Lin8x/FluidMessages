package org.fluidmessages;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * IRC from the phone: the same behavior as the companion's client (TLS only, SASL PLAIN, IRCv3 message
 * tags for replies and reactions, channel topics and members). It needs an open connection, so it runs while
 * FluidMessages is open, or always with "Stay connected".
 */
final class DirectIrc implements DirectProtocols.DirectClient {
    private static final List<String> WANTED = List.of("sasl", "message-tags", "server-time", "multi-prefix");
    private final String signature;
    private final Set<String> offered = new HashSet<>(), caps = new HashSet<>();
    private final Map<String, Set<String>> members = new HashMap<>(), pending = new HashMap<>();
    private final Map<String, String> topics = new HashMap<>();
    private volatile boolean stopped;
    private volatile boolean ready;
    private SSLSocket socket;
    private OutputStream output;
    private Thread thread;
    private Context context;
    private JSONObject account;
    private String nick;

    DirectIrc(String signature) { this.signature = signature; }

    @Override public String toString() { return signature; }
    @Override public boolean persistent() { return true; }

    @Override public void start(Context context, JSONObject account) {
        this.context = context;
        this.account = account;
        nick = account.optString("nick");
        thread = new Thread(this::run, "fluid-direct-irc");
        thread.start();
    }

    @Override public void stop() {
        stopped = true;
        try { write("QUIT :FluidMessages"); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (thread != null) thread.interrupt();
    }

    private void run() {
        long delay = 5_000;
        while (!stopped) {
            try {
                session();
                delay = 5_000;
            } catch (Exception ignored) {
            } finally {
                ready = false;
                try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            }
            try { Thread.sleep(delay); } catch (InterruptedException interrupted) { return; }
            delay = Math.min(delay * 2, 300_000);
        }
    }

    private void session() throws Exception {
        String server = account.getString("server");
        var plain = new java.net.Socket();
        plain.connect(new InetSocketAddress(server, account.optInt("port", 6697)), 20_000);
        socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, server, account.optInt("port", 6697), true);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(List.of(new SNIHostName(server)));
        socket.setSSLParameters(parameters);
        socket.setSoTimeout(330_000);
        socket.startHandshake();
        output = socket.getOutputStream();
        offered.clear();
        caps.clear();
        write("CAP LS 302");
        write("NICK " + nick);
        write("USER " + account.getString("nick") + " 0 * :" + account.optString("real_name", "FluidMessages"));
        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8), 16384);
        for (String line; !stopped && (line = reader.readLine()) != null; ) {
            if (line.length() > 8192) throw new IllegalStateException("IRC line too long");
            handle(line);
        }
    }

    private synchronized void write(String line) throws Exception {
        if (output == null) throw new IllegalStateException("IRC is reconnecting. Try again in a moment.");
        if (line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid IRC line");
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        output.write(data, 0, Math.min(data.length, 510));
        output.write(new byte[]{'\r', '\n'});
        output.flush();
    }

    private record Line(Map<String, String> tags, String nick, String command, List<String> params) {}

    static Line parse(String raw) {
        var tags = new HashMap<String, String>();
        String rest = raw;
        if (rest.startsWith("@")) {
            int space = rest.indexOf(' ');
            if (space < 0) return null;
            for (String item : rest.substring(1, space).split(";")) {
                int equals = item.indexOf('=');
                String value = equals < 0 ? "" : item.substring(equals + 1).replace("\\:", ";").replace("\\s", " ")
                    .replace("\\\\", "\\").replace("\\r", "\r").replace("\\n", "\n");
                tags.put(equals < 0 ? item : item.substring(0, equals), value);
            }
            rest = rest.substring(space + 1);
        }
        String prefix = "";
        if (rest.startsWith(":")) {
            int space = rest.indexOf(' ');
            if (space < 0) return null;
            prefix = rest.substring(1, space);
            rest = rest.substring(space + 1);
        }
        var params = new ArrayList<String>();
        int space = rest.indexOf(' ');
        String command = (space < 0 ? rest : rest.substring(0, space)).toUpperCase(Locale.ROOT);
        rest = space < 0 ? "" : rest.substring(space + 1);
        while (!rest.isEmpty()) {
            if (rest.startsWith(":")) { params.add(rest.substring(1)); break; }
            space = rest.indexOf(' ');
            params.add(space < 0 ? rest : rest.substring(0, space));
            rest = space < 0 ? "" : rest.substring(space + 1).stripLeading();
        }
        return new Line(tags, prefix.split("!", 2)[0], command, params);
    }

    private void handle(String raw) throws Exception {
        var line = parse(raw);
        if (line == null) return;
        var p = line.params();
        switch (line.command()) {
            case "PING" -> write("PONG :" + (p.isEmpty() ? "" : p.get(0)));
            case "CAP" -> { if (p.size() >= 3) capability(p); }
            case "AUTHENTICATE" -> {
                if (p.equals(List.of("+"))) {
                    String user = account.optString("sasl_user", account.getString("nick"));
                    if (user.isEmpty()) user = account.getString("nick");
                    write("AUTHENTICATE " + Base64.getEncoder().encodeToString((user + "\0" + user + "\0"
                        + account.optString("sasl_password")).getBytes(StandardCharsets.UTF_8)));
                }
            }
            case "903", "904", "905", "906", "907" -> write("CAP END");
            case "001" -> {
                if (!p.isEmpty()) nick = p.get(0);
                for (String channel : DirectProtocols.targets(context, MessageProtocol.IRC, true)) write("JOIN " + channel);
                ready = true;
            }
            case "433" -> { nick = nick.substring(0, Math.min(nick.length(), 28)) + "_"; write("NICK " + nick); }
            case "PRIVMSG", "NOTICE", "TAGMSG" -> { if (!p.isEmpty()) message(line); }
            case "332" -> { if (p.size() >= 3) { topics.put(p.get(1).toLowerCase(Locale.ROOT), p.get(2)); publish(p.get(1)); } }
            case "TOPIC" -> {
                if (p.size() == 2) {
                    topics.put(p.get(0).toLowerCase(Locale.ROOT), p.get(1));
                    publish(p.get(0));
                    event(p.get(0), line.nick() + " changed the topic to “" + p.get(1) + "”", line);
                }
            }
            case "353" -> {
                if (p.size() >= 4) {
                    var set = pending.computeIfAbsent(p.get(2).toLowerCase(Locale.ROOT), key -> new TreeSet<>());
                    for (String name : p.get(3).split(" ")) if (!name.isEmpty()) set.add(name.replaceFirst("^[~&@%+]+", ""));
                }
            }
            case "366" -> {
                if (p.size() >= 2) {
                    String key = p.get(1).toLowerCase(Locale.ROOT);
                    members.put(key, pending.getOrDefault(key, new TreeSet<>()));
                    pending.remove(key);
                    publish(p.get(1));
                }
            }
            case "JOIN" -> {
                if (!p.isEmpty()) {
                    members.computeIfAbsent(p.get(0).toLowerCase(Locale.ROOT), key -> new TreeSet<>()).add(line.nick());
                    publish(p.get(0));
                    if (!line.nick().equals(nick)) event(p.get(0), line.nick() + " joined", line);
                }
            }
            case "PART" -> {
                if (!p.isEmpty()) {
                    members.computeIfAbsent(p.get(0).toLowerCase(Locale.ROOT), key -> new TreeSet<>()).remove(line.nick());
                    publish(p.get(0));
                    event(p.get(0), line.nick() + " left", line);
                }
            }
            case "QUIT" -> {
                for (var entry : members.entrySet()) if (entry.getValue().remove(line.nick())) {
                    publish(entry.getKey());
                    event(entry.getKey(), line.nick() + " quit", line);
                }
            }
            case "NICK" -> {
                if (p.isEmpty()) return;
                for (var entry : members.entrySet()) if (entry.getValue().remove(line.nick())) {
                    entry.getValue().add(p.get(0));
                    publish(entry.getKey());
                    event(entry.getKey(), line.nick() + " is now " + p.get(0), line);
                }
                if (line.nick().equals(nick)) nick = p.get(0);
            }
            default -> {}
        }
    }

    private void capability(List<String> p) throws Exception {
        String action = p.get(1).toUpperCase(Locale.ROOT);
        if (action.equals("LS")) {
            for (String item : p.get(p.size() - 1).split(" ")) if (!item.isEmpty()) offered.add(item.split("=", 2)[0]);
            if (p.size() > 3 && p.get(2).equals("*")) return;
            var wanted = new ArrayList<String>();
            for (String cap : WANTED) {
                if (offered.contains(cap) && (!cap.equals("sasl") || !account.optString("sasl_password").isEmpty())) wanted.add(cap);
            }
            write(wanted.isEmpty() ? "CAP END" : "CAP REQ :" + String.join(" ", wanted));
        } else if (action.equals("ACK")) {
            caps.addAll(Arrays.asList(p.get(p.size() - 1).split(" ")));
            write(caps.contains("sasl") && !account.optString("sasl_password").isEmpty() ? "AUTHENTICATE PLAIN" : "CAP END");
        } else if (action.equals("NAK")) {
            write("CAP END");
        }
    }

    private void publish(String channel) {
        var route = DirectProtocols.routeForTarget(context, MessageProtocol.IRC, channel);
        if (route == null) return;
        try {
            var prefs = DirectProtocols.prefs(context);
            var all = new JSONObject(prefs.getString("gatewayChannels", "{}"));
            String key = channel.toLowerCase(Locale.ROOT);
            all.put(route.getString("address"), new JSONObject().put("name", channel).put("topic", topics.getOrDefault(key, ""))
                .put("members", new JSONArray(members.getOrDefault(key, Set.of()))));
            prefs.edit().putString("gatewayChannels", all.toString()).apply();
        } catch (Exception ignored) {}
    }

    private long date(Map<String, String> tags) {
        try { return tags.containsKey("time") ? Instant.parse(tags.get("time")).toEpochMilli() : System.currentTimeMillis(); }
        catch (Exception error) { return System.currentTimeMillis(); }
    }

    private void event(String channel, String text, Line line) throws Exception {
        var route = DirectProtocols.routeForTarget(context, MessageProtocol.IRC, channel);
        if (route == null) return;
        var record = DirectProtocols.record(route, "irc-" + hash(channel + text + date(line.tags())), text, false, date(line.tags()), "");
        DirectProtocols.incoming(context, record.put("event", true).put("read", 1));
    }

    private void message(Line line) throws Exception {
        var p = line.params();
        String target = p.get(0), text = p.size() > 1 ? p.get(1) : "";
        if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) text = "/me " + text.substring(8, text.length() - 1);
        else if (text.startsWith("\u0001")) return;
        if (line.nick().equals(nick)) return;
        String conversation = target.startsWith("#") || target.startsWith("&") ? target : line.nick();
        var route = DirectProtocols.routeForTarget(context, MessageProtocol.IRC, conversation);
        if (route == null) return; // Direct messages from nicks you haven't linked are ignored.
        String msgid = line.tags().getOrDefault("msgid", "");
        String id = "irc-" + hash(msgid.isEmpty() ? conversation + line.nick() + date(line.tags()) + text : msgid);
        String address = route.getString("address");
        var target_ = DirectProtocols.findBy(context, address, "irc_msgid", line.tags().get("+draft/reply"));
        String react = line.tags().get("+draft/react");
        JSONObject record;
        if (react != null) {
            if (target_ == null) return;
            record = DirectProtocols.record(route, id, MessageInteractionsKt.emojiReactionSms(react, target_.optString("body"), false),
                false, date(line.tags()), line.nick()).put("reaction_target", target_.getString("id"));
        } else {
            String body = target_ == null ? text : "Reply to “" + MessageInteractionsKt.messageExcerpt(target_.optString("body"), 240) + "”\n\n" + text;
            record = DirectProtocols.record(route, id, body, false, date(line.tags()), line.nick());
            if (target_ != null) record.put("reply_target", target_.getString("id"));
        }
        DirectProtocols.incoming(context, record.put("irc_msgid", msgid));
    }

    @Override public JSONObject send(Context context, JSONObject route, JSONObject message, MediaSanitizer.Prepared file) throws Exception {
        if (file != null) throw new IllegalStateException("IRC has no file transfer. Send the file with another protocol.");
        long deadline = System.currentTimeMillis() + 15_000;
        while (!ready && System.currentTimeMillis() < deadline) Thread.sleep(200);
        if (!ready) throw new IllegalStateException("IRC isn’t connected yet");
        String target = route.getString("target");
        var relation = message.optJSONObject("relation");
        String reply = relation == null ? "" : relation.optString("irc_msgid");
        boolean tagged = !reply.isEmpty() && caps.contains("message-tags");
        String prefix = tagged ? "@+draft/reply=" + reply.replace("\\", "\\\\").replace(";", "\\:").replace(" ", "\\s") + " " : "";
        if (relation != null && "reaction".equals(relation.optString("kind")) && tagged) {
            write(prefix.trim() + ";+draft/react=" + relation.optString("emoji") + " TAGMSG " + target);
            return new JSONObject();
        }
        String text = tagged && "reply".equals(relation.optString("kind")) ? message.optString("text") : message.getString("body");
        if (text.startsWith("/me ")) {
            write(prefix + "PRIVMSG " + target + " :\u0001ACTION " + text.substring(4) + "\u0001");
            return new JSONObject();
        }
        for (String part : split(text)) {
            write(prefix + "PRIVMSG " + target + " :" + part);
            prefix = "";
        }
        return new JSONObject();
    }

    /** Splits on 400-byte UTF-8 boundaries without breaking characters, one IRC line per text line. */
    static List<String> split(String text) {
        var lines = new ArrayList<String>();
        for (String line : text.replace("\r", "").split("\n")) {
            byte[] data = line.getBytes(StandardCharsets.UTF_8);
            int start = 0;
            while (data.length - start > 400) {
                int cut = start + 400;
                while (cut > start && (data[cut] & 0xC0) == 0x80) cut--;
                int space = cut;
                while (space > start + 200 && data[space] != ' ') space--;
                if (space > start + 200) cut = space;
                lines.add(new String(data, start, cut - start, StandardCharsets.UTF_8));
                start = cut;
                while (start < data.length && data[start] == ' ') start++;
            }
            if (start < data.length) lines.add(new String(data, start, data.length - start, StandardCharsets.UTF_8));
        }
        return lines;
    }

    @Override public void changeMember(Context context, JSONObject route, boolean add, String member) throws Exception {
        String channel = route.getString("target");
        if (!channel.startsWith("#")) throw new IllegalStateException("Only IRC channels have members to change");
        if (!member.matches("[^\\s,:#]{1,64}")) throw new IllegalArgumentException("Enter a nickname");
        // Kicking needs channel operator status; invites may need it on invite-only channels.
        write(add ? "INVITE " + member + " " + channel : "KICK " + channel + " " + member + " :Removed from the channel");
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
