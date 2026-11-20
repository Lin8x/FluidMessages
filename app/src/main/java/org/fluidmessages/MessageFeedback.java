package org.fluidmessages;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class MessageFeedback {
    static final String FLUID = "fluid", DEFAULT = "default", SILENT = "silent";
    private static final String DEVICE_TONE = "tone:";
    private static final AudioAttributes AUDIO = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();

    static String toneKey(boolean receive) { return receive ? "receiveTone" : "sendTone"; }

    static String selection(SharedPreferences prefs, boolean receive) {
        String saved = prefs.getString(toneKey(receive), "");
        if (saved.equals(FLUID) || saved.equals(DEFAULT) || saved.equals(SILENT) || deviceTone(saved) != null) return saved;
        if (prefs.getBoolean(receive ? "receiveSound" : "sound", false)) return FLUID;
        return receive ? DEFAULT : SILENT;
    }

    static String fromPicker(Uri uri) {
        if (uri == null) return SILENT;
        if (RingtoneManager.isDefault(uri)) return DEFAULT;
        return "content".equals(uri.getScheme()) && uri.getAuthority() != null ? DEVICE_TONE + uri : DEFAULT;
    }

    private static Uri deviceTone(String value) {
        if (!value.startsWith(DEVICE_TONE)) return null;
        Uri uri = Uri.parse(value.substring(DEVICE_TONE.length()));
        return "content".equals(uri.getScheme()) && uri.getAuthority() != null ? uri : null;
    }

    static Uri toneUri(Context context, String choice, boolean receive) {
        if (choice.equals(SILENT)) return null;
        if (choice.equals(FLUID)) return Uri.parse("android.resource://" + context.getPackageName()
            + "/raw/" + (receive ? "message_receive" : "message_send"));
        Uri device = deviceTone(choice);
        return device == null ? Settings.System.DEFAULT_NOTIFICATION_URI : device;
    }

    static void play(Context context, SharedPreferences prefs, boolean receive) {
        Uri tone = toneUri(context, selection(prefs, receive), receive);
        if (tone == null) return;
        try {
            var ringtone = RingtoneManager.getRingtone(context, tone);
            if (ringtone == null) return;
            ringtone.setAudioAttributes(AUDIO);
            ringtone.play();
        } catch (RuntimeException ignored) {}
    }

    static String haptics(SharedPreferences prefs) {
        return prefs.getString("sendHaptics", prefs.getBoolean("haptics", true) ? "tap" : "off");
    }

    static void vibrate(View target, SharedPreferences prefs) {
        int effect = switch (haptics(prefs)) {
            case "soft" -> HapticFeedbackConstants.TEXT_HANDLE_MOVE;
            case "tap" -> HapticFeedbackConstants.KEYBOARD_TAP;
            case "confirm" -> HapticFeedbackConstants.CONFIRM;
            case "long" -> HapticFeedbackConstants.LONG_PRESS;
            default -> -1;
        };
        if (effect >= 0) target.performHapticFeedback(effect);
    }

    static String notificationChannel(Context context, NotificationManager manager, SharedPreferences prefs) {
        String choice = selection(prefs, true);
        String id = switch (choice) {
            case DEFAULT -> "messages";
            case FLUID -> "fluid_chime";
            case SILENT -> "messages_silent";
            default -> "messages_tone_" + UUID.nameUUIDFromBytes(choice.getBytes(StandardCharsets.UTF_8));
        };
        String name = switch (choice) {
            case DEFAULT -> "Messages";
            case FLUID -> "Messages with Fluid chime";
            case SILENT -> "Messages without sound";
            default -> "Messages with a device tone";
        };
        NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(toneUri(context, choice, true), AUDIO);
        try {
            manager.createNotificationChannel(channel);
            return id;
        } catch (SecurityException error) {
            manager.createNotificationChannel(new NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_DEFAULT));
            return "messages";
        }
    }
}
