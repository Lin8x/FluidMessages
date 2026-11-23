package org.fluidmessages;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Saves call recordings. Voice recordings go to the phone's Recordings folder and video to Movies (each in a
 * FluidMessages folder), where other apps can play them, or only inside FluidMessages if you choose.
 */
final class CallRecordings {
    static final String DEVICE = "device";
    static final String APP = "app";
    private static File partial;
    private static FileOutputStream output;
    private static String mime = "audio/webm";
    private static boolean video;
    private static String title = "Call";

    private CallRecordings() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    static boolean allowed(SharedPreferences prefs) { return prefs.getBoolean("recordCalls", true); }

    static String destination(SharedPreferences prefs, boolean video) {
        return prefs.getString(video ? "recordings.video" : "recordings.audio", DEVICE);
    }

    static synchronized void start(Context context, String type, boolean withVideo, String name) throws IOException {
        stopQuietly();
        mime = type == null || type.isEmpty() ? (withVideo ? "video/webm" : "audio/webm") : type.split(";")[0];
        video = withVideo;
        title = name == null || name.isBlank() ? "Call" : name.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        partial = File.createTempFile("recording-", ".webm", context.getCacheDir());
        output = new FileOutputStream(partial);
    }

    static synchronized void append(String base64) throws IOException {
        if (output != null && base64 != null && !base64.isEmpty()) output.write(Base64.decode(base64, Base64.DEFAULT));
    }

    /** Finishes the recording and saves it where Settings says. Returns a short description of where it went. */
    static synchronized String stop(Context context) throws IOException {
        if (output == null || partial == null) return "";
        output.close();
        output = null;
        File file = partial;
        partial = null;
        try {
            if (file.length() == 0) return "";
            String name = (video ? "Video call with " : "Call with ") + title + " "
                + new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(new Date()) + ".webm";
            if (DEVICE.equals(destination(prefs(context), video))) {
                var values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_RECORDINGS) + "/FluidMessages");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                var collection = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                var uri = context.getContentResolver().insert(collection, values);
                if (uri == null) throw new IOException("Android couldn't save the recording");
                try (var target = context.getContentResolver().openOutputStream(uri)) {
                    if (target == null) throw new IOException("Android couldn't save the recording");
                    Files.copy(file.toPath(), target);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
                return video ? "Movies/FluidMessages" : "Recordings/FluidMessages";
            }
            File folder = folder(context);
            Files.copy(file.toPath(), new File(folder, name).toPath());
            return "FluidMessages";
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    private static void stopQuietly() {
        try { if (output != null) output.close(); } catch (IOException ignored) {}
        if (partial != null) partial.delete();
        output = null;
        partial = null;
    }

    static File folder(Context context) {
        File folder = new File(context.getFilesDir(), "call-recordings");
        if (!folder.isDirectory()) folder.mkdirs();
        return folder;
    }

    /** Recordings kept only inside FluidMessages, newest first. */
    static List<File> inApp(Context context) {
        var files = folder(context).listFiles();
        var list = new ArrayList<File>(files == null ? List.of() : Arrays.asList(files));
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        return list;
    }
}
