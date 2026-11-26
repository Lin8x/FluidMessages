package org.fluidmessages;

import android.app.*;
import android.content.*;
import android.graphics.drawable.Icon;
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
        notifyText(context, address, body.toString(), intent.getIntExtra("subscription", -1));
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

    /**
     * The channel a new message in this chat alerts on, or null when it stays silent: muted right now, by this chat
     * or its whole server (Mutes), blocked, or a message request or automated sender set to make no alert
     * (MessageRequests). The message is already stored and shows unread.
     */
    private static String alertChannel(Context context, NotificationManager manager, SharedPreferences preferences, String address) {
        if (Mutes.isMuted(preferences, address)) return null;
        if (!address.isEmpty() && MainActivity.addressList(preferences, "blocked").contains(address)) return null;
        var alert = MessageRequests.alertFor(context, preferences, address);
        if (alert == MessageRequests.Alert.NONE) return null;
        return alert == MessageRequests.Alert.QUIET ? quietChannel(manager)
            : MessageFeedback.notificationChannel(context, manager, preferences);
    }

    private static PendingIntent openChat(Context context, String address) {
        return PendingIntent.getActivity(context, address.hashCode(), new Intent(context, MainActivity.class).putExtra("address", address),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The plain notification: a label and nothing about who wrote or what they said. Tapping it opens the chat. */
    private static Notification.Builder plain(Context context, String channelId, String label, String address) {
        return new Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(label)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(openChat(context, address)).setAutoCancel(true);
    }

    static void notify(Context context, String label, String address) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        if (Mutes.isMuted(preferences, address)) return;
        if (HiddenChats.addressHidden(preferences, address)) { notifyHidden(context); return; }
        String channelId = alertChannel(context, manager, preferences, address);
        if (channelId == null) return;
        manager.notify(address.hashCode(), plain(context, channelId, label, address).build());
    }

    /** Tells the person a reply typed in a notification didn't go out. Says nothing about the chat; tapping opens it. */
    static void notifyReplyFailed(Context context, String label, String address) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        if (HiddenChats.addressHidden(preferences, address)) { notifyHidden(context); return; }
        manager.notify(address.hashCode(), plain(context, MessageFeedback.notificationChannel(context, manager, preferences),
            label, address).build());
    }

    /** What may be shown about a text from [address] right now (NotificationContent). Anything unsure shows nothing. */
    static NotificationContent.Guard guard(SharedPreferences preferences, String address) {
        try {
            boolean code = Privacy.hasLock(preferences, Privacy.chatScope(address))
                || Privacy.hasLock(preferences, Privacy.protocolScope(MessageProtocol.SMS));
            boolean decoy = Privacy.decoyMode(preferences) && !Privacy.decoyAllowed(preferences).contains(address);
            return new NotificationContent.Guard(preferences.getBoolean(NotificationContent.PREF, true),
                preferences.getBoolean("lock", false), HiddenChats.addressHidden(preferences, address)
                    || Privacy.hidden(preferences, address, MessageProtocol.SMS), code, decoy);
        } catch (RuntimeException error) {
            return new NotificationContent.Guard(false, true, true, true, true);
        }
    }

    private static final int KEPT_MESSAGES = 8;

    /**
     * A new text, the way texting apps show one: the contact's name (or the number), the message, earlier unread
     * texts of the same chat above it, and Reply and Mark as read. The lock screen shows it only as far as the phone
     * allows; a secure lock screen set to hide sensitive content shows the plain "New text message". Chats in the
     * private folder, chats with a code, decoy mode, app lock, and the setting on Sounds and notifications keep it
     * plain everywhere (NotificationContent).
     */
    static void notifyText(Context context, String address, String body, int sim) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        if (Mutes.isMuted(preferences, address)) return;
        if (HiddenChats.addressHidden(preferences, address)) { notifyHidden(context); return; }
        String channelId = alertChannel(context, manager, preferences, address);
        if (channelId == null) return;
        int id = address.hashCode();
        var guard = guard(preferences, address);
        String name = null, text = body == null ? "" : body;
        if (guard.getReveals()) {
            try {
                var contact = SmsStore.validRecipient(address) ? ContactDirectory.shared(context).find(address) : null;
                if (contact != null) name = contact.name();
            } catch (RuntimeException ignored) {}
            // A text locked with a secret code both people typed shows its words when this phone has the code.
            if (text.startsWith(SmsCrypto.PREFIX)) {
                SmsCrypto.Opened opened = null;
                try { opened = SmsCrypto.open(preferences, text, java.util.List.of(address)); } catch (RuntimeException ignored) {}
                text = opened == null ? NotificationContent.LOCKED_TEXT : opened.text();
            }
        }
        var shown = NotificationContent.build(guard, name, address, text);
        if (!shown.getReveals()) {
            manager.notify(id, plain(context, channelId, NotificationContent.GENERIC_TITLE, address).build());
            return;
        }
        long now = System.currentTimeMillis();
        android.app.Person sender = new android.app.Person.Builder().setName(shown.getTitle()).setKey(address).build();
        var style = new Notification.MessagingStyle(new android.app.Person.Builder().setName("You").build());
        // Earlier unread texts of this chat stay in the notification. They're read back from the notification itself,
        // so message text is never kept anywhere else for this.
        try {
            for (var active : manager.getActiveNotifications()) {
                if (active.getId() != id || active.getTag() != null) continue;
                var earlier = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    active.getNotification().extras.getParcelableArray(Notification.EXTRA_MESSAGES));
                for (var message : earlier.subList(Math.max(0, earlier.size() - KEPT_MESSAGES + 1), earlier.size())) style.addMessage(message);
            }
        } catch (RuntimeException ignored) {}
        style.addMessage(new Notification.MessagingStyle.Message(shown.getText(), now, sender));
        var reply = new Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply",
                NotificationActions.pending(context, NotificationActions.REPLY, address, sim, true))
            .addRemoteInput(new RemoteInput.Builder(NotificationActions.REPLY_TEXT).setLabel("Reply").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).setAllowGeneratedReplies(true).build();
        var read = new Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_view), "Mark as read",
                NotificationActions.pending(context, NotificationActions.MARK_READ, address, sim, false))
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build();
        manager.notify(id, plain(context, channelId, shown.getTitle(), address)
            .setContentText(shown.getText()).setStyle(style).addPerson(sender).setWhen(now).setShowWhen(true)
            .setPublicVersion(plain(context, channelId, NotificationContent.GENERIC_TITLE, address).build())
            .addAction(reply).addAction(read).build());
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
