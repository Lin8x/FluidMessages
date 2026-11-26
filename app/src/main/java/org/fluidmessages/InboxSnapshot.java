package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The inbox as it last looked, so it shows the moment it opens while the full list loads in the background.
 * Kept in memory for every folder, and on disk (sealed like stored messages) for the main inbox, so a cold start
 * shows it too. Before it's shown, each row is checked against today's hidden, archived, blocked, locked, and
 * disappearing state, so nothing appears that the full list wouldn't show.
 */
final class InboxSnapshot {
    /** One inbox row and what's needed to check it again later. */
    record Entry(ThreadRow row, String number, String protocol, long date, long expires) {}

    private static final int DISK_ROWS = 40;
    private static final String FILE = "inbox-snapshot";
    private static final Object lock = new Object();
    private static final java.util.Map<String, List<Entry>> memory = new java.util.HashMap<>();
    private static String written;

    private InboxSnapshot() {}

    /** Which inbox a snapshot belongs to. Searches, decoy mode, and the open private folder aren't kept. */
    static String key(SharedPreferences prefs, String folder, String filter) {
        if (filter != null && !filter.isBlank() || Privacy.decoyMode(prefs) || Privacy.vaultOpen()) return null;
        return folder + "\u001f" + prefs.getBoolean("combineProtocols", false);
    }

    static void remember(Context context, String key, List<Entry> entries) {
        if (key == null) return;
        synchronized (lock) {
            memory.put(key, List.copyOf(entries));
        }
        if (key.startsWith("Inbox\u001f")) save(context, key, entries);
    }

    /** Whether this inbox's rows are in memory, so {@link #recall} is quick enough for the main thread. */
    static boolean inMemory(String key) {
        synchronized (lock) { return key != null && memory.containsKey(key); }
    }

    /** The last rows for this inbox, checked against the current state, or an empty list. Reads the disk if needed. */
    static List<ThreadRow> recall(Context context, SharedPreferences prefs, String key, int photoRevision, LongFunction<String> time) {
        if (key == null) return List.of();
        List<Entry> entries;
        synchronized (lock) { entries = memory.get(key); }
        if (entries == null && key.startsWith("Inbox\u001f")) entries = load(context, prefs, key);
        if (entries == null) return List.of();
        String folder = key.substring(0, key.indexOf('\u001f'));
        Set<String> pinned = MainActivity.addressList(prefs, "pinned"), archived = MainActivity.addressList(prefs, "archived"),
            blocked = MainActivity.addressList(prefs, "blocked");
        long now = System.currentTimeMillis();
        var rows = new ArrayList<ThreadRow>();
        var pinnedRows = new ArrayList<ThreadRow>();
        for (Entry entry : entries) {
            String number = entry.number();
            var format = MessageProtocol.parse(entry.protocol());
            if (!shown(folder, archived.contains(number), blocked.contains(number), Privacy.hidden(prefs, number, format),
                    entry.expires(), now)) continue;
            var old = entry.row();
            String locked = Privacy.lockedPreview(prefs, number, format);
            String preview = locked != null ? locked : old.getPreview();
            boolean pin = pinned.contains(number);
            var row = new ThreadRow(old.getId(), old.getName(), old.getInitials(), preview, time.apply(entry.date()), old.getUnread(),
                pin, old.getPhoto(), old.getPhotoUri(), photoRevision, old.getProtocol(), old.getAutomated(), old.getMembers());
            (pin ? pinnedRows : rows).add(row);
        }
        pinnedRows.addAll(rows);
        return pinnedRows;
    }

    /** Whether a remembered row still belongs in this folder now. */
    static boolean shown(String folder, boolean archived, boolean blocked, boolean hidden, long expires, long now) {
        if (hidden || expires > 0 && expires <= now) return false;
        return switch (folder) {
            case "Archived" -> archived && !blocked;
            case "Blocked" -> blocked;
            case "Inbox" -> !archived && !blocked;
            // Unread changes with every message read, so a remembered list could be stale; it always loads fresh.
            default -> false;
        };
    }

    /** Forgets every snapshot, e.g. when everything is reloaded from scratch. */
    static void clear(Context context) {
        synchronized (lock) {
            memory.clear();
            written = null;
        }
        try { Files.deleteIfExists(file(context).toPath()); } catch (Exception ignored) {}
    }

    private static File file(Context context) { return new File(context.getNoBackupFilesDir(), FILE); }

    private static void save(Context context, String key, List<Entry> entries) {
        try {
            var array = new JSONArray();
            for (int i = 0; i < entries.size() && i < DISK_ROWS; i++) {
                var entry = entries.get(i);
                var row = entry.row();
                var members = new JSONArray();
                for (var member : row.getMembers()) {
                    members.put(new JSONObject().put("name", member.getName()).put("address", member.getAddress())
                        .put("photo", member.getPhoto() == null ? "" : member.getPhoto().toString()));
                }
                array.put(new JSONObject().put("id", row.getId()).put("name", row.getName()).put("initials", row.getInitials())
                    .put("preview", row.getPreview()).put("unread", row.getUnread())
                    .put("photo", row.getPhotoUri() == null ? "" : row.getPhotoUri().toString()).put("protocol", row.getProtocol())
                    .put("automated", row.getAutomated()).put("members", members).put("number", entry.number())
                    .put("format", entry.protocol()).put("date", entry.date()).put("expires", entry.expires()));
            }
            String content = new JSONObject().put("key", key).put("rows", array).toString();
            synchronized (lock) {
                if (content.equals(written)) return;
                written = content;
            }
            var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
            File target = file(context), partial = new File(target.getPath() + ".part");
            Files.write(partial.toPath(), Privacy.seal(prefs, content).getBytes(StandardCharsets.UTF_8));
            Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) { /* only a head start; the full inbox still loads */ }
    }

    private static List<Entry> load(Context context, SharedPreferences prefs, String key) {
        try {
            File source = file(context);
            if (!source.isFile() || source.length() > 2 * 1024 * 1024) return null;
            var stored = new JSONObject(Privacy.open(prefs, new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8)));
            if (!key.equals(stored.optString("key"))) return null;
            var array = stored.getJSONArray("rows");
            var entries = new ArrayList<Entry>();
            for (int i = 0; i < array.length(); i++) {
                var item = array.getJSONObject(i);
                var members = new ArrayList<GroupMember>();
                var list = item.optJSONArray("members");
                for (int m = 0; list != null && m < list.length(); m++) {
                    var member = list.getJSONObject(m);
                    members.add(new GroupMember(member.optString("name"), member.optString("address"), uri(member.optString("photo"))));
                }
                var row = new ThreadRow(item.getString("id"), item.optString("name"), item.optString("initials"),
                    item.optString("preview"), "", item.optBoolean("unread"), false, 0, uri(item.optString("photo")), 0,
                    item.optString("protocol"), item.optBoolean("automated"), members);
                entries.add(new Entry(row, item.getString("number"), item.optString("format"), item.optLong("date"), item.optLong("expires")));
            }
            synchronized (lock) {
                memory.putIfAbsent(key, List.copyOf(entries));
            }
            return entries;
        } catch (Exception unreadable) { return null; }
    }

    private static Uri uri(String value) { return value == null || value.isEmpty() ? null : Uri.parse(value); }
}
