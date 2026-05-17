package com.example.biliusage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;

public class DailyUploadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        scheduleNext(context);
        new Thread(() -> upload(context, false)).start();
    }

    static void scheduleNext(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, DailyUploadReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Calendar next = Calendar.getInstance();
        next.add(Calendar.DAY_OF_MONTH, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 5);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                next.getTimeInMillis(),
                AlarmManager.INTERVAL_DAY,
                pendingIntent
        );
    }

    static String upload(Context context, boolean includeToday) {
        try {
            SettingsStore settings = SettingsStore.load(context);
            if (!settings.isCloudConfigured()) return "D1 settings missing";
            if (!UsageCollector.hasUsageAccess(context)) return "Usage access missing";
            D1Client client = new D1Client(settings);
            List<JSONObject> days = UsageCollector.collectRecent(context, settings, 7, includeToday);
            int uploaded = 0;
            for (JSONObject payload : days) {
                if (payload.getLong("totalMs") <= 0L) continue;
                client.upload(payload);
                uploaded += 1;
            }
            return "Uploaded " + uploaded + " day(s)";
        } catch (Exception error) {
            return error.getMessage();
        }
    }
}
