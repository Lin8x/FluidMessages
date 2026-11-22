package org.fluidmessages;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

final class CloudBackup {
    static final int MAX_BYTES = 64 * 1024 * 1024 + 64;
    enum Target {
        ONEDRIVE("onedrive", "OneDrive"), DRIVE("drive", "Google Drive"), S3("s3", "S3");
        final String id;
        final String label;
        Target(String id, String label) { this.id = id; this.label = label; }
    }

    static boolean paired(Context context) {
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        return paired(prefs.getString("server", ""), prefs.getString("token", ""));
    }

    static boolean paired(String origin, String token) { return hasTlsScheme(origin) && token.trim().length() >= 32; }

    private static boolean hasTlsScheme(String origin) { return origin.regionMatches(true, 0, "https://", 0, 8); }

    static JSONArray targets(Context context) throws Exception {
        var connection = connect(context, "", "GET");
        try {
            require(connection, 200);
            try (var input = connection.getInputStream()) {
                return new JSONObject(new String(input.readNBytes(8192), StandardCharsets.UTF_8)).getJSONArray("targets");
            }
        } finally { connection.disconnect(); }
    }

    static void upload(Context context, Target target, File file, String expectedOrigin, String fixedName) throws Exception {
        var connection = connect(context, "/" + target.id, "PUT", expectedOrigin);
        try {
            if (file.length() > MAX_BYTES) throw new IOException("Backup is too large for upload");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            if (!fixedName.isEmpty()) connection.setRequestProperty("X-Fluid-Backup-Name", fixedName);
            connection.setFixedLengthStreamingMode(file.length());
            try (var input = new java.io.FileInputStream(file); var output = connection.getOutputStream()) {
                input.transferTo(output);
            }
            require(connection, 201);
            if (!fixedName.isEmpty()) try (var input = connection.getInputStream()) {
                var result = new JSONObject(new String(input.readNBytes(4096), StandardCharsets.UTF_8));
                if (!fixedName.equals(result.optString("name"))) throw new IOException("Update the companion to use fixed backup names");
            }
        } finally { connection.disconnect(); }
    }

    static void newest(Context context, Target target, File file) throws Exception {
        var connection = connect(context, "/" + target.id + "/latest", "GET");
        try {
            require(connection, 200);
            long length = connection.getContentLengthLong();
            if (length < 5 || length > MAX_BYTES) throw new IOException("Invalid backup size");
            try (var input = connection.getInputStream(); var output = new java.io.FileOutputStream(file)) {
                byte[] buffer = new byte[65536];
                long remaining = length;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("Incomplete backup");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        } finally { connection.disconnect(); }
    }

    private static HttpsURLConnection connect(Context context, String suffix, String method) throws Exception {
        return connect(context, suffix, method, null);
    }

    private static HttpsURLConnection connect(Context context, String suffix, String method, String expectedOrigin) throws Exception {
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        var settings = prefs.getAll();
        String origin = settings.get("server") instanceof String value ? value : "";
        if (!hasTlsScheme(origin)) throw new IOException("Pair an HTTPS companion first");
        if (expectedOrigin != null && !expectedOrigin.equals(origin)) throw new IOException("The paired companion has changed");
        var connection = (HttpsURLConnection) new URL(origin + "/api/backups" + suffix).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(195000);
        connection.setRequestMethod(method);
        String token = settings.get("token") instanceof String value ? value : "";
        connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private static void require(HttpsURLConnection connection, int expected) throws IOException {
        int actual = connection.getResponseCode();
        if (actual != expected) {
            throw new IOException(actual == 404 ? "Destination is missing or has no backups"
                : "Cloud backup failed (HTTP " + actual + ")");
        }
    }
}
