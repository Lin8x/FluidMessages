package org.fluidmessages;

import android.content.SharedPreferences;
import org.json.JSONObject;

record ChatProfile(String alias, String theme, String wallpaper, Integer background, Integer outgoing, Integer incoming) {
    private static final String KEY = "chatProfiles";

    static ChatProfile read(SharedPreferences prefs, String address) {
        JSONObject entry = profiles(prefs).optJSONObject(address);
        if (entry == null) return new ChatProfile("", "", "", null, null, null);
        return new ChatProfile(entry.optString("alias"), entry.optString("theme"), entry.optString("wallpaper"),
            color(entry, "background"), color(entry, "outgoing"), color(entry, "incoming"));
    }

    void save(SharedPreferences prefs, String address) {
        try {
            JSONObject all = profiles(prefs);
            if (alias.isEmpty() && theme.isEmpty() && wallpaper.isEmpty()
                && background == null && outgoing == null && incoming == null) {
                all.remove(address);
            } else {
                all.put(address, new JSONObject().put("alias", alias).put("theme", theme)
                    .put("wallpaper", wallpaper).put("background", background)
                    .put("outgoing", outgoing).put("incoming", incoming));
            }
            prefs.edit().putString(KEY, all.toString()).apply();
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("Could not save profile", error); }
    }

    private static JSONObject profiles(SharedPreferences prefs) {
        try { return new JSONObject(prefs.getString(KEY, "{}")); }
        catch (org.json.JSONException error) { return new JSONObject(); }
    }

    private static Integer color(JSONObject entry, String name) {
        return entry.opt(name) instanceof Integer value ? value : null;
    }
}
