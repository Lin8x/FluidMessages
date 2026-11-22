package org.fluidmessages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class MmsResult extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = getResultCode();
        var pending = goAsync();
        new Thread(() -> {
            try { MmsTransport.finish(context, intent, status); }
            catch (Exception error) { SmsDelivery.notify(context, "Could not finish the MMS transfer", ""); }
            finally { pending.finish(); }
        }, "fluid-mms-result").start();
    }
}
