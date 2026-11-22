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
    private final Context context;

    private ProtocolStore(Context context) {
        super(context, "protocol-history.db", null, 1);
        this.context = context;
    }

    static synchronized ProtocolStore get(Context context) {
        if (instance == null) instance = new ProtocolStore(context.getApplicationContext());
        return instance;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, date INTEGER NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX recent_messages ON messages (date DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    void put(JSONObject message) throws Exception {
        ContentValues row = new ContentValues();
        row.put("id", message.getString("id"));
        row.put("date", message.getLong("date"));
        row.put("payload", message.toString());
        getWritableDatabase().insertWithOnConflict("messages", null, row, SQLiteDatabase.CONFLICT_REPLACE);
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
            return cursor.moveToFirst() ? new JSONObject(cursor.getString(0)) : null;
        }
    }

    List<JSONObject> read() throws Exception {
        var result = new ArrayList<JSONObject>();
        try (var cursor = getReadableDatabase().query("messages", new String[]{"payload"}, null, null, null, null,
                "date DESC, id DESC", Integer.toString(MAX_HISTORY))) {
            while (cursor.moveToNext()) result.add(new JSONObject(cursor.getString(0)));
        }
        return result;
    }

    void remove(String id) throws IOException {
        getWritableDatabase().delete("messages", "id = ?", new String[]{id});
        Files.deleteIfExists(photoFile(id).toPath());
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

    Uri photo(JSONObject message) {
        if (!message.optBoolean("photo")) return null;
        File file = photoFile(message.optString("id"));
        return file.isFile() ? FileProvider.getUriForFile(context, context.getPackageName() + ".backup-files", file) : null;
    }
}
