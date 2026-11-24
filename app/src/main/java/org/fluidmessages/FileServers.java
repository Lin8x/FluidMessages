package org.fluidmessages;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Backups to your own file server: SFTP, FTPS, FTP, or WebDAV (Nextcloud, ownCloud, and most NAS boxes), on any
 * port. Server details and passwords are stored sealed. SFTP remembers the server's key the first time it connects
 * and refuses to continue if it ever changes, the way SSH does.
 */
final class FileServers {
    static final String SFTP = "sftp", FTPS = "ftps", FTP_PLAIN = "ftp", WEBDAV = "webdav";
    private static final Pattern BACKUP = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.()-]{0,120}\\.fmb");

    private FileServers() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    static int defaultPort(String kind) {
        return switch (kind) { case SFTP -> 22; case WEBDAV -> 443; default -> 21; };
    }

    static String label(String kind) {
        return switch (kind) { case SFTP -> "SFTP"; case FTPS -> "FTPS"; case FTP_PLAIN -> "FTP"; default -> "WebDAV"; };
    }

    static List<JSONObject> list(SharedPreferences prefs) {
        var result = new ArrayList<JSONObject>();
        String stored = prefs.getString("fileServers", "");
        if (stored == null || stored.isEmpty()) return result;
        try {
            var array = new JSONArray(Privacy.open(prefs, stored));
            for (int i = 0; i < array.length(); i++) result.add(array.getJSONObject(i));
        } catch (Exception ignored) {}
        return result;
    }

    static JSONObject find(SharedPreferences prefs, String id) {
        for (var server : list(prefs)) if (server.optString("id").equals(id)) return server;
        return null;
    }

    /** Adds or replaces a server. A blank password keeps the saved one. */
    static void save(SharedPreferences prefs, JSONObject server) throws Exception {
        var servers = list(prefs);
        if (server.optString("id").isEmpty()) server.put("id", UUID.randomUUID().toString());
        for (var existing : servers) {
            if (!existing.optString("id").equals(server.optString("id"))) continue;
            if (server.optString("password").isEmpty()) server.put("password", existing.optString("password"));
            // A changed address forgets the remembered SFTP key.
            if (existing.optString("host").equals(server.optString("host")) && existing.optInt("port") == server.optInt("port")) {
                server.put("hostKey", existing.optString("hostKey"));
            }
            for (String keep : new String[]{"secret", "minutes", "categories", "wifi", "last"}) {
                if (existing.has(keep) && !server.has(keep)) server.put(keep, existing.get(keep));
            }
        }
        servers.removeIf(existing -> existing.optString("id").equals(server.optString("id")));
        servers.add(server);
        write(prefs, servers);
    }

    static void remove(Context context, String id) {
        context.getSystemService(JobScheduler.class).cancel(jobId(id));
        var prefs = prefs(context);
        var servers = list(prefs);
        servers.removeIf(server -> server.optString("id").equals(id));
        write(prefs, servers);
    }

    private static void write(SharedPreferences prefs, List<JSONObject> servers) {
        prefs.edit().putString("fileServers", Privacy.seal(prefs, new JSONArray(servers).toString())).apply();
    }

    static String folder(JSONObject server) {
        String folder = server.optString("folder", "FluidMessages").trim();
        return folder.replaceAll("^/+|/+$", "");
    }

    // Transfers

    /** Checks the address, sign-in, and folder without uploading anything. */
    static void test(Context context, JSONObject server) throws Exception { listBackups(context, server); }

    static void upload(Context context, JSONObject server, File file, String name) throws Exception {
        switch (server.optString("kind")) {
            case SFTP -> sftp(context, server, channel -> {
                makeDirs(channel, folder(server));
                try (InputStream in = new FileInputStream(file)) { channel.put(in, path(server, name)); }
                return null;
            });
            case WEBDAV -> webdavPut(server, file, name);
            default -> ftp(server, client -> {
                for (String part : folder(server).split("/")) {
                    if (part.isEmpty()) continue;
                    if (!client.changeWorkingDirectory(part)) {
                        client.makeDirectory(part);
                        if (!client.changeWorkingDirectory(part)) throw new IOException("Couldn't open the folder " + folder(server));
                    }
                }
                try (InputStream in = new FileInputStream(file)) {
                    if (!client.storeFile(name, in)) throw new IOException("The server didn't accept the backup: " + client.getReplyString().trim());
                }
                return null;
            });
        }
    }

    /** Backup files in the server folder, oldest first. */
    static List<String> listBackups(Context context, JSONObject server) throws Exception {
        var names = new ArrayList<String>();
        switch (server.optString("kind")) {
            case SFTP -> sftp(context, server, channel -> {
                makeDirs(channel, folder(server));
                var entries = channel.ls(folder(server).isEmpty() ? "." : folder(server));
                var sorted = new ArrayList<ChannelSftp.LsEntry>();
                for (Object entry : entries) if (entry instanceof ChannelSftp.LsEntry item && BACKUP.matcher(item.getFilename()).matches()) sorted.add(item);
                sorted.sort((a, b) -> Integer.compare(a.getAttrs().getMTime(), b.getAttrs().getMTime()));
                for (var item : sorted) names.add(item.getFilename());
                return null;
            });
            case WEBDAV -> names.addAll(webdavList(server));
            default -> ftp(server, client -> {
                if (!folder(server).isEmpty() && !client.changeWorkingDirectory(folder(server))) return null;
                var files = new ArrayList<FTPFile>();
                for (var file : client.listFiles()) if (file.isFile() && BACKUP.matcher(file.getName()).matches()) files.add(file);
                files.sort((a, b) -> a.getTimestamp() == null || b.getTimestamp() == null ? 0 : a.getTimestamp().compareTo(b.getTimestamp()));
                for (var file : files) names.add(file.getName());
                return null;
            });
        }
        return names;
    }

    /** Downloads the newest backup into target. */
    static void newest(Context context, JSONObject server, File target) throws Exception {
        var names = listBackups(context, server);
        if (names.isEmpty()) throw new IOException("There are no FluidMessages backups in " + folder(server) + " yet");
        String name = names.get(names.size() - 1);
        switch (server.optString("kind")) {
            case SFTP -> sftp(context, server, channel -> {
                try (var out = new FileOutputStream(target)) { channel.get(path(server, name), out); }
                return null;
            });
            case WEBDAV -> {
                var connection = webdav(server, name, "GET");
                if (connection.getResponseCode() != 200) throw new IOException("The server returned " + connection.getResponseCode());
                try (var in = connection.getInputStream(); var out = new FileOutputStream(target)) { in.transferTo(out); }
                finally { connection.disconnect(); }
            }
            default -> ftp(server, client -> {
                if (!folder(server).isEmpty()) client.changeWorkingDirectory(folder(server));
                try (var out = new FileOutputStream(target)) {
                    if (!client.retrieveFile(name, out)) throw new IOException("Couldn't download " + name);
                }
                return null;
            });
        }
    }

    private static String path(JSONObject server, String name) {
        return folder(server).isEmpty() ? name : folder(server) + "/" + name;
    }

    // SFTP

    private interface SftpWork { Object run(ChannelSftp channel) throws Exception; }

    private static void sftp(Context context, JSONObject server, SftpWork work) throws Exception {
        var jsch = new JSch();
        var session = jsch.getSession(server.optString("username"), server.optString("host"), server.optInt("port", 22));
        session.setPassword(server.optString("password"));
        session.setConfig("StrictHostKeyChecking", "no");
        session.setTimeout(20_000);
        session.connect(20_000);
        try {
            // Trust on first use: remember the server's key, and stop if it ever changes.
            String key = session.getHostKey().getFingerPrint(jsch);
            String known = server.optString("hostKey");
            if (!known.isEmpty() && !known.equals(key)) {
                throw new IOException("This server's identity changed since you added it. If you didn't change the server, "
                    + "someone may be intercepting the connection. Remove and re-add it if the change is expected.");
            }
            if (known.isEmpty()) {
                server.put("hostKey", key);
                if (!server.optString("id").isEmpty() && find(prefs(context), server.optString("id")) != null) save(prefs(context), server);
            }
            var channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(20_000);
            try { work.run(channel); } finally { channel.disconnect(); }
        } finally { session.disconnect(); }
    }

    private static void makeDirs(ChannelSftp channel, String folder) throws Exception {
        String current = "";
        for (String part : folder.split("/")) {
            if (part.isEmpty()) continue;
            current = current.isEmpty() ? part : current + "/" + part;
            try { channel.stat(current); } catch (Exception missing) { channel.mkdir(current); }
        }
    }

    // FTP and FTPS

    private interface FtpWork { Object run(FTPClient client) throws Exception; }

    private static void ftp(JSONObject server, FtpWork work) throws Exception {
        boolean secure = FTPS.equals(server.optString("kind"));
        FTPClient client = secure ? new FTPSClient(false) : new FTPClient();
        client.setConnectTimeout(20_000);
        client.setDefaultTimeout(30_000);
        client.connect(server.optString("host"), server.optInt("port", 21));
        try {
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) throw new IOException("The FTP server refused the connection");
            if (!client.login(server.optString("username"), server.optString("password"))) throw new IOException("The username or password was wrong");
            if (client instanceof FTPSClient tls) {
                tls.execPBSZ(0);
                tls.execPROT("P");
            }
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            work.run(client);
            client.logout();
        } finally {
            if (client.isConnected()) client.disconnect();
        }
    }

    // WebDAV (HTTPS)

    private record Dav(String host, int port, String path, String auth) {}

    private static Dav dav(JSONObject server, String name) throws IOException {
        String base = server.optString("host").replaceAll("/+$", "");
        if (base.startsWith("http://")) throw new IOException("WebDAV needs an https:// address");
        if (!base.startsWith("https://")) base = "https://" + base;
        var url = new URL(base);
        String path = (url.getPath() + "/" + folder(server) + "/").replaceAll("//+", "/");
        if (!name.isEmpty()) path += Uri.encode(name);
        String login = server.optString("username") + ":" + server.optString("password");
        return new Dav(url.getHost(), server.optInt("port", 443), path,
            "Basic " + Base64.getEncoder().encodeToString(login.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpURLConnection webdav(JSONObject server, String name, String method) throws IOException {
        var target = dav(server, name);
        var url = new URL("https", target.host(), target.port() == 443 ? -1 : target.port(), target.path());
        var connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", target.auth());
        return connection;
    }

    /**
     * Sends a WebDAV-only request (PROPFIND, MKCOL), which Android's HttpURLConnection refuses, over a TLS socket
     * whose certificate and hostname are checked the same way. Returns the status code and the body.
     */
    private static Object[] davRequest(Dav target, String method, String depth, String body) throws IOException {
        var factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        try (var socket = (javax.net.ssl.SSLSocket) factory.createSocket(target.host(), target.port())) {
            socket.setSoTimeout(30_000);
            socket.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(target.host(), socket.getSession())) {
                throw new IOException("The server's certificate doesn't match " + target.host());
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            String request = method + " " + target.path() + " HTTP/1.1\r\nHost: " + target.host()
                + (target.port() == 443 ? "" : ":" + target.port()) + "\r\nAuthorization: " + target.auth()
                + "\r\nUser-Agent: FluidMessages\r\nConnection: close\r\n" + (depth == null ? "" : "Depth: " + depth + "\r\n")
                + (payload.length > 0 ? "Content-Type: application/xml; charset=utf-8\r\n" : "")
                + "Content-Length: " + payload.length + "\r\n\r\n";
            var out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            var in = new java.io.BufferedInputStream(socket.getInputStream());
            String status = line(in);
            var parts = status.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("The server didn't answer like a WebDAV server");
            int code = Integer.parseInt(parts[1]);
            long length = -1;
            boolean chunked = false;
            for (String header; !(header = line(in)).isEmpty(); ) {
                String lower = header.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Long.parseLong(lower.substring(15).trim());
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
            }
            var data = new java.io.ByteArrayOutputStream();
            int limit = 4_000_000;
            if (chunked) {
                for (int size; (size = Integer.parseInt(line(in).split(";")[0].trim(), 16)) > 0; ) {
                    if (data.size() + size > limit) throw new IOException("The folder listing is too large");
                    data.write(in.readNBytes(size));
                    line(in);
                }
            } else if (length >= 0) {
                if (length > limit) throw new IOException("The folder listing is too large");
                data.write(in.readNBytes((int) length));
            } else data.write(in.readNBytes(limit));
            return new Object[]{code, data.toString(StandardCharsets.UTF_8)};
        }
    }

    private static String line(InputStream in) throws IOException {
        var line = new StringBuilder();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) {
            if (c != '\r') line.append((char) c);
            if (line.length() > 16_000) throw new IOException("The server sent an invalid response");
        }
        return line.toString();
    }

    private static void webdavPut(JSONObject server, File file, String name) throws IOException {
        // Make the folder if it isn't there yet; "already exists" answers are fine.
        String made = "";
        for (String part : folder(server).split("/")) {
            if (part.isEmpty()) continue;
            made = made.isEmpty() ? part : made + "/" + part;
            var folder = new JSONObject();
            try { folder = new JSONObject(server.toString()).put("folder", made); } catch (Exception ignored) {}
            int code = (int) davRequest(dav(folder, ""), "MKCOL", null, "")[0];
            if (code == 401) throw new IOException("The username or password was wrong");
        }
        var connection = webdav(server, name, "PUT");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(file.length());
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        try (var out = connection.getOutputStream(); var in = new FileInputStream(file)) { in.transferTo(out); }
        int code = connection.getResponseCode();
        connection.disconnect();
        if (code == 401) throw new IOException("The username or password was wrong");
        if (code < 200 || code >= 300) throw new IOException("The server returned " + code);
    }

    private static List<String> webdavList(JSONObject server) throws IOException {
        var reply = davRequest(dav(server, ""), "PROPFIND", "1",
            "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getlastmodified/></d:prop></d:propfind>");
        int code = (int) reply[0];
        if (code == 404) return List.of();
        if (code == 401) throw new IOException("The username or password was wrong");
        if (code != 207 && code != 200) throw new IOException("The server returned " + code);
        String body = (String) reply[1];
        var names = new ArrayList<String>();
        var matcher = Pattern.compile("<(?:[A-Za-z]+:)?href>([^<]+)</(?:[A-Za-z]+:)?href>").matcher(body);
        while (matcher.find()) {
            String href = Uri.decode(matcher.group(1));
            String name = href.substring(href.replaceAll("/+$", "").lastIndexOf('/') + 1);
            if (BACKUP.matcher(name).matches()) names.add(name);
        }
        names.sort(String::compareTo);
        return names;
    }

    // Running backups, now or on a schedule

    static void run(Context context, JSONObject server, Backup.Options options, String password) throws Exception {
        var file = File.createTempFile("fluid-server-", ".fmb", context.getCacheDir());
        try {
            Backup.write(context, Uri.fromFile(file), options, password);
            upload(context, server, file, BackupNames.suggest(prefs(context)));
            server.put("last", System.currentTimeMillis());
            save(prefs(context), server);
        } finally { file.delete(); }
    }

    static int jobId(String id) { return 400 + Math.floorMod(id.hashCode(), 5000); }

    static void schedule(Context context, JSONObject server, Backup.Options options, String password, int minutes, boolean wifi)
            throws Exception {
        long period = Math.max(15, minutes) * 60_000L;
        var request = new JobInfo.Builder(jobId(server.getString("id")), new ComponentName(context, Job.class))
            .setPersisted(true).setPeriodic(period)
            .setRequiredNetworkType(wifi ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
            .setExtras(new android.os.PersistableBundle()).build();
        if (context.getSystemService(JobScheduler.class).schedule(request) != JobScheduler.RESULT_SUCCESS) {
            throw new IOException("Android couldn't schedule the backup");
        }
        server.put("minutes", minutes).put("wifi", wifi).put("categories", options.categories())
            .put("secret", Privacy.seal(prefs(context), password));
        save(prefs(context), server);
    }

    static void unschedule(Context context, JSONObject server) throws Exception {
        context.getSystemService(JobScheduler.class).cancel(jobId(server.getString("id")));
        server.remove("minutes");
        server.remove("secret");
        save(prefs(context), server);
    }

    /** Runs scheduled file-server backups. */
    public static final class Job extends JobService {
        private Thread worker;

        @Override public boolean onStartJob(JobParameters params) {
            worker = new Thread(() -> {
                boolean retry = false;
                try {
                    var prefs = prefs(this);
                    for (var server : list(prefs)) {
                        if (jobId(server.optString("id")) != params.getJobId() || server.optInt("minutes") <= 0) continue;
                        run(this, server, new Backup.Options(server.optInt("categories")), Privacy.open(prefs, server.optString("secret")));
                    }
                } catch (Exception error) { retry = true; }
                boolean again = retry;
                getMainExecutor().execute(() -> jobFinished(params, again));
            }, "fluid-server-backup");
            worker.start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) {
            if (worker != null) worker.interrupt();
            return true;
        }
    }
}
