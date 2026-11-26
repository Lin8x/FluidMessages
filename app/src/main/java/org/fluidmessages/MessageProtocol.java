package org.fluidmessages;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import java.util.Locale;

enum MessageProtocol {
    RCS("RCS", "Preview. Needs carrier support"),
    SMS("SMS", "Carrier texts"),
    MMS("MMS", "Carrier groups and pictures"),
    MATRIX("Matrix", "Homeserver rooms"),
    XMPP("XMPP", "Federated chat"),
    EMAIL("Email", "IMAP and SMTP"),
    IRC("IRC", "Channels and direct messages"),
    /** FluidMessages phones close by, with no internet: same Wi-Fi now (DirectNearby). Off until you turn it on. */
    NEARBY("Nearby", "Friends close by, no internet needed");

    final String label;
    final String description;

    MessageProtocol(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** Sending, capabilities, and encryption status live in the protocol's shared transport. */
    MessageTransport transport() { return Transports.of(this); }

    static MessageProtocol parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException error) { return SMS; }
    }

    String key() { return "protocol" + name(); }
    boolean enabled(SharedPreferences prefs) { return prefs.getBoolean(key(), this != NEARBY); }
    /** With every way to message turned off, FluidMessages is read-only: you can read, but nothing is sent. */
    static boolean anyEnabled(SharedPreferences prefs) {
        for (MessageProtocol format : values()) if (format.enabled(prefs)) return true;
        return false;
    }

    static final String READ_ONLY = "Read-only: every chat service is turned off in Settings → Chat services.";

    String disabledReason(SharedPreferences prefs) {
        return enabled(prefs) ? "" : label + " is turned off in Settings. This conversation is read-only.";
    }
    void requireEnabled(Context context) {
        String reason = disabledReason(context.getSharedPreferences("fluid", Context.MODE_PRIVATE));
        if (!reason.isEmpty()) throw new IllegalStateException(reason);
    }

    /**
     * RCS, SMS, and MMS are one text conversation. RCS covers it when available, SMS is the fallback,
     * and MMS is what SMS becomes for photos, files, and groups.
     */
    MessageProtocol family() { return this == RCS || this == MMS ? SMS : this; }
    boolean isText() { return family() == SMS; }

    /** The route this conversation actually uses for {@code requested}, so a text thread opens as RCS, SMS, or MMS. */
    static MessageProtocol concrete(List<MessageProtocol> routes, MessageProtocol requested) {
        if (routes.contains(requested)) return requested;
        for (MessageProtocol route : routes) if (route.family() == requested.family()) return route;
        return routes.isEmpty() ? requested : routes.get(0);
    }

    /** A saved per-contact default wins; otherwise the first enabled route in RCS, SMS/MMS, Matrix, XMPP, Email, IRC order. */
    static MessageProtocol preferred(SharedPreferences prefs, String conversation, List<MessageProtocol> routes) {
        if (routes.isEmpty()) throw new IllegalArgumentException("No message routes");
        String saved = prefs.getString("protocolRoute." + conversation, "");
        if (!saved.isEmpty()) {
            var selected = concrete(routes, parse(saved));
            if (routes.contains(selected)) return selected;
        }
        for (MessageProtocol format : values()) if (routes.contains(format) && format.enabled(prefs)) return format;
        return routes.get(0);
    }
}
