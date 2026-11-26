package org.fluidmessages;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Keeps phone-direct connections open while FluidMessages is closed, for protocols set to "Stay connected".
 * Android requires an ongoing notification for this; it names the protocols and opens their settings.
 */
public final class ProtocolService extends Service {
    private static final String CHANNEL = "protocol_connections";

    private static volatile boolean running;

    /** Whether the "Staying connected" service is running now (Settings → Running now). */
    static boolean running() { return running; }

    static void update(Context context, boolean needed) {
        if (needed == running) return;
        var intent = new Intent(context, ProtocolService.class);
        if (needed) {
            try { context.startForegroundService(intent); } catch (RuntimeException ignored) {}
        } else {
            context.stopService(intent);
        }
    }

    @Override public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        var manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Connections from this phone",
            NotificationManager.IMPORTANCE_MIN));
        var prefs = DirectProtocols.prefs(this);
        var names = new StringBuilder();
        for (MessageProtocol protocol : MessageProtocol.values()) {
            if (DirectProtocols.on(prefs, protocol) && DirectProtocols.stayConnected(prefs, protocol)) {
                if (names.length() > 0) names.append(", ");
                names.append(protocol.label);
            }
        }
        if (SipAgent.alwaysOn(prefs)) names.append(names.length() > 0 ? ", " : "").append("SIP calls");
        var open = PendingIntent.getActivity(this, 7, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Staying connected")
            .setContentText(names.length() == 0 ? "Chat services" : names + " · ready on this phone")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC).build();
        startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        // After Android restarts the service, reconnect the clients. update() sees the service running and doesn't loop.
        DirectProtocols.restart(this);
        SipAgent.sync(this);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
