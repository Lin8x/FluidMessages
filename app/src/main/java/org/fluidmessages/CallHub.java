package org.fluidmessages;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Routes Matrix call signaling between the call screen and whichever connection links the chat: the phone's own
 * Matrix session or the companion. An invite that arrives with no call open rings with a full-screen alert.
 */
final class CallHub {
    static final List<String> TYPES = List.of("m.call.invite", "m.call.candidates", "m.call.answer", "m.call.hangup",
        "m.call.select_answer", "m.call.reject", "m.call.negotiate");
    private static final int RING = 31;

    interface Listener { void event(JSONObject event); }

    /** The open call screen, if any. */
    static volatile Listener listener;
    private static long cursor = -1;

    private CallHub() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    static final String JINGLE_MESSAGE = "urn:xmpp:jingle-message:0";

    /** Whether this chat can place Matrix calls: a Matrix person or room, linked on the phone or the server, and allowed. */
    static boolean callable(SharedPreferences prefs, String address) {
        if (!Calling.allowed(prefs, Calling.Method.MATRIX)) return false;
        if (DirectProtocols.route(prefs, address, MessageProtocol.MATRIX) != null) return true;
        return GatewayClient.formats(prefs, address).contains(MessageProtocol.MATRIX);
    }

    /** XMPP calls need XMPP connected from this phone, since the call screen sends its own stanzas. */
    static boolean xmppCallable(SharedPreferences prefs, String address) {
        return Calling.allowed(prefs, Calling.Method.XMPP) && DirectProtocols.route(prefs, address, MessageProtocol.XMPP) != null
            && DirectProtocols.on(prefs, MessageProtocol.XMPP);
    }

    static String xmppTarget(SharedPreferences prefs, String address) {
        var route = DirectProtocols.route(prefs, address, MessageProtocol.XMPP);
        return route == null ? "" : route.optString("target");
    }

    static void sendXmpp(String xml) throws Exception {
        var client = DirectProtocols.xmpp();
        if (client == null) throw new IllegalStateException("XMPP isn’t connected. Open FluidMessages and try again.");
        client.sendRaw(xml);
    }

    static String xmppSelf() {
        var client = DirectProtocols.xmpp();
        return client == null ? "" : client.self();
    }

    /** A call stanza from XMPP: forwarded to the open call, or rings when it's a new call from a linked contact. */
    static void xmppStanza(Context context, String from, String xml) {
        try {
            String bare = from.split("/", 2)[0].toLowerCase(java.util.Locale.ROOT);
            var route = DirectProtocols.routeForTarget(context, MessageProtocol.XMPP, bare);
            if (route == null) return;  // Only people you've linked can call.
            String address = route.getString("address");
            String title = route.optString("title", bare);
            var event = new JSONObject().put("protocol", "xmpp").put("type", "stanza").put("xml", xml).put("from", from)
                .put("address", address).put("title", title);
            var open = listener;
            if (open != null) { open.event(event); return; }
            if (xml.contains("<propose") && xml.contains(JINGLE_MESSAGE) && Calling.allowed(prefs(context), Calling.Method.XMPP)) {
                var id = java.util.regex.Pattern.compile("<propose[^>]*\\sid=['\"]([^'\"]+)").matcher(xml);
                if (!id.find()) return;
                boolean video = xml.contains("media=\"video\"") || xml.contains("media='video'");
                var invite = new JSONObject().put("protocol", "xmpp").put("sid", id.group(1)).put("from", from).put("video", video);
                ring(context, address, title, invite, video, "XMPP");
            }
        } catch (Exception ignored) {}
    }

    static void send(Context context, String address, String type, JSONObject content) throws Exception {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Unknown call signal");
        var prefs = prefs(context);
        var route = DirectProtocols.route(prefs, address, MessageProtocol.MATRIX);
        var direct = DirectProtocols.matrix();
        if (route != null) {
            if (direct == null) throw new IllegalStateException("Matrix isn’t connected. Open FluidMessages and try again.");
            direct.sendCall(route, type, content);
        } else GatewayClient.callSend(prefs, address, type, content);
    }

    static JSONArray iceServers(Context context, String address) {
        var prefs = prefs(context);
        var direct = DirectProtocols.matrix();
        if (DirectProtocols.route(prefs, address, MessageProtocol.MATRIX) != null && direct != null) return direct.iceServers();
        var servers = GatewayClient.iceServers(prefs);
        return servers == null ? new JSONArray() : servers;
    }

