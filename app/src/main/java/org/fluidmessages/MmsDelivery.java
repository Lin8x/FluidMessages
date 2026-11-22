package org.fluidmessages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class MmsDelivery extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        var pending = goAsync();
        new Thread(() -> {
            try { MmsTransport.retrieve(context, intent.getByteArrayExtra("data"), intent.getIntExtra("subscription", -1)); }
            catch (Exception error) { SmsDelivery.notify(context, "Could not download MMS", ""); }
            finally { pending.finish(); }
        }, "fluid-mms-download").start();
    }
}
