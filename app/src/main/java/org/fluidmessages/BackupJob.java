package org.fluidmessages;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class BackupJob extends JobService {
    private static final String KEY = "fluid_backup_schedule";
    private static final int JOB = 201;
    private final java.util.Map<Integer, Thread> workers = new java.util.HashMap<>();
    private static final java.util.Set<CloudBackup.Target> activeCloud =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Integer> activeFolders = java.util.concurrent.ConcurrentHashMap.newKeySet();

    record FolderSchedule(CloudBackup.Target target) {
        static final FolderSchedule LOCAL = new FolderSchedule(null);
        int id() { return target == null ? JOB : 205 + target.ordinal(); }
        String key(String suffix, String local) { return target == null ? local : "phoneBackup." + target.id + "." + suffix; }
        String tree(SharedPreferences prefs) { return prefs.getString(key("tree", "backupTree"), ""); }
        int minutes(SharedPreferences prefs) {
            return target == null ? interval(prefs, null) : prefs.getInt(key("minutes", ""), 0);
        }
        int categories(SharedPreferences prefs) {
            return target == null ? BackupJob.categories(prefs, null) : prefs.getInt(key("categories", ""), 0);
        }
        boolean wifiOnly(SharedPreferences prefs) { return prefs.getBoolean(key("wifi", "backupWifi"), false); }
        boolean savedPassword(SharedPreferences prefs) { return !secret(prefs).isEmpty(); }
        long last(SharedPreferences prefs) { return prefs.getLong(key("last", "lastScheduledBackup"), 0); }
        private String secret(SharedPreferences prefs) { return prefs.getString(key("secret", "backupSecret"), ""); }
    }

    private static int cloudJobId(CloudBackup.Target target) { return 202 + target.ordinal(); }

    static String cloudKey(CloudBackup.Target target, String name) { return "cloudBackup." + target.id + "." + name; }

    static int interval(android.content.SharedPreferences prefs, CloudBackup.Target target) {
        String minutes = target == null ? "backupMinutes" : cloudKey(target, "minutes");
        String hours = target == null ? "backupHours" : cloudKey(target, "hours");
        return prefs.getInt(minutes, prefs.getInt(hours, 0) * 60);
    }

    private static String categoryKey(CloudBackup.Target target) {
        return target == null ? "backupCategories" : cloudKey(target, "categories");
    }

    private static String secretKey(CloudBackup.Target target) {
        return target == null ? "backupSecret" : cloudKey(target, "secret");
    }

    static int categories(SharedPreferences prefs, CloudBackup.Target target) {
        if (prefs.contains(categoryKey(target))) return prefs.getInt(categoryKey(target), 0);
        int categories = 0;
        if (prefs.getBoolean(target == null ? "backupSms" : cloudKey(target, "sms"), target == null)) {
            categories |= BackupCategory.SMS.getFlag() | BackupCategory.PROTOCOLS.getFlag();
        }
        if (prefs.getBoolean(target == null ? "backupTheme" : cloudKey(target, "theme"), true)) {
            categories |= BackupCategory.APPEARANCE.getFlag() | BackupCategory.EFFECTS.getFlag();
        }
        if (prefs.getBoolean(target == null ? "backupSettings" : cloudKey(target, "settings"), true)) {
            categories |= BackupCategory.CHAT_SETTINGS.getFlag() | BackupCategory.CHAT_THEMES.getFlag();
        }
        return categories;
    }

    static boolean hasSavedPassword(SharedPreferences prefs, CloudBackup.Target target) {
        return (target == null || prefs.getString(cloudKey(target, "origin"), "").equals(prefs.getString("server", "")))
            && !prefs.getString(secretKey(target), "").isEmpty();
    }

    private static String schedulePassword(SharedPreferences prefs, CloudBackup.Target target, String password) throws Exception {
        if (password == null) {
            if (!hasSavedPassword(prefs, target)) throw new IOException("Enter a backup password");
            return prefs.getString(secretKey(target), "");
        }
        return password.isEmpty() ? "" : seal(password);
    }

    private static long periodMillis(int minutes) {
        long period = minutes * 60_000L;
        if (period < JobInfo.getMinPeriodMillis() || minutes > 10_080) {
            throw new IllegalArgumentException("Choose an interval from 15 minutes to 7 days");
        }
        return period;
    }

    static void configureCloud(Context context, CloudBackup.Target target, Backup.Options options,
                               String password, int minutes, boolean wifiOnly) throws Exception {
        if (!CloudBackup.paired(context)) throw new IOException("Pair FluidMessages Web first");
        var prefs = context.getSharedPreferences("fluid", MODE_PRIVATE);
        String secret = schedulePassword(prefs, target, password);
        var request = new JobInfo.Builder(cloudJobId(target), new ComponentName(context, BackupJob.class))
            .setPersisted(true).setPeriodic(periodMillis(minutes))
            .setRequiredNetworkType(wifiOnly ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
            .build();
        if (context.getSystemService(JobScheduler.class).schedule(request) != JobScheduler.RESULT_SUCCESS) {
            throw new IOException("Unable to schedule " + target.label);
        }
        prefs.edit()
            .putString(cloudKey(target, "secret"), secret)
            .putString(cloudKey(target, "origin"), prefs.getString("server", ""))
            .putInt(categoryKey(target), options.categories())
            .putBoolean(cloudKey(target, "wifi"), wifiOnly)
            .putInt(cloudKey(target, "minutes"), minutes).remove(cloudKey(target, "hours")).apply();
    }

    static void cancelCloud(Context context, CloudBackup.Target target) {
        context.getSystemService(JobScheduler.class).cancel(cloudJobId(target));
        context.getSharedPreferences("fluid", MODE_PRIVATE).edit()
            .remove(cloudKey(target, "minutes")).remove(cloudKey(target, "hours")).remove(cloudKey(target, "secret")).apply();
    }

    static void runCloud(Context context, CloudBackup.Target target) throws Exception {
        var prefs = context.getSharedPreferences("fluid", MODE_PRIVATE);
        if (interval(prefs, target) == 0) throw new IOException("Enable a schedule first");
        String expectedOrigin = prefs.getString(cloudKey(target, "origin"), "");
        if (expectedOrigin.isEmpty() || !expectedOrigin.equals(prefs.getString("server", ""))) {
            throw new IOException("Update this backup schedule for the current FluidMessages Web");
        }
        var options = new Backup.Options(categories(prefs, target));
        runCloud(context, target, options, unseal(prefs.getString(secretKey(target), "")), expectedOrigin);
    }

    static void runCloud(Context context, CloudBackup.Target target, Backup.Options options,
                         String password, String expectedOrigin) throws Exception {
        var prefs = context.getSharedPreferences("fluid", MODE_PRIVATE);
        if (!activeCloud.add(target)) throw new IOException("A backup to " + target.label + " is already running");
        try {
            requireSmsRole(context, options);
            var file = java.io.File.createTempFile("fluid-cloud-", ".fmb", context.getCacheDir());
            try {
                Backup.write(context, Uri.fromFile(file), options, password);
                CloudBackup.upload(context, target, file, expectedOrigin, BackupNames.fixed(prefs));
                prefs.edit().putLong(cloudKey(target, "last"), System.currentTimeMillis()).apply();
            } finally { file.delete(); }
        } finally { activeCloud.remove(target); }
    }

    private static void requireSmsRole(Context context, Backup.Options options) throws IOException {
        if (options.messages() && !context.getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_SMS)) {
            throw new IOException("FluidMessages needs to be your texting app");
        }
    }

    static void configureFolder(Context context, FolderSchedule folder, Uri tree, Backup.Options options,
                                String password, int minutes, boolean wifiOnly) throws Exception {
        var prefs = context.getSharedPreferences("fluid", MODE_PRIVATE);
        if (password == null && !folder.savedPassword(prefs)) throw new IOException("Enter a backup password");
        String savedPassword = password == null ? folder.secret(prefs) : password.isEmpty() ? "" : seal(password);
        String previous = folder.tree(prefs);
        scheduleFolder(context, folder, minutes, wifiOnly);
        prefs.edit().putString(folder.key("tree", "backupTree"), tree.toString())
            .putString(folder.key("secret", "backupSecret"), savedPassword)
            .putInt(folder.key("categories", "backupCategories"), options.categories())
            .putInt(folder.key("minutes", "backupMinutes"), minutes)
            .putBoolean(folder.key("wifi", "backupWifi"), wifiOnly).apply();
        if (!previous.isEmpty() && !tree.toString().equals(previous)) release(context, Uri.parse(previous));
    }

    private static void scheduleFolder(Context context, FolderSchedule folder, int minutes, boolean wifiOnly) throws IOException {
        int network = wifiOnly ? JobInfo.NETWORK_TYPE_UNMETERED : folder.target() == null
            ? JobInfo.NETWORK_TYPE_NONE : JobInfo.NETWORK_TYPE_ANY;
        var request = new JobInfo.Builder(folder.id(), new ComponentName(context, BackupJob.class))
            .setPersisted(true).setPeriodic(periodMillis(minutes))
            .setRequiredNetworkType(network)
            .build();
        if (context.getSystemService(JobScheduler.class).schedule(request) != JobScheduler.RESULT_SUCCESS) {
            throw new IOException("Unable to schedule backup");
        }
    }

    static void cancelFolder(Context context, FolderSchedule folder) {
        var prefs = context.getSharedPreferences("fluid", MODE_PRIVATE);
        context.getSystemService(JobScheduler.class).cancel(folder.id());
        String tree = folder.tree(prefs);
        prefs.edit().remove(folder.key("tree", "backupTree")).remove(folder.key("secret", "backupSecret")).apply();
        if (!tree.isEmpty()) release(context, Uri.parse(tree));
    }

    static void release(Context context, Uri tree) {
        var prefs = context.getSharedPreferences("fluid", MODE_PRIVATE);
        if (tree.toString().equals(FolderSchedule.LOCAL.tree(prefs))) return;
        for (CloudBackup.Target target : CloudBackup.Target.values()) {
            if (tree.toString().equals(new FolderSchedule(target).tree(prefs))) return;
        }
        var resolver = context.getContentResolver();
        for (var permission : resolver.getPersistedUriPermissions()) {
            if (!permission.getUri().equals(tree)) continue;
            int flags = 0;
            if (permission.isReadPermission()) flags |= android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) flags |= android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try { resolver.releasePersistableUriPermission(tree, flags); }
            catch (SecurityException ignored) {}
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        Thread worker = new Thread(() -> {
            boolean retry = false;
            try {
                if (params.getJobId() == JOB) runFolder(this, FolderSchedule.LOCAL);
                else for (CloudBackup.Target target : CloudBackup.Target.values()) {
                    var folder = new FolderSchedule(target);
                    if (params.getJobId() == cloudJobId(target)) {
                        runCloud(this, target);
                        break;
                    }
                    if (params.getJobId() == folder.id()) {
                        runFolder(this, folder);
                        break;
                    }
                }
            } catch (Exception error) {
                retry = true;
            }
            boolean shouldRetry = retry;
            Thread finished = Thread.currentThread();
            getMainExecutor().execute(() -> {
                if (workers.get(params.getJobId()) == finished) {
                    workers.remove(params.getJobId());
                    jobFinished(params, shouldRetry);
                }
            });
        }, "fluid-scheduled-backup");
        workers.put(params.getJobId(), worker);
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Thread worker = workers.remove(params.getJobId());
        if (worker != null) worker.interrupt();
        return true;
    }

    static void runFolder(Context context, FolderSchedule folder) throws Exception {
        if (!activeFolders.add(folder.id())) throw new IOException("A backup to this folder is already running");
        try { writeToFolder(context, folder); }
        finally { activeFolders.remove(folder.id()); }
    }

    private static void writeToFolder(Context context, FolderSchedule folder) throws Exception {
        var prefs = context.getSharedPreferences("fluid", MODE_PRIVATE);
        String selected = folder.tree(prefs);
        if (selected.isEmpty()) throw new IOException("Choose a backup folder first");
        var options = new Backup.Options(folder.categories(prefs));
        requireSmsRole(context, options);
        String password = unseal(folder.secret(prefs));
        Uri tree = Uri.parse(selected);
        var resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String fixed = BackupNames.fixed(prefs);
        String resultName = fixed.isEmpty() ? BackupNames.suggest(prefs) : fixed;
        Uri previous = fixed.isEmpty() ? null : namedDocument(context, tree, parent, fixed);
        String stagingName = previous == null ? resultName : "FluidMessages_pending_" + java.util.UUID.randomUUID() + ".tmp";
        Uri file = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", stagingName);
        if (file == null) throw new IOException("Unable to create backup file");
        Uri renamedPrevious = null;
        try {
            Backup.write(context, file, options, password);
            if (previous != null) {
                renamedPrevious = DocumentsContract.renameDocument(resolver, previous,
                    "FluidMessages_previous_" + java.util.UUID.randomUUID() + ".fmb");
                if (renamedPrevious == null) throw new IOException("This folder does not support replacing backups");
                if (DocumentsContract.renameDocument(resolver, file, resultName) == null) {
                    throw new IOException("Unable to replace the existing backup");
                }
            }
            prefs.edit().putLong(folder.key("last", "lastScheduledBackup"), System.currentTimeMillis()).apply();
        } catch (Exception error) {
            if (renamedPrevious != null) {
                try { DocumentsContract.renameDocument(resolver, renamedPrevious, resultName); }
                catch (Exception ignored) {}
            }
            try { DocumentsContract.deleteDocument(resolver, file); }
            catch (Exception ignored) {}
            throw error;
        }
        if (renamedPrevious != null) {
            try { DocumentsContract.deleteDocument(resolver, renamedPrevious); }
            catch (Exception ignored) {}
        }
    }

    private static Uri namedDocument(Context context, Uri tree, Uri parent, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (var cursor = context.getContentResolver().query(children, columns, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
            }
        }
        return null;
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(KEY, null) instanceof SecretKey existing) return existing;
        var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }

    private static String seal(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
            + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String unseal(String value) throws Exception {
        if (value.isEmpty()) return "";
        String[] parts = value.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
