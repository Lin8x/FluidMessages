package org.fluidmessages;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;

/** Putting settings back to their defaults, and erasing everything FluidMessages stores. */
final class Resets {
    /**
     * Kept by a settings reset: messages and accounts, the companion pairing, hidden chats, codes and keys,
     * and your pinned, archived, blocked, and muted chats. Everything else returns to its default.
     */
    private static final List<String> KEEP = List.of("atRestKey", "token", "server", "onboarded", "direct.", "directRoutes",
        "gatewayRoutes", "gatewayAccounts", "gatewayChannels", "protocolRoute.", "vault.", "hidden.", "code.", "smsKey.",
        "decoys", "wallet.", "mmsOwnNumber", "asked", "lock", "pinned", "archived", "blocked", "muted", "sim");

    private Resets() {}

    static boolean kept(String key) {
        for (String prefix : KEEP) if (key.startsWith(prefix)) return true;
        return false;
    }

    static void resetSettings(Context context, SharedPreferences prefs) {
        var editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (!kept(key)) editor.remove(key);
        // Follow the phone's light or dark mode again, the way a fresh install starts.
        int night = context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        editor.putBoolean("dark", night == android.content.res.Configuration.UI_MODE_NIGHT_YES).commit();
        SyncJob.reschedule(context);
    }

    /**
     * Deletes everything FluidMessages keeps on this phone: accounts, internet and picture messages, hidden chats,
     * keys, codes, and settings. Android closes the app when it's done. Texts in Android's shared SMS store stay.
     */
    static void eraseEverything(Context context) {
        context.getSystemService(ActivityManager.class).clearApplicationUserData();
    }
}
