package org.fluidmessages;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Connect through Tor (Settings → Privacy and security), off by default, like Briar's Tor protection.
 *
 * Tor runs inside FluidMessages (BuiltInTor: Guardian Project's tor-android, BSD-3-Clause), on random SOCKS and HTTP
 * tunnel ports on 127.0.0.1. "Use the Orbot app instead" uses Orbot's SOCKS port at 127.0.0.1:9050 and its HTTP tunnel
 * at 127.0.0.1:8118, for people who already run Orbot. Every internet connection FluidMessages opens itself asks here
 * first:
 * - With Tor off, nothing changes.
 * - Each connection can differ from the setting: chat details' "Connect through Tor" adds its servers to an override
 *   set ({@link #HOSTS_KEY}: "+host" through Tor, "-host" not), so one IRC network, Matrix or XMPP account, or mailbox
 *   can use Tor while the rest don't, or the other way around. Everything this phone opens on that server follows it.
 * - With Tor on, host names go to Tor unresolved, so this phone never looks them up itself (no DNS leak).
 * - With Tor on but not running, the connection fails with {@link #NOT_READY} instead of going direct. Connections
 *   that reconnect on their own keep trying, so they connect once Tor is up.
 * - Addresses on your own network (192.168.x.x, .local, Tailscale) connect directly: Tor can't reach them, and they
 *   never leave your network.
 * - Internet calls (WebRTC and SIP) can't go through Tor, so they're off unless you allow them to connect directly.
 *
 * Texts (SMS and MMS) and calls over your phone plan use the carrier, never the internet, so Tor never applies.
 */
final class TorRoute {
    static final String KEY = "tor";
    /** Internet calls allowed to connect directly while Tor is on. Off by default, so nothing goes around Tor. */
    static final String CALLS_KEY = "tor.callsDirect";
    /** "Use the Orbot app instead": off by default, so Tor runs inside FluidMessages. */
    static final String ORBOT_KEY = "tor.orbot";
    /** Per-connection choices from chat details: "+host" goes through Tor, "-host" doesn't, whatever the setting. */
    static final String HOSTS_KEY = "tor.hosts";
    static final String HOST = "127.0.0.1";
    /** Orbot's SOCKS port. Tor inside the app uses a random free port instead ({@link #socksPort()}). */
    static final int SOCKS_PORT = 9050;
    /** Orbot's HTTP tunnel. Email uses an HTTP tunnel, because the mail library would look up host names itself over SOCKS. */
    static final int HTTP_PORT = 8118;
    static final String ORBOT = "org.torproject.android";
    static final String ORBOT_FDROID = "https://f-droid.org/packages/org.torproject.android/";
    static final String ORBOT_GUARDIAN = "https://guardianproject.info/apps/org.torproject.android/";
    static final String ORBOT_START = "org.torproject.android.intent.action.START";
    static final String ORBOT_STATUS = "org.torproject.android.intent.action.STATUS";
    static final String ORBOT_STATUS_EXTRA = "org.torproject.android.intent.extra.STATUS";
    static final String ORBOT_PACKAGE_EXTRA = "org.torproject.android.intent.extra.PACKAGE_NAME";

    static final String WARNING = "Tor hides your internet address from chat servers and from anyone watching your network. "
        + "It makes connections slower and less reliable, and some servers block Tor. Texts (SMS) and calls over your phone "
        + "plan never use Tor.";
    static final String NOT_READY = "Tor isn't ready yet, so FluidMessages didn't connect without it. It connects once Tor "
        + "is running. To connect without Tor, turn off Connect through Tor in Settings → Privacy and security.";
    static final String ORBOT_NOT_READY = "Tor isn't running in Orbot, so FluidMessages didn't connect. Open Orbot and start "
        + "it, or turn off Connect through Tor in Settings → Privacy and security.";
    static final String MAIL_NOT_READY = "Email waits for Tor's HTTP tunnel, which isn't open yet, so it doesn't connect "
        + "without Tor. It connects once Tor is running.";
    static final String ORBOT_MAIL_NOT_READY = "Email goes through Orbot's HTTP port (8118), and it isn't open. Start Orbot, "
        + "or turn off Connect through Tor in Settings → Privacy and security.";
    static final String CALLS_HELD = "Internet calls are off while Connect through Tor is on, because calls can't go through "
        + "Tor. To allow them, see Settings → Privacy and security → Connect through Tor.";
    static final String GIF_SITES_HELD = "GIF sites open in a web page that can't use Tor, so they're off while Connect "
        + "through Tor is on. GIF search still works.";
    static final String FTP_HELD = "FTP can't go through Tor. Use SFTP or WebDAV for this server, or turn off Connect "
        + "through Tor in Settings → Privacy and security.";

    /** What a connection is for. Only calls are treated differently; the rest is for the tests and the settings page. */
    enum Use { CHAT, COMPANION, BACKUP, PREVIEW, UPDATES, CALL }

    /** How a connection goes: directly, through Tor, not now (Tor isn't ready), or not at all (calls held). */
    enum Way { DIRECT, TOR, WAIT, BLOCKED }

    /** What the setting shows. STARTING, WAITING, and CANT_START are Tor inside the app; NO_ORBOT and NOT_RUNNING are Orbot's. */
    enum Status { OFF, NO_ORBOT, NOT_RUNNING, CONNECTING, CONNECTED, STARTING, WAITING, CANT_START }

    /**
     * Tor right now, for the settings page, chat details, and Settings → Running now: the state, its words
     * ("Starting Tor… 45%"), whether Tor is running inside FluidMessages, and whether it uses Orbot.
     */
    static final class Now {
        final Status state;
        final int percent;
        final String words;
        final boolean builtInRunning;
        final boolean orbot;

        Now(Status state, int percent, String words, boolean builtInRunning, boolean orbot) {
            this.state = state;
            this.percent = percent;
            this.words = words;
            this.builtInRunning = builtInRunning;
            this.orbot = orbot;
        }

        boolean ready() { return state == Status.CONNECTED; }
    }

    private TorRoute() {}

    // The decision, kept pure for the tests.

    static Way decide(boolean on, boolean ready, Use use, boolean local, boolean callsDirect) {
        if (!on) return Way.DIRECT;
        if (use == Use.CALL) return callsDirect ? Way.DIRECT : Way.BLOCKED;
        if (local) return Way.DIRECT;
        return ready ? Way.TOR : Way.WAIT;
    }

    static Status status(boolean on, boolean ready, String orbot, boolean installed) {
        if (!on) return Status.OFF;
        if ("STARTING".equals(orbot)) return Status.CONNECTING;
        if (ready) return Status.CONNECTED;
        return installed ? Status.NOT_RUNNING : Status.NO_ORBOT;
    }

    /** Tor inside the app: [on] is whether anything uses Tor (the setting or one connection). */
    static Status builtInStatus(boolean on, BuiltInTor.Phase phase, boolean ready) {
        if (!on) return Status.OFF;
        if (ready) return Status.CONNECTED;
        return switch (phase) {
            case STARTING, ON -> Status.STARTING;
            case FAILED, UNSUPPORTED -> Status.CANT_START;
            case OFF -> Status.WAITING;
        };
    }

    static String words(Status status) {
        return words(status, 0);
    }

    static String words(Status status, int percent) {
        return switch (status) {
            case OFF -> "Off";
            case NO_ORBOT -> "Tor isn't running · Install Orbot first";
            case NOT_RUNNING -> "Tor isn't running · Open Orbot and start it";
            case CONNECTING -> "Connecting to Tor…";
            case CONNECTED -> "Connected through Tor";
            case STARTING -> percent > 0 ? "Starting Tor… " + Math.min(percent, 99) + "%" : "Starting Tor…";
            case WAITING -> "Tor starts when a connection needs it";
            case CANT_START -> "Tor couldn't start · Try again, or use the Orbot app instead";
        };
    }

    /**
     * Whether a connection to [host] goes through Tor: its own choice in [overrides] ("+host" or "-host", from chat
     * details) when it has one, else the setting ([global]). Names are compared without case or a trailing dot.
     */
    static boolean wants(boolean global, Set<String> overrides, String host) {
        String name = hostKey(host);
        if (overrides == null || name.isEmpty()) return global;
        if (overrides.contains("+" + name)) return true;
        if (overrides.contains("-" + name)) return false;
        return global;
    }

    /** Any connection set to use Tor by itself, even with the setting off. */
    static boolean anyThrough(Set<String> overrides) {
        if (overrides != null) for (String entry : overrides) if (entry.startsWith("+")) return true;
        return false;
    }

    static String hostKey(String host) {
        if (host == null) return "";
        String name = host.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("[") && name.endsWith("]")) name = name.substring(1, name.length() - 1);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return name;
    }

    /**
     * The override set after one connection's switch in chat details: [hosts] all follow [through], or the setting
     * again (no entry) when that's what the setting already does.
     */
    static Set<String> withChoice(Set<String> overrides, List<String> hosts, boolean through, boolean global) {
        var next = new HashSet<String>(overrides == null ? Set.of() : overrides);
        for (String host : hosts) {
            String name = hostKey(host);
            if (name.isEmpty()) continue;
            next.remove("+" + name);
            next.remove("-" + name);
            if (through != global) next.add((through ? "+" : "-") + name);
        }
        return next;
    }

    /**
     * A host on your own network or this phone, found from the name alone (nothing is looked up): IP addresses in
     * private, loopback, link-local, and Tailscale ranges, names with no dot, and .local, .lan, .home.arpa,
     * .internal, and .ts.net names. Tor can't reach these, and connecting to them doesn't reveal you to the internet.
     */
    static boolean localHost(String host) {
        if (host == null) return false;
        String name = host.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("[") && name.endsWith("]")) name = name.substring(1, name.length() - 1);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) return false;
        if (name.endsWith(".onion")) return false;
        int[] v4 = ipv4(name);
        if (v4 != null) {
            int a = v4[0], b = v4[1];
            return a == 10 || a == 127 || a == 0 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168
                || a == 169 && b == 254 || a == 100 && b >= 64 && b <= 127;
        }
        if (name.indexOf(':') >= 0) {
            return name.equals("::1") || name.startsWith("fe8") || name.startsWith("fe9") || name.startsWith("fea")
                || name.startsWith("feb") || name.startsWith("fc") || name.startsWith("fd");
        }
        if (name.indexOf('.') < 0) return true;
        for (String suffix : List.of(".local", ".lan", ".home.arpa", ".internal", ".ts.net", ".localhost")) {
            if (name.endsWith(suffix)) return true;
        }
        return false;
    }

    /** The four parts of a dotted IPv4 address, or null when [name] isn't one. */
    static int[] ipv4(String name) {
        String[] parts = name.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return null;
            for (char c : parts[i].toCharArray()) if (c < '0' || c > '9') return null;
            out[i] = Integer.parseInt(parts[i]);
            if (out[i] > 255) return null;
        }
        return out;
    }

    /** The proxy for a decision; WAIT and BLOCKED throw with words instead of ever going direct. */
    static Proxy proxyFor(Way way) throws IOException {
        return proxyFor(way, SOCKS_PORT, NOT_READY);
    }

    static Proxy proxyFor(Way way, int port, String waiting) throws IOException {
        return switch (way) {
            case DIRECT -> Proxy.NO_PROXY;
            case TOR -> new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(HOST, port));
            case WAIT -> throw new IOException(waiting);
            case BLOCKED -> throw new IOException(CALLS_HELD);
        };
    }

    /** Whether something answers like a SOCKS5 proxy at [host]:[port] (a greeting with "no sign-in" accepted). */
    static boolean socksAnswers(String host, int port, int timeout) {
        try (var socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            var input = socket.getInputStream();
            return input.read() == 5 && input.read() == 0;
        } catch (IOException error) {
            return false;
        }
    }

    static boolean portOpen(String host, int port, int timeout) {
        try (var socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    // State

    private static volatile Context app;
    private static volatile long checkedAt;
    private static volatile boolean lastReady;
    /** Orbot's last status broadcast: ON, OFF, STARTING, STOPPING, or "" before it said anything. */
    static volatile String orbot = "";

    /** Called by FluidApp when the app starts, so every connection can check the setting. */
    static void remember(Context context) {
        if (context != null && app == null) app = context.getApplicationContext();
    }

    private static SharedPreferences prefs() throws IOException {
        var context = app;
        if (context == null) throw new IOException("FluidMessages is still starting. Try again in a moment.");
        return context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
    }

    static boolean on(SharedPreferences prefs) { return prefs.getBoolean(KEY, false); }

    /** Whether Tor is on. If the app hasn't started yet this reads as on, so a caller never assumes direct. */
    static boolean on() {
        var context = app;
        return context == null || on(context.getSharedPreferences("fluid", Context.MODE_PRIVATE));
    }

    static void set(SharedPreferences prefs, boolean on) {
        prefs.edit().putBoolean(KEY, on).apply();
        checkedAt = 0;
        if (!on && !anyThrough(overrides(prefs))) BuiltInTor.stop();
    }

    static boolean usesOrbot(SharedPreferences prefs) { return prefs.getBoolean(ORBOT_KEY, false); }

    /** "Use the Orbot app instead": stops Tor inside the app when it's turned on; the next connection starts the other. */
    static void setOrbot(SharedPreferences prefs, boolean orbot) {
        prefs.edit().putBoolean(ORBOT_KEY, orbot).apply();
        checkedAt = 0;
        if (orbot) BuiltInTor.stop();
    }

    static Set<String> overrides(SharedPreferences prefs) {
        return new HashSet<>(prefs.getStringSet(HOSTS_KEY, Set.of()));
    }

    /** Whether a connection to [host] goes through Tor: its own choice from chat details, else the setting. */
    static boolean wanted(SharedPreferences prefs, String host) {
        return wants(on(prefs), overrides(prefs), host);
    }

    static boolean wanted(String host) {
        var context = app;
        return context == null || wanted(context.getSharedPreferences("fluid", Context.MODE_PRIVATE), host);
    }

    /** One connection's switch in chat details, for every server it uses (an email account has two). */
    static void choose(SharedPreferences prefs, List<String> hosts, boolean through) {
        var next = withChoice(overrides(prefs), hosts, through, on(prefs));
        prefs.edit().putStringSet(HOSTS_KEY, next).apply();
        checkedAt = 0;
        if (!on(prefs) && !anyThrough(next)) BuiltInTor.stop();
    }

    /** Whether anything uses Tor inside the app: the setting or one connection, and Orbot isn't chosen instead. */
    static boolean builtInWanted() {
        var context = app;
        if (context == null) return false;
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        return !usesOrbot(prefs) && (on(prefs) || anyThrough(overrides(prefs)));
    }

    /** A "Stay connected" connection on this phone while Tor is in use, which keeps Tor running in the background. */
    static boolean stayConnectedOverTor() {
        var context = app;
        if (context == null) return false;
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        for (MessageProtocol protocol : List.of(MessageProtocol.IRC, MessageProtocol.XMPP, MessageProtocol.MATRIX)) {
            if (DirectProtocols.on(prefs, protocol) && DirectProtocols.stayConnected(prefs, protocol)) return true;
        }
        return false;
    }

    /** The SOCKS port connections use: Orbot's 9050, or the random one of Tor inside the app. */
    static int socksPort() {
        var context = app;
        if (context != null && usesOrbot(context.getSharedPreferences("fluid", Context.MODE_PRIVATE))) return SOCKS_PORT;
        return BuiltInTor.socksPort();
    }

    /** The HTTP tunnel port email uses: Orbot's 8118, or the random one of Tor inside the app (-1 until it's open). */
    static int httpPort() {
        var context = app;
        if (context != null && usesOrbot(context.getSharedPreferences("fluid", Context.MODE_PRIVATE))) return HTTP_PORT;
        return BuiltInTor.httpPort();
    }

    static boolean callsDirect(SharedPreferences prefs) { return prefs.getBoolean(CALLS_KEY, false); }

    /** Matrix, XMPP, and SIP calls while Tor is on and calls aren't allowed to connect directly. */
    static boolean holdsCalls(SharedPreferences prefs, Calling.Method method) {
        return (method == Calling.Method.MATRIX || method == Calling.Method.XMPP || method == Calling.Method.SIP)
            && decide(on(prefs), false, Use.CALL, false, callsDirect(prefs)) == Way.BLOCKED;
    }

    /**
     * Whether Tor answers right now. Tor inside the app: started if needed, and waited for a little while off the main
     * thread. Orbot: its SOCKS port, checked at most every 3 seconds.
     */
    static boolean ready() {
        var context = app;
        if (context != null && !usesOrbot(context.getSharedPreferences("fluid", Context.MODE_PRIVATE))) {
            BuiltInTor.need(context);
            return BuiltInTor.waitReady(30_000);
        }
        return orbotReady();
    }

    private static boolean orbotReady() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 3000) return lastReady;
        lastReady = socksAnswers(HOST, SOCKS_PORT, 1500);
        checkedAt = now;
        return lastReady;
    }

    /** Whether Tor is ready now, without starting or waiting for anything (for marks and status lines). */
    static boolean readyNow() {
        var context = app;
        if (context == null) return false;
        if (!usesOrbot(context.getSharedPreferences("fluid", Context.MODE_PRIVATE))) return BuiltInTor.ready();
        return orbotReady();
    }

    static boolean orbotInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(ORBOT, 0);
            return true;
        } catch (Exception missing) {
            return false;
        }
    }

    /** Asks Orbot to start Tor; Orbot answers with a status broadcast ({@link #ORBOT_STATUS}). */
    static void askOrbot(Context context) {
        try {
            context.sendBroadcast(new Intent(ORBOT_START).setPackage(ORBOT).putExtra(ORBOT_PACKAGE_EXTRA, context.getPackageName()));
        } catch (Exception ignored) {}
    }

    /**
     * Tor right now: its state and words for the setting, chat details, and Settings → Running now. With Orbot it
     * checks Orbot's SOCKS port, so call it off the main thread.
     */
    static Now status(Context context) {
        remember(context);
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        boolean on = on(prefs) || anyThrough(overrides(prefs));
        if (usesOrbot(prefs)) {
            Status state = status(on, on && socksAnswers(HOST, SOCKS_PORT, 1500), orbot, orbotInstalled(context));
            return new Now(state, 0, words(state), false, true);
        }
        Status state = builtInStatus(on, BuiltInTor.phase(), BuiltInTor.ready());
        return new Now(state, BuiltInTor.percent(), words(state, BuiltInTor.percent()), BuiltInTor.running(), false);
    }

    /** Starts Tor inside the app now, as when a connection needs it (the settings page's Try again). */
    static void start(Context context) {
        remember(context);
        if (!usesOrbot(context.getSharedPreferences("fluid", Context.MODE_PRIVATE))) BuiltInTor.need(context);
    }

    // Routing: one call where each connection is opened.

    /**
     * The proxy for a connection to [host] for [use]: NO_PROXY, Tor's SOCKS port, or an IOException with words. The
     * connection's own choice from chat details counts first, then the setting. Calls follow the setting only.
     */
    static Proxy proxy(Use use, String host) throws IOException {
        var prefs = prefs();
        boolean on = use == Use.CALL ? on(prefs) : wanted(prefs, host);
        boolean local = localHost(host);
        boolean ready = on && use != Use.CALL && !local && ready();
        boolean orbotApp = usesOrbot(prefs);
        return proxyFor(decide(on, ready, use, local, callsDirect(prefs)), orbotApp ? SOCKS_PORT : BuiltInTor.socksPort(),
            orbotApp ? ORBOT_NOT_READY : NOT_READY);
    }

    /** How a connection to [host] goes right now, without starting or waiting for Tor: for marks and status lines. */
    static Way wayNow(SharedPreferences prefs, String host) {
        boolean on = wants(on(prefs), overrides(prefs), host);
        boolean local = localHost(host);
        return decide(on, on && !local && readyNow(), Use.CHAT, local, false);
    }

    static Proxy proxy(Context context) throws IOException {
        remember(context);
        return proxy(Use.CHAT, null);
    }

    /** Opens an internet address: a chat service, link preview, picture, GIF, or update check. */
    static URLConnection open(URL url) throws IOException {
        return url.openConnection(proxy(Use.CHAT, url.getHost()));
    }

    /** Opens FluidMessages Web or your own file server: direct on your own network, through Tor on the internet. */
    static URLConnection openOwn(URL url) throws IOException {
        return url.openConnection(proxy(Use.COMPANION, url.getHost()));
    }

    /** A connected plain socket to [host]:[port], through Tor when it's on (the name is sent to Tor unresolved). */
    static Socket connect(String host, int port, int timeout) throws IOException {
        Proxy proxy = proxy(Use.CHAT, host);
        var socket = new Socket(proxy);
        try {
            socket.connect(proxy == Proxy.NO_PROXY ? new InetSocketAddress(host, port) : InetSocketAddress.createUnresolved(host, port), timeout);
        } catch (IOException error) {
            socket.close();
            throw error;
        }
        return socket;
    }

    /**
     * Email: JavaMail goes through Tor's HTTP tunnel (Tor inside the app's, or Orbot's 8118), which takes the host name
     * as it is, so nothing is looked up here. JavaMail's SOCKS mode would look names up itself, so it's never used:
     * without the HTTP tunnel, email waits.
     */
    static void mail(Properties properties, String imapHost, String smtpHost) throws IOException {
        for (String protocol : List.of("imap", "imaps", "smtp", "smtps")) {
            String host = protocol.startsWith("imap") ? imapHost : smtpHost;
            if (proxy(Use.CHAT, host) == Proxy.NO_PROXY) continue;
            int port = httpPort();
            var context = app;
            boolean orbotApp = context != null && usesOrbot(context.getSharedPreferences("fluid", Context.MODE_PRIVATE));
            if (port <= 0 || !portOpen(HOST, port, 1500)) throw new IOException(orbotApp ? ORBOT_MAIL_NOT_READY : MAIL_NOT_READY);
            properties.put("mail." + protocol + ".proxy.host", HOST);
            properties.put("mail." + protocol + ".proxy.port", String.valueOf(port));
        }
    }

    /** XMPP (Smack): SOCKS5 with the host name, and no DNS or SRV lookups on this phone while Tor is on. */
    static void xmpp(org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration.Builder builder, String domain, String host) throws IOException {
        if (proxy(Use.CHAT, host == null || host.isEmpty() ? domain : host) == Proxy.NO_PROXY) return;
        TorDns.install();
        builder.setProxyInfo(org.jivesoftware.smack.proxy.ProxyInfo.forSocks5Proxy(HOST, socksPort(), null, null));
    }

    /** SFTP backups (JSch): SOCKS5 with the host name. FTP can't go through Tor, so it's refused instead. */
    static void sftp(com.jcraft.jsch.Session session, String host) throws IOException {
        if (proxy(Use.BACKUP, host) != Proxy.NO_PROXY) session.setProxy(new com.jcraft.jsch.ProxySOCKS5(HOST, socksPort()));
    }

    static void ftp(String host) throws IOException {
        if (proxy(Use.BACKUP, host) != Proxy.NO_PROXY) throw new IOException(FTP_HELD);
    }

    /**
     * Smack looks up XMPP servers itself. While Tor is on, this answers without asking DNS: no SRV records, and a
     * placeholder address that's never used, because the SOCKS5 proxy connects by name.
     */
    private static final class TorDns extends org.jivesoftware.smack.util.dns.DNSResolver {
        private final org.jivesoftware.smack.util.dns.DNSResolver real;

        private TorDns(org.jivesoftware.smack.util.dns.DNSResolver real) {
            super(false);
            this.real = real;
        }

        static synchronized void install() {
            var current = org.jivesoftware.smack.util.DNSUtil.getDNSResolver();
            if (!(current instanceof TorDns)) org.jivesoftware.smack.util.DNSUtil.setDNSResolver(new TorDns(current));
        }

        private static boolean hidden(org.minidns.dnsname.DnsName name) {
            return wanted(name.toString()) && !localHost(name.toString());
        }

        @Override protected java.util.Collection<org.minidns.record.SRV> lookupSrvRecords0(org.minidns.dnsname.DnsName name,
                List<org.jivesoftware.smack.util.rce.RemoteConnectionEndpointLookupFailure> failures,
                org.jivesoftware.smack.ConnectionConfiguration.DnssecMode mode) {
            if (hidden(name) || real == null) return java.util.Collections.emptyList();
            return real.lookupSrvRecords(name, failures, mode);
        }

        @Override protected List<InetAddress> lookupHostAddress0(org.minidns.dnsname.DnsName name,
                List<org.jivesoftware.smack.util.rce.RemoteConnectionEndpointLookupFailure> failures,
                org.jivesoftware.smack.ConnectionConfiguration.DnssecMode mode) {
            if (hidden(name) || real == null) {
                try {
                    return List.of(InetAddress.getByAddress(name.toString(), new byte[]{0, 0, 0, 0}));
                } catch (java.net.UnknownHostException impossible) {
                    return List.of();
                }
            }
            return real.lookupHostAddress(name, failures, mode);
        }
    }
}
