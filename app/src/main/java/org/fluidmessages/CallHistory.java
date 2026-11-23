package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.CallLog;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Internet calls for the Phone tab's Recents. Android's call history only has phone-network calls, so
 * FluidMessages keeps its own list of Matrix calls, sealed like other stored data and kept on this phone.
 */
final class CallHistory {
    private static final int LIMIT = 300;

    private CallHistory() {}

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    static synchronized List<JSONObject> read(Context context) {
        var result = new ArrayList<JSONObject>();
        var prefs = prefs(context);
        String stored = prefs.getString("internetCalls", "");
        if (stored == null || stored.isEmpty()) return result;
        try {
            var array = new JSONArray(Privacy.open(prefs, stored));
            for (int i = 0; i < array.length(); i++) result.add(array.getJSONObject(i));
        } catch (Exception ignored) {}
        return result;
    }

    /** Adds a call, or updates it when the same call id was already recorded (a ring that was then answered). */
    static synchronized void record(Context context, String callId, String address, String title, boolean video, int type,
                                    long date, long seconds, String protocol) {
        try {
            var calls = read(context);
            calls.removeIf(call -> !callId.isEmpty() && callId.equals(call.optString("call_id")));
            calls.add(0, new JSONObject().put("call_id", callId).put("address", address).put("title", title).put("protocol", protocol)
                .put("video", video).put("type", type).put("date", date).put("seconds", seconds));
            while (calls.size() > LIMIT) calls.remove(calls.size() - 1);
            var prefs = prefs(context);
            prefs.edit().putString("internetCalls", Privacy.seal(prefs, new JSONArray(calls).toString())).apply();
        } catch (Exception ignored) {}
    }

    /** Records how a call on the call screen ended. */
    static void finished(Context context, String address, String title, boolean video, String protocol, JSONObject report) {
        boolean incoming = report.optBoolean("incoming");
        boolean connected = report.optBoolean("connected");
        int type = !incoming ? CallLog.Calls.OUTGOING_TYPE
            : connected ? CallLog.Calls.INCOMING_TYPE : report.optBoolean("declined") ? CallLog.Calls.REJECTED_TYPE : CallLog.Calls.MISSED_TYPE;
        record(context, report.optString("callId"), address, title, video, type,
            report.optLong("started", System.currentTimeMillis()), Math.max(0, report.optLong("seconds")), protocol);
    }
}
