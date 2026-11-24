package org.fluidmessages;

import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * Which ways of calling are allowed, separate from messaging: Matrix can be off for messages and on for calls.
 * Also holds the SIP account, which is only used for calls.
 */
final class Calling {
    enum Method {
        PHONE("Phone", "Calls with your phone number, through Android's call screen"),
        MATRIX("Matrix", "Voice and video with Matrix contacts. End-to-end encrypted."),
        XMPP("XMPP", "Voice and video with XMPP contacts, such as people using Conversations. End-to-end encrypted."),
        SIP("SIP", "Internet phone service from a SIP provider, including regular phone numbers"),
        MEETING("Meeting links", "A Jitsi Meet link anyone can tap to join, on any app. Free and open source.");

        final String label;
        final String detail;
        Method(String label, String detail) { this.label = label; this.detail = detail; }
    }

    private Calling() {}

    static boolean allowed(SharedPreferences prefs, Method method) {
        // Meeting links used their own switch before calling had a section of its own.
        boolean fallback = method != Method.MEETING || prefs.getBoolean("meetingLinks", true);
        return prefs.getBoolean("call." + method.name(), fallback);
    }

    /** With every way to call turned off, you can still see calls, but you can't place any. */
    static boolean anyAllowed(SharedPreferences prefs) {
        for (Method method : Method.values()) if (allowed(prefs, method)) return true;
        return false;
    }

    static final String CALLS_OFF = "Calling is off: every way to call is turned off in Settings → Calls.";

    /** The calling method for a call screen protocol name (Phone, Matrix, XMPP, SIP). */
    static Method method(String protocol) {
        if (protocol == null) return Method.MATRIX;
        return switch (protocol.toUpperCase(java.util.Locale.ROOT)) {
            case "PHONE" -> Method.PHONE;
            case "XMPP" -> Method.XMPP;
            case "SIP" -> Method.SIP;
            case "MEETING", "JITSI" -> Method.MEETING;
            default -> Method.MATRIX;
        };
    }

    static void set(SharedPreferences prefs, Method method, boolean on) { prefs.edit().putBoolean("call." + method.name(), on).apply(); }

    /** Calling over a protocol keeps its account connected even when its messaging is turned off. */
    static boolean keepsConnected(SharedPreferences prefs, MessageProtocol protocol) {
        return protocol == MessageProtocol.MATRIX && allowed(prefs, Method.MATRIX) || protocol == MessageProtocol.XMPP && allowed(prefs, Method.XMPP);
    }

    // SIP account: a WebSocket address, SIP address, password, and the phone domain for calling numbers.

    static JSONObject sipAccount(SharedPreferences prefs) {
        String sealed = prefs.getString("sip.account", "");
        if (sealed == null || sealed.isEmpty()) return null;
        try { return new JSONObject(Privacy.open(prefs, sealed)); } catch (Exception error) { return null; }
    }

    static void saveSipAccount(SharedPreferences prefs, JSONObject account) {
        if (account == null) prefs.edit().remove("sip.account").apply();
        else prefs.edit().putString("sip.account", Privacy.seal(prefs, account.toString())).apply();
    }

    static boolean sipReady(SharedPreferences prefs) { return allowed(prefs, Method.SIP) && sipAccount(prefs) != null; }

    /** sip:user@domain for a SIP address or a phone number dialed through the provider. */
    static String sipTarget(SharedPreferences prefs, String target) {
        var account = sipAccount(prefs);
        if (target.startsWith("sip:")) return target;
        if (target.contains("@")) return "sip:" + target;
        String domain = account == null ? "" : account.optString("domain");
        String digits = target.replaceAll("[^+0-9*#]", "");
        return "sip:" + digits + "@" + domain;
    }
}
