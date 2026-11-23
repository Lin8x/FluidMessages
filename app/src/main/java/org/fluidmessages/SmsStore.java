package org.fluidmessages;

import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class SmsStore {
    private static final Uri SMS = Telephony.Sms.CONTENT_URI;
    private static final String[] COLUMNS = {"_id", "address", "body", "date", "type", "sub_id", "read", "date_sent"};

    static JSONArray read(Context context, int limit) throws Exception {
        JSONArray messages = new JSONArray();
        try (Cursor cursor = context.getContentResolver().query(SMS, COLUMNS, null, null, "date DESC, _id DESC")) {
            while (cursor != null && cursor.moveToNext() && (limit <= 0 || messages.length() < limit)) {
                String address = cursor.getString(1);
                if (address == null || address.isBlank()) continue;
                messages.put(new JSONObject()
                    .put("id", cursor.getLong(0)).put("address", address)
                    .put("body", cursor.getString(2) == null ? "" : cursor.getString(2))
                    .put("date", cursor.getLong(3)).put("type", cursor.getInt(4))
                    .put("sim", cursor.getInt(5)).put("read", cursor.getInt(6)).put("sentAt", cursor.getLong(7)));
            }
        }
        return messages;
    }

    static Uri insert(Context context, String address, String body, long date, int type, int sim, int read) {
        return insert(context, address, body, date, type, sim, read, 0);
    }

    static Uri insert(Context context, String address, String body, long date, int type, int sim, int read, long sentAt) {
        ContentValues values = new ContentValues();
        values.put("address", address);
        if (sentAt > 0) values.put("date_sent", sentAt);
        values.put("body", body);
        values.put("date", date);
        values.put("type", type);
        values.put("read", read);
        values.put("seen", read);
        values.put("sub_id", sim);
        Uri uri = context.getContentResolver().insert(SMS, values);
        if (uri == null) throw new IllegalStateException("Android could not save the message");
        return uri;
    }

    static void send(Context context, String address, String body, int sim) {
        MessageProtocol.SMS.requireEnabled(context);
        if (!validRecipient(address) || body == null || body.isBlank() || body.length() > 1600) {
            throw new IllegalArgumentException("Enter a phone number and 1–1,600 characters");
        }
        try { body = SmsCrypto.outgoing(context, address, MessageProtocol.SMS, body); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Could not encrypt the text", error); }
        if (sim < 0) sim = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (sim < 0) throw new IllegalStateException("Choose a SIM before sending");
        SmsManager manager = SmsManager.getSmsManagerForSubscriptionId(sim);
        ArrayList<String> parts = manager.divideMessage(body);
        Uri uri = insert(context, address, body, System.currentTimeMillis(), Telephony.Sms.MESSAGE_TYPE_OUTBOX, sim, 1);
        ArrayList<PendingIntent> callbacks = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            Intent result = new Intent(context, SmsResult.class)
                .setData(uri.buildUpon().appendQueryParameter("part", Integer.toString(index)).build())
                .putExtra("total", parts.size());
            callbacks.add(PendingIntent.getBroadcast(context, 0, result, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT));
        }
        try {
            manager.sendMultipartTextMessage(address, null, parts, callbacks, null);
        } catch (RuntimeException error) {
            updateType(context, uri, Telephony.Sms.MESSAGE_TYPE_FAILED);
            throw error;
        }
    }

    static List<String> recipients(String input) {
        var addresses = new LinkedHashMap<String, String>();
        for (String part : input.split("[,;\\n]")) {
            String address = part.trim();
            if (address.isEmpty()) continue;
            if (!validRecipient(address)) throw new IllegalArgumentException("Enter valid phone numbers");
            addresses.putIfAbsent(address.replaceAll("[(). -]", ""), address);
        }
        if (addresses.isEmpty()) throw new IllegalArgumentException("Enter a phone number");
        return List.copyOf(addresses.values());
    }

    static boolean validRecipient(String address) {
        return address != null && address.matches("[+0-9*#(). -]{1,40}") && address.matches(".*[0-9].*");
    }

    static void delete(Context context, long id, String address) {
        Uri item = android.content.ContentUris.withAppendedId(SMS, id);
        context.getContentResolver().delete(item, "address = ?", new String[]{address});
    }

    static void updateType(Context context, Uri uri, int type) {
        ContentValues values = new ContentValues();
        values.put("type", type);
        context.getContentResolver().update(uri, values, null, null);
    }
}