    /** A Matrix call event for a linked chat. event is the raw Matrix event. */
    static void incoming(Context context, String address, String title, JSONObject event) {
        try {
            var content = event.optJSONObject("content");
            if (content == null) return;
            long date = event.optLong("origin_server_ts", System.currentTimeMillis());
            boolean live = System.currentTimeMillis() - date < content.optLong("lifetime", 60_000);
            var forward = new JSONObject().put("type", event.optString("type")).put("content", content).put("address", address)
                .put("title", title).put("live", live);
            dispatch(context, forward);
        } catch (Exception ignored) {}
    }

    private static void dispatch(Context context, JSONObject event) {
        var open = listener;
        if (open != null) { open.event(event); return; }
        var content = event.optJSONObject("content");
        if ("m.call.invite".equals(event.optString("type")) && event.optBoolean("live") && content != null && content.has("offer")
                && Calling.allowed(prefs(context), Calling.Method.MATRIX)) {
            boolean video = content.optJSONObject("offer") != null && content.optJSONObject("offer").optString("sdp").contains("m=video");
            ring(context, event.optString("address"), event.optString("title"), content, video, "Matrix");
        } else if ("m.call.hangup".equals(event.optString("type")) || "m.call.select_answer".equals(event.optString("type"))) {
            context.getSystemService(NotificationManager.class).cancel(RING);
        }
    }

    /** Checks the companion for call events, for companion-linked chats. Runs while the app or a call is open. */
    static synchronized void pollCompanion(Context context) {
        var prefs = prefs(context);
        if (!CloudBackup.paired(prefs.getString("server", ""), Privacy.token(prefs))) return;
        try {
            var result = GatewayClient.calls(prefs, Math.max(cursor, 0));
            long next = result.optLong("next");
            // The first check only learns where events stand, so old calls don't ring.
            if (cursor < 0) { cursor = next; return; }
            cursor = next;
            var events = result.optJSONArray("events");
            for (int i = 0; events != null && i < events.length(); i++) {
                var event = events.getJSONObject(i);
                if (!"matrix".equals(event.optString("protocol"))) continue;
                dispatch(context, new JSONObject().put("type", event.optString("type")).put("content", event.optJSONObject("content"))
                    .put("address", event.optString("address")).put("title", event.optString("title")).put("live", event.optBoolean("live")));
            }
        } catch (Exception ignored) {}
    }

    static Intent screen(Context context, String address, String title, boolean video, JSONObject invite) {
        return screen(context, address, title, video, invite, "Matrix");
    }

    /** The call screen for Matrix, XMPP, or SIP. SIP goes through a provider, so it isn't end-to-end encrypted. */
    static Intent screen(Context context, String address, String title, boolean video, JSONObject invite, String protocol) {
        var intent = new Intent(context, CallActivity.class).putExtra("address", address).putExtra("title", title)
            .putExtra("video", video).putExtra("protocol", protocol)
            .putExtra("encryption", "SIP".equals(protocol) ? Encryption.IN_TRANSIT.name() : Encryption.END_TO_END.name())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (invite != null) intent.putExtra("invite", invite.toString());
        return intent;
    }

    /** A pretend call for the sample inbox: the real call screen, with nothing sent or recorded. */
    static Intent demo(Context context, String address, String title, boolean video, String protocol, Encryption encryption) {
        return screen(context, address, title, video, null).putExtra("demo", true).putExtra("protocol", protocol)
            .putExtra("encryption", encryption.name());
    }

    private static void ring(Context context, String address, String title, JSONObject invite, boolean video, String protocol) {
        var prefs = prefs(context);
        if (HiddenChats.addressHidden(prefs, address) && !Privacy.vaultOpen()) title = "Someone";
        String callId = invite.optString("call_id", invite.optString("sid"));
        // Shows as missed in Recents until it's answered.
        CallHistory.record(context, callId, address, title, video, android.provider.CallLog.Calls.MISSED_TYPE,
            System.currentTimeMillis(), 0, protocol);
        var manager = context.getSystemService(NotificationManager.class);
        var channel = new NotificationChannel("incoming-calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Voice and video calls over Matrix, XMPP, and SIP");
        manager.createNotificationChannel(channel);
        var open = PendingIntent.getActivity(context, RING, screen(context, address, title, video, invite, protocol),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(RING, new Notification.Builder(context, "incoming-calls")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle((video ? "Video call from " : "Voice call from ") + title)
            .setContentText(protocol + " · tap to answer")
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setAutoCancel(true)
            .setFullScreenIntent(open, true).setContentIntent(open).setTimeoutAfter(60_000).build());
    }

    static void stopRinging(Context context) { context.getSystemService(NotificationManager.class).cancel(RING); }
}
