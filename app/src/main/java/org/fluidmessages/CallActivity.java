package org.fluidmessages;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * The call screen. A WebView runs the same WebRTC call code as the browser companion (call.js, bundled in the
 * app), served from a private https origin so the camera and microphone work. Signaling goes through CallHub.
 */
public final class CallActivity extends androidx.activity.ComponentActivity {
    private static final String ORIGIN = "https://call.fluidmessages.invalid/";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private String address;
    private volatile boolean open = true;
    private boolean started;
    private int previousMode;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SECURE);
        address = getIntent().getStringExtra("address");
        if (address == null) { finish(); return; }
        CallHub.stopRinging(this);
        if (getIntent().getBooleanExtra("demo", false)) { show(); return; }
        var needed = new ArrayList<String>();
        needed.add(Manifest.permission.RECORD_AUDIO);
        if (getIntent().getBooleanExtra("video", false)) needed.add(Manifest.permission.CAMERA);
        needed.removeIf(permission -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
        if (needed.isEmpty()) show();
        else requestPermissions(needed.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "Calls need the microphone", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        show();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void show() {
        var audio = getSystemService(AudioManager.class);
        previousMode = audio.getMode();
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audio.setSpeakerphoneOn(getIntent().getBooleanExtra("video", false));
        web = new WebView(this);
        // Transparent, so the chat's wallpaper and the lock status show through behind the call.
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        var settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "FluidBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                // Only the bundled call page asks, and only for what this call already has Android permission for.
                if (!request.getOrigin().toString().startsWith(ORIGIN.substring(0, ORIGIN.length() - 1))) { request.deny(); return; }
                var granted = new ArrayList<String>();
                for (String resource : request.getResources()) {
                    if (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) granted.add(resource);
                    if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                        && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) granted.add(resource);
                }
                if (granted.isEmpty()) request.deny(); else request.grant(granted.toArray(new String[0]));
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith(ORIGIN)) return new WebResourceResponse("text/plain", "utf-8", 404, "Blocked", null, null);
                String name = url.substring(ORIGIN.length()).split("[?#]", 2)[0];
                if (!List.of("call.html", "call.js", "jingle.js", "jssip.min.js").contains(name)) return null;
                try {
                    return new WebResourceResponse(name.endsWith(".js") ? "text/javascript" : "text/html", "utf-8", getAssets().open(name));
                } catch (java.io.IOException error) { return null; }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public void onPageFinished(WebView view, String url) {
                if (started) return;
                started = true;
                try {
                    var options = new JSONObject().put("title", getIntent().getStringExtra("title"))
                        .put("video", getIntent().getBooleanExtra("video", false))
                        .put("demo", getIntent().getBooleanExtra("demo", false));
                    String protocol = getIntent().getStringExtra("protocol");
                    options.put("protocol", protocol == null ? "matrix" : protocol.toLowerCase(java.util.Locale.ROOT));
                    var prefs = getSharedPreferences("fluid", MODE_PRIVATE);
                    if ("XMPP".equals(protocol)) options.put("self", CallHub.xmppSelf()).put("peer", CallHub.xmppTarget(prefs, address));
                    // What the call screen shows and offers: its lock, recording, and the keypad (touch tones work on SIP).
                    String encryptionName = getIntent().getStringExtra("encryption");
                    var encryption = Encryption.valueOf(encryptionName == null ? "END_TO_END" : encryptionName);
                    String label = (protocol == null ? "Matrix" : protocol) + " · " + switch (encryption) {
                        case END_TO_END -> "End-to-end encrypted";
                        case IN_TRANSIT -> "SIP".equals(protocol) ? "Your SIP provider can access this call" : "The meeting server can access this call";
                        default -> "Not encrypted";
                    };
                    options.put("security", new JSONObject().put("tone", switch (encryption) {
                        case END_TO_END, COMPANION_E2EE, FLUIDMESSAGES -> "green";
                        case IN_TRANSIT -> "yellow";
                        default -> "red";
                    }).put("label", label));
                    boolean internet = protocol == null || List.of("Matrix", "XMPP", "SIP").contains(protocol);
                    options.put("recording", internet && CallRecordings.allowed(prefs));
                    options.put("keypad", "SIP".equals(protocol) || "Phone".equals(protocol));
                    if ("SIP".equals(protocol)) {
                        // The call screen connects to the SIP provider itself, so it needs the account for this call.
                        options.put("sip", Calling.sipAccount(prefs)).put("target", getIntent().getStringExtra("sipTarget"));
                    }
                    String invite = getIntent().getStringExtra("invite");
                    if (invite != null) options.put("invite", new JSONObject(invite));
                    run("fluidStart(" + options + ")");
                } catch (Exception error) { finish(); }
            }
        });
        var frame = new android.widget.FrameLayout(this);
        String protocol = getIntent().getStringExtra("protocol");
        var encryption = Encryption.valueOf(getIntent().getStringExtra("encryption") == null ? "END_TO_END" : getIntent().getStringExtra("encryption"));
        frame.addView(CallBackdrop.view(this, address, protocol == null ? "Matrix" : protocol, encryption));
        if (getIntent().getBooleanExtra("sipAgent", false)) {
            // An incoming SIP call lives in the always-ready SIP agent: show its page here instead of a new one.
            setContentView(frame);
            try {
                var prefs = getSharedPreferences("fluid", MODE_PRIVATE);
                var options = new JSONObject().put("title", getIntent().getStringExtra("title"))
                    .put("security", new JSONObject().put("tone", "yellow").put("label", "SIP · Your SIP provider can access this call"))
                    .put("recording", CallRecordings.allowed(prefs));
                if (!SipAgent.attach(this, frame, options)) finish();
            } catch (Exception error) { finish(); }
            web.destroy();
            web = null;
            return;
        }
        frame.addView(web);
        setContentView(frame);
        CallHub.listener = event -> {
            if (address.equals(event.optString("address"))) run("fluidEvent(" + event + ")");
        };
        // Companion-linked chats get their signaling by polling while the call is open.
        if (!getIntent().getBooleanExtra("demo", false)) new Thread(() -> {
            while (open) {
                CallHub.pollCompanion(getApplicationContext());
                try { Thread.sleep(1000); } catch (InterruptedException stop) { return; }
            }
        }, "fluid-call-poll").start();
        web.loadUrl(ORIGIN + "call.html");
    }

    private void toast(String text) {
        handler.post(() -> android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show());
    }

    /** Tells the other person in the chat that you started recording, when Settings says to (Matrix and XMPP). */
    private void announceRecording(String title) {
        var prefs = getSharedPreferences("fluid", MODE_PRIVATE);
        String protocol = getIntent().getStringExtra("protocol");
        if (!prefs.getBoolean("announceRecording", true) || getIntent().getBooleanExtra("demo", false)
                || !(protocol == null || "Matrix".equals(protocol) || "XMPP".equals(protocol))) return;
        var format = "XMPP".equals(protocol) ? MessageProtocol.XMPP : MessageProtocol.MATRIX;
        new Thread(() -> {
            try {
                format.transport().send(getApplicationContext(), new OutgoingMessage(address, List.of(address),
                    "🔴 I started recording this call.", null, -1, null));
            } catch (Exception ignored) {}
        }, "fluid-recording-notice").start();
    }

    private void run(String script) { handler.post(() -> { if (web != null) web.evaluateJavascript(script, null); }); }

    private void resolve(String id, boolean ok, String value) {
        run("fluidResolve(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(value == null ? "" : value) + ")");
    }

    private final class Bridge {
        @JavascriptInterface public void send(String id, String type, String content) {
            new Thread(() -> {
                try {
                    CallHub.send(getApplicationContext(), address, type, new JSONObject(content));
                    resolve(id, true, "");
                } catch (Exception error) {
                    resolve(id, false, error.getMessage() == null ? "Couldn’t reach Matrix" : error.getMessage());
                }
            }, "fluid-call-send").start();
        }

        @JavascriptInterface public void sendXml(String id, String xml) {
            new Thread(() -> {
                try {
                    CallHub.sendXmpp(xml);
                    resolve(id, true, "");
                } catch (Exception error) {
                    resolve(id, false, error.getMessage() == null ? "Couldn’t reach XMPP" : error.getMessage());
                }
            }, "fluid-call-xmpp").start();
        }

        @JavascriptInterface public void ice(String id) {
            new Thread(() -> resolve(id, true, CallHub.iceServers(getApplicationContext(), address).toString()), "fluid-call-ice").start();
        }

        @JavascriptInterface public void finished() { handler.post(CallActivity.this::finish); }

        @JavascriptInterface public void speaker(boolean on) {
            handler.post(() -> getSystemService(AudioManager.class).setSpeakerphoneOn(on));
        }

        @JavascriptInterface public void recordingStart(String mime, boolean video, String title) {
            try {
                CallRecordings.start(getApplicationContext(), mime, video, title);
                announceRecording(title);
            } catch (Exception error) { toast("Couldn't start recording"); }
        }

        @JavascriptInterface public void recordingChunk(String base64) {
            try { CallRecordings.append(base64); } catch (Exception ignored) {}
        }

        @JavascriptInterface public void recordingStop() {
            new Thread(() -> {
                try {
                    String where = CallRecordings.stop(getApplicationContext());
                    if (!where.isEmpty()) toast("Recording saved to " + where);
                } catch (Exception error) { toast("Couldn't save the recording"); }
            }, "fluid-recording").start();
        }

        /** How the call ended, for Recents. */
        @JavascriptInterface public void report(String json) {
            if (getIntent().getBooleanExtra("demo", false)) return;
            try {
                String protocol = getIntent().getStringExtra("protocol");
                CallHistory.finished(getApplicationContext(), address, getIntent().getStringExtra("title"),
                    getIntent().getBooleanExtra("video", false), protocol == null ? "Matrix" : protocol, new JSONObject(json));
            } catch (Exception ignored) {}
        }
    }

    @Override public void onBackPressed() {
        // Hang up first, so the other person's phone stops ringing, then close.
        run("fluidLeave()");
        handler.postDelayed(this::finish, 700);
    }

    @Override protected void onDestroy() {
        SipAgent.detach(this);
        open = false;
        if (CallHub.listener != null) CallHub.listener = null;
        if (web != null) {
            web.evaluateJavascript("fluidLeave()", null);
            web.destroy();
            web = null;
        }
        if (started || getIntent().getBooleanExtra("sipAgent", false)) {
            var audio = getSystemService(AudioManager.class);
            audio.setSpeakerphoneOn(false);
            audio.setMode(previousMode);
        }
        super.onDestroy();
    }
}
