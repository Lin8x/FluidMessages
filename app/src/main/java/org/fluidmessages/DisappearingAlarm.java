package org.fluidmessages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Wakes FluidMessages when a disappearing message is due, even when the app is closed, and after a restart or
 * an update (which clear alarms). The sweep deletes what's due, sets the alarm for the next one, and asks the
 * companion sync to run so FluidMessages Web drops its copies too.
 */
public final class DisappearingAlarm extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean restarted = Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!restarted && !Disappearing.ALARM.equals(action)) return;
        var app = context.getApplicationContext();
        var pending = goAsync();
        new Thread(() -> {
            try {
                if (restarted) Disappearing.forgetAlarm(app);
                if (Disappearing.sweep(app) > 0) SyncJob.syncSoon(app);
            } finally { pending.finish(); }
        }, "fluid-disappear-alarm").start();
    }
}
