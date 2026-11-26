package org.fluidmessages

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The envelopes this phone holds for Nearby (NearbyMesh): channel messages, room messages, and sealed one-to-one
 * messages it carries for others, plus your own until they're old. Capped by NearbyRelay (2,000 envelopes, 8 MB,
 * 7 days). Channel words are public by design and the rest are encrypted, so nothing here is readable that isn't
 * meant to be; what you read in a channel you joined is also kept in your messages, sealed like other chats.
 * Not in backups: it's a passing-along cache, and it's cleared with Remove or Erase everything.
 */
internal class NearbyRelayStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "nearby-relay.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE envelopes (id TEXT PRIMARY KEY, created INTEGER NOT NULL, kind INTEGER NOT NULL, " +
            "tag TEXT NOT NULL, hops INTEGER NOT NULL, size INTEGER NOT NULL, own INTEGER NOT NULL, origin TEXT NOT NULL DEFAULT '', " +
            "payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX envelopes_created ON envelopes (created DESC)")
        db.execSQL("CREATE INDEX envelopes_origin ON envelopes (origin)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized fun has(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM envelopes WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    /** Keeps [envelope]; [hops] is how far it may still go from here (0 keeps it only to recognize it again). */
    @Synchronized fun put(envelope: Envelope, hops: Int, own: Boolean, origin: String = "") {
        val row = ContentValues().apply {
            put("id", envelope.id); put("created", envelope.created); put("kind", envelope.kind); put("tag", envelope.tag)
            put("hops", hops); put("size", envelope.size); put("own", if (own) 1 else 0); put("origin", origin)
            put("payload", NearbyMesh.encode(envelope.copy(hops = hops)))
        }
        writableDatabase.insertWithOnConflict("envelopes", null, row, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized fun get(id: String): Envelope? =
        readableDatabase.rawQuery("SELECT payload FROM envelopes WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) runCatching { NearbyMesh.decode(it.getBlob(0)) }.getOrNull() else null
        }

    /** The envelope made for one of your waiting messages, so it's sealed and handed on only once. */
    @Synchronized fun byOrigin(origin: String): Envelope? =
        readableDatabase.rawQuery("SELECT payload FROM envelopes WHERE origin = ?", arrayOf(origin)).use {
            if (it.moveToFirst()) runCatching { NearbyMesh.decode(it.getBlob(0)) }.getOrNull() else null
        }

    @Synchronized fun items(): List<NearbyRelay.Stored> =
        readableDatabase.rawQuery("SELECT id, created, size, own FROM envelopes ORDER BY created DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(NearbyRelay.Stored(cursor.getString(0), cursor.getLong(1), cursor.getInt(2), cursor.getInt(3) == 1)) }
        }

    @Synchronized fun hops(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT id, hops FROM envelopes", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1)) }
        }

    /** Open channels seen nearby lately: name → how many messages, busiest first. */
    @Synchronized fun channels(): List<Pair<String, Int>> {
        val counts = HashMap<String, Pair<String, Int>>()
        readableDatabase.rawQuery("SELECT payload FROM envelopes WHERE kind = ${Envelope.CHANNEL}", null).use { cursor ->
            while (cursor.moveToNext()) {
                val envelope = runCatching { NearbyMesh.decode(cursor.getBlob(0)) }.getOrNull() ?: continue
                val name = NearbyMesh.cleanChannel(envelope.channel) ?: continue
                val previous = counts[envelope.tag]
                counts[envelope.tag] = (previous?.first ?: name) to ((previous?.second ?: 0) + 1)
            }
        }
        return counts.values.sortedByDescending { it.second }
    }

    @Synchronized fun bytes(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(size), 0) FROM envelopes", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0
    }

    @Synchronized fun prune(now: Long) {
        val removed = NearbyRelay.prune(items(), now)
        if (removed.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try { for (id in removed) db.delete("envelopes", "id = ?", arrayOf(id)); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    /** Forgets everything carried for others (Clear on the Nearby page). Your own stay until they're old. */
    @Synchronized fun clearOthers() { writableDatabase.delete("envelopes", "own = 0", null) }

    companion object {
        @Volatile private var instance: NearbyRelayStore? = null
        @JvmStatic fun get(context: Context): NearbyRelayStore =
            instance ?: synchronized(this) { instance ?: NearbyRelayStore(context.applicationContext).also { instance = it } }
    }
}
