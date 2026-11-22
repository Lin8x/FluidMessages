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
