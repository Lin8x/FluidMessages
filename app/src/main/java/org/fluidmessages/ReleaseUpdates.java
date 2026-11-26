package org.fluidmessages;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class ReleaseUpdates {
    static final String REPOSITORY = "https://github.com/Lin8x/FluidMessages";
    static final long CHECK_INTERVAL = 24 * 60 * 60 * 1000L;
    private static final String API = "https://api.github.com/repos/Lin8x/FluidMessages/releases/latest";
    private static final int MAX_METADATA = 1_048_576;
    private static final long MAX_APK = 200L * 1024 * 1024;
    private static final Set<String> ASSET_HOSTS = Set.of("github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com");
    private static final Set<String> MANAGED_INSTALLERS = Set.of("com.android.vending", "org.fdroid.fdroid",
        "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid", "com.aurora.store", "dev.imranr.obtainium",
        "dev.imranr.obtainium.fdroid", "com.sec.android.app.samsungapps", "com.huawei.appmarket");

    record Release(String version, URL download, long size, String digest) {}
    record Result(Release update, String status) {}

    /** True for F-Droid's build (-Pfdroid=true): F-Droid delivers updates, so the app never checks or downloads them itself. */
    static boolean fdroidBuild() { return BuildConfig.FDROID; }

    /**
     * True for builds made for an app store: F-Droid's, and the app bundle (.aab) for stores like Google Play
     * (-Pstore=true). The store delivers updates, so the app never checks or downloads them itself.
     */
    static boolean storeBuild() { return BuildConfig.STORE; }

    static String storeUpdates() { return fdroidBuild() ? "Updates come from F-Droid." : "Updates come from the app store you installed FluidMessages from."; }

    static boolean managedByStore(Context context) {
        if (storeBuild()) return true;
        try {
            String installer = context.getPackageManager().getInstallSourceInfo(context.getPackageName()).getInstallingPackageName();
            return installer != null && MANAGED_INSTALLERS.contains(installer);
        } catch (PackageManager.NameNotFoundException ignored) { return false; }
    }

    static PackageInfo installed(Context context, int flags) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), flags);
    }

    static Result check(String installedVersion) throws Exception {
        if (storeBuild()) return new Result(null, storeUpdates());
        var connection = connect(new URL(API));
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = connection.getResponseCode();
            if (code == 404) return new Result(null, "No public GitHub release is available yet.");
            if (code != 200) throw new IOException("GitHub could not complete the update check");
            try (var input = connection.getInputStream()) {
                byte[] data = input.readNBytes(MAX_METADATA + 1);
                if (data.length > MAX_METADATA) throw new IOException("Release metadata is too large");
                return release(installedVersion, new JSONObject(new String(data, StandardCharsets.UTF_8)));
            }
        } finally { connection.disconnect(); }
    }

    static Result release(String installedVersion, JSONObject response) throws Exception {
        String tag = response.optString("tag_name");
        int[] latest = version(tag);
        int[] current = version(installedVersion);
        if (latest == null || current == null || response.optBoolean("draft") || response.optBoolean("prerelease")) {
            return new Result(null, "No compatible stable GitHub release is available.");
        }
        String version = String.format(Locale.ROOT, "%d.%d.%d", latest[0], latest[1], latest[2]);
        if (Arrays.compare(latest, current) <= 0) return new Result(null, "You’re up to date.");
        var assets = response.optJSONArray("assets");
        if (assets != null) for (String name : new String[]{"FluidMessages-" + version + ".apk", "FluidMessages.apk",
                "FluidMessages-" + version + "-debug.apk"}) {
            for (int i = 0; i < assets.length(); i++) {
                var asset = assets.optJSONObject(i);
                if (asset == null || !name.equals(asset.optString("name"))) continue;
                long size = asset.optLong("size", -1);
                URL url = new URL(asset.optString("browser_download_url"));
                String path = "/Lin8x/FluidMessages/releases/download/" + tag + "/" + name;
                if (!trusted(url, Set.of("github.com")) || !path.equalsIgnoreCase(url.getPath())
                        || url.getQuery() != null || size < 1 || size > MAX_APK) continue;
                String digest = asset.optString("digest", "");
                if (!digest.matches("sha256:[0-9a-fA-F]{64}")) digest = "";
                return new Result(new Release(version, url, size, digest), "Version " + version + " is available.");
            }
        }
        return new Result(null, "GitHub has a newer release without a compatible APK.");
    }

    private static int[] version(String value) {
        if (!value.matches("[vV]?[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) return null;
        return Arrays.stream(value.replaceFirst("^[vV]", "").split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

    static File download(Context context, Release release) throws Exception {
        if (storeBuild()) throw new IOException(storeUpdates());
        File directory = new File(context.getCacheDir(), "app-updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not prepare an update download");
        File[] oldFiles = directory.listFiles();
        if (oldFiles != null) for (File old : oldFiles) {
            if (old.lastModified() < System.currentTimeMillis() - CHECK_INTERVAL) old.delete();
        }
        File file = File.createTempFile("FluidMessages-", ".apk", directory);
        boolean verified = false;
        try {
            transfer(release, file);
            verify(context, file);
            verified = true;
            return file;
        } finally { if (!verified) file.delete(); }
    }

    private static void transfer(Release release, File file) throws Exception {
        URL url = release.download();
        for (int redirects = 0; redirects <= 4; redirects++) {
            if (!trusted(url, ASSET_HOSTS)) throw new IOException("Untrusted update download");
            var connection = connect(url);
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new IOException("Missing update redirect");
                    url = new URL(url, next);
                    continue;
                }
                if (code != 200) throw new IOException("GitHub could not download the APK");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long received = 0;
                try (var input = connection.getInputStream(); var output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[65536];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        received += count;
                        if (received > release.size()) throw new IOException("Update download is larger than expected");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
                if (received != release.size()) throw new IOException("Incomplete update download");
                var sha256 = new StringBuilder("sha256:");
                for (byte value : digest.digest()) sha256.append(String.format(Locale.ROOT, "%02x", value & 255));
                if (!release.digest().isEmpty() && !release.digest().equalsIgnoreCase(sha256.toString())) {
                    throw new IOException("Update download failed its integrity check");
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many update redirects");
    }

    private static void verify(Context context, File file) throws Exception {
        int flags = PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo current = installed(context, flags);
        PackageInfo update = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (update == null || !current.packageName.equals(update.packageName) || update.getLongVersionCode() <= current.getLongVersionCode()) {
            throw new IOException("The downloaded APK is not a newer FluidMessages build");
        }
        if (current.signingInfo == null || update.signingInfo == null || !sameOrRotated(
                signers(current.signingInfo.getApkContentsSigners()), signers(update.signingInfo.getApkContentsSigners()),
                update.signingInfo.hasPastSigningCertificates() ? signers(update.signingInfo.getSigningCertificateHistory()) : Set.of())) {
            throw new IOException("This release uses a different signing key. Use your original installation channel.");
        }
    }

    /**
     * The update is signed by this copy's key, or by a newer key that this copy's key handed over to (APK key rotation:
     * the update's signing history, its lineage, includes every key this copy is signed with). Android checks the
     * same thing when it installs.
     */
    static <T> boolean sameOrRotated(Set<T> current, Set<T> update, Set<T> updateHistory) {
        if (current.isEmpty() || update.isEmpty()) return false;
        return current.equals(update) || updateHistory.containsAll(current) && updateHistory.containsAll(update);
    }

    private static Set<Signature> signers(Signature[] signatures) {
        return new HashSet<>(Arrays.asList(signatures));
    }

    private static boolean trusted(URL url, Set<String> hosts) {
        return "https".equals(url.getProtocol()) && hosts.contains(url.getHost().toLowerCase(Locale.ROOT))
            && url.getUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443);
    }

    private static HttpsURLConnection connect(URL url) throws IOException {
        NetworkGate.requireInternet();
        var connection = (HttpsURLConnection) TorRoute.open(url);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "FluidMessages Android");
        return connection;
    }
}
