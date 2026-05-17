package com.example.biliusage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        DailyUploadReceiver.scheduleNext(context);
        new Thread(() -> DailyUploadReceiver.upload(context, false)).start();
    }
}
