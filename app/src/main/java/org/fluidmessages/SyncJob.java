package org.fluidmessages;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

public final class SyncJob extends JobService {
    private static final int PERIODIC = 200;
    private volatile Thread worker;

    static void configure(Context context, boolean enabled) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        var prefs = SyncPolicy.prefs(context);
        int minutes = SyncPolicy.backgroundMinutes(prefs);
        if (enabled && minutes > 0) {
            // Android batches periodic jobs and never runs them more often than every 15 minutes.
            scheduler.schedule(new JobInfo.Builder(PERIODIC, new ComponentName(context, SyncJob.class))
                .setPersisted(true).setPeriodic(Math.max(15, minutes) * 60 * 1000L)
                .setRequiredNetworkType(SyncPolicy.wifiOnly(prefs) ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .build());
        } else {
            scheduler.cancel(PERIODIC);
        }
    }

    private static final int SOON = 201;

    /**
     * Runs one sync as soon as there's a network, e.g. after disappearing messages were deleted with the app closed,
     * so FluidMessages Web drops its copies on this sync rather than the next periodic one.
     */
    static void syncSoon(Context context) {
        var prefs = SyncPolicy.prefs(context);
        if (!CloudBackup.paired(prefs.getString("server", ""), Privacy.token(prefs)) || !prefs.getBoolean("syncSms", true)) return;
        context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(SOON, new ComponentName(context, SyncJob.class))
            .setRequiredNetworkType(SyncPolicy.wifiOnly(prefs) ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
            .build());
    }

    /** Applies the current battery and sync settings to the background job. */
    static void reschedule(Context context) {
        var prefs = SyncPolicy.prefs(context);
        configure(context, DirectProtocols.anyOn(prefs) || Disappearing.anyTimers(prefs) || CloudBackup.paired(prefs.getString("server", ""), Privacy.token(prefs))
            && (prefs.getBoolean("syncSms", true) || GatewayClient.wanted(prefs)));
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        worker = new Thread(() -> {
            try {
                var context = getApplicationContext();
                Disappearing.sweep(context);
                if (SyncPolicy.allowed(context)) {
                    DirectProtocols.poll(context);
                    try { GatewayClient.refresh(context, SyncPolicy.prefs(context)); } catch (Exception ignored) {}
                    sync(context);
                }
                if (!Thread.currentThread().isInterrupted()) jobFinished(parameters, false);
            } catch (Exception error) {
                if (!Thread.currentThread().isInterrupted()) jobFinished(parameters, true);
            }
        }, "fluid-companion");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        if (worker != null) worker.interrupt();
        return true;
    }

