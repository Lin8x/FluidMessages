package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import java.util.Locale;

enum MessageProtocol {
    RCS("RCS", "Carrier preview", "Encryption varies by service"),
    SMS("SMS", "Carrier texts", "No end-to-end encryption"),
    MMS("MMS", "Carrier groups and pictures", "No end-to-end encryption"),
    MATRIX("Matrix", "Homeserver rooms", "No end-to-end encryption in supported rooms"),
    XMPP("XMPP", "Federated chat", "No verified end-to-end encryption"),
    EMAIL("Email", "IMAP and SMTP", "No end-to-end encryption"),
    IRC("IRC", "Channels and direct messages", "No end-to-end encryption");

    final String label;
    final String description;
    final String privacy;

    MessageProtocol(String label, String description, String privacy) {
        this.label = label;
        this.description = description;
        this.privacy = privacy;
    }

    static MessageProtocol parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException error) { return SMS; }
    }

    String key() { return "protocol" + name(); }
    boolean enabled(SharedPreferences prefs) { return prefs.getBoolean(key(), true); }
    String disabledReason(SharedPreferences prefs) {
        return enabled(prefs) ? "" : label + " is turned off in Settings. This conversation is read-only.";
    }
    void requireEnabled(Context context) {
        String reason = disabledReason(context.getSharedPreferences("fluid", Context.MODE_PRIVATE));
        if (!reason.isEmpty()) throw new IllegalStateException(reason);
    }

    static MessageProtocol preferred(SharedPreferences prefs, String conversation, List<MessageProtocol> routes) {
        if (routes.isEmpty()) throw new IllegalArgumentException("No message routes");
        String saved = prefs.getString("protocolRoute." + conversation, "");
        if (!saved.isEmpty()) {
            var selected = parse(saved);
            if (routes.contains(selected)) return selected;
        }
        if (prefs.getBoolean("protocolAuto", true)) {
            for (MessageProtocol format : values()) if (routes.contains(format) && format.enabled(prefs)) return format;
        }
        return routes.get(0);
    }
}
