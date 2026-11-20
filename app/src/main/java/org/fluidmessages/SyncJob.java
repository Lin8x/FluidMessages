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
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

public final class SyncJob extends JobService {
    private static final int PERIODIC = 200;
    private volatile Thread worker;

    static void configure(Context context, boolean enabled) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (enabled) {
            scheduler.schedule(new JobInfo.Builder(PERIODIC, new ComponentName(context, SyncJob.class))
                .setPersisted(true).setPeriodic(15 * 60 * 1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        } else {
            scheduler.cancel(PERIODIC);
        }
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        worker = new Thread(() -> {
            try {
                sync(getApplicationContext());
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
        String token = preferences.getString("token", "");
        if (!CloudBackup.paired(url, token) || !preferences.getBoolean("syncSms", true)
            || !context.getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_SMS)) return;
        post(url, token, "sync", new JSONObject().put("messages", SmsStore.read(context, 2000)));
        for (int count = 0; count < 10 && !Thread.currentThread().isInterrupted(); count++) {
            JSONObject job = post(url, token, "claim", new JSONObject()).optJSONObject("job");
            if (job == null) break;
            String state = "submitted";
            try {
                SmsStore.send(context, job.getString("address"), job.getString("body"), preferences.getInt("sim", -1));
            } catch (Exception error) {
                state = "failed";
            }
            post(url, token, "result", new JSONObject().put("id", job.getLong("id")).put("state", state));
        }
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
            if (connection.getResponseCode() != 200) throw new IOException("Companion returned HTTP " + connection.getResponseCode());
            try (var input = connection.getInputStream()) {
                byte[] response = input.readNBytes(32769);
                if (response.length > 32768) throw new IOException("Companion response is too large");
                return new JSONObject(new String(response, StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
