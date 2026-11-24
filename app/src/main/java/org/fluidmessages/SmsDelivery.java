package org.fluidmessages;

import android.app.*;
import android.content.*;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public final class SmsDelivery extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SmsMessage[] segments = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (segments == null || segments.length == 0 || segments[0] == null) return;
        String address = segments[0].getDisplayOriginatingAddress();
        if (address == null) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage segment : segments) {
            if (segment != null && segment.getDisplayMessageBody() != null) body.append(segment.getDisplayMessageBody());
        }
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        if (HiddenChats.addressHidden(preferences, address)) {
            // Hidden conversations skip Android's SMS store and show only a plain alert.
            HiddenChats.receive(context, address, body.toString(), System.currentTimeMillis(), intent.getIntExtra("subscription", -1));
            if (!Mutes.isMuted(preferences, address)) notifyHidden(context);
            return;
        }
        SmsStore.insert(context, address, body.toString(), System.currentTimeMillis(),
            Telephony.Sms.MESSAGE_TYPE_INBOX, intent.getIntExtra("subscription", -1), 0, segments[0].getTimestampMillis());
        notify(context, "New text message", address);
    }

    /** An alert that names no one and opens the inbox, for messages in hidden chats. */
    static void notifyHidden(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        String channelId = MessageFeedback.notificationChannel(context, manager, preferences);
        PendingIntent link = PendingIntent.getActivity(context, 7, new Intent(context, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(7, new Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("New message")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_SECRET).setContentIntent(link).setAutoCancel(true).build());
    }

    static void notify(Context context, String label, String address) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        // Muted right now, by this chat or its whole server (Mutes): no notification, sound, or vibration. The message
        // is already stored and shows unread.
        if (Mutes.isMuted(preferences, address)) return;
        if (HiddenChats.addressHidden(preferences, address)) { notifyHidden(context); return; }
        // Message requests notify quietly or not at all, and automated senders can be silenced or blocked (MessageRequests).
        var alert = MessageRequests.alertFor(context, preferences, address);
        if (alert == MessageRequests.Alert.NONE) return;
        String channelId = alert == MessageRequests.Alert.QUIET ? quietChannel(manager)
            : MessageFeedback.notificationChannel(context, manager, preferences);
        Intent open = new Intent(context, MainActivity.class).putExtra("address", address);
        PendingIntent link = PendingIntent.getActivity(context, address.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(address.hashCode(), new Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(label)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(link).setAutoCancel(true).build());
    }

    /** Message requests' channel: shown, without sound or vibration. */
    private static String quietChannel(NotificationManager manager) {
        var channel = new NotificationChannel("message-requests", "Message requests", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Chats from people you don't know");
        manager.createNotificationChannel(channel);
        return "message-requests";
    }

    static final int TEST = 0x7e57;

    /**
     * Settings → Sounds and notifications → Send a test notification: a real notification on the same channel, sound,
     * and visibility as a new message, with made-up text. Returns false when Android would drop it.
     */
    static boolean notifyTest(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        String channelId = MessageFeedback.notificationChannel(context, manager, preferences);
        PendingIntent link = PendingIntent.getActivity(context, TEST, new Intent(context, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(TEST, new Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("Test notification")
            .setContentText("Sam Rivera (sample): Notifications from FluidMessages are working.")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(link).setAutoCancel(true).build());
        return status(context).getAllowed();
    }

    /**
     * A call that rang here and stopped before anyone answered. Opens the app; the Phone tab shows it in Recents.
     * Nothing for a muted chat: the call still shows as missed in Recents.
     */
    static void notifyMissedCall(Context context, String title, String protocol, String address) {
        if (Mutes.isMuted(context.getSharedPreferences("fluid", Context.MODE_PRIVATE), address)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        var channel = new NotificationChannel("missed-calls", "Missed calls", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Matrix, XMPP, and SIP calls you didn't answer");
        manager.createNotificationChannel(channel);
        PendingIntent link = PendingIntent.getActivity(context, MISSED, new Intent(context, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(MISSED, new Notification.Builder(context, "missed-calls")
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle("Missed call" + (title == null || title.isEmpty() ? "" : " from " + title))
            .setContentText(protocol).setCategory(Notification.CATEGORY_MISSED_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(link).setAutoCancel(true).build());
    }

    private static final int MISSED = 0x6d15;

    /** Whether a notification posted now would show: the permission, the app switch, and the message channel. */
    static NotificationStatus status(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        boolean permission = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean channel = true;
        String id = currentChannel(context);
        if (id != null) {
            var existing = manager.getNotificationChannel(id);
            channel = existing == null || existing.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return new NotificationStatus(permission, manager.areNotificationsEnabled(), channel);
    }

    /** The message channel's id, making the channel first so Android's channel settings page can open it. */
    static String currentChannel(Context context) {
        try {
            return MessageFeedback.notificationChannel(context, context.getSystemService(NotificationManager.class),
                context.getSharedPreferences("fluid", Context.MODE_PRIVATE));
        } catch (RuntimeException error) { return null; }
    }
}
