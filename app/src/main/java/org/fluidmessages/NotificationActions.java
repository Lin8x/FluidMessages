package org.fluidmessages;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import java.util.List;

/**
 * Reply and Mark as read on a new-text notification (SmsDelivery.notifyText). A reply goes out through the same
 * transport as a text typed in the chat, so it is stored and shown like any other sent text.
 */
public final class NotificationActions extends BroadcastReceiver {
    static final String REPLY = "org.fluidmessages.notification.REPLY";
    static final String MARK_READ = "org.fluidmessages.notification.MARK_READ";
    static final String REPLY_TEXT = "reply";

    static PendingIntent pending(Context context, String action, String address, int sim, boolean reply) {
        Intent intent = new Intent(context, NotificationActions.class).setAction(action)
            .setData(Uri.fromParts("smsto", address, null)).putExtra("sim", sim);
        // Android fills the typed reply into the intent, so that one has to be mutable. It names this receiver only.
        return PendingIntent.getBroadcast(context, address.hashCode(), intent,
            (reply ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE) | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent.getData() == null || intent.getAction() == null) return;
        String address = intent.getData().getSchemeSpecificPart();
        if (address == null || address.isEmpty()) return;
        var typed = RemoteInput.getResultsFromIntent(intent);
        CharSequence text = typed == null ? null : typed.getCharSequence(REPLY_TEXT);
        boolean reply = REPLY.equals(intent.getAction());
        if (!reply && !MARK_READ.equals(intent.getAction())) return;
        int sim = intent.getIntExtra("sim", -1);
        Context app = context.getApplicationContext();
        var pending = goAsync();
        new Thread(() -> {
            try {
                var manager = app.getSystemService(NotificationManager.class);
                var prefs = app.getSharedPreferences("fluid", Context.MODE_PRIVATE);
                if (reply) {
                    // The chat was locked or moved to the private folder after the notification showed: answer in the app.
                    if (text == null || text.toString().isBlank() || !SmsDelivery.guard(prefs, address).getReveals()) {
                        SmsDelivery.notifyReplyFailed(app, "Reply not sent. Open the chat to send it.", address);
                        return;
                    }
                    try {
                        MessageProtocol.SMS.transport().send(app, new OutgoingMessage(address, List.of(address), text.toString(), null, sim));
                    } catch (Exception error) {
                        SmsDelivery.notifyReplyFailed(app, "Reply not sent. Open the chat to try again.", address);
                        return;
                    }
                    // Replying to a message request accepts it, as it does in the chat (MessageRequests).
                    MessageRequests.acceptOnReply(app, prefs, address);
                }
                markRead(app, address);
                manager.cancel(address.hashCode());
            } finally {
                pending.finish();
            }
        }, "fluid-notification-action").start();
    }

    /** Marks the chat's received texts read, the way opening the chat does. */
    private static void markRead(Context context, String address) {
        var values = new ContentValues();
        values.put("read", 1);
        values.put("seen", 1);
        try {
            context.getContentResolver().update(Telephony.Sms.CONTENT_URI, values, "address = ? AND read = 0 AND type = "
                + Telephony.Sms.MESSAGE_TYPE_INBOX, new String[]{address});
        } catch (RuntimeException ignored) {}
    }
}
