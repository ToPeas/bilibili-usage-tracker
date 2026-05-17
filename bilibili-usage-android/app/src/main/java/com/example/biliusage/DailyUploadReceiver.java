package com.example.biliusage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;

/**
 * 每天凌晨自动补传一次「昨天/前几天」的数据；也支持手动一次性补传多天。
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>默认补传范围由 {@link #BACKFILL_DAYS} 控制（180 天）；
 *       payload.totalMs == 0 时跳过上传，避免覆盖已有的其他设备记录。</li>
 *   <li>手动入口 {@link #upload(Context, boolean, int)} 让 MainActivity 按选定范围补传。</li>
 * </ul>
 */
public class DailyUploadReceiver extends BroadcastReceiver {
    /** 自动补传范围：半年。 */
    public static final int BACKFILL_DAYS = 180;

    @Override
    public void onReceive(Context context, Intent intent) {
        scheduleNext(context);
        new Thread(() -> upload(context, false, BACKFILL_DAYS)).start();
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

    /** 兼容老调用方：默认 7 天。 */
    static String upload(Context context, boolean includeToday) {
        return upload(context, includeToday, 7);
    }

    static String upload(Context context, boolean includeToday, int days) {
        try {
            SettingsStore settings = SettingsStore.load(context);
            if (!settings.isCloudConfigured()) return "D1 settings missing";
            if (!UsageCollector.hasUsageAccess(context)) return "Usage access missing";
            D1Client client = new D1Client(settings);
            List<JSONObject> payloads = UsageCollector.collectRecent(context, settings, Math.max(1, days), includeToday);
            int uploaded = 0;
            int skippedEmpty = 0;
            for (JSONObject payload : payloads) {
                if (payload.getLong("totalMs") <= 0L) {
                    skippedEmpty += 1;
                    continue;
                }
                client.upload(payload);
                uploaded += 1;
            }
            return "已上传 " + uploaded + " 天，跳过 " + skippedEmpty + " 个空日";
        } catch (Exception error) {
            return error.getMessage();
        }
    }
}
