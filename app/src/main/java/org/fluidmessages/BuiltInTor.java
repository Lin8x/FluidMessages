package org.fluidmessages;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.freehaven.tor.control.RawEventListener;
import net.freehaven.tor.control.TorControlConnection;

/**
 * Tor inside FluidMessages: Guardian Project's tor-android (BSD-3-Clause), which runs Tor in this app's own process,
 * with jtorctl (BSD-3-Clause) for its control connection. Connect through Tor uses it unless "Use the Orbot app
 * instead" is on (TorRoute).
 *
 * - It starts the first time a connection needs Tor (TorRoute.proxy), not when the app opens.
 * - Its SOCKS port and HTTP tunnel port are random free ports on 127.0.0.1 only, never on the network.
 * - While it starts, the bootstrap events from Tor's control connection give "Starting Tor… 45%".
 * - It stops when nothing has needed it for {@link #IDLE} and the app has been in the background that long, unless a
 *   "Stay connected" connection is using Tor.
 * - Its state and cache stay in app-private storage (app_TorService and the cache folder). Android backups are off
 *   for this app, and FluidMessages' own backups never include them.
 *
 * tor-android's TorService is built for Java 21, newer than the Java 17 this app builds with, so it's reached by
 * name (TOR_SERVICE) rather than compiled against. Its intents use the words below, the same as Orbot's.
 */
final class BuiltInTor {
    enum Phase { OFF, STARTING, ON, FAILED, UNSUPPORTED }

    /** How long Tor stays up with nothing using it while the app is in the background. */
    static final long IDLE = 10 * 60_000L;
    /** After Tor fails to start, wait this long before trying again, so a broken start doesn't loop. */
    static final long RETRY = 30_000L;
    static final String TORRC = "SOCKSPort 127.0.0.1:auto\nHTTPTunnelPort 127.0.0.1:auto\nClientOnly 1\nAvoidDiskWrites 1\n";
    static final String TOR_SERVICE = "org.torproject.jni.TorService";
    static final String ACTION_STATUS = "org.torproject.android.intent.action.STATUS";
    static final String ACTION_ERROR = "org.torproject.android.intent.action.ERROR";
    static final String EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS";
    /** Set on TorService's own status intents, so Orbot's can be told apart from them. */
    static final String EXTRA_SERVICE_PACKAGE_NAME = "org.torproject.android.intent.extra.SERVICE_PACKAGE_NAME";

    private static volatile Phase phase = Phase.OFF;
    private static volatile int percent;
    private static volatile String problem = "";
    private static volatile int socks = -1;
    private static volatile int http = -1;
    private static volatile long lastNeed;
    private static volatile long failedAt;
    private static volatile Boolean supported;
    /** The running TorService, reached by name (see the class notes). */
    private static volatile Object service;
    private static volatile boolean listening;
    private static volatile ServiceConnection binding;
    private static BroadcastReceiver receiver;
    private static Context app;

    private static final RawEventListener EVENTS = (keyword, data) -> {
        if ("STATUS_CLIENT".equals(keyword)) progress(data);
    };

    private BuiltInTor() {}

    // Pure parts, for the tests.

    private static final Pattern PROGRESS = Pattern.compile("\\bBOOTSTRAP\\b.*?\\bPROGRESS=(\\d{1,3})");

    /** 45 from "NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors SUMMARY=…", or -1 for any other line. */
    static int bootstrapPercent(String data) {
        if (data == null) return -1;
        Matcher found = PROGRESS.matcher(data);
        if (!found.find()) return -1;
        return Math.min(100, Integer.parseInt(found.group(1)));
    }

    /** Whether Tor can stop: nothing wants it, or nothing used it lately while the app was in the background. */
    static boolean canStop(boolean wanted, long idleFor, long backgroundFor, boolean stayConnected) {
        if (!wanted) return true;
        if (stayConnected) return false;
        return idleFor >= IDLE && backgroundFor >= IDLE;
    }

    // State

    static Phase phase() { return phase; }
    static int percent() { return percent; }
    static String problem() { return problem; }
    static int socksPort() { return socks; }
    static int httpPort() { return http; }
    static boolean ready() { return phase == Phase.ON && socks > 0; }
    static boolean running() { return binding != null; }

    /** Whether this phone's processor has a Tor build in the app (arm64-v8a, armeabi-v7a, x86, or x86_64). */
    static boolean supported() {
        if (supported == null) {
            try {
                System.loadLibrary("tor");
                supported = true;
            } catch (Throwable missing) {
                supported = false;
            }
        }
        return supported;
    }

