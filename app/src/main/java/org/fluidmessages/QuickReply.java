package org.fluidmessages;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.util.List;

public final class QuickReply extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && intent.getData() != null) {
                String recipient = intent.getData().getSchemeSpecificPart();
                // Only send encrypted messages (QuickSetup) applies to replies from a notification too.
                var prefs = getSharedPreferences("fluid", MODE_PRIVATE);
                String refused = QuickSetup.sendRefused(QuickSetup.encryptedOnly(prefs), MessageProtocol.SMS,
                    SmsCrypto.active(prefs, recipient, MessageProtocol.SMS) ? Encryption.FLUIDMESSAGES : Encryption.NONE);
                if (!refused.isEmpty()) { SmsDelivery.notify(this, "Reply not sent", refused); return START_NOT_STICKY; }
                MessageProtocol.SMS.transport().send(this, new OutgoingMessage(recipient, List.of(recipient),
                    intent.getStringExtra(Intent.EXTRA_TEXT), null, -1));
            }
        } catch (Exception error) {
            SmsDelivery.notify(this, "Quick reply failed", "");
        } finally {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }
}
