package org.fluidmessages;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * IRC from the phone: the same behavior as the companion's client (TLS only, SASL PLAIN, IRCv3 message tags for
 * replies and reactions, channel topics, members, and their @ and + prefixes). It needs an open connection, so it runs
 * while FluidMessages is open, or always with "Stay connected".
 *
 * For daily use it also opens direct chats for bots (NickServ, ChanServ…) and people who message you, keeps server
 * notices in the network's server chat, shows joins, parts, kicks, modes, and /whois replies as event lines, marks
 * mentions, identifies to NickServ when SASL isn't used, falls back to nick_ when your nick is taken, runs slash
 * commands (ChatCommands), answers CTCP only when allowed, and paces what you send (IrcFlood).
 *
 * Reconnecting on a spotty phone connection is careful, so the network never sees a reconnect flood: it waits for a
 * network (ConnectivityManager callbacks, not blind retries), backs off 15 s, 30 s, 1, 2, 5, then 10 minutes with
 * jitter, and after five automatic reconnects in an hour it stops and shows "Disconnected · Reconnect" until you tap
 * Reconnect. After a reconnect only channels set to Keep joined rejoin at once; the others rejoin when opened.
 */
final class DirectIrc implements DirectProtocols.DirectClient {
    private static final List<String> WANTED = List.of("sasl", "message-tags", "server-time", "multi-prefix");
    private static final String SERVER = "*";
    private static final Set<String> ERRORS = Set.of("401", "402", "403", "404", "405", "406", "411", "412", "421", "432", "433",
        "436", "437", "441", "442", "443", "461", "471", "472", "473", "474", "475", "476", "477", "478", "481", "482", "484",
        "485", "489", "491", "501", "502");
    private static final Set<String> WHOIS = Set.of("301", "311", "312", "313", "317", "318", "319", "330", "671");
    private static final String PREFIXES = "~&@%+";
    private static final Map<Character, Character> PREFIX_MODES = Map.of('q', '~', 'a', '&', 'o', '@', 'h', '%', 'v', '+');
    private final String signature;
    private final Set<String> offered = new HashSet<>(), caps = new HashSet<>();
    private final Map<String, Set<String>> members = new HashMap<>(), pending = new HashMap<>();
    private final Map<String, Map<String, String>> modes = new HashMap<>(), pendingModes = new HashMap<>();
    private final Map<String, String> topics = new HashMap<>();
    private final Set<String> joined = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> whoisFor = new HashMap<>();
    private final IrcFlood flood = new IrcFlood(5, 2000, System::currentTimeMillis);
    private final ReconnectBudget budget = new ReconnectBudget(IrcRules.RECONNECTS_PER_HOUR, 3_600_000L);
    private final ArrayDeque<Long> newChats = new ArrayDeque<>();
    private final SecureRandom random = new SecureRandom();
    private final Object wake = new Object();
    // Browsing (LIST) and joining by name from the Join and Browse pages.
    private final Object listLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger listAsked = new java.util.concurrent.atomic.AtomicInteger();
    private final Set<String> joining = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, String[]> joinErrors = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> forwards = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile IrcChannelList listing;
    private volatile boolean listStreaming;
    private volatile long listAt;
    private volatile String elist = "", listProblem = "";
    private volatile boolean stopped, ready, registered, saslOk, online = true, manual;
    private long ctcpAt;
    private int nickTries;
    private SSLSocket socket;
    private OutputStream output;
    private Thread thread;
    private Context context;
    private JSONObject account;
    private String nick, wanted;

    DirectIrc(String signature) { this.signature = signature; }

    @Override public String toString() { return signature; }
    @Override public boolean persistent() { return true; }

    @Override public void start(Context context, JSONObject account) {
        this.context = context;
        this.account = account;
        wanted = nick = account.optString("nick");
        thread = new Thread(this::run, "fluid-direct-irc");
        thread.start();
    }

