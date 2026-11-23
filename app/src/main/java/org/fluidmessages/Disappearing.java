package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Telephony;
import java.util.List;
import org.json.JSONObject;

/**
 * Disappearing messages. A chat's timer (or the default for every chat) removes each message that long after
 * it was sent or received, counting only messages from after the timer was turned on, on this phone and on the companion. Where the protocol allows it, your own messages
 * are also taken back for everyone: Matrix redacts them and XMPP retracts them. Encrypted texts carry the timer,
 * so a FluidMessages phone on the other end removes them too. Other apps may keep their own copies.
 */
final class Disappearing {
    /** Never, 30 seconds, 5 minutes, 1 hour, 8 hours, 1 day, 1 week, 4 weeks. */
    static final int[] CHOICES = {0, 30, 300, 3600, 8 * 3600, 86400, 7 * 86400, 28 * 86400};

    private Disappearing() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    static int globalSeconds(SharedPreferences prefs) { return prefs.getInt("disappearAll", 0); }

    /** This chat's timer in seconds, or the default when it has none. 0 means messages stay. */
    static int seconds(SharedPreferences prefs, String address) {
        int own = prefs.getInt("disappear." + address, -1);
        return own >= 0 ? own : globalSeconds(prefs);
    }

    /** Sets this chat's timer; -1 follows the default. Earlier messages stay. */
    static void set(SharedPreferences prefs, String address, int seconds) {
        if (seconds < 0) prefs.edit().remove("disappear." + address).remove("disappearSince." + address).apply();
        else prefs.edit().putInt("disappear." + address, seconds).putLong("disappearSince." + address, System.currentTimeMillis()).apply();
    }

    static void setGlobal(SharedPreferences prefs, int seconds) {
        prefs.edit().putInt("disappearAll", seconds).putLong("disappearAllSince", System.currentTimeMillis()).apply();
    }

    /** When the timer that applies to this chat was turned on. Older messages never disappear. */
    static long since(SharedPreferences prefs, String address) {
        return prefs.getInt("disappear." + address, -1) >= 0 ? prefs.getLong("disappearSince." + address, Long.MAX_VALUE)
            : prefs.getLong("disappearAllSince", Long.MAX_VALUE);
    }

    static String label(int seconds) {
        if (seconds <= 0) return "Off";
        if (seconds % (7 * 86400) == 0) return plural(seconds / (7 * 86400), "week");
        if (seconds % 86400 == 0) return plural(seconds / 86400, "day");
        if (seconds % 3600 == 0) return plural(seconds / 3600, "hour");
        if (seconds % 60 == 0) return plural(seconds / 60, "minute");
        return plural(seconds, "second");
    }

    private static String plural(int count, String unit) { return count + " " + unit + (count == 1 ? "" : "s"); }

    /** When a message goes, or 0. A timer carried by the message itself wins over this chat's timer. */
    static long expiry(SharedPreferences prefs, JSONObject row, String address) {
        long carried = row.optLong("expires");
        if (carried > 0) return carried;
        int seconds = seconds(prefs, address);
        long date = row.optLong("date");
        return seconds > 0 && date >= since(prefs, address) ? date + seconds * 1000L : 0;
    }

    static boolean expired(SharedPreferences prefs, JSONObject row, String address, long now) {
        long at = expiry(prefs, row, address);
        return at > 0 && at <= now;
    }

    static boolean anyTimers(SharedPreferences prefs) {
        if (globalSeconds(prefs) > 0) return true;
        for (var entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("disappear.") && entry.getValue() instanceof Integer value && value > 0) return true;
        }
        return false;
    }

    /**
     * Deletes every message whose time is up, and takes back your own on protocols that support it. Runs on
     * background threads; texts still sending are left until they finish.
     */
    static synchronized int sweep(Context context) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        int removed = 0;
        try {
            var store = ProtocolStore.get(context);
            for (JSONObject row : store.read()) {
                if (!expired(prefs, row, row.optString("address"), now)) continue;
                if (row.optInt("type") == 2 && (!row.optString("event_id").isEmpty() || !row.optString("xmpp_id").isEmpty())) {
                    try { DirectProtocols.retract(context, row); } catch (Exception ignored) { /* still remove it here */ }
                }
                store.remove(row.getString("id"));
                removed++;
            }
            for (JSONObject row : store.readHidden()) {
                if (expired(prefs, row, row.optString("address"), now)) { store.removeHidden(row.getString("id")); removed++; }
            }
            if (context.getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_SMS)) {
                var texts = SmsStore.read(context, 0);
                for (int i = 0; i < texts.length(); i++) {
                    var text = texts.getJSONObject(i);
                    int type = text.optInt("type");
                    if (type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED) continue;
                    SmsCrypto.reveal(prefs, text);
                    if (!expired(prefs, text, text.optString("address"), now)) continue;
                    SmsStore.delete(context, text.getLong("id"), text.optString("address"));
                    removed++;
                }
            }
            if (removed > 0) MmsTransport.changed(context);
        } catch (Exception ignored) {}
        return removed;
    }

    /** Timers for the companion, so it removes its own copies at the same time. */
    static JSONObject timers(SharedPreferences prefs) throws Exception {
        var result = new JSONObject().put("default", globalSeconds(prefs))
            .put("default_since", prefs.getLong("disappearAllSince", 0));
        var chats = new JSONObject();
        var since = new JSONObject();
        for (var entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("disappear.") && entry.getValue() instanceof Integer value) {
                String address = entry.getKey().substring("disappear.".length());
                chats.put(address, value);
                since.put(address, prefs.getLong("disappearSince." + address, 0));
            }
        }
        return result.put("chats", chats).put("since", since);
    }

    static List<String> choiceLabels() {
        var labels = new java.util.ArrayList<String>();
        for (int seconds : CHOICES) labels.add(label(seconds));
        return labels;
    }
}
