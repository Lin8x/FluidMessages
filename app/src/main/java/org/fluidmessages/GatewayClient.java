package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

final class GatewayClient {
    static final String ROUTES = "gatewayRoutes";
    private static long nextCheck;
    private static final int LIMIT = 7 * 1024 * 1024;

    private static JSONArray routes(SharedPreferences prefs) {
        try { return new JSONArray(prefs.getString(ROUTES, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    static boolean hasRoutes(SharedPreferences prefs) { return routes(prefs).length() > 0; }

    static List<MessageProtocol> formats(SharedPreferences prefs, String identity) {
        var formats = new ArrayList<MessageProtocol>();
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && identity.equals(route.optString("address"))) {
                formats.add(MessageProtocol.parse(route.optString("protocol")));
            }
        }
        return formats;
    }

    static String title(SharedPreferences prefs, String address) {
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route != null && address.equals(route.optString("address"))) return route.optString("title");
        }
        return "";
    }

    static List<GroupMember> members(SharedPreferences prefs, String address) {
        var items = routes(prefs);
        var members = new LinkedHashMap<String, GroupMember>();
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            if (route == null || !address.equals(route.optString("address"))) continue;
            var people = route.optJSONArray("members");
            if (people == null) continue;
            for (int index = 0; index < people.length(); index++) {
                String person = people.optString(index);
                if (!person.isBlank()) members.putIfAbsent(person, new GroupMember(person));
            }
        }
        return List.copyOf(members.values());
    }

    static String identity(SharedPreferences prefs, String address, ContactDirectory contacts) {
        if (!prefs.getBoolean("combineProtocols", false) || !SmsStore.validRecipient(address)) return address;
        var items = routes(prefs);
        for (int i = 0; i < items.length(); i++) {
            var route = items.optJSONObject(i);
            String other = route == null ? "" : route.optString("address");
            if (SmsStore.validRecipient(other) && contacts.key(address).equals(contacts.key(other))) return other;
        }
        return address;
    }

    static String unavailable(SharedPreferences prefs, String number, MessageProtocol format) {
        if (format == MessageProtocol.RCS) return "Carrier RCS is a preview on this Android build.";
        if (format == MessageProtocol.SMS || format == MessageProtocol.MMS) return "Set FluidMessages as your default SMS app.";
        if (!formats(prefs, number).contains(format) ||
                !CloudBackup.paired(prefs.getString("server", ""), prefs.getString("token", ""))) {
            return "Connect " + format.label + " for this contact on the web companion.";
        }
        return "";
    }

    static synchronized boolean refresh(Context context, SharedPreferences prefs) throws Exception {
        if (!CloudBackup.paired(prefs.getString("server", ""), prefs.getString("token", ""))
                || !prefs.getBoolean("syncInternetProtocols", false) || SystemClock.elapsedRealtime() < nextCheck) return false;
        nextCheck = SystemClock.elapsedRealtime() + 60_000;
        var response = request(prefs, "protocols", new JSONObject().put("enabled", enabled(prefs)));
        JSONArray messages = response.optJSONArray("messages");
        if (messages != null) ProtocolStore.get(context).putBatch(messages);
        var endpoints = response.optJSONArray("routes");
        String value = endpoints == null ? "[]" : endpoints.toString();
        boolean changed = !value.equals(prefs.getString(ROUTES, "[]"));
        prefs.edit().putString(ROUTES, value).apply();
        return changed || messages != null && messages.length() > 0;
    }

    static void send(Context context, String address, MessageProtocol format, String body) throws Exception {
        format.requireEnabled(context);
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        var result = request(prefs, "protocols/send", new JSONObject().put("address", address)
            .put("protocol", format.name().toLowerCase(Locale.ROOT)).put("body", body)
            .put("request", UUID.randomUUID().toString()));
        var message = result.optJSONObject("message");
        if (message != null) ProtocolStore.get(context).put(message);
    }

    static JSONArray enabled(SharedPreferences prefs) {
        var result = new JSONArray();
        for (MessageProtocol format : MessageProtocol.values()) {
            if (format.enabled(prefs)) result.put(format.name().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static JSONObject request(SharedPreferences prefs, String endpoint, JSONObject payload) throws Exception {
        String origin = prefs.getString("server", "");
        String token = prefs.getString("token", "");
        if (!CloudBackup.paired(origin, token)) throw new IOException("Pair the HTTPS companion first.");
        var connection = (HttpsURLConnection) new URL(origin + "/api/" + endpoint).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream()) { output.write(data); }
            int code = connection.getResponseCode();
            try (var input = code == 200 ? connection.getInputStream() : connection.getErrorStream()) {
                if (input == null) throw new IOException("Update and reconnect your web companion.");
                byte[] bytes = input.readNBytes(LIMIT + 1);
                if (bytes.length > LIMIT) throw new IOException("The protocol snapshot is too large.");
                var reply = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (code != 200) throw new IOException(reply.optString("error", "Could not connect the message protocol."));
                return reply;
            }
        } finally { connection.disconnect(); }
    }
}