    /** A connection needs Tor: starts it if it isn't running, and keeps it from stopping for a while. */
    static synchronized void need(Context context) {
        lastNeed = SystemClock.elapsedRealtime();
        if (binding != null || context == null) return;
        if (phase == Phase.FAILED && SystemClock.elapsedRealtime() - failedAt < RETRY) return;
        app = context.getApplicationContext();
        if (!supported()) {
            phase = Phase.UNSUPPORTED;
            problem = "Tor isn't built for this phone's processor. Turn on Use the Orbot app instead.";
            return;
        }
        try {
            // TorService reads app_TorService/torrc, which wins over the defaults it writes itself.
            writeTorrc(new File(app.getDir("TorService", Context.MODE_PRIVATE), "torrc"));
        } catch (IOException error) {
            fail("Tor couldn't save its settings on this phone.");
            return;
        }
        phase = Phase.STARTING;
        percent = 0;
        problem = "";
        listening = false;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (ACTION_ERROR.equals(intent.getAction())) {
                    problem = String.valueOf(intent.getStringExtra(Intent.EXTRA_TEXT));
                    return;
                }
                String status = intent.getStringExtra(EXTRA_STATUS);
                if ("ON".equals(status)) {
                    percent = 100;
                    readPorts();
                    if (phase == Phase.STARTING) phase = Phase.ON;
                } else if (("STOPPING".equals(status) || "OFF".equals(status)) && binding != null) {
                    // Tor stopped by itself (it couldn't start): let it go, and try again later.
                    new Thread(() -> stopped("Tor stopped. FluidMessages tries again in a moment."), "fluid-tor-stop").start();
                }
            }
        };
        var filter = new IntentFilter(ACTION_STATUS);
        filter.addAction(ACTION_ERROR);
        LocalBroadcastManager.getInstance(app).registerReceiver(receiver, filter);
        binding = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    service = binder.getClass().getMethod("getService").invoke(binder);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    service = null;
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                service = null;
                socks = http = -1;
            }
        };
        boolean bound;
        try {
            bound = app.bindService(new Intent().setClassName(app, TOR_SERVICE), binding, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            bound = false;
        }
        if (!bound) {
            unregister();
            binding = null;
            fail("Android didn't let FluidMessages start Tor.");
            return;
        }
        var watcher = new Thread(BuiltInTor::watch, "fluid-tor");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** Stops Tor now: Connect through Tor went off, Orbot was chosen instead, or nothing needs it. */
    static synchronized void stop() {
        stopped("");
        phase = Phase.OFF;
        problem = "";
    }

    private static synchronized void stopped(String why) {
        if (binding != null && app != null) {
            try { app.unbindService(binding); } catch (RuntimeException ignored) {}
        }
        binding = null;
        service = null;
        listening = false;
        unregister();
        socks = http = -1;
        percent = 0;
        if (!why.isEmpty()) fail(why);
    }

    private static void fail(String why) {
        phase = Phase.FAILED;
        problem = why;
        failedAt = SystemClock.elapsedRealtime();
    }

    private static void unregister() {
        if (receiver != null && app != null) {
            try { LocalBroadcastManager.getInstance(app).unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        }
        receiver = null;
    }

    /**
     * Waits up to [millis] for Tor to be ready, off the main thread only, so a connection made just as Tor starts
     * goes through once it's up instead of failing at once. Returns whether it's ready.
     */
    static boolean waitReady(long millis) {
        if (ready()) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        long until = SystemClock.elapsedRealtime() + millis;
        while (SystemClock.elapsedRealtime() < until) {
            if (ready()) return true;
            if (phase != Phase.STARTING) return false;
            try { Thread.sleep(250); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return false; }
        }
        return ready();
    }

    /** While Tor runs: follows its bootstrap, reads its ports, and stops it once nothing needs it. */
    private static void watch() {
        long checked = 0;
        while (binding != null) {
            var running = service;
            if (running != null) {
                var control = control(running);
                if (control != null && !listening) {
                    listening = true;
                    control.addRawEventListener(EVENTS);
                    // TorService follows circuits (CIRC); STATUS_CLIENT adds the bootstrap events, keeping its own.
                    try { control.setEvents(java.util.List.of("CIRC", "STATUS_CLIENT")); } catch (IOException | RuntimeException ignored) {}
                }
                // Also asked directly while starting, in case an event was missed before the listener was added.
                if (control != null && phase == Phase.STARTING) {
                    try { progress(control.getInfo("status/bootstrap-phase")); } catch (IOException | RuntimeException ignored) {}
                }
                readPorts();
            }
            long now = SystemClock.elapsedRealtime();
            if (now - checked > 15_000) {
                checked = now;
                if (canStop(TorRoute.builtInWanted(), now - lastNeed, FluidApp.backgroundFor(), TorRoute.stayConnectedOverTor())) {
                    stop();
                    return;
                }
            }
            try { Thread.sleep(phase == Phase.ON ? 3000 : 500); } catch (InterruptedException stop) { return; }
        }
    }

    private static TorControlConnection control(Object running) {
        try {
            return (TorControlConnection) running.getClass().getMethod("getTorControlConnection").invoke(running);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static int port(Object running, String getter) {
        try {
            return (Integer) running.getClass().getMethod(getter).invoke(running);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return -1;
        }
    }

    private static void readPorts() {
        var running = service;
        if (running == null) return;
        int s = port(running, "getSocksPort"), h = port(running, "getHttpTunnelPort");
        if (s > 0) socks = s;
        if (h > 0) http = h;
        if (percent >= 100 && socks > 0 && phase == Phase.STARTING) phase = Phase.ON;
    }

    private static void progress(String data) {
        int value = bootstrapPercent(data);
        if (value < 0) return;
        percent = Math.max(percent, value);
        if (value >= 100) readPorts();
    }

    private static void writeTorrc(File torrc) throws IOException {
        File folder = torrc.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) throw new IOException("No folder for Tor");
        java.nio.file.Files.write(torrc.toPath(), TORRC.getBytes(StandardCharsets.UTF_8));
    }
}
