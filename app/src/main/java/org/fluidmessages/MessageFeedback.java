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
    /** The notification channel's sound always plays as a notification; in-app sounds follow {@link SoundPrefs#STREAM}. */
    private static final AudioAttributes AUDIO = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    /** The one message channel id currently in use, and the ids older choices left behind. */
    static final String[] MESSAGE_CHANNELS = {"messages", "fluid_chime", "messages_silent"};
    private static final String TONE_CHANNEL = "messages_tone_";

    static String toneKey(boolean receive) { return receive ? "receiveTone" : "sendTone"; }

    static String selection(SharedPreferences prefs, boolean receive) {
        String saved = prefs.getString(toneKey(receive), "");
        if (saved.equals(FLUID) || saved.equals(DEFAULT) || saved.equals(SILENT) || deviceTone(saved) != null) return saved;
        // Fluid sounds are the default. Someone who switched the old on/off sound setting off keeps their choice.
        String legacy = receive ? "receiveSound" : "sound";
        if (prefs.contains(legacy)) return prefs.getBoolean(legacy, false) ? FLUID : (receive ? DEFAULT : SILENT);
        return FLUID;
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

    /** Plays the send or receive sound inside the app, at the chosen volume, through media or notification volume. */
    static void play(Context context, SharedPreferences prefs, boolean receive) {
        play(context, prefs, selection(prefs, receive), receive);
    }

    /** Plays [choice] the way in-app sounds play, for previews of a choice before it's saved. */
    static void play(Context context, SharedPreferences prefs, String choice, boolean receive) {
        Uri tone = toneUri(context, choice, receive);
        float gain = SoundPrefs.gain(prefs.getInt(SoundPrefs.VOLUME, SoundPrefs.DEFAULT_VOLUME));
        if (tone == null || gain <= 0f) return;
        try {
            var ringtone = RingtoneManager.getRingtone(context, tone);
            if (ringtone == null) return;
            ringtone.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(SoundPrefs.usage(prefs.getString(SoundPrefs.STREAM, SoundPrefs.NOTIFICATION)))
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ringtone.setVolume(gain);
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
        // High importance, like other messaging apps: new messages pop up over what's on screen, not only in the shade.
        // Android keeps a channel's importance once it's made, so a channel made earlier keeps the person's own choice.
        NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("New texts and chat messages");
        channel.setSound(toneUri(context, choice, true), AUDIO);
        channel.enableVibration(true);
        String made;
        try {
            manager.createNotificationChannel(channel);
            made = id;
        } catch (SecurityException error) {
            manager.createNotificationChannel(new NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH));
            made = "messages";
        }
        // A channel left by an earlier receive chime would show up as a second "Messages" entry in Android's settings.
        try {
            for (NotificationChannel old : manager.getNotificationChannels()) {
                if (!old.getId().equals(made) && isMessageChannel(old.getId())) manager.deleteNotificationChannel(old.getId());
            }
        } catch (RuntimeException ignored) {}
        return made;
    }

    static boolean isMessageChannel(String id) {
        if (id.startsWith(TONE_CHANNEL)) return true;
        for (String known : MESSAGE_CHANNELS) if (known.equals(id)) return true;
        return false;
    }
}
