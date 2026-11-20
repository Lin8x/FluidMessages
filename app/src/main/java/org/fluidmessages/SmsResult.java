package org.fluidmessages;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;

public final class SmsResult extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Uri data = intent.getData();
        if (data == null) return;
        Uri uri = data.buildUpon().clearQuery().build();
        String key = uri.getLastPathSegment();
        var progress = context.getSharedPreferences("send_results", Context.MODE_PRIVATE);
        int total = intent.getIntExtra("total", 1);
        int count = progress.getInt(key + "_count", 0) + 1;
        boolean failed = progress.getBoolean(key + "_failed", false) || getResultCode() != Activity.RESULT_OK;
        if (count >= total) {
            SmsStore.updateType(context, uri, failed ? Telephony.Sms.MESSAGE_TYPE_FAILED : Telephony.Sms.MESSAGE_TYPE_SENT);
            progress.edit().remove(key + "_count").remove(key + "_failed").apply();
        } else {
            progress.edit().putInt(key + "_count", count).putBoolean(key + "_failed", failed).apply();
        }
    }
}
