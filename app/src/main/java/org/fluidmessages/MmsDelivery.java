package org.fluidmessages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class MmsDelivery extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SmsDelivery.notify(context, "MMS received · downloads unavailable in this build", "");
    }
}
