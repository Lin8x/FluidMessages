package org.fluidmessages;

import android.content.SharedPreferences;
import java.util.Collection;
import org.json.JSONObject;

enum SenderStyle {
    AUTOMATED, SMS, RCS;

    /** Preference prefix for a chat's own sender type, saved per address like {@code disappear.<address>}. */
    static final String OVERRIDE = "senderType.";
    /** The values a chat's sender type can hold. No value means Automatic, the detection in {@link #infer}. */
    static final String AUTOMATED_CHOICE = "automated", PERSON_CHOICE = "person";

    static SenderStyle infer(String address) {
        String value = address.trim();
        if (value.matches("[0-9]{5,6}") ||
            (value.matches("[A-Za-z0-9 ._-]{2,20}") && value.matches(".*[A-Za-z].*"))) {
            return AUTOMATED;
        }
        return SMS;
    }

    static SenderStyle group(Collection<SenderStyle> members) {
        if (members.contains(AUTOMATED)) return AUTOMATED;
        return members.isEmpty() || members.contains(SMS) ? SMS : RCS;
    }

    /**
     * The style after this chat's own sender type: Automated sender always uses the automated style, and Person (SMS)
     * turns a detected automated sender into a regular texter. Automatic, or an unknown value, keeps the detection.
     */
    static SenderStyle resolve(SenderStyle detected, String override) {
        if (AUTOMATED_CHOICE.equals(override)) return AUTOMATED;
        if (PERSON_CHOICE.equals(override)) return detected == AUTOMATED ? SMS : detected;
        return detected;
    }

    /** How a one-to-one text chat with this address is shown: the detection, then the chat's own choice. */
    static SenderStyle forAddress(String address, String override) {
        return resolve(infer(address), override);
    }

    static SenderStyle forAddress(SharedPreferences prefs, String address) {
        return forAddress(address, override(prefs, address));
    }

    /** This chat's own sender type, or "" for Automatic. */
    static String override(SharedPreferences prefs, String address) {
        return clean(prefs.getString(OVERRIDE + address, ""));
    }

    /** Saves this chat's sender type; "" (or anything unknown) goes back to Automatic. */
    static void setOverride(SharedPreferences prefs, String address, String choice) {
        String value = clean(choice);
        if (value.isEmpty()) prefs.edit().remove(OVERRIDE + address).apply();
        else prefs.edit().putString(OVERRIDE + address, value).apply();
    }

    static String clean(String choice) {
        return AUTOMATED_CHOICE.equals(choice) || PERSON_CHOICE.equals(choice) ? choice : "";
    }

    static String label(String choice) {
        return switch (clean(choice)) {
            case AUTOMATED_CHOICE -> "Automated sender";
            case PERSON_CHOICE -> "Person (SMS)";
            default -> "Automatic";
        };
    }

    /** Every chat's own sender type, address to choice, for backups and FluidMessages Web. */
    static JSONObject overrides(SharedPreferences prefs) throws org.json.JSONException {
        var result = new JSONObject();
        for (var entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith(OVERRIDE) && entry.getValue() instanceof String value && !clean(value).isEmpty()) {
                result.put(entry.getKey().substring(OVERRIDE.length()), value);
            }
        }
        return result;
    }
}
