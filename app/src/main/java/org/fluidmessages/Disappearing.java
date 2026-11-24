package org.fluidmessages;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Telephony;
import java.util.List;
import org.json.JSONObject;

/**
 * Disappearing messages. A chat's timer (or the default for every chat) removes each message that long after
 * it was sent or received, counting only messages from after the timer was turned on, on this phone and on the
 * companion. An alarm wakes FluidMessages when the next message is due, so this works with the app closed and
 * after a restart. Where the protocol allows it, your own messages are also taken back for everyone: Matrix
 * redacts them and XMPP retracts them. Encrypted texts carry the timer, so a FluidMessages phone on the other end
 * removes them too. SMS, MMS, Email, and IRC can't reach the other person's copy, so it stays on their device.
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
        return expiryAt(0, seconds(prefs, address), row.optLong("date"), since(prefs, address));
    }

    /**
     * The rule behind {@link #expiry}: a timer the message carries wins; otherwise the chat's timer applies to
     * messages from after it was turned on. 0 means the message stays.
     */
    static long expiryAt(long carried, int seconds, long date, long since) {
        if (carried > 0) return carried;
        return seconds > 0 && date > 0 && date >= since ? date + seconds * 1000L : 0;
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

    /** The earlier of two due times, where 0 means none. */
    static long earliest(long one, long two) {
        if (one <= 0) return two;
        if (two <= 0) return one;
        return Math.min(one, two);
    }

    /**
     * Deletes every message whose time is up, and takes back your own on protocols that support it: internet and
     * picture messages, hidden chats (even while the private folder is closed), and texts in Android's SMS store.
     * Then sets an alarm for the next message that's due, so this runs again then, even with the app closed.
     * Runs on background threads; texts still sending are checked again shortly.
     */
    static synchronized int sweep(Context context) {
        var prefs = prefs(context);
        long now = System.currentTimeMillis();
        int removed = 0;
        long next = 0;
        var notifications = context.getSystemService(NotificationManager.class);
        var store = ProtocolStore.get(context);
        try {
            for (JSONObject row : store.read()) {
                long at = expiry(prefs, row, row.optString("address"));
                if (at <= 0) continue;
                if (at > now) { next = earliest(next, at); continue; }
                try {
                    if (row.optInt("type") == 2 && (!row.optString("event_id").isEmpty() || !row.optString("xmpp_id").isEmpty())) {
                        try { DirectProtocols.retract(context, row); } catch (Exception ignored) { /* still remove it here */ }
                    }
                    store.remove(row.getString("id"));
                    removed++;
                    if (row.optInt("read", 1) == 0 && notifications != null) notifications.cancel(row.optString("address").hashCode());
                } catch (Exception skipped) { /* one bad row never keeps the rest from going */ }
            }
        } catch (Exception ignored) {}
        try {
            // Hidden chats keep each message's due time beside the sealed copy, so they go while the folder is closed.
            removed += store.removeExpiredHidden(now);
            next = earliest(next, store.nextHiddenExpiry(now));
            for (JSONObject row : store.readHidden()) {
                long at = expiry(prefs, row, row.optString("address"));
                if (at <= 0) continue;
                if (at > now) next = earliest(next, at);
                else { store.removeHidden(row.getString("id")); removed++; }
            }
        } catch (Exception ignored) {}
        try {
            if (context.getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_SMS)) {
                var texts = SmsStore.read(context, 0);
                for (int i = 0; i < texts.length(); i++) {
                    var text = texts.getJSONObject(i);
                    SmsCrypto.reveal(prefs, text);
                    String address = text.optString("address");
                    long at = expiry(prefs, text, address);
                    if (at <= 0) continue;
                    if (at > now) { next = earliest(next, at); continue; }
                    int type = text.optInt("type");
                    if (type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED) {
                        // Still sending: Android reports the result soon, and then it goes.
                        next = earliest(next, now + RETRY_SENDING);
                        continue;
                    }
                    try {
                        SmsStore.delete(context, text.getLong("id"), address);
                        removed++;
                        if (text.optInt("read", 1) == 0 && notifications != null) notifications.cancel(address.hashCode());
                    } catch (Exception skipped) {}
                }
            }
        } catch (Exception ignored) {}
        schedule(context, next);
        if (removed > 0) MmsTransport.changed(context);
        return removed;
    }

    // Waking up when a message is due

    private static final long RETRY_SENDING = 60_000;
    private static final String ALARM_PREFS = "disappear_alarm";
    static final String ALARM = "org.fluidmessages.DISAPPEAR";

    /**
     * Makes sure a sweep runs by {@code at} (0 does nothing): sets the alarm unless one is already due sooner.
     * Cheap enough to call for every new message.
     */
    static void wakeBy(Context context, long at) {
        if (at <= 0) return;
        synchronized (Disappearing.class) {
            long scheduled = context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).getLong("at", 0);
            if (needsAlarm(scheduled, at, System.currentTimeMillis())) setAlarm(context, at);
        }
    }

    /** Whether a message due at {@code at} needs the alarm moved, given the one already set (0 for none). */
    static boolean needsAlarm(long scheduled, long at, long now) {
        if (at <= 0) return false;
        // A time well in the past already fired or was lost, so it no longer covers anything.
        return scheduled <= 0 || scheduled < now - STALE_ALARM || at < scheduled;
    }

    private static final long STALE_ALARM = 60_000;

    /** Puts the alarm at the next due message, or cancels it when nothing is waiting. */
    private static void schedule(Context context, long at) {
        synchronized (Disappearing.class) {
            if (at > 0) { setAlarm(context, at); return; }
            var alarms = context.getSystemService(AlarmManager.class);
            if (alarms != null) alarms.cancel(alarmIntent(context));
            context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit().remove("at").apply();
        }
    }

    private static void setAlarm(Context context, long at) {
        var alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        // Never sooner than a second from now, so a burst of due messages makes one sweep.
        long when = Math.max(at, System.currentTimeMillis() + 1000);
        var intent = alarmIntent(context);
        // Exact when Android allows it; otherwise Android may run it a little late, still while the phone sleeps.
        try {
            if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
        } catch (SecurityException denied) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
        }
        context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit().putLong("at", when).apply();
    }

    private static PendingIntent alarmIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(ALARM).setClass(context, DisappearingAlarm.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Whether a message is waiting to disappear, including ones whose timer came with the message. */
    static boolean alarmSet(Context context) {
        return context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).getLong("at", 0) > 0;
    }

    /** Alarms don't survive a restart or an update: forget the one that was set, so the next sweep sets it again. */
    static void forgetAlarm(Context context) {
        context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit().remove("at").commit();
    }

    /**
     * Called when a message is stored: wakes the sweep when it's due. Encrypted texts can carry the sender's timer,
     * which only shows once they're opened, so those get a sweep straight away to read it.
     */
    static void track(Context context, JSONObject row) {
        try {
            if (row.optString("body").startsWith(SmsCrypto.PREFIX)) wakeBy(context, System.currentTimeMillis() + 1000);
            else wakeBy(context, expiry(prefs(context), row, row.optString("address")));
        } catch (Exception ignored) {}
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
