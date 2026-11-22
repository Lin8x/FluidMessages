package org.fluidmessages

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object CompanionAppearance {
    @JvmStatic
    fun snapshot(prefs: SharedPreferences, messages: JSONArray): JSONObject {
        val appearance = Appearance.read(prefs)
        val palette = appearance.uiColors
        val addresses = (0 until messages.length()).map { messages.getJSONObject(it).getString("address") }.toSet()
        val folders = JSONObject()
        for (key in listOf("pinned", "archived", "blocked")) {
            folders.put(key, JSONArray(MainActivity.addressList(prefs, key).filter { it in addresses }))
        }
        return JSONObject()
            .put("dark", palette.ink() == android.graphics.Color.WHITE)
            .put("exactTimes", prefs.getBoolean("exactTimes", false))
            .put("radius", appearance.radius)
            .put("size", appearance.size)
            .put("padding", appearance.padding)
            .put("folders", folders)
            .put("colors", JSONObject()
                .put("canvas", hex(appearance.canvas))
                .put("ink", hex(palette.ink()))
                .put("muted", hex(palette.muted()))
                .put("surface", hex(palette.surface()))
                .put("raised", hex(palette.raised()))
                .put("line", hex(palette.divider()))
                .put("outline", hex(palette.outline()))
                .put("action", hex(palette.action()))
                .put("incoming", hex(accessibleBubbleFill(Color(appearance.incoming)).toArgb()))
                .put("incomingInk", hex(bubbleTextColor(Color(appearance.incoming)).toArgb()))
                .put("sms", hex(accessibleBubbleFill(Color(appearance.accent)).toArgb()))
                .put("smsInk", hex(bubbleTextColor(Color(appearance.accent)).toArgb()))
                .put("automated", hex(accessibleBubbleFill(Color(appearance.automated)).toArgb()))
                .put("automatedInk", hex(bubbleTextColor(Color(appearance.automated)).toArgb()))
                .put("accent", hex(MessageStyle.filledControl(appearance.controls)))
                .put("accentInk", hex(MessageStyle.filledControlInk(appearance.controls))))
    }

    private fun hex(color: Int) = String.format(Locale.ROOT, "#%06x", color and 0xffffff)
}
