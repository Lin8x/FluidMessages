package org.fluidmessages;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

enum SenderStyle {
    AUTO("Automatic"), SMS("Standard SMS"), AUTOMATED("Automated");

    private static final String KEY = "senderCategories";
    final String label;

    SenderStyle(String label) { this.label = label; }

    static SenderStyle setting(SharedPreferences prefs, String address) {
        String saved = read(prefs).optString(address);
        if (saved.equals("IPHONE")) return SMS;
        try { return valueOf(saved); }
        catch (IllegalArgumentException error) { return AUTO; }
    }

    static SenderStyle forAddress(SharedPreferences prefs, String address, SenderStyle fallback) {
        var style = setting(prefs, address);
        return style == AUTO ? fallback : style;
    }

    static SenderStyle infer(String address) {
        String value = address.trim();
        if (value.matches("[0-9]{5,6}") ||
            (value.matches("[A-Za-z0-9 ._-]{2,20}") && value.matches(".*[A-Za-z].*"))) {
            return AUTOMATED;
        }
        return SMS;
    }

    void save(SharedPreferences prefs, String address) {
        JSONObject entries = read(prefs);
        try {
            if (this == AUTO) entries.remove(address);
            else entries.put(address, name());
            prefs.edit().putString(KEY, entries.toString()).apply();
        } catch (JSONException error) {
            throw new IllegalArgumentException("Could not set sender color", error);
        }
    }

    private static JSONObject read(SharedPreferences prefs) {
        try { return new JSONObject(prefs.getString(KEY, "{}")); }
        catch (JSONException error) { return new JSONObject(); }
    }
}
