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
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Connect through Tor (Settings → Privacy and security), off by default, like Briar's Tor protection.
 *
 * Tor runs in Orbot (Guardian Project, BSD-3-Clause), which this phone already has or installs from F-Droid. Orbot
 * offers a SOCKS port at 127.0.0.1:9050 and an HTTP tunnel at 127.0.0.1:8118. Every internet connection FluidMessages
 * opens itself asks here first:
 * - With Tor off, nothing changes.
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
    static final String HOST = "127.0.0.1";
    static final int SOCKS_PORT = 9050;
    /** Orbot's HTTP tunnel. Email uses it, because the mail library would look up host names itself over SOCKS. */
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
    static final String NOT_READY = "Tor isn't running, so FluidMessages didn't connect. Open Orbot and start it, or turn "
        + "off Connect through Tor in Settings → Privacy and security.";
    static final String MAIL_NOT_READY = "Email goes through Orbot's HTTP port (8118), and it isn't open. Start Orbot, or "
        + "turn off Connect through Tor in Settings → Privacy and security.";
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

    /** What the setting shows. */
    enum Status { OFF, NO_ORBOT, NOT_RUNNING, CONNECTING, CONNECTED }

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

    static String words(Status status) {
        return switch (status) {
            case OFF -> "Off";
            case NO_ORBOT -> "Tor isn't running · Install Orbot first";
            case NOT_RUNNING -> "Tor isn't running · Open Orbot and start it";
            case CONNECTING -> "Connecting to Tor…";
            case CONNECTED -> "Connected through Tor";
        };
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
        return switch (way) {
            case DIRECT -> Proxy.NO_PROXY;
            case TOR -> new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(HOST, SOCKS_PORT));
            case WAIT -> throw new IOException(NOT_READY);
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
    }

    static boolean callsDirect(SharedPreferences prefs) { return prefs.getBoolean(CALLS_KEY, false); }

    /** Matrix, XMPP, and SIP calls while Tor is on and calls aren't allowed to connect directly. */
    static boolean holdsCalls(SharedPreferences prefs, Calling.Method method) {
        return (method == Calling.Method.MATRIX || method == Calling.Method.XMPP || method == Calling.Method.SIP)
            && decide(on(prefs), false, Use.CALL, false, callsDirect(prefs)) == Way.BLOCKED;
    }

    /** Whether Tor answers right now, checked at most every 3 seconds. */
    static boolean ready() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 3000) return lastReady;
        lastReady = socksAnswers(HOST, SOCKS_PORT, 1500);
        checkedAt = now;
        return lastReady;
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

    /** The setting's status line. Checks the SOCKS port, so call it off the main thread. */
    static Status status(Context context) {
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        boolean on = on(prefs);
        return status(on, on && socksAnswers(HOST, SOCKS_PORT, 1500), orbot, orbotInstalled(context));
    }

    // Routing: one call where each connection is opened.

    /** The proxy for a connection to [host] for [use]: NO_PROXY, Tor's SOCKS port, or an IOException with words. */
    static Proxy proxy(Use use, String host) throws IOException {
        var prefs = prefs();
        boolean on = on(prefs);
        boolean local = localHost(host);
        boolean ready = on && use != Use.CALL && !local && ready();
        return proxyFor(decide(on, ready, use, local, callsDirect(prefs)));
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

    /** Email: JavaMail goes through Orbot's HTTP tunnel, which takes the host name as it is (no lookup here). */
    static void mail(Properties properties, String imapHost, String smtpHost) throws IOException {
        for (String protocol : List.of("imap", "imaps", "smtp", "smtps")) {
            String host = protocol.startsWith("imap") ? imapHost : smtpHost;
            if (proxy(Use.CHAT, host) == Proxy.NO_PROXY) continue;
            if (!portOpen(HOST, HTTP_PORT, 1500)) throw new IOException(MAIL_NOT_READY);
            properties.put("mail." + protocol + ".proxy.host", HOST);
            properties.put("mail." + protocol + ".proxy.port", String.valueOf(HTTP_PORT));
        }
    }

    /** XMPP (Smack): SOCKS5 with the host name, and no DNS or SRV lookups on this phone while Tor is on. */
    static void xmpp(org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration.Builder builder, String domain, String host) throws IOException {
        if (proxy(Use.CHAT, host == null || host.isEmpty() ? domain : host) == Proxy.NO_PROXY) return;
        TorDns.install();
        builder.setProxyInfo(org.jivesoftware.smack.proxy.ProxyInfo.forSocks5Proxy(HOST, SOCKS_PORT, null, null));
    }

    /** SFTP backups (JSch): SOCKS5 with the host name. FTP can't go through Tor, so it's refused instead. */
    static void sftp(com.jcraft.jsch.Session session, String host) throws IOException {
        if (proxy(Use.BACKUP, host) != Proxy.NO_PROXY) session.setProxy(new com.jcraft.jsch.ProxySOCKS5(HOST, SOCKS_PORT));
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
            return on() && !localHost(name.toString());
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
