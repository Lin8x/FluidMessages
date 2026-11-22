package org.fluidmessages;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import java.util.*;

final class ContactDirectory {
    record Match(String name, Uri uri, Uri photo) {}

    private final Context context;
    private volatile Map<String, Optional<Match>> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private int revision;

    ContactDirectory(Context context) { this.context = context; }

    boolean allowed() { return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED; }

    void invalidate() {
        cache = new java.util.concurrent.ConcurrentHashMap<>();
        revision++;
    }
    int revision() { return revision; }


    Match find(String address) {
        if (!allowed()) return null;
        return cache.computeIfAbsent(address, key -> Optional.ofNullable(lookup(key))).orElse(null);
    }

    private Match lookup(String address) {
        var columns = new String[]{ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY,
            ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI};
        var uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        Match best = null;
        long bestUpdated = -1;
        try (var cursor = context.getContentResolver().query(uri, columns, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                Uri contact = ContactsContract.Contacts.getLookupUri(cursor.getLong(0), cursor.getString(1));
                String name = cursor.getString(2);
                Uri photo = parseUri(cursor.getString(3));
                long updated = 0;
                if (contact != null) {
                    var details = new String[]{ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts.PHOTO_THUMBNAIL_URI, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP};
                    try (var row = context.getContentResolver().query(contact, details, null, null, null)) {
                        if (row != null && row.moveToFirst()) {
                            name = row.getString(0);
                            photo = parseUri(row.getString(1));
                            updated = row.getLong(2);
                        }
                    } catch (RuntimeException ignored) {}
                }
                if (updated > bestUpdated) {
                    best = new Match(name == null || name.isBlank() ? address : name, contact, photo);
                    bestUpdated = updated;
                }
            }
        } catch (RuntimeException ignored) {}
        return best;
    }

    private static Uri parseUri(String value) { return value == null ? null : Uri.parse(value); }

    private static String contactNumber(String value) {
        return value == null ? "" : value.replace(' ', ' ').replace(' ', ' ');
    }

    String key(String address) {
        if (address.indexOf('*') >= 0 || address.indexOf('#') >= 0) return PhoneNumberUtils.stripSeparators(address);
        var telephony = context.getSystemService(TelephonyManager.class);
        var country = telephony == null ? "" : telephony.getNetworkCountryIso();
        if (country == null || country.isBlank()) country = Locale.getDefault().getCountry();
        var e164 = PhoneNumberUtils.formatNumberToE164(address, country.toUpperCase(Locale.ROOT));
        return e164 == null ? PhoneNumberUtils.normalizeNumber(address) : e164;
    }

    List<Recipient> recipients() {
        Map<String, Recipient> contacts = new LinkedHashMap<>();
        if (allowed()) {
            var phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            var projection = new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI};
            try (var cursor = context.getContentResolver().query(phone, projection, null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC")) {
                if (cursor != null) while (cursor.moveToNext()) {
                    String address = contactNumber(cursor.getString(1));
                    if (!SmsStore.validRecipient(address)) continue;
                    String key = key(address);
                    String name = cursor.getString(0);
                    contacts.putIfAbsent(key, new Recipient(key, name == null ? address : name,
                        address, false, parseUri(cursor.getString(2))));
                }
            } catch (RuntimeException ignored) {}
        }

        Map<String, Recipient> result = new LinkedHashMap<>();
        try {
            var messages = SmsStore.read(context, 1200);
            for (int i = 0; i < messages.length(); i++) {
                String address = contactNumber(messages.getJSONObject(i).getString("address"));
                if (!SmsStore.validRecipient(address)) continue;
                String key = key(address);
                if (result.containsKey(key)) continue;
                var contact = contacts.get(key);
                result.put(key, new Recipient(key, contact == null ? address : contact.getName(), address, true,
                    contact == null ? null : contact.getPhoto()));
            }
        } catch (Exception ignored) {}
        contacts.forEach(result::putIfAbsent);
        return List.copyOf(result.values());
    }
}
