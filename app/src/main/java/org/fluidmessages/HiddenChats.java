package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Moves hidden text conversations out of Android's shared SMS store into the encrypted vault, and back.
 * Other apps, Android's own search, and backups made by other apps never see a hidden text once it moves.
 */
final class HiddenChats {
    private HiddenChats() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("fluid", Context.MODE_PRIVATE); }

    /** A phone number matches however it's written, so "+1 555…" and "555…" hide together. */
    static boolean addressHidden(SharedPreferences prefs, String address) {
        if (!Privacy.vaultExists(prefs) || address == null) return false;
        for (String hidden : Privacy.hiddenAddresses(prefs)) {
            if (hidden.equals(address)) return true;
            if (SmsStore.validRecipient(hidden) && SmsStore.validRecipient(address) && PhoneNumberUtils.compare(hidden, address)) return true;
        }
        return false;
    }

    static boolean rowHidden(SharedPreferences prefs, JSONObject row) {
        if (row.optBoolean("hidden")) return true;
        if (!Privacy.vaultExists(prefs)) return false;
        var protocol = MessageProtocol.parse(row.optString("protocol"));
        if (Privacy.hiddenProtocols(prefs).contains(protocol.family().name())) return true;
        return addressHidden(prefs, row.optString("address"));
    }

    /** Stores a text that arrived for a hidden conversation without it touching Android's SMS store. */
    static void receive(Context context, String address, String body, long date, int sim) {
        try {
            ProtocolStore.get(context).putHidden(new JSONObject().put("id", "hsms-r" + date + Integer.toHexString(body.hashCode()))
                .put("address", address).put("body", body).put("date", date).put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
                .put("sim", sim).put("read", 0).put("protocol", "sms"));
        } catch (Exception error) {
            // Never lose a text: if the vault can't take it, it goes to the normal inbox.
            SmsStore.insert(context, address, body, date, Telephony.Sms.MESSAGE_TYPE_INBOX, sim, 0);
        }
    }

    /**
     * Moves finished texts of hidden conversations into the vault. Texts still sending stay until Android
     * reports their result, so their status is kept.
     */
    static synchronized int sweep(Context context) {
        var prefs = prefs(context);
        if (!Privacy.vaultExists(prefs) || Privacy.hiddenAddresses(prefs).isEmpty()) return 0;
        int moved = 0;
        try {
            var store = ProtocolStore.get(context);
            var texts = SmsStore.read(context, 0);
            for (int i = 0; i < texts.length(); i++) {
                var text = texts.getJSONObject(i);
                int type = text.optInt("type");
                if (type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED) continue;
                String address = text.optString("address");
                if (!addressHidden(prefs, address)) continue;
                store.putHidden(new JSONObject(text.toString()).put("id", "hsms-" + text.getLong("id")).put("protocol", "sms")
                    .put("status", type == Telephony.Sms.MESSAGE_TYPE_FAILED ? "Couldn’t send" : ""));
                SmsStore.delete(context, text.getLong("id"), address);
                moved++;
            }
        } catch (Exception ignored) {
            // Without the SMS role there's nothing to move; the inbox still filters hidden chats.
        }
        return moved;
    }

    /** Puts a conversation's hidden texts back in Android's SMS store. The vault must be open. */
    static int restore(Context context, String address) throws Exception {
        if (!Privacy.vaultOpen()) throw new IllegalStateException("Open hidden chats first");
        var store = ProtocolStore.get(context);
        int restored = 0;
        for (JSONObject row : store.readHidden()) {
            String other = row.optString("address");
            boolean match = other.equals(address) || SmsStore.validRecipient(other) && SmsStore.validRecipient(address)
                && PhoneNumberUtils.compare(other, address);
            if (!match || !"sms".equals(row.optString("protocol"))) continue;
            SmsStore.insert(context, other, row.optString("body"), row.optLong("date"), row.optInt("type", 1),
                row.optInt("sim", -1), row.optInt("read", 1), row.optLong("sentAt"));
            store.removeHidden(row.getString("id"));
            restored++;
        }
        return restored;
    }

    /** Every hidden address, for leaving the vault entirely. */
    static List<String> addresses(SharedPreferences prefs) { return new ArrayList<>(Privacy.hiddenAddresses(prefs)); }
}
