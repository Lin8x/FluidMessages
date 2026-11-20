package org.fluidmessages;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class QuickReply extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && intent.getData() != null) {
                SmsStore.send(this, intent.getData().getSchemeSpecificPart(), intent.getStringExtra(Intent.EXTRA_TEXT), -1);
            }
        } catch (RuntimeException error) {
            SmsDelivery.notify(this, "Quick reply failed", "");
        } finally {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }
}
