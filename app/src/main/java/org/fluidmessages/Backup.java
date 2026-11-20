package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class Backup {
    static final String[] THEME = {
        "colorTheme", "classicColors", "contrastColors", "dark",
        "accent", "rcsColor", "iphoneColor", "automatedColor", "canvas", "incoming", "controlColor", "chromeColor",
        "accentSwatch", "rcsColorSwatch", "iphoneColorSwatch", "automatedColorSwatch",
        "canvasSwatch", "incomingSwatch", "controlColorSwatch", "chromeColorSwatch",
        "wallpaper", "searchPosition", "toolbarFinish", "chromeOpacity", "inboxDensity", "bubbleTails",
        "radius", "size", "padding", "savedColorThemes", "activeColorTheme"
    };
    static final String[] EFFECTS = {"speed", "sound", "receiveSound", "haptics", "sendTone", "receiveTone", "sendHaptics"};
    static final String[] SETTINGS = {"pinned", "archived", "blocked", "muted", "exactTimes", "senderCategories"};
    private static final int MAX = 64 * 1024 * 1024;

    record Options(int categories) {
        boolean has(BackupCategory category) { return (categories & category.getFlag()) != 0; }
        boolean messages() { return has(BackupCategory.SMS); }
    }

    static synchronized void write(Context context, Uri uri, Options options, String password) throws Exception {
        JSONObject object = new JSONObject().put("version", 1).put("categories", options.categories());
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        if (options.messages()) object.put("sms", SmsStore.read(context, 0));
        JSONObject theme = new JSONObject();
        JSONObject settings = new JSONObject();
        String wallpaper = preferences.getString("wallpaper", "none");
        JSONObject profiles = parseProfiles(preferences.getString("chatProfiles", "{}"));
        if (options.has(BackupCategory.WALLPAPER_PHOTOS)) {
            var photos = WallpaperPhotos.export(context, wallpaper, profiles);
            if (photos.length() > 0) object.put("wallpaperPhotos", photos);
        }
        if (options.has(BackupCategory.APPEARANCE)) {
            copyPreferences(preferences, THEME, theme);
            if (WallpaperPhotos.isPhoto(wallpaper)) theme.remove("wallpaper");
            else theme.put("wallpaper", wallpaper);
        }
        if (options.has(BackupCategory.EFFECTS)) copyPreferences(preferences, EFFECTS, theme);
        if (options.has(BackupCategory.CHAT_SETTINGS)) copyPreferences(preferences, SETTINGS, settings);
        if (options.has(BackupCategory.CHAT_THEMES)) {
            for (var keys = profiles.keys(); keys.hasNext();) {
                JSONObject profile = profiles.optJSONObject(keys.next());
                if (profile != null && WallpaperPhotos.isPhoto(profile.optString("wallpaper"))) profile.remove("wallpaper");
            }
            settings.put("chatProfiles", profiles.toString());
        }
        if (options.has(BackupCategory.APPEARANCE) || options.has(BackupCategory.EFFECTS)) object.put("theme", theme);
        if (options.has(BackupCategory.CHAT_SETTINGS) || options.has(BackupCategory.CHAT_THEMES)) object.put("settings", settings);
        byte[] json = object.toString().getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX) throw new IOException("Expanded backup is too large");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (var zip = new GZIPOutputStream(compressed)) {
            zip.write(json);
        }
        if (compressed.size() > MAX - (password.isEmpty() ? 0 : 16)) throw new IOException("Backup is too large");
        try (var stream = "file".equals(uri.getScheme()) ? new FileOutputStream(uri.getPath())
                : context.getContentResolver().openOutputStream(uri, "wt");
             var output = new DataOutputStream(stream)) {
            output.writeInt(0x464D4231);
            output.writeBoolean(!password.isEmpty());
            if (password.isEmpty()) {
                compressed.writeTo(output);
            } else {
                var random = new SecureRandom();
                byte[] salt = new byte[16];
                byte[] nonce = new byte[12];
                random.nextBytes(salt);
                random.nextBytes(nonce);
                output.write(salt);
                output.write(nonce);
                output.write(cipher(Cipher.ENCRYPT_MODE, password, salt, nonce).doFinal(compressed.toByteArray()));
            }
        }
    }

    static JSONObject read(Context context, Uri uri, String password) throws Exception {
        try (var stream = "file".equals(uri.getScheme()) ? new FileInputStream(uri.getPath())
                : context.getContentResolver().openInputStream(uri);
             var input = new DataInputStream(stream)) {
            if (input.readInt() != 0x464D4231) throw new IOException("This is not a FluidMessages backup");
            boolean encrypted = input.readBoolean();
            byte[] salt = encrypted ? input.readNBytes(16) : null;
            byte[] nonce = encrypted ? input.readNBytes(12) : null;
            byte[] bytes = input.readNBytes(MAX + 1);
            if (bytes.length > MAX) throw new IOException("Backup is too large");
            if (encrypted) bytes = cipher(Cipher.DECRYPT_MODE, password, salt, nonce).doFinal(bytes);
            try (var zip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                byte[] json = zip.readNBytes(MAX + 1);
                if (json.length > MAX) throw new IOException("Expanded backup is too large");
                JSONObject object = new JSONObject(new String(json, StandardCharsets.UTF_8));
                if (object.getInt("version") != 1) throw new IOException("Unsupported backup version");
                return object;
            }
        }
    }

    static synchronized int restore(Context context, JSONObject object, Options options) throws Exception {
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        JSONObject photos = options.has(BackupCategory.WALLPAPER_PHOTOS) ? object.optJSONObject("wallpaperPhotos") : null;
        JSONObject restoredPhotos = photos == null ? new JSONObject() : WallpaperPhotos.restore(context, photos);
        int restored = 0;
        JSONArray saved = options.messages() ? object.optJSONArray("sms") : null;
        if (saved != null) {
            JSONArray existing = SmsStore.read(context, 0);
            var keys = new HashSet<String>();
            for (int index = 0; index < existing.length(); index++) keys.add(key(existing.getJSONObject(index)));
            for (int index = 0; index < saved.length(); index++) {
                JSONObject message = saved.getJSONObject(index);
                String key = key(message);
                int type = message.getInt("type");
                if (type < 1 || type > 6 || keys.contains(key)) continue;
                SmsStore.insert(context, message.getString("address"), message.getString("body"),
                    message.getLong("date"), type, -1, message.optInt("read", 1), message.optLong("sentAt", 0));
                keys.add(key);
                restored++;
            }
        }
        var editor = preferences.edit();
        JSONObject theme = object.optJSONObject("theme");
        JSONObject settings = object.optJSONObject("settings");
        if (options.has(BackupCategory.APPEARANCE) && theme != null) {
            for (String key : new String[]{"accent", "rcsColor", "iphoneColor", "automatedColor",
                    "canvas", "incoming", "controlColor", "chromeColor"}) {
                if (theme.opt(key) instanceof Integer) editor.remove(key + "Swatch");
                if (theme.opt(key + "Swatch") instanceof String) editor.remove(key);
            }
            apply(editor, theme, THEME, archivedCategory(object, BackupCategory.APPEARANCE));
        }
        if (options.has(BackupCategory.EFFECTS) && theme != null) {
            if (theme.opt("sound") instanceof Boolean && !theme.has("sendTone")) editor.remove("sendTone");
            if (theme.opt("receiveSound") instanceof Boolean && !theme.has("receiveTone")) editor.remove("receiveTone");
            if (theme.opt("haptics") instanceof Boolean && !theme.has("sendHaptics")) editor.remove("sendHaptics");
            apply(editor, theme, EFFECTS, archivedCategory(object, BackupCategory.EFFECTS));
        }
        if (options.has(BackupCategory.CHAT_SETTINGS)) apply(editor, settings, SETTINGS, archivedCategory(object, BackupCategory.CHAT_SETTINGS));
        if (restoredPhotos.has("global")) editor.putString("wallpaper", restoredPhotos.getString("global"));
        String savedProfiles = options.has(BackupCategory.CHAT_THEMES) && settings != null
            ? settings.optString("chatProfiles") : "";
        JSONObject restoredChatPhotos = restoredPhotos.optJSONObject("chats");
        if (!savedProfiles.isEmpty() || restoredChatPhotos != null) {
            JSONObject current = parseProfiles(preferences.getString("chatProfiles", "{}"));
            JSONObject archived = parseProfiles(savedProfiles);
            for (var keys = archived.keys(); keys.hasNext();) {
                String address = keys.next();
                JSONObject profile = archived.optJSONObject(address);
                if (profile == null) continue;
                JSONObject prior = current.optJSONObject(address);
                if (!profile.has("wallpaper") && prior != null && prior.has("wallpaper")) {
                    profile.put("wallpaper", prior.getString("wallpaper"));
                }
                current.put(address, profile);
            }
            if (restoredChatPhotos != null) {
                for (var keys = restoredChatPhotos.keys(); keys.hasNext();) {
                    String address = keys.next();
                    JSONObject profile = current.optJSONObject(address);
                    if (profile == null) profile = new JSONObject();
                    current.put(address, profile.put("wallpaper", restoredChatPhotos.getString(address)));
                }
            }
            editor.putString("chatProfiles", current.toString());
        }
        editor.apply();
        return restored;
    }

    private static String key(JSONObject message) throws Exception {
        return new JSONArray().put(message.getString("address")).put(message.getString("body"))
            .put(message.getLong("date")).put(message.getInt("type")).toString();
    }

    private static void copyPreferences(SharedPreferences preferences, String[] keys, JSONObject result) throws Exception {
        var stored = preferences.getAll();
        for (String key : keys) if (stored.containsKey(key)) result.put(key, stored.get(key));
    }

    private static JSONObject parseProfiles(String value) {
        try { return new JSONObject(value); }
        catch (org.json.JSONException error) { return new JSONObject(); }
    }

    static java.util.List<String> summary(JSONObject archive, Options options) {
        var result = new java.util.ArrayList<String>();
        var sms = archive.optJSONArray("sms");
        var theme = archive.optJSONObject("theme");
        var settings = archive.optJSONObject("settings");
        var photos = archive.optJSONObject("wallpaperPhotos");
        if (options.messages() && sms != null) result.add(sms.length() + " text messages");
        if (options.has(BackupCategory.APPEARANCE) && (archivedCategory(archive, BackupCategory.APPEARANCE)
            || containsAny(theme, THEME))) result.add(BackupCategory.APPEARANCE.getLabel());
        if (options.has(BackupCategory.EFFECTS) && (archivedCategory(archive, BackupCategory.EFFECTS)
            || containsAny(theme, EFFECTS))) result.add(BackupCategory.EFFECTS.getLabel());
        if (options.has(BackupCategory.CHAT_SETTINGS) && (archivedCategory(archive, BackupCategory.CHAT_SETTINGS)
            || containsAny(settings, SETTINGS))) result.add(BackupCategory.CHAT_SETTINGS.getLabel());
        if (options.has(BackupCategory.CHAT_THEMES) && settings != null && settings.has("chatProfiles")) result.add(BackupCategory.CHAT_THEMES.getLabel());
        if (options.has(BackupCategory.WALLPAPER_PHOTOS) && photos != null) result.add(BackupCategory.WALLPAPER_PHOTOS.getLabel());
        return result;
    }

    private static boolean containsAny(JSONObject object, String[] keys) {
        if (object != null) for (String key : keys) if (object.has(key)) return true;
        return false;
    }

    private static boolean archivedCategory(JSONObject archive, BackupCategory category) {
        return (archive.optInt("categories", 0) & category.getFlag()) != 0;
    }

    private static void apply(SharedPreferences.Editor editor, JSONObject data, String[] keys, boolean resetMissing) {
        if (data == null) return;
        for (String key : keys) {
            Object value = data.opt(key);
            if (value instanceof Integer number) editor.putInt(key, number);
            if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            if (value instanceof String text) editor.putString(key, text);
            if (value == null && resetMissing && !key.equals("wallpaper")) editor.remove(key);
        }
    }

    private static Cipher cipher(int mode, String password, byte[] salt, byte[] nonce) throws Exception {
        var spec = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return cipher;
        } finally {
            spec.clearPassword();
        }
    }
}