    @Override public void stop() {
        stopped = true;
        try { write("QUIT :FluidMessages"); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        synchronized (wake) { wake.notifyAll(); }
        if (thread != null) thread.interrupt();
    }

    @Override public void reconnectNow() {
        synchronized (wake) { manual = true; wake.notifyAll(); }
    }

    // Connecting and reconnecting

    private void run() {
        var connectivity = context.getSystemService(ConnectivityManager.class);
        var callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { synchronized (wake) { online = true; wake.notifyAll(); } }
            @Override public void onLost(Network network) { online = false; }
        };
        try {
            online = connectivity == null || connectivity.getActiveNetwork() != null;
            if (connectivity != null) connectivity.registerDefaultNetworkCallback(callback);
        } catch (Exception error) { online = true; }
        try {
            int attempt = 0;
            while (!stopped) {
                waitForNetwork();
                if (stopped) break;
                status("connecting", 0, "");
                String error = "";
                try {
                    session();
                } catch (Exception problem) {
                    error = problem.getMessage() == null ? "" : problem.getMessage();
                } finally {
                    ready = false;
                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                    output = null;
                }
                if (stopped) break;
                if (registered) attempt = 0; // It was working: start the schedule again.
                long delay = IrcRules.backoffMillis(attempt++, random.nextDouble());
                status("reconnecting", System.currentTimeMillis() + delay, error);
                if (pause(delay)) { budget.reset(); attempt = 0; continue; } // Reconnect by hand.
                if (!budget.tryUse(System.currentTimeMillis())) {
                    // Five automatic reconnects this hour: stop until you tap Reconnect.
                    status("disconnected", 0, "Reconnected " + IrcRules.RECONNECTS_PER_HOUR + " times in the last hour");
                    waitForManual();
                    budget.reset();
                    attempt = 0;
                }
            }
        } finally {
            try { if (connectivity != null) connectivity.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        }
    }

    private void waitForNetwork() {
        synchronized (wake) {
            if (!online && !stopped) status("offline", 0, "Waiting for a network");
            while (!online && !stopped && !manual) {
                try { wake.wait(); } catch (InterruptedException interrupted) { return; }
            }
            manual = false;
        }
    }

    /** Waits [millis] or until Reconnect; true when it was Reconnect. */
    private boolean pause(long millis) {
        synchronized (wake) {
            manual = false;
            long end = System.currentTimeMillis() + millis;
            for (long left = millis; left > 0 && !stopped && !manual; left = end - System.currentTimeMillis()) {
                try { wake.wait(left); } catch (InterruptedException interrupted) { return false; }
            }
            boolean pressed = manual;
            manual = false;
            return pressed;
        }
    }

    private void waitForManual() {
        synchronized (wake) {
            while (!manual && !stopped) {
                try { wake.wait(); } catch (InterruptedException interrupted) { return; }
            }
            manual = false;
        }
    }

    private void status(String state, long retryAt, String error) {
        try {
            DirectProtocols.saveStatus(context, MessageProtocol.IRC, new JSONObject().put("state", state)
                .put("network", account.optString("server")).put("retry_at", retryAt).put("error", error));
        } catch (Exception ignored) {}
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
        joined.clear();
        listStreaming = false;
        elist = "";
        // Each connection asks for your own nick again, even if the last one fell back to nick_.
        nick = wanted;
        nickTries = 0;
        registered = saslOk = false;
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
        if (line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0 || line.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid IRC line");
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        output.write(data, 0, Math.min(data.length, 510));
        output.write(new byte[]{'\r', '\n'});
        output.flush();
    }

    /** A line you sent, paced to stay under the network's flood limits. */
    private void say(String line) throws Exception {
        long pause = flood.wait();
        if (pause > 0) Thread.sleep(pause);
        write(line);
    }

    // Reading

    private record Line(Map<String, String> tags, String nick, String source, String command, List<String> params) {}

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
        return new Line(tags, prefix.split("!", 2)[0], prefix, command, params);
    }

    private static String lower(String value) { return value.toLowerCase(Locale.ROOT); }

    private void handle(String raw) throws Exception {
        var line = parse(raw);
        if (line == null) return;
        var p = line.params();
        String command = line.command();
        switch (command) {
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
            case "903", "904", "905", "906", "907" -> { saslOk |= command.equals("903"); write("CAP END"); }
            case "001" -> {
                if (!p.isEmpty()) nick = p.get(0);
                registered = true;
                identify();
                // Only channels set to Keep joined rejoin now; the others rejoin when you open them.
                for (String channel : IrcRules.keepJoined(DirectProtocols.targets(context, MessageProtocol.IRC, true), this::keeps)) {
                    write("JOIN " + channel);
                }
                ready = true;
                status("connected", 0, "");
            }
            case "005" -> { String can = IrcChannels.elist(p); if (!can.isEmpty()) elist = can; }
            // The public channel list: rows, then its end. 263 and 416 mean the network won't send (more of) it now.
            case "322" -> { var list = listing; if (list != null) list.add(p); }
            case "323" -> listStreaming = false;
            case "416" -> { listProblem = "too many"; listStreaming = false; }
            case "263" -> { if (listStreaming) { listProblem = "busy"; listStreaming = false; } }
            // A channel that sends newcomers to another one (such as an overflow channel for unregistered nicknames).
            case "470" -> { if (p.size() >= 3 && joining.contains(lower(p.get(1)))) forwards.put(lower(p.get(1)), p.get(2)); }
            case "PRIVMSG", "NOTICE", "TAGMSG" -> { if (!p.isEmpty()) message(line); }
            case "332" -> { if (p.size() >= 3) { topics.put(lower(p.get(1)), p.get(2)); publish(p.get(1)); } }
            case "TOPIC" -> {
                if (p.size() == 2) {
                    topics.put(lower(p.get(0)), p.get(1));
                    publish(p.get(0));
                    event(p.get(0), line.nick() + " changed the topic to “" + p.get(1) + "”", line, "topic");
                }
            }
            case "353" -> {
                if (p.size() >= 4) {
                    String key = lower(p.get(2));
                    var set = pending.computeIfAbsent(key, k -> new TreeSet<>());
                    var marks = pendingModes.computeIfAbsent(key, k -> new HashMap<>());
                    for (String name : p.get(3).split(" ")) {
                        if (name.isEmpty()) continue;
                        String bare = name.replaceFirst("^[~&@%+]+", "");
                        set.add(bare);
                        if (!bare.equals(name)) marks.put(bare, name.substring(0, 1));
                    }
                }
            }
            case "366" -> {
                if (p.size() >= 2) {
                    String key = lower(p.get(1));
                    members.put(key, pending.getOrDefault(key, new TreeSet<>()));
                    modes.put(key, pendingModes.getOrDefault(key, new HashMap<>()));
                    pending.remove(key);
                    pendingModes.remove(key);
                    publish(p.get(1));
                }
            }
            case "JOIN" -> {
                if (p.isEmpty()) return;
                if (line.nick().equals(nick)) joined.add(lower(p.get(0)));
                members.computeIfAbsent(lower(p.get(0)), key -> new TreeSet<>()).add(line.nick());
                publish(p.get(0));
                if (!line.nick().equals(nick)) event(p.get(0), line.nick() + " joined", line, "join");
            }
            case "PART" -> {
                if (p.isEmpty()) return;
                forget(p.get(0), line.nick());
                event(p.get(0), line.nick() + " left" + (p.size() > 1 && !p.get(1).isEmpty() ? " (" + p.get(1) + ")" : ""), line, "part");
            }
            case "KICK" -> {
                if (p.size() < 2) return;
                String reason = p.size() > 2 && !p.get(2).isEmpty() && !p.get(2).equals(line.nick()) ? " (" + p.get(2) + ")" : "";
                forget(p.get(0), p.get(1));
                event(p.get(0), line.nick() + " removed " + (p.get(1).equals(nick) ? "you from " + p.get(0) : p.get(1)) + reason, line, "kick");
            }
            case "QUIT" -> {
                String reason = !p.isEmpty() && !p.get(0).isEmpty() ? " (" + p.get(0) + ")" : "";
                for (var entry : new ArrayList<>(members.entrySet())) if (entry.getValue().contains(line.nick())) {
                    forget(entry.getKey(), line.nick());
                    event(entry.getKey(), line.nick() + " quit" + reason, line, "quit");
                }
            }
            case "NICK" -> {
                if (p.isEmpty()) return;
                for (var entry : members.entrySet()) if (entry.getValue().remove(line.nick())) {
                    entry.getValue().add(p.get(0));
                    var marks = modes.get(entry.getKey());
                    if (marks != null && marks.containsKey(line.nick())) marks.put(p.get(0), marks.remove(line.nick()));
                    publish(entry.getKey());
                    event(entry.getKey(), line.nick() + " is now " + p.get(0), line, "nick");
                }
                if (line.nick().equals(nick)) { nick = p.get(0); wanted = p.get(0); }
            }
            case "MODE" -> { if (p.size() >= 2 && (p.get(0).startsWith("#") || p.get(0).startsWith("&"))) mode(line); }
            case "INVITE" -> { if (p.size() >= 2) invited(p.get(1), line.nick()); }
            case "341" -> { if (p.size() >= 3) event(p.get(2), "Invited " + p.get(1) + " to " + p.get(2), line, "info"); }
            case "305", "306" -> { if (p.size() >= 2) event(SERVER, p.get(p.size() - 1), line, "info"); }
            case "ERROR" -> { if (!p.isEmpty()) event(SERVER, "Disconnected: " + p.get(0), line, "error"); }
            default -> {
                if ((command.equals("433") || command.equals("432") || command.equals("436") || command.equals("437")) && !registered) {
                    // Taken while connecting: nick_, nick__, then nick1…
                    if (++nickTries > 8) throw new IllegalStateException("No free nickname");
                    nick = IrcRules.fallbackNick(wanted, nickTries);
                    write("NICK " + nick);
                } else if (WHOIS.contains(command) && p.size() >= 3) {
                    whois(line);
                } else if (ERRORS.contains(command) && p.size() >= 3 && joining.contains(lower(p.get(1)))) {
                    // The Join page is waiting on this channel: it shows why the network said no.
                    joinErrors.put(lower(p.get(1)), new String[]{command, p.get(p.size() - 1)});
                } else if (ERRORS.contains(command) && p.size() >= 2) {
                    String about = p.size() >= 3 ? p.get(1) : "";
                    String where = whoisFor.getOrDefault(lower(about), about.startsWith("#") || about.startsWith("&") ? about : SERVER);
                    event(where, (where.equals(SERVER) && !about.isEmpty() ? about + ": " : "") + p.get(p.size() - 1), line, "error");
                }
            }
        }
    }

    private boolean keeps(String channel) {
        var route = DirectProtocols.routeForTarget(context, MessageProtocol.IRC, channel);
        return route == null || DirectProtocols.keepJoined(DirectProtocols.prefs(context), route.optString("address"));
    }

    private void identify() throws Exception {
        String password = account.optString("nickserv_password");
        if (password.isEmpty() || saslOk) return;
        String user = account.optString("sasl_user");
        write("PRIVMSG NickServ :IDENTIFY " + (user.isEmpty() ? account.getString("nick") : user) + " " + password);
    }

    private void forget(String channel, String who) {
        if (who.equals(nick)) joined.remove(lower(channel));
        members.computeIfAbsent(lower(channel), key -> new TreeSet<>()).remove(who);
        var marks = modes.get(lower(channel));
        if (marks != null) marks.remove(who);
        publish(channel);
    }

    private void mode(Line line) throws Exception {
        var p = line.params();
        String channel = p.get(0), changes = p.get(1);
        var args = new ArrayDeque<>(p.subList(2, p.size()));
        var marks = modes.computeIfAbsent(lower(channel), key -> new HashMap<>());
        boolean adding = true;
        for (char letter : changes.toCharArray()) {
            if (letter == '+' || letter == '-') adding = letter == '+';
            else if (PREFIX_MODES.containsKey(letter) && !args.isEmpty()) {
                String who = args.removeFirst(), prefix = String.valueOf(PREFIX_MODES.get(letter)), current = marks.getOrDefault(who, "");
                if (adding && (current.isEmpty() || PREFIXES.indexOf(prefix) < PREFIXES.indexOf(current))) marks.put(who, prefix);
                else if (!adding && current.equals(prefix)) marks.remove(who);
            } else if ("beIkfljL".indexOf(letter) >= 0 && !args.isEmpty() && (adding || "beIk".indexOf(letter) >= 0)) args.removeFirst();
        }
        publish(channel);
        event(channel, line.nick() + " set mode " + String.join(" ", p.subList(1, p.size())), line, "mode");
    }

    private void whois(Line line) throws Exception {
        var p = line.params();
        String who = p.get(1), command = line.command();
        var rest = p.subList(2, p.size());
        String where = whoisFor.getOrDefault(lower(who), command.equals("301") ? who : SERVER);
        if (command.equals("318")) { whoisFor.remove(lower(who)); return; }
        String last = rest.get(rest.size() - 1);
        String text = switch (command) {
            case "311" -> rest.size() >= 2 ? who + " is " + rest.get(0) + "@" + rest.get(1) + (last.isEmpty() ? "" : " (" + last + ")") : "";
            case "312" -> who + " is on " + rest.get(0);
            case "317" -> rest.get(0).matches("\\d+") ? who + " has been idle " + Long.parseLong(rest.get(0)) / 60 + " min" : "";
            case "319" -> who + " is in " + last;
            case "330" -> who + " is signed in as " + rest.get(0);
            case "301" -> who + " is away: " + last;
            case "671" -> who + " is using a secure connection";
            case "313" -> who + " is a network operator";
            default -> who + " " + last;
        };
        if (!text.isEmpty()) event(where, text, line, command.equals("301") ? "info" : "whois");
    }

    /** An invitation waits on the Invitations page (InvitesScreen) until you accept or decline it. */
    private void invited(String channel, String from) {
        try {
            var prefs = DirectProtocols.prefs(context);
            var invites = new JSONArray(prefs.getString("directInvites", "[]"));
            for (int i = 0; i < invites.length(); i++) if (channel.equalsIgnoreCase(invites.getJSONObject(i).optString("target"))) return;
            if (DirectProtocols.routeForTarget(context, MessageProtocol.IRC, channel) != null) return;
            invites.put(new JSONObject().put("id", "direct-" + hash(channel + from + System.currentTimeMillis())).put("protocol", "irc")
                .put("kind", "channel").put("target", channel).put("from", from).put("name", channel)
                .put("date", System.currentTimeMillis()).put("direct", true).put("account", "irc:" + account.optString("nick")
                    + "@" + lower(account.optString("server"))));
            while (invites.length() > 100) invites.remove(0);
            prefs.edit().putString("directInvites", invites.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void capability(List<String> p) throws Exception {
        String action = p.get(1).toUpperCase(Locale.ROOT);
        if (action.equals("LS")) {
            for (String item : p.get(p.size() - 1).split(" ")) if (!item.isEmpty()) offered.add(item.split("=", 2)[0]);
            if (p.size() > 3 && p.get(2).equals("*")) return;
            var wantedCaps = new ArrayList<String>();
            for (String cap : WANTED) {
                if (offered.contains(cap) && (!cap.equals("sasl") || !account.optString("sasl_password").isEmpty())) wantedCaps.add(cap);
            }
            write(wantedCaps.isEmpty() ? "CAP END" : "CAP REQ :" + String.join(" ", wantedCaps));
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
            String key = lower(channel);
            var present = members.getOrDefault(key, Set.of());
            var marks = new JSONObject();
            for (var entry : modes.getOrDefault(key, Map.of()).entrySet()) if (present.contains(entry.getKey())) marks.put(entry.getKey(), entry.getValue());
            all.put(route.getString("address"), new JSONObject().put("name", channel).put("topic", topics.getOrDefault(key, ""))
                .put("members", new JSONArray(present)).put("modes", marks));
            prefs.edit().putString("gatewayChannels", all.toString()).apply();
        } catch (Exception ignored) {}
    }

    private long date(Map<String, String> tags) {
        try { return tags.containsKey("time") ? Instant.parse(tags.get("time")).toEpochMilli() : System.currentTimeMillis(); }
        catch (Exception error) { return System.currentTimeMillis(); }
    }

    /** The network's server chat: server notices, errors, and replies with no other chat. Made on first use. */
    private JSONObject serverRoute() throws Exception {
        return DirectProtocols.linkQuietly(context, MessageProtocol.IRC, SERVER, account.optString("server", "IRC"), false,
            new JSONObject().put("automated", true).put("server_chat", true));
    }

    private void event(String channel, String text, Line line, String kind) throws Exception {
        var route = DirectProtocols.routeForTarget(context, MessageProtocol.IRC, channel);
        if (route == null) {
            route = serverRoute();
            if (!channel.equals(SERVER)) text = channel + ": " + text;
        }
        var record = DirectProtocols.record(route, "irc-" + hash(channel + text + date(line.tags())), text, false, date(line.tags()), "");
        DirectProtocols.incoming(context, record.put("event", true).put("event_kind", kind).put("read", 1));
    }

    /** At most 20 new direct chats an hour open on their own, so a spam wave can't fill the inbox. */
    private JSONObject newChat(String who, boolean automated) throws Exception {
        long now = System.currentTimeMillis();
        while (!newChats.isEmpty() && now - newChats.peekFirst() > 3_600_000L) newChats.removeFirst();
        if (newChats.size() >= 20) return null;
        newChats.addLast(now);
        return DirectProtocols.linkQuietly(context, MessageProtocol.IRC, who, who, false,
            automated ? new JSONObject().put("automated", true) : null);
    }

    private void ctcp(String who, String request) throws Exception {
        if (!account.optBoolean("ctcp_replies") || who.isEmpty() || who.equals(nick)) return;
        String word = request.split(" ", 2)[0].toUpperCase(Locale.ROOT), value = request.contains(" ") ? request.split(" ", 2)[1] : "";
        long now = System.currentTimeMillis();
        if (now - ctcpAt < 5000 || !List.of("VERSION", "PING", "CLIENTINFO").contains(word)) return;
        ctcpAt = now;
        String reply = switch (word) {
            case "VERSION" -> "FluidMessages";
            case "PING" -> value.length() > 64 ? value.substring(0, 64) : value;
            default -> "ACTION PING VERSION CLIENTINFO";
        };
        if (reply.chars().anyMatch(c -> c < 0x20)) return;
        say("NOTICE " + who + " :\u0001" + word + (reply.isEmpty() ? "" : " " + reply) + "\u0001");
    }

    private void message(Line line) throws Exception {
        var p = line.params();
        String target = p.get(0), text = p.size() > 1 ? p.get(1) : "";
        boolean notice = line.command().equals("NOTICE");
        if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) text = "/me " + text.substring(8, text.length() - 1);
        else if (text.startsWith("\u0001")) {
            if (!notice) ctcp(line.nick(), text.replace("\u0001", ""));
            return; // CTCP replies and DCC are never shown.
        }
        if (line.nick().equals(nick)) return;
        boolean fromServer = line.source().isEmpty() || !line.source().contains("!") && (line.source().contains(".") || !registered);
        boolean channel = target.startsWith("#") || target.startsWith("&");
        String conversation = fromServer || target.startsWith("$") || target.equals("*") ? SERVER : channel ? target : line.nick();
        var route = DirectProtocols.routeForTarget(context, MessageProtocol.IRC, conversation);
        if (route == null) {
            // Someone new (a person or a bot such as NickServ) gets a direct chat; the rest goes to the server chat.
            if (!conversation.equals(SERVER) && !channel) route = newChat(conversation, IrcRules.automated(conversation, notice));
            if (route == null) {
                route = serverRoute();
                if (!conversation.equals(SERVER)) text = conversation + ": " + text;
            }
        }
        String msgid = line.tags().getOrDefault("msgid", "");
        String author = fromServer ? (line.source().isEmpty() ? account.optString("server") : line.source()) : line.nick();
        String id = "irc-" + hash(msgid.isEmpty() ? conversation + author + date(line.tags()) + text : msgid);
        String address = route.getString("address");
        var target_ = DirectProtocols.findBy(context, address, "irc_msgid", line.tags().get("+draft/reply"));
        String react = line.tags().get("+draft/react");
        JSONObject record;
        if (react != null) {
            if (target_ == null) return;
            record = DirectProtocols.record(route, id, MessageInteractionsKt.emojiReactionSms(react, target_.optString("body"), false),
                false, date(line.tags()), author).put("reaction_target", target_.getString("id"));
        } else {
            String body = target_ == null ? text : "Reply to “" + MessageInteractionsKt.messageExcerpt(target_.optString("body"), 240) + "”\n\n" + text;
            record = DirectProtocols.record(route, id, body, false, date(line.tags()), author);
            if (target_ != null) record.put("reply_target", target_.getString("id"));
        }
        if (notice) record.put("notice", true);
        if (channel && IrcRules.mentions(text, nick, wanted, account.optString("nick"))) record.put("mention", true);
        if (route.optBoolean("server_chat")) record.put("read", 1); // Connection notices never leave an unread dot.
        DirectProtocols.incoming(context, record.put("irc_msgid", msgid));
    }

    // Sending and commands

    private void waitReady() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!ready && System.currentTimeMillis() < deadline) Thread.sleep(200);
        if (!ready) throw new IllegalStateException("IRC isn’t connected yet");
    }

    /** Joins a channel that isn't kept joined when you open or write to it. */
    private void ensureJoined(String channel, long waitMillis) throws Exception {
        if (!ready || !(channel.startsWith("#") || channel.startsWith("&")) || joined.contains(lower(channel))) return;
        write("JOIN " + channel);
        long deadline = System.currentTimeMillis() + waitMillis;
        while (!joined.contains(lower(channel)) && System.currentTimeMillis() < deadline) Thread.sleep(50);
    }

    boolean ready() { return ready && !stopped; }

    private String network() {
        String server = account.optString("server");
        String name = ServerDirectory.INSTANCE.ircName(server);
        return name == null ? server : name;
    }

    /**
     * Joins a channel by name, with or without the leading #, and returns its chat. Waits a few seconds for the
     * network's answer, so a refusal (invite-only, full, registered nicknames only…) comes back in plain words instead
     * of an empty chat. A channel that forwards newcomers elsewhere opens the channel it sent you to.
     */
    JSONObject join(Context context, String typed, String key) throws Exception {
        String target = IrcChannels.channelName(typed);
        if (target == null) throw new IllegalArgumentException("“" + typed.trim() + "” isn’t a channel name. Channel names look like #linux.");
        if (!ready) throw new IllegalStateException("You’re not connected to " + network() + " yet. Connect, then try again.");
        var chat = DirectProtocols.routeForTarget(context, MessageProtocol.IRC, target);
        boolean added = chat == null;
        if (added) chat = DirectProtocols.linkQuietly(context, MessageProtocol.IRC, target, target, true, null);
        String name = lower(target);
        if (joined.contains(name)) return chat;
        joinErrors.remove(name);
        forwards.remove(name);
        joining.add(name);
        try {
            say("JOIN " + target + (key == null || key.isEmpty() ? "" : " " + key));
            long deadline = System.currentTimeMillis() + 8000;
            while (ready && System.currentTimeMillis() < deadline) {
                if (joined.contains(name)) return chat;
                String forward = forwards.get(name);
                if (forward != null && joined.contains(lower(forward))) {
                    if (added) DirectProtocols.unlink(context, chat.getString("address"), MessageProtocol.IRC);
                    var moved = DirectProtocols.linkQuietly(context, MessageProtocol.IRC, forward, forward, true, null);
                    publish(forward);
                    return moved;
                }
                String[] refused = joinErrors.get(name);
                if (refused != null) {
                    if (added) DirectProtocols.unlink(context, chat.getString("address"), MessageProtocol.IRC);
                    throw new IllegalStateException(IrcChannels.joinError(refused[0], target, network(), refused[1]));
                }
                Thread.sleep(50);
            }
            return chat; // No answer yet: the chat opens, and fills in when the network lets you in.
        } finally {
            joining.remove(name);
            joinErrors.remove(name);
            forwards.remove(name);
        }
    }

    /**
     * The network's public channels matching [search], busiest first, at most [limit] (IRC LIST). Big networks send
     * thousands of rows: the network filters where it can (ELIST), the rest is filtered and capped as it arrives, and
     * waiting stops after IrcChannels.TIMEOUT_MILLIS with whatever came. Only one LIST runs at a time, never sooner
     * than IrcChannels.GAP_MILLIS after the last, and only the newest search is sent, so typing can't flood the network.
     */
    List<IrcChannelRow> list(String search, int limit) throws Exception {
        if (!ready) throw new IllegalStateException("You’re not connected to " + network() + " yet. Connect, then try again.");
        int mine = listAsked.incrementAndGet();
        synchronized (listLock) {
            long gap = listAt + IrcChannels.GAP_MILLIS - System.currentTimeMillis();
            if (gap > 0) Thread.sleep(gap);
            // The last list may still be streaming in after its page stopped waiting: let it finish first.
            for (long until = System.currentTimeMillis() + 10_000; listStreaming && System.currentTimeMillis() < until; ) Thread.sleep(100);
            if (mine != listAsked.get()) throw new IllegalStateException("A newer search replaced this one.");
            if (listStreaming && System.currentTimeMillis() - listAt < 60_000) {
                throw new IllegalStateException(network() + " is still sending its last channel list. Try again in a moment.");
            }
            var list = new IrcChannelList(search, limit);
            listProblem = "";
            listing = list;
            listStreaming = true;
            listAt = System.currentTimeMillis();
            try {
                say(IrcChannels.command(search, elist));
                long deadline = System.currentTimeMillis() + IrcChannels.TIMEOUT_MILLIS;
                while (listStreaming && ready && System.currentTimeMillis() < deadline) Thread.sleep(50);
            } finally {
                listing = null;
                listAt = System.currentTimeMillis();
            }
            var rows = list.result();
            if (rows.isEmpty() && !ready) throw new IllegalStateException("The connection to " + network() + " dropped. Connect, then try again.");
            if (rows.isEmpty() && listProblem.equals("busy")) throw new IllegalStateException(network() + " is busy and asked us to wait. Try again in a minute.");
            if (rows.isEmpty() && listProblem.equals("too many")) throw new IllegalStateException(network() + " has too many channels to list at once. Type a word to search for.");
            if (rows.isEmpty() && listStreaming) throw new IllegalStateException(network() + " didn’t send its channel list in time. Try again, or join a channel by name.");
            return rows;
        }
    }

    @Override public void opened(JSONObject route) {
        String channel = route.optString("target");
        new Thread(() -> { try { ensureJoined(channel, 0); } catch (Exception ignored) {} }, "fluid-irc-open").start();
    }

    /** Joins channels linked since the connection opened and parts ones that were removed, without reconnecting. */
    void syncChannels() {
        if (!ready) return;
        new Thread(() -> {
            try {
                var wantedChannels = new HashSet<String>();
                for (String channel : DirectProtocols.targets(context, MessageProtocol.IRC, true)) {
                    wantedChannels.add(lower(channel));
                    if (!joined.contains(lower(channel)) && keeps(channel)) write("JOIN " + channel);
                }
                for (String channel : new ArrayList<>(joined)) {
                    if (!wantedChannels.contains(channel)) { write("PART " + channel + " :Left in FluidMessages"); joined.remove(channel); }
                }
            } catch (Exception ignored) {}
        }, "fluid-irc-sync").start();
    }

    @Override public JSONObject send(Context context, JSONObject route, JSONObject message, MediaSanitizer.Prepared file) throws Exception {
        if (file != null) throw new IllegalStateException("IRC has no file transfer. Send the file with another protocol.");
        if (route.optBoolean("server_chat")) throw new IllegalStateException("This is the network’s server chat. Type a command here, such as /join, /msg, or /quote.");
        waitReady();
        String target = route.getString("target");
        ensureJoined(target, 5000);
        var relation = message.optJSONObject("relation");
        String reply = relation == null ? "" : relation.optString("irc_msgid");
        boolean tagged = !reply.isEmpty() && caps.contains("message-tags");
        String prefix = tagged ? "@+draft/reply=" + reply.replace("\\", "\\\\").replace(";", "\\:").replace(" ", "\\s") + " " : "";
        if (relation != null && "reaction".equals(relation.optString("kind")) && tagged) {
            say(prefix.trim() + ";+draft/react=" + relation.optString("emoji") + " TAGMSG " + target);
            return new JSONObject();
        }
        String text = tagged && "reply".equals(relation.optString("kind")) ? message.optString("text") : message.getString("body");
        send(target, text, prefix, "PRIVMSG", message.optBoolean("literal"));
        return new JSONObject();
    }

    private void send(String target, String text, String prefix, String command, boolean plain) throws Exception {
        int limit = IrcRules.limit(command, target, nick);
        if (text.startsWith("/me ") && !plain) {
            for (String part : split(text.substring(4), limit - 9)) {
                say(prefix + command + " " + target + " :\u0001ACTION " + part + "\u0001");
                prefix = "";
            }
            return;
        }
        for (String part : split(text, limit)) {
            say(prefix + command + " " + target + " :" + part);
            prefix = "";
        }
    }

    @Override public JSONObject command(Context context, JSONObject route, ChatCommand command) throws Exception {
        waitReady();
        String name = command.getName(), args = command.getArgs();
        List<String> argv = command.getArgv();
        String here = route.optString("target");
        String channel = here.startsWith("#") || here.startsWith("&") ? here : null;
        var result = new JSONObject().put("notice", "");
        String nickPattern = "[A-Za-z\\[\\]\\\\`_^{|}][A-Za-z0-9\\[\\]\\\\`_^{|}-]{0,31}";
        switch (name) {
            case "msg", "query" -> {
                String who = argv.get(0), text = args.substring(who.length()).trim();
                JSONObject chat = who.startsWith("#") || who.startsWith("&") ? DirectProtocols.routeForTarget(context, MessageProtocol.IRC, who)
                    : who.matches(nickPattern) ? DirectProtocols.linkQuietly(context, MessageProtocol.IRC, who, who, false,
                        IrcRules.isService(who) ? new JSONObject().put("automated", true) : null) : null;
                if (chat == null) throw new IllegalArgumentException(who.startsWith("#") ? "Join " + who + " first" : "“" + who + "” isn’t an IRC nickname");
                if (!text.isEmpty()) DirectProtocols.send(context, chat.getString("address"), MessageProtocol.IRC, text, text, null, null);
                result.put("open", chat.getString("address"));
            }
            case "join" -> {
                // The same join as the Join page, so a refusal or a forward to another channel is explained here too.
                result.put("open", join(context, argv.get(0), argv.size() > 1 ? argv.get(1) : "").getString("address"));
            }
            case "part" -> {
                JSONObject chat = !argv.isEmpty() && (argv.get(0).startsWith("#") || argv.get(0).startsWith("&"))
                    ? DirectProtocols.routeForTarget(context, MessageProtocol.IRC, argv.get(0)) : channel == null ? null : route;
                if (chat == null) throw new IllegalArgumentException("Use /part in a channel");
                String reason = chat == route ? args : args.substring(argv.get(0).length()).trim();
                say("PART " + chat.getString("target") + " :" + (reason.isEmpty() ? "Left in FluidMessages" : reason));
                joined.remove(lower(chat.getString("target")));
                DirectProtocols.unlink(context, chat.getString("address"), MessageProtocol.IRC);
                result.put("notice", "Left " + chat.getString("target"));
            }
            case "nick" -> {
                if (!argv.get(0).matches(nickPattern)) throw new IllegalArgumentException("“" + argv.get(0) + "” isn’t an IRC nickname");
                wanted = argv.get(0);
                say("NICK " + wanted);
                result.put("notice", "Changing your nickname to " + wanted + " on this connection. Change it in the account to keep it.");
            }
            case "topic" -> {
                String target = channel;
                String text = args;
                if (!argv.isEmpty() && (argv.get(0).startsWith("#") || argv.get(0).startsWith("&"))) { target = argv.get(0); text = args.substring(target.length()).trim(); }
                if (target == null) throw new IllegalArgumentException("Use /topic in a channel");
                if (text.isEmpty()) {
                    String current = topics.getOrDefault(lower(target), "");
                    result.put("notice", current.isEmpty() ? target + " has no topic" : "Topic of " + target + ": " + current);
                } else say("TOPIC " + target + " :" + text);
            }
            case "whois" -> { whoisFor.put(lower(argv.get(0)), here); say("WHOIS " + argv.get(0)); result.put("notice", "Looking up " + argv.get(0) + "…"); }
            case "away" -> { say(args.isEmpty() ? "AWAY" : "AWAY :" + args); result.put("notice", args.isEmpty() ? "You’re back" : "Away: " + args); }
            case "invite" -> {
                String target = argv.size() > 1 ? argv.get(1) : channel;
                if (target == null) throw new IllegalArgumentException("Usage: /invite nick #channel");
                say("INVITE " + argv.get(0) + " " + target);
            }
            case "kick" -> {
                if (channel == null) throw new IllegalArgumentException("Use /kick in a channel");
                String reason = args.substring(argv.get(0).length()).trim();
                say("KICK " + channel + " " + argv.get(0) + " :" + (reason.isEmpty() ? "Removed from the channel" : reason));
            }
            case "mode" -> say("MODE " + (argv.get(0).startsWith("+") || argv.get(0).startsWith("-") ? (channel == null ? nick : channel) + " " + args : args));
            case "notice" -> { send(argv.get(0), args.substring(argv.get(0).length()).trim(), "", "NOTICE", true); result.put("notice", "Notice sent to " + argv.get(0)); }
            case "quote" -> { say(args.trim()); result.put("notice", "Sent"); }
            default -> throw new IllegalArgumentException("/" + name + " isn’t available on IRC.");
        }
        return result;
    }

    /** Splits on [limit]-byte UTF-8 boundaries without breaking characters, at word breaks, one IRC line per text line. */
    static List<String> split(String text, int limit) {
        limit = Math.max(32, limit);
        var lines = new ArrayList<String>();
        for (String line : text.replace("\r", "").split("\n")) {
            byte[] data = line.getBytes(StandardCharsets.UTF_8);
            int start = 0;
            while (data.length - start > limit) {
                int cut = start + limit;
                while (cut > start && (data[cut] & 0xC0) == 0x80) cut--;
                int space = cut;
                while (space > start + limit / 2 && data[space] != ' ') space--;
                if (space > start + limit / 2) cut = space;
                lines.add(new String(data, start, cut - start, StandardCharsets.UTF_8));
                start = cut;
                while (start < data.length && data[start] == ' ') start++;
            }
            if (start < data.length) lines.add(new String(data, start, data.length - start, StandardCharsets.UTF_8));
        }
        return lines;
    }

    static List<String> split(String text) { return split(text, IrcRules.MAX_PAYLOAD); }

    @Override public void changeMember(Context context, JSONObject route, boolean add, String member) throws Exception {
        String channel = route.getString("target");
        if (!channel.startsWith("#")) throw new IllegalStateException("Only IRC channels have members to change");
        if (!member.matches("[^\\s,:#]{1,64}")) throw new IllegalArgumentException("Enter a nickname");
        // Kicking needs channel operator status; invites may need it on invite-only channels.
        say(add ? "INVITE " + member + " " + channel : "KICK " + channel + " " + member + " :Removed from the channel");
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
