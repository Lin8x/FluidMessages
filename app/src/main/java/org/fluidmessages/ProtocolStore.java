package org.fluidmessages;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class ProtocolStore extends SQLiteOpenHelper {
    private static ProtocolStore instance;
    private static final int MAX_HISTORY = 2000;
    /** Bumped on every write, so cached message lists know when to reload. */
    static final java.util.concurrent.atomic.AtomicInteger revision = new java.util.concurrent.atomic.AtomicInteger();
    private final Context context;

    private ProtocolStore(Context context) {
        super(context, "protocol-history.db", null, 3);
        this.context = context;
    }

    static synchronized ProtocolStore get(Context context) {
        if (instance == null) instance = new ProtocolStore(context.getApplicationContext());
        return instance;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, date INTEGER NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX recent_messages ON messages (date DESC)");
        db.execSQL("CREATE TABLE hidden (id TEXT PRIMARY KEY, date INTEGER NOT NULL, sealed TEXT NOT NULL, expires INTEGER NOT NULL DEFAULT 0)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS hidden (id TEXT PRIMARY KEY, date INTEGER NOT NULL, sealed TEXT NOT NULL)");
            // Messages saved before encryption at rest are sealed in place.
            try (var cursor = db.query("messages", new String[]{"id", "payload"}, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    if (Privacy.sealed(cursor.getString(1))) continue;
                    ContentValues row = new ContentValues();
                    row.put("payload", Privacy.seal(prefs(), cursor.getString(1)));
                    db.update("messages", row, "id = ?", new String[]{cursor.getString(0)});
                }
            }
        }
        if (oldVersion < 3) {
            // When each hidden message disappears, so it can go while the private folder is closed. Only the time is
            // kept outside the sealed copy. Messages hidden earlier go the next time the folder is opened.
            db.execSQL("ALTER TABLE hidden ADD COLUMN expires INTEGER NOT NULL DEFAULT 0");
        }
    }

    private android.content.SharedPreferences prefs() { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    private JSONObject decode(String payload) throws Exception { return new JSONObject(Privacy.open(prefs(), payload)); }

    // Hidden chats: rows sealed to the vault key, readable only while hidden chats are open.

    void putHidden(JSONObject message) throws Exception {
        ContentValues row = new ContentValues();
        row.put("id", message.getString("id"));
        row.put("date", message.optLong("date"));
        row.put("sealed", Privacy.vaultSeal(prefs(), message.toString()));
        long expires = hiddenExpiry(message);
        row.put("expires", expires);
        getWritableDatabase().insertWithOnConflict("hidden", null, row, SQLiteDatabase.CONFLICT_REPLACE);
        revision.incrementAndGet();
        Disappearing.wakeBy(context, expires);
    }

    /** When a hidden message disappears, worked out now because the sealed copy can't be read later. */
    private long hiddenExpiry(JSONObject message) {
        try {
            var row = message;
            if (message.optString("body").startsWith(SmsCrypto.PREFIX)) {
                row = new JSONObject(message.toString());
                SmsCrypto.reveal(prefs(), row);
            }
            return Disappearing.expiry(prefs(), row, row.optString("address"));
        } catch (Exception error) { return 0; }
    }

    /** Deletes hidden messages whose time is up without opening them. Returns how many went. */
    int removeExpiredHidden(long now) {
        int gone = getWritableDatabase().delete("hidden", "expires > 0 AND expires <= ?", new String[]{Long.toString(now)});
        if (gone > 0) revision.incrementAndGet();
        return gone;
    }

    /** The next time a hidden message disappears, or 0. */
    long nextHiddenExpiry(long now) {
        try (var cursor = getReadableDatabase().rawQuery("SELECT MIN(expires) FROM hidden WHERE expires > ?",
                new String[]{Long.toString(now)})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0;
        }
    }

    List<JSONObject> readHidden() {
        var result = new ArrayList<JSONObject>();
        if (!Privacy.vaultOpen()) return result;
        try (var cursor = getReadableDatabase().query("hidden", new String[]{"sealed"}, null, null, null, null, "date DESC")) {
            while (cursor.moveToNext()) {
                try { result.add(new JSONObject(Privacy.vaultOpen(cursor.getString(0))).put("hidden", true)); }
                catch (Exception skipped) {}
            }
        }
        return result;
    }

    void removeHidden(String id) {
        getWritableDatabase().delete("hidden", "id = ?", new String[]{id});
        revision.incrementAndGet();
    }

    void put(JSONObject message) throws Exception {
        ContentValues row = new ContentValues();
        row.put("id", message.getString("id"));
        row.put("date", message.getLong("date"));
        row.put("payload", Privacy.seal(prefs(), message.toString()));
        getWritableDatabase().insertWithOnConflict("messages", null, row, SQLiteDatabase.CONFLICT_REPLACE);
        revision.incrementAndGet();
        Disappearing.track(context, message);
    }

    void putBatch(JSONArray rows) throws Exception {
        var db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < rows.length(); i++) put(rows.getJSONObject(i));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    JSONObject find(String id) throws Exception {
        try (var cursor = getReadableDatabase().query("messages", new String[]{"payload"}, "id = ?", new String[]{id}, null, null, null)) {
            return cursor.moveToFirst() ? decode(cursor.getString(0)) : null;
        }
    }

    List<JSONObject> read() throws Exception {
        var result = new ArrayList<JSONObject>();
        try (var cursor = getReadableDatabase().query("messages", new String[]{"payload"}, null, null, null, null,
                "date DESC, id DESC", Integer.toString(MAX_HISTORY))) {
            while (cursor.moveToNext()) result.add(decode(cursor.getString(0)));
        }
        return result;
    }

    void remove(String id) throws IOException {
        getWritableDatabase().delete("messages", "id = ?", new String[]{id});
        revision.incrementAndGet();
        // Ids that can't name a file never had one stored.
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) return;
        Files.deleteIfExists(photoFile(id).toPath());
        Files.deleteIfExists(attachmentFile(id).toPath());
    }

    void putPhoto(String id, byte[] photo) throws IOException {
        if (photo.length > 5 * 1024 * 1024) throw new IOException("Picture is too large");
        File file = photoFile(id);
        try (var output = new FileOutputStream(file)) { output.write(photo); }
    }

    File photoFile(String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Unexpected message identifier");
        File folder = new File(context.getFilesDir(), "protocol-photos");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Could not open photo storage");
        return new File(folder, id + ".jpg");
    }

    /** Stores a received or sent attachment under the message id. Size limits are enforced by the caller. */
    void putFile(String id, java.io.InputStream input, long limit) throws IOException {
        File file = attachmentFile(id);
        File partial = new File(file.getPath() + ".part");
        try (var output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int read; (read = input.read(buffer)) > 0; ) {
                total += read;
                if (total > limit) throw new IOException("Attachment is too large");
                output.write(buffer, 0, read);
            }
        } catch (IOException error) {
            Files.deleteIfExists(partial.toPath());
            throw error;
        }
        Files.move(partial.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    File attachmentFile(String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Unexpected message identifier");
        File folder = new File(context.getFilesDir(), "protocol-files");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("Could not open attachment storage");
        return new File(folder, id);
    }

    /** The local copy of a message's non-photo attachment, or null until it has been downloaded. */
    Uri file(JSONObject message) {
        if (message.optJSONObject("attachment") == null) return null;
        File file = attachmentFile(message.optString("id"));
        return file.isFile() ? FileProvider.getUriForFile(context, context.getPackageName() + ".backup-files", file) : null;
    }

    Uri photo(JSONObject message) {
        if (!message.optBoolean("photo")) return null;
        File file = photoFile(message.optString("id"));
        return file.isFile() ? FileProvider.getUriForFile(context, context.getPackageName() + ".backup-files", file) : null;
    }
}
