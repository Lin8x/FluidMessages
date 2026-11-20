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
        SmsStore.insert(context, address, body.toString(), System.currentTimeMillis(),
            Telephony.Sms.MESSAGE_TYPE_INBOX, intent.getIntExtra("subscription", -1), 0, segments[0].getTimestampMillis());
        notify(context, "New text message", address);
    }

    static void notify(Context context, String label, String address) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        var preferences = context.getSharedPreferences("fluid", Context.MODE_PRIVATE);
        if (MainActivity.addressList(preferences, "muted").contains(address)) return;
        String channelId = MessageFeedback.notificationChannel(context, manager, preferences);
        Intent open = new Intent(context, MainActivity.class).putExtra("address", address);
        PendingIntent link = PendingIntent.getActivity(context, address.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(address.hashCode(), new Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(label)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(link).setAutoCancel(true).build());
    }
}
