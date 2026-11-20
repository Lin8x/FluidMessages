package org.fluidmessages;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;

final class WallpaperPhotos {
    private static final String PREFIX = "wallpaper:";
    private static final int FILE_LIMIT = 8 * 1024 * 1024;
    private static final int TOTAL_LIMIT = 16 * 1024 * 1024;

    static boolean isPhoto(String value) {
        return value.startsWith("content://") || value.startsWith(PREFIX);
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "wallpapers");
    }

    static Uri source(Context context, String value) {
        if (value.startsWith("content://")) return Uri.parse(value);
        if (!value.matches("wallpaper:[a-f0-9]{64}")) return null;
        return Uri.fromFile(new File(directory(context), value.substring(PREFIX.length())));
    }

    static JSONObject export(Context context, String global, JSONObject profiles) throws Exception {
        JSONObject photos = new JSONObject();
        int remaining = store(context, photos, "global", global, TOTAL_LIMIT);
        JSONObject chats = new JSONObject();
        for (var keys = profiles.keys(); keys.hasNext();) {
            String address = keys.next();
            var profile = profiles.optJSONObject(address);
            if (profile != null) remaining = store(context, chats, address, profile.optString("wallpaper"), remaining);
        }
        if (chats.length() > 0) photos.put("chats", chats);
        return photos;
    }

    private static int store(Context context, JSONObject into, String key, String value, int remaining) throws Exception {
        if (!isPhoto(value)) return remaining;
        Uri uri = source(context, value);
        if (uri == null) throw new IOException("Reselect the missing wallpaper photo before backing it up");
        try (var stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("Could not read a wallpaper photo");
            int limit = Math.min(remaining, FILE_LIMIT);
            byte[] data = stream.readNBytes(limit + 1);
            if (data.length > limit) throw new IOException("Wallpaper photos exceed the backup size limit");
            into.put(key, Base64.encodeToString(data, Base64.NO_WRAP));
            return remaining - data.length;
        } catch (java.io.FileNotFoundException | SecurityException error) {
            throw new IOException("Reselect the missing wallpaper photo before backing it up", error);
        }
    }

    static JSONObject restore(Context context, JSONObject photos) throws Exception {
        JSONObject restored = new JSONObject();
        int remaining = install(context, restored, "global", photos.optString("global"), TOTAL_LIMIT);
        var chats = photos.optJSONObject("chats");
        if (chats != null) {
            JSONObject savedChats = new JSONObject();
            for (var keys = chats.keys(); keys.hasNext();) {
                String address = keys.next();
                remaining = install(context, savedChats, address, chats.optString(address), remaining);
            }
            restored.put("chats", savedChats);
        }
        return restored;
    }

    private static int install(Context context, JSONObject into, String key, String encoded, int remaining) throws Exception {
        if (encoded.isEmpty()) return remaining;
        int limit = Math.min(remaining, FILE_LIMIT);
        if (encoded.length() > ((limit + 2) / 3) * 4) throw new IOException("Wallpaper photos exceed the backup size limit");
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
        if (bytes.length > limit) throw new IOException("Wallpaper photos exceed the backup size limit");
        var bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid wallpaper photo");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder name = new StringBuilder();
        for (byte value : digest) name.append(Character.forDigit((value & 255) >>> 4, 16))
            .append(Character.forDigit(value & 15, 16));
        File directory = directory(context);
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Could not save wallpaper photos");
        File target = new File(directory, name.toString());
        if (!target.isFile()) {
            File temp = File.createTempFile("wallpaper-", ".tmp", directory);
            try {
                Files.write(temp.toPath(), bytes);
                if (!temp.renameTo(target)) throw new IOException("Could not save a wallpaper photo");
            } finally { temp.delete(); }
        }
        into.put(key, PREFIX + name);
        return remaining - bytes.length;
    }
}
