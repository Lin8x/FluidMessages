package org.fluidmessages;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import androidx.core.content.FileProvider;
import com.android.messaging.mmslib.pdu.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class MmsTransport {
    static final String CHANGED = "org.fluidmessages.PROTOCOL_CHANGED";
    private static final int MAX_DOWNLOAD = 5 * 1024 * 1024;
    private static final int MAX_PHOTO = 240 * 1024;

    static int subscription(int selected) throws IOException {
        int sim = selected < 0 ? SubscriptionManager.getDefaultSmsSubscriptionId() : selected;
        if (!SubscriptionManager.isValidSubscriptionId(sim)) throw new IOException("Choose a SIM in order to use MMS.");
        return sim;
    }

    static String thread(Context context, List<String> members) {
        var contacts = new ContactDirectory(context);
        return "mms:" + String.join(",", members.stream().map(contacts::key).distinct().sorted().toList());
    }

    static void send(Context context, List<String> members, String body, Uri photo, int selectedSim) throws Exception {
        MessageProtocol.MMS.requireEnabled(context);
        if (members.isEmpty() || members.size() > 20 || members.stream().anyMatch(number -> !SmsStore.validRecipient(number))
                || (body.isBlank() && photo == null) || body.length() > 1600) {
            throw new IOException("Choose up to 20 phone numbers and enter a message or photo.");
        }
        int sim = subscription(selectedSim);
        byte[] image = photo == null ? null : preparePhoto(context, photo);
        byte[] pdu = encode(context, members, body, image);
        String id = "mms-" + UUID.randomUUID();
        var store = ProtocolStore.get(context);
        var record = record(context, id, members, "", body, true, System.currentTimeMillis(), image);
        record.put("status", "Sending…");
        if (image != null) store.putPhoto(id, image);
        store.put(record);
        File spool = spool(context, id);
        try {
            Files.write(spool.toPath(), pdu);
            SmsManager.getSmsManagerForSubscriptionId(sim).sendMultimediaMessage(context, uri(context, spool), null, null,
                callback(context, id, sim, false));
        } catch (Exception error) {
            record.put("status", "Couldn’t send MMS");
            store.put(record);
            spool.delete();
            throw error;
        } finally { changed(context); }
    }

    static byte[] encode(Context context, List<String> members, String text, byte[] image) throws Exception {
        var req = new SendReq();
        req.setTo(members.stream().map(EncodedStringValue::new).toArray(EncodedStringValue[]::new));
        req.setDate(System.currentTimeMillis() / 1000);
        req.setMessageClass(ascii("personal"));
        req.setExpiry(48 * 60 * 60);
        var body = new PduBody();
        if (!text.isEmpty()) {
            var caption = part("text/plain", "message.txt", text.getBytes(StandardCharsets.UTF_8));
            caption.setCharset(CharacterSets.UTF_8);
            body.addPart(caption);
        }
        if (image != null) body.addPart(part("image/jpeg", "photo.jpg", image));
        req.setBody(body);
        byte[] pdu = new PduComposer(context, req).make();
        if (pdu == null) throw new IOException("Could not encode the carrier message.");
        return pdu;
    }

    private static PduPart part(String mime, String filename, byte[] data) {
        var part = new PduPart();
        part.setName(ascii(filename));
        part.setContentId(ascii(filename));
        part.setContentLocation(ascii(filename));
        part.setContentType(ascii(mime));
        part.setData(data);
        return part;
    }

    private static byte[] preparePhoto(Context context, Uri source) throws IOException {
        var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("stripMetadata", true)) {
            try (var stream = context.getContentResolver().openInputStream(source)) {
                byte[] jpeg = stream == null ? null : stream.readNBytes(MAX_PHOTO + 1);
                if (jpeg != null && jpeg.length > 3 && jpeg.length <= MAX_PHOTO &&
                    (jpeg[0] & 255) == 255 && (jpeg[1] & 255) == 216) return jpeg;
            }
            throw new IOException("Turn on Remove photo metadata to convert or resize this picture for MMS.");
        }
        Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), source), (decoder, info, ignored) -> {
            var size = info.getSize();
            float scale = Math.min(1f, 960f / Math.max(size.getWidth(), size.getHeight()));
            decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * scale)), Math.max(1, Math.round(size.getHeight() * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        try {
            var output = new ByteArrayOutputStream();
            for (int quality = 84; quality >= 35; quality -= 12) {
                output.reset();
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= MAX_PHOTO) {
                    return output.toByteArray();
                }
            }
            throw new IOException("This picture is too large for MMS.");
        } finally { bitmap.recycle(); }
    }

    static void retrieve(Context context, byte[] notification, int selectedSim) throws Exception {
        if (notification == null || notification.length > 64 * 1024 ||
                !(new PduParser(notification, false).parse() instanceof NotificationInd incoming)) return;
        byte[] location = incoming.getContentLocation();
        if (location == null) return;
        String url = new String(location, StandardCharsets.UTF_8);
        Uri locationUri = Uri.parse(url);
        if (locationUri.getHost() == null || !List.of("https", "http").contains(locationUri.getScheme())) return;
        String id = "mms-" + UUID.nameUUIDFromBytes((selectedSim + ":" + url).getBytes(StandardCharsets.UTF_8));
        if (ProtocolStore.get(context).find(id) != null) return;
        int sim = subscription(selectedSim);
        File spool = spool(context, id);
        if (!spool.createNewFile()) {
            if (System.currentTimeMillis() - spool.lastModified() < 5 * 60_000) return;
            Files.delete(spool.toPath());
            if (!spool.createNewFile()) return;
        }
        try {
            SmsManager.getSmsManagerForSubscriptionId(sim).downloadMultimediaMessage(context, url, uri(context, spool), null,
                callback(context, id, sim, true));
        } catch (RuntimeException error) {
            spool.delete();
            throw error;
        }
    }

    static void finish(Context context, Intent result, int status) throws Exception {
        String id = result.getStringExtra("id");
        if (id == null) return;
        File spool = spool(context, id);
        var store = ProtocolStore.get(context);
        try {
            if (result.getBooleanExtra("retrieve", false)) {
                if (status != Activity.RESULT_OK || spool.length() < 1 || spool.length() > MAX_DOWNLOAD) {
                    SmsDelivery.notify(context, "MMS couldn’t be downloaded", "");
                    return;
                }
                if (store.find(id) != null) return;
                var record = decode(context, id, Files.readAllBytes(spool.toPath()), result.getIntExtra("sim", -1));
                if (record != null) {
                    store.put(record);
                    SmsDelivery.notify(context, "New MMS message", record.getString("address"));
                }
            } else {
                var record = store.find(id);
                if (record != null) {
                    byte[] confirmation = result.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA);
                    boolean accepted = status == Activity.RESULT_OK;
                    if (accepted && confirmation != null && confirmation.length <= 64 * 1024) {
                        var response = new PduParser(confirmation, false).parse();
                        if (response instanceof SendConf reply) accepted = reply.getResponseStatus() == PduHeaders.RESPONSE_STATUS_OK;
                    }
                    store.put(record.put("status", accepted ? "Sent" : "Couldn’t send MMS"));
                }
            }
        } finally { spool.delete(); changed(context); }
    }

    static JSONObject decode(Context context, String id, byte[] data, int sim) throws Exception {
        if (!(new PduParser(data, false).parse() instanceof RetrieveConf message) || message.getFrom() == null) return null;
        String sender = address(message.getFrom());
        if (!SmsStore.validRecipient(sender)) return null;
        var contacts = new ContactDirectory(context);
        String own = "";
        if (sim >= 0) {
            try {
                String number = context.getSystemService(TelephonyManager.class).createForSubscriptionId(sim).getLine1Number();
                if (number != null && !number.isBlank()) own = contacts.key(number);
            } catch (RuntimeException ignored) {}
        }
        if (own.isEmpty()) {
            var prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
            String number = prefs.getString("mmsOwnNumber", "");
            if (!number.isBlank()) own = contacts.key(number);
        }
        Map<String, String> members = new LinkedHashMap<>();
        members.put(contacts.key(sender), sender);
        for (var addresses : new EncodedStringValue[][]{message.getTo(), message.getCc()}) {
            if (addresses == null) continue;
            for (var value : addresses) {
                String number = address(value);
                if (!SmsStore.validRecipient(number)) continue;
                String key = contacts.key(number);
                if (!key.equals(own)) members.putIfAbsent(key, number);
            }
        }
        var body = message.getBody();
        var text = new StringBuilder();
        byte[] image = null;
        if (body != null) for (int i = 0; i < body.getPartsNum(); i++) {
            var part = body.getPart(i);
            byte[] partData = part.getData();
            if (part.getContentType() == null || partData == null) continue;
            String type = new String(part.getContentType(), StandardCharsets.US_ASCII);
            if (type.equals("text/plain") && text.length() < 2000) {
                if (!text.isEmpty()) text.append('\n');
                text.append(new EncodedStringValue(part.getCharset(), partData).getString());
            } else if (image == null && type.startsWith("image/") && partData.length <= MAX_DOWNLOAD) image = partData;
        }
        var record = record(context, id, List.copyOf(members.values()), sender, text.toString(), false,
            System.currentTimeMillis(), image);
        if (image != null) ProtocolStore.get(context).putPhoto(id, image);
        return record;
    }

    private static JSONObject record(Context context, String id, List<String> members, String sender, String body,
                                     boolean outgoing, long date, byte[] photo) throws Exception {
        return new JSONObject().put("id", id).put("protocol", "mms")
            .put("address", members.size() == 1 ? members.get(0) : thread(context, members))
            .put("members", new JSONArray(members)).put("author", sender)
            .put("body", body.isBlank() ? (photo == null ? "Multimedia message" : "Photo") : body)
            .put("date", date).put("type", outgoing ? 2 : 1).put("read", outgoing ? 1 : 0).put("photo", photo != null);
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static String address(EncodedStringValue value) { return value.getString().split("/TYPE=", 2)[0]; }

    private static File spool(Context context, String id) throws IOException {
        if (!id.matches("mms-[0-9a-f-]{36}")) throw new IOException("Invalid MMS transaction");
        File folder = new File(context.getCacheDir(), "mms-transfer");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not open MMS transfer storage");
        return new File(folder, id + ".pdu");
    }

    private static Uri uri(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".backup-files", file);
    }

    private static PendingIntent callback(Context context, String id, int sim, boolean retrieve) {
        var result = new Intent(context, MmsResult.class).setData(Uri.parse("fluid-mms:" + id))
            .putExtra("id", id).putExtra("sim", sim).putExtra("retrieve", retrieve);
        return PendingIntent.getBroadcast(context, 0, result, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT);
    }

    static void changed(Context context) { context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName())); }
}
