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
        "radius", "size", "padding", "savedColorThemes", "activeColorTheme",
        "protocolMATRIXColor", "protocolXMPPColor", "protocolEMAILColor", "protocolIRCColor",
        "protocolMATRIXColorSwatch", "protocolXMPPColorSwatch", "protocolEMAILColorSwatch", "protocolIRCColorSwatch"
    };
    // Protocol choices, links, and battery settings. Account passwords stay on the companion and are never backed up.
    static final String[] PROTOCOLS = {
        "protocolRCS", "protocolSMS", "protocolMMS", "protocolMATRIX", "protocolXMPP", "protocolEMAIL", "protocolIRC",
        "combineProtocols", "protocolTags", "protocolPrivacy", DisplayRules.TAGS, DisplayRules.ENCRYPTION, "stripMetadata", GatewayClient.ROUTES, "gatewayChannels",
        SyncPolicy.FOREGROUND, SyncPolicy.BACKGROUND, SyncPolicy.WIFI_ONLY, SyncPolicy.BATTERY_SAVER, SyncPolicy.DOWNLOADS
    };
    private static final long FILE_BUDGET = 24L * 1024 * 1024, FILE_LIMIT = 2L * 1024 * 1024;
    static final String[] EFFECTS = {"speed", "sound", "receiveSound", "haptics", "sendTone", "receiveTone", "sendHaptics",
        "soundVolume", "soundStream"};
    // "mutedUntil": when timed mutes end (Mutes). Older versions skip it when they restore, so those mutes become forever.
    static final String[] SETTINGS = {"pinned", "archived", "blocked", "muted", Mutes.UNTIL, "exactTimes", BackupNames.KEY, "automaticUpdateChecks"};
    private static final int MAX = 64 * 1024 * 1024;
    static final String SENDER_TYPES = "senderTypes";

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
        if (options.has(BackupCategory.CHAT_SETTINGS)) {
            copyPreferences(preferences, SETTINGS, settings);
            // Each chat's own sender type, by address. Older versions ignore this key when they restore.
            JSONObject senderTypes = SenderStyle.overrides(preferences);
            if (senderTypes.length() > 0) settings.put(SENDER_TYPES, senderTypes);
        }
        if (options.has(BackupCategory.CHAT_THEMES)) {
            for (var keys = profiles.keys(); keys.hasNext();) {
                JSONObject profile = profiles.optJSONObject(keys.next());
                if (profile != null && WallpaperPhotos.isPhoto(profile.optString("wallpaper"))) profile.remove("wallpaper");
            }
            settings.put("chatProfiles", profiles.toString());
        }
        if (options.has(BackupCategory.PROTOCOLS)) object.put("protocols", exportProtocols(context, preferences));
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
        if (options.has(BackupCategory.CHAT_SETTINGS)) {
            apply(editor, settings, SETTINGS, archivedCategory(object, BackupCategory.CHAT_SETTINGS));
            restoreSenderTypes(editor, settings);
        }
        JSONObject protocols = options.has(BackupCategory.PROTOCOLS) ? object.optJSONObject("protocols") : null;
        if (protocols != null) restored += restoreProtocols(context, protocols, editor);
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

    /**
     * MMS and internet message history, with small attachments embedded (up to 2 MB each and 24 MB in
     * all). Larger files are listed but stay on the companion or phone, and show as not downloaded.
     */
    private static JSONObject exportProtocols(Context context, SharedPreferences preferences) throws Exception {
        var store = ProtocolStore.get(context);
        var messages = new JSONArray();
        var files = new JSONObject();
        long budget = FILE_BUDGET;
        for (JSONObject message : store.read()) {
            messages.put(message);
            String id = message.getString("id");
            for (var file : new java.io.File[]{message.optBoolean("photo") ? store.photoFile(id) : null,
                    message.has("attachment") ? store.attachmentFile(id) : null}) {
                if (file == null || !file.isFile() || file.length() > FILE_LIMIT || file.length() > budget) continue;
                budget -= file.length();
                files.put((file.getName().endsWith(".jpg") ? "photo:" : "file:") + id,
                    android.util.Base64.encodeToString(java.nio.file.Files.readAllBytes(file.toPath()), android.util.Base64.NO_WRAP));
            }
        }
        JSONObject settings = new JSONObject();
        copyPreferences(preferences, PROTOCOLS, settings);
        for (var entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith("protocolRoute.") && entry.getValue() instanceof String value) settings.put(entry.getKey(), value);
        }
        return new JSONObject().put("messages", messages).put("files", files).put("settings", settings);
    }

    /** Adds messages that aren't on this phone yet; repeating a restore adds nothing new. */
    private static int restoreProtocols(Context context, JSONObject protocols, SharedPreferences.Editor editor) throws Exception {
        var store = ProtocolStore.get(context);
        var messages = protocols.optJSONArray("messages");
        var files = protocols.optJSONObject("files");
        int restored = 0;
        for (int i = 0; messages != null && i < messages.length(); i++) {
            var message = messages.optJSONObject(i);
            if (message == null || !message.optString("id").matches("[A-Za-z0-9_-]{1,100}") || !message.has("date")
                    || store.find(message.getString("id")) != null) continue;
            String id = message.getString("id");
            String photo = files == null ? "" : files.optString("photo:" + id);
            String file = files == null ? "" : files.optString("file:" + id);
            if (!photo.isEmpty()) store.putPhoto(id, android.util.Base64.decode(photo, android.util.Base64.NO_WRAP));
            if (!file.isEmpty()) try (var input = new ByteArrayInputStream(android.util.Base64.decode(file, android.util.Base64.NO_WRAP))) {
                store.putFile(id, input, FILE_LIMIT);
            }
            store.put(message);
            restored++;
        }
        var settings = protocols.optJSONObject("settings");
        apply(editor, settings, PROTOCOLS, false);
        if (settings != null) for (var keys = settings.keys(); keys.hasNext();) {
            String key = keys.next();
            if (key.startsWith("protocolRoute.") && key.length() < 200) editor.putString(key, settings.optString(key));
        }
        return restored;
    }

    /** Adds each chat's saved sender type; chats the backup doesn't mention keep theirs, and older backups change nothing. */
    private static void restoreSenderTypes(SharedPreferences.Editor editor, JSONObject settings) {
        JSONObject saved = settings == null ? null : settings.optJSONObject(SENDER_TYPES);
        if (saved == null) return;
        for (var keys = saved.keys(); keys.hasNext();) {
            String address = keys.next();
            String choice = SenderStyle.clean(saved.optString(address));
            if (!address.isEmpty() && address.length() < 200 && !choice.isEmpty()) editor.putString(SenderStyle.OVERRIDE + address, choice);
        }
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
            || containsAny(settings, SETTINGS) || settings != null && settings.has(SENDER_TYPES))) result.add(BackupCategory.CHAT_SETTINGS.getLabel());
        if (options.has(BackupCategory.CHAT_THEMES) && settings != null && settings.has("chatProfiles")) result.add(BackupCategory.CHAT_THEMES.getLabel());
        if (options.has(BackupCategory.WALLPAPER_PHOTOS) && photos != null) result.add(BackupCategory.WALLPAPER_PHOTOS.getLabel());
        var protocols = archive.optJSONObject("protocols");
        if (options.has(BackupCategory.PROTOCOLS) && protocols != null) {
            var messages = protocols.optJSONArray("messages");
            result.add((messages == null ? 0 : messages.length()) + " MMS and internet messages, with protocol settings");
        }
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
