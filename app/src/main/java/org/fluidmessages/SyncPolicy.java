package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.PowerManager;

/**
 * Settings → Battery and sync (BatteryPage): the choices shared by the check while FluidMessages is open (it syncs
 * with FluidMessages Web), the background check (SyncJob: FluidMessages Web, and Matrix and Email on this phone),
 * and attachment downloads. Connections that stay open (Stay connected, Nearby's Stay discoverable) don't follow
 * them. Defaults: every 20 seconds while open, every 15 minutes in the background. The keys are kept for backups.
 */
final class SyncPolicy {
    static final String FOREGROUND = "syncForegroundSeconds", BACKGROUND = "syncBackgroundMinutes",
        WIFI_ONLY = "syncWifiOnly", BATTERY_SAVER = "syncPauseOnBatterySaver", DOWNLOADS = "attachmentDownloads";
    static final int[] FOREGROUND_CHOICES = {10, 20, 60, 0};
    static final int[] BACKGROUND_CHOICES = {15, 30, 60, 360, 0};

    private SyncPolicy() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    /** Seconds between checks while the app is open; 0 means only when you pull or send. */
    static int foregroundSeconds(SharedPreferences prefs) { return prefs.getInt(FOREGROUND, 20); }

    /** Minutes between background checks; 0 turns background sync off. Android enforces 15 minutes at least. */
    static int backgroundMinutes(SharedPreferences prefs) { return prefs.getInt(BACKGROUND, 15); }

    static boolean wifiOnly(SharedPreferences prefs) { return prefs.getBoolean(WIFI_ONLY, false); }

    static String downloads(SharedPreferences prefs) { return prefs.getString(DOWNLOADS, "wifi"); }

    static boolean unmetered(Context context) {
        var manager = context.getSystemService(ConnectivityManager.class);
        var caps = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    /** Whether automatic (not user-requested) network work may run now. */
    static boolean allowed(Context context) {
        var prefs = prefs(context);
        if (prefs.getBoolean(BATTERY_SAVER, true)) {
            var power = context.getSystemService(PowerManager.class);
            if (power != null && power.isPowerSaveMode()) return false;
        }
        return !wifiOnly(prefs) || unmetered(context);
    }

    /** Whether a received attachment should download without a tap. */
    static boolean autoDownload(Context context) {
        return switch (downloads(prefs(context))) {
            case "always" -> allowed(context);
            case "wifi" -> allowed(context) && unmetered(context);
            default -> false;
        };
    }
    // The choices' words are on Settings → Battery and sync (BatteryPage).
}
