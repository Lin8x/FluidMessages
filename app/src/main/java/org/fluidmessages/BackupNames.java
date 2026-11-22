package org.fluidmessages;

import android.content.SharedPreferences;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

final class BackupNames {
    static final String KEY = "backupFileName";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    static String fixed(SharedPreferences prefs) {
        try { return normalize(prefs.getString(KEY, "")); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    static String normalize(String input) {
        String base = input.trim();
        if (base.isEmpty()) return "";
        if (base.toLowerCase(Locale.ROOT).endsWith(".fmb")) base = base.substring(0, base.length() - 4).trim();
        if (!base.matches("[A-Za-z0-9][A-Za-z0-9 _.()-]{0,79}") || base.endsWith(".")) {
            throw new IllegalArgumentException("Use 1–80 letters, numbers, spaces, dots, dashes or parentheses.");
        }
        return base + ".fmb";
    }

    static String suggest(SharedPreferences prefs) {
        String name = fixed(prefs);
        return name.isEmpty() ? "FluidMessages_" + DATE.format(LocalDateTime.now()) + "_" +
            UUID.randomUUID().toString().substring(0, 8) + ".fmb" : name;
    }
}