    static synchronized void sync(Context context) throws Exception {
        if (Thread.currentThread().isInterrupted()) return;
        var preferences = context.getSharedPreferences("fluid", MODE_PRIVATE);
        String url = preferences.getString("server", "");
        String token = Privacy.token(preferences);
        if (!CloudBackup.paired(url, token)) return;
        // Settings first: combined with FluidMessages Web's (SyncMergeRun) before this sync sends the phone's copy.
        try { SyncMergeRun.run(context, false); } catch (Exception ignored) {}
        if (!preferences.getBoolean("syncSms", true)
            || !context.getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_SMS)) return;
        publishInbox(context, preferences, url, token);
        boolean submitted = false;
        for (int count = 0; count < 10 && !Thread.currentThread().isInterrupted(); count++) {
            JSONObject job = post(url, token, "claim", new JSONObject()).optJSONObject("job");
            if (job == null) break;
            String state = "submitted";
            try {
                String recipient = job.getString("address");
                if ("call".equals(job.optString("protocol"))) {
                    callRequest(context, recipient);
                    post(url, token, "result", new JSONObject().put("id", job.getLong("id")).put("state", "submitted"));
                    continue;
                }
                MessageProtocol.SMS.transport().send(context, new OutgoingMessage(recipient, List.of(recipient),
                    job.getString("body"), null, preferences.getInt("sim", -1)));
                submitted = true;
            } catch (Exception error) {
                state = "failed";
            }
            post(url, token, "result", new JSONObject().put("id", job.getLong("id")).put("state", state));
        }
        if (submitted && !Thread.currentThread().isInterrupted()) publishInbox(context, preferences, url, token);
    }

    /**
     * A call started from the web companion. Android doesn't let apps place calls from the background without
     * the person, so this shows a notification that opens the dialer with the number filled in.
     */
    static void callRequest(Context context, String number) {
        var manager = context.getSystemService(android.app.NotificationManager.class);
        manager.createNotificationChannel(new android.app.NotificationChannel("calls", "Calls from FluidMessages Web",
            android.app.NotificationManager.IMPORTANCE_HIGH));
        var contact = new ContactDirectory(context).find(number);
        var dial = android.app.PendingIntent.getActivity(context, number.hashCode(),
            new android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null)),
            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(("call" + number).hashCode(), new android.app.Notification.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call).setContentTitle("Tap to call " + (contact == null ? number : contact.name()))
            .setContentText("Requested from FluidMessages Web").setCategory(android.app.Notification.CATEGORY_CALL)
            .setContentIntent(dial).setAutoCancel(true).setTimeoutAfter(120_000).build());
    }

    /**
     * Adds picture and group messages, which FluidMessages keeps itself rather than in Android's SMS store, so group
     * chats show up in the browser too. Files stay on the phone; the companion shows their caption.
     */
    private static org.json.JSONArray withMms(Context context, org.json.JSONArray texts) {
        var rows = new java.util.ArrayList<JSONObject>();
        for (int i = 0; i < texts.length(); i++) rows.add(texts.optJSONObject(i));
        try {
            var contacts = ContactDirectory.shared(context);
            for (JSONObject item : ProtocolStore.get(context).read()) {
                if (!"mms".equals(item.optString("protocol"))) continue;
                var row = new JSONObject().put("id", item.optString("id")).put("address", item.optString("address"))
                    .put("body", item.optString("body")).put("date", item.optLong("date")).put("type", item.optInt("type"))
                    .put("read", item.optInt("read", 1)).put("protocol", "mms").put("status", item.optString("status"));
                if (item.has("reaction_target")) row.put("reaction_target", item.optString("reaction_target"));
                String author = item.optString("author");
                var person = SmsStore.validRecipient(author) ? contacts.find(author) : null;
                if (!author.isEmpty()) row.put("author", person == null ? author : person.name());
                var members = item.optJSONArray("members");
                if (members != null && members.length() > 1) {
                    var names = new java.util.ArrayList<String>();
                    for (int m = 0; m < members.length(); m++) {
                        var match = contacts.find(members.optString(m));
                        names.add(match == null ? members.optString(m) : match.name());
                    }
                    row.put("title", String.join(", ", names));
                }
                rows.add(row);
            }
        } catch (Exception ignored) {}
        rows.sort((a, b) -> Long.compare(b.optLong("date"), a.optLong("date")));
        var result = new org.json.JSONArray();
        for (int i = 0; i < rows.size() && i < 2000; i++) result.put(rows.get(i));
        return result;
    }

    private static void publishInbox(Context context, android.content.SharedPreferences prefs, String url, String token) throws Exception {
        var all = withMms(context, SmsStore.read(context, 2000));
        // Hidden chats and protocols never leave the phone; the companion is told to hide its own copies too.
        var messages = new org.json.JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            var row = all.getJSONObject(i);
            if (HiddenChats.rowHidden(prefs, row)) continue;
            SmsCrypto.reveal(prefs, row);
            if (Disappearing.expired(prefs, row, row.optString("address"), now)) continue;
            long expires = Disappearing.expiry(prefs, row, row.optString("address"));
            if (expires > 0) row.put("expires", expires);
            messages.put(row);
        }
        var privacy = new JSONObject()
            .put("hidden_protocols", new org.json.JSONArray(Privacy.vaultExists(prefs) ? Privacy.hiddenProtocols(prefs) : java.util.Set.of()))
            .put("hidden_addresses", new org.json.JSONArray(Privacy.vaultExists(prefs) ? Privacy.hiddenAddresses(prefs) : java.util.Set.of()));
        privacy.put("disappearing", Disappearing.timers(prefs));
        // Which chats the phone encrypts, so the browser can say its own sends there aren't. Keys never leave the phone.
        var fluidProtocols = new org.json.JSONArray();
        for (var protocol : SmsCrypto.PROTOCOLS) if (SmsCrypto.globalOn(prefs, protocol)) fluidProtocols.put(protocol.name().toLowerCase(java.util.Locale.ROOT));
        var fluidChats = new JSONObject();
        for (var entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("smsEncrypt.") && entry.getValue() instanceof String choice && !choice.isEmpty()) {
                fluidChats.put(entry.getKey().substring("smsEncrypt.".length()), choice);
            }
        }
        privacy.put("fluid_encryption", new JSONObject().put("protocols", fluidProtocols).put("chats", fluidChats));
        post(url, token, "sync", new JSONObject().put("messages", messages).put("privacy", privacy)
            .put("appearance", CompanionAppearance.snapshot(prefs, messages)));
    }

    private static JSONObject post(String root, String token, String endpoint, JSONObject data) throws Exception {
        var connection = (HttpsURLConnection) new URL(root + "/api/" + endpoint).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] content = data.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(content.length);
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream()) { output.write(content); }
            if (connection.getResponseCode() != 200) throw new IOException("FluidMessages Web returned HTTP " + connection.getResponseCode());
            try (var input = connection.getInputStream()) {
                byte[] response = input.readNBytes(32769);
                if (response.length > 32768) throw new IOException("FluidMessages Web response is too large");
                return new JSONObject(new String(response, StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
