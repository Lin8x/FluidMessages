package org.fluidmessages;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.util.List;
import org.json.JSONObject;

/**
 * Stays registered with your SIP provider so SIP calls can ring this phone. It runs the same call page as the
 * call screen in a hidden WebView; when a call comes in, the call screen borrows that WebView to show it, then
 * hands it back. How long it stays online is up to you: off, while FluidMessages is open, or always.
 */
final class SipAgent {
    static final String OFF = "off";
    static final String OPEN = "open";
    static final String ALWAYS = "always";
    private static final String ORIGIN = "https://call.fluidmessages.invalid/";
    private static final int RING = 33;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static Context app;
    private static WebView web;
    private static MutableContextWrapper wrapper;
    private static String signature = "";
    private static boolean appVisible;
    private static volatile String status = "Offline";
    private static CallActivity screen;
    private static JSONObject caller;

    private SipAgent() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    static String mode(SharedPreferences prefs) { return prefs.getString("sip.receive", OPEN); }

    static String status() { return status; }

    static boolean alwaysOn(SharedPreferences prefs) { return Calling.sipReady(prefs) && ALWAYS.equals(mode(prefs)); }

    static void setAppVisible(Context context, boolean visible) {
        appVisible = visible;
        sync(context);
    }

    /** Starts, restarts, or stops the agent to match the SIP account, the setting, and whether the app is open. */
    static void sync(Context context) {
        main.post(() -> {
            app = context.getApplicationContext();
            var prefs = prefs(app);
            var account = Calling.sipReady(prefs) ? Calling.sipAccount(prefs) : null;
            String want = mode(prefs);
            boolean wanted = account != null && (ALWAYS.equals(want) || OPEN.equals(want) && appVisible || screen != null);
            String next = wanted ? account.toString() : "";
            if (next.equals(signature) && (web != null) == wanted) return;
            stop();
            if (wanted) start(account);
            signature = next;
        });
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void start(JSONObject account) {
        wrapper = new MutableContextWrapper(app);
        web = new WebView(wrapper);
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        var settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "FluidBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                if (!request.getOrigin().toString().startsWith(ORIGIN.substring(0, ORIGIN.length() - 1))
                        || app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    request.deny();
                    return;
                }
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith(ORIGIN)) return new WebResourceResponse("text/plain", "utf-8", 404, "Blocked", null, null);
                String name = url.substring(ORIGIN.length()).split("[?#]", 2)[0];
                if (!List.of("call.html", "call.js", "jingle.js", "jssip.min.js").contains(name)) return null;
                try {
                    return new WebResourceResponse(name.endsWith(".js") ? "text/javascript" : "text/html", "utf-8", app.getAssets().open(name));
                } catch (java.io.IOException error) { return null; }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public void onPageFinished(WebView view, String url) {
                run("fluidAgent(" + account + ")");
            }
        });
        status = "Connecting…";
        web.loadUrl(ORIGIN + "call.html");
    }

    private static void stop() {
        if (web == null) return;
        if (screen != null) return;  // Never cut off a call in progress.
        web.evaluateJavascript("fluidAgentStop()", null);
        web.destroy();
        web = null;
        status = "Offline";
    }

    private static void run(String script) { main.post(() -> { if (web != null) web.evaluateJavascript(script, null); }); }

    /** The call screen shows the incoming SIP call in the agent's WebView. */
    static boolean attach(CallActivity activity, FrameLayout frame, JSONObject options) {
        if (web == null) return false;
        screen = activity;
        wrapper.setBaseContext(activity);
        if (web.getParent() instanceof ViewGroup parent) parent.removeView(web);
        frame.addView(web);
        main.post(() -> app.getSystemService(NotificationManager.class).cancel(RING));
        run("agentShow(" + options + ")");
        return true;
    }

    static void detach(CallActivity activity) {
        if (screen != activity) return;
        screen = null;
        if (web != null) {
            run("agentHide()");
            if (web.getParent() instanceof ViewGroup parent) parent.removeView(web);
            wrapper.setBaseContext(app);
        }
        sync(app);
    }

    /** Matches the caller to a contact by their number, so the ring shows a name. */
    private static void ring(JSONObject info) {
        caller = info;
        String user = info.optString("user");
        var contact = SmsStore.validRecipient(user) ? new ContactDirectory(app).find(user) : null;
        String title = contact != null ? contact.name() : info.optString("name").isEmpty() ? user : info.optString("name");
        try { info.put("title", title).put("address", SmsStore.validRecipient(user) ? user : "sip:" + info.optString("uri")); }
        catch (Exception ignored) {}
        CallHistory.record(app, info.optString("id"), info.optString("address"), title, false, android.provider.CallLog.Calls.MISSED_TYPE,
            System.currentTimeMillis(), 0, "SIP");
        var intent = CallHub.screen(app, info.optString("address"), title, false, null, "SIP").putExtra("sipAgent", true);
        var manager = app.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("incoming-calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH));
        var open = PendingIntent.getActivity(app, RING, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(RING, new Notification.Builder(app, "incoming-calls").setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Call from " + title).setContentText("SIP · tap to answer").setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true).setFullScreenIntent(open, true).setContentIntent(open).setTimeoutAfter(60_000).build());
    }

    private static final class Bridge {
        @JavascriptInterface public void status(String value) { status = value; }

        @JavascriptInterface public void incoming(String json) {
            main.post(() -> { try { ring(new JSONObject(json)); } catch (Exception ignored) {} });
        }

        /** The caller hung up before anyone answered. */
        @JavascriptInterface public void missed() {
            main.post(() -> {
                app.getSystemService(NotificationManager.class).cancel(RING);
                var info = caller;
                boolean hidden = info != null && HiddenChats.addressHidden(app.getSharedPreferences("fluid", Context.MODE_PRIVATE),
                    info.optString("address")) && !Privacy.vaultOpen();
                SmsDelivery.notifyMissedCall(app, info == null || hidden ? "" : info.optString("title"), "SIP");
                if (screen != null) screen.finish();
            });
        }

        @JavascriptInterface public void finished() { main.post(() -> { if (screen != null) screen.finish(); }); }

        @JavascriptInterface public void report(String json) {
            try {
                var info = caller == null ? new JSONObject() : caller;
                var report = new JSONObject(json).put("callId", info.optString("id")).put("incoming", true);
                CallHistory.finished(app, info.optString("address"), info.optString("title"), false, "SIP", report);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public void speaker(boolean on) { main.post(() -> app.getSystemService(AudioManager.class).setSpeakerphoneOn(on)); }

        @JavascriptInterface public void recordingStart(String mime, boolean video, String title) {
            try { CallRecordings.start(app, mime, video, title); } catch (Exception ignored) {}
        }

        @JavascriptInterface public void recordingChunk(String base64) { try { CallRecordings.append(base64); } catch (Exception ignored) {} }

        @JavascriptInterface public void recordingStop() {
            new Thread(() -> { try { CallRecordings.stop(app); } catch (Exception ignored) {} }, "fluid-recording").start();
        }

        @JavascriptInterface public void ice(String id) {
            new Thread(() -> {
                String servers = CallHub.iceServers(app, "").toString();
                run("fluidResolve(" + JSONObject.quote(id) + ",true," + JSONObject.quote(servers) + ")");
            }, "fluid-sip-ice").start();
        }
    }
}
