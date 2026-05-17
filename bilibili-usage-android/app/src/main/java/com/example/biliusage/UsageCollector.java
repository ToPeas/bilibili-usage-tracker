package com.example.biliusage;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

final class UsageCollector {
    private static final String[] TARGET_PACKAGES = {
            "tv.danmaku.bili",
            "tv.danmaku.bilibilihd",
            "com.bilibili.app.in"
    };

    static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.getPackageName()
        );
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static JSONObject collectDay(Context context, SettingsStore settings, Calendar day) throws Exception {
        Calendar start = (Calendar) day.clone();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_MONTH, 1);

        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        List<UsageStats> stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                start.getTimeInMillis(),
                end.getTimeInMillis()
        );

        Map<String, Long> durations = new LinkedHashMap<>();
        for (String packageName : TARGET_PACKAGES) {
            durations.put(packageName, 0L);
        }
        if (stats != null) {
            for (UsageStats item : stats) {
                if (!durations.containsKey(item.getPackageName())) continue;
                long current = durations.get(item.getPackageName());
                durations.put(item.getPackageName(), current + Math.max(0L, item.getTotalTimeInForeground()));
            }
        }

        JSONArray items = new JSONArray();
        long totalMs = 0L;
        for (Map.Entry<String, Long> entry : durations.entrySet()) {
            if (entry.getValue() <= 0L) continue;
            JSONObject detail = new JSONObject();
            detail.put("bundle", entry.getKey());
            detail.put("durationMs", entry.getValue());
            items.put(detail);
            totalMs += entry.getValue();
        }

        JSONObject payload = new JSONObject();
        payload.put("date", formatDate(start));
        payload.put("source", "android");
        payload.put("deviceId", settings.deviceId);
        payload.put("deviceAlias", settings.deviceAlias.isEmpty() ? settings.deviceId : settings.deviceAlias);
        payload.put("timezone", TimeZone.getDefault().getID());
        payload.put("items", items);
        payload.put("totalMs", totalMs);
        payload.put("reportedAt", formatIsoWithOffset(Calendar.getInstance()));
        payload.put("appVersion", "1.0.0");
        payload.put("schemaVersion", 1);
        return payload;
    }

    static List<JSONObject> collectRecent(Context context, SettingsStore settings, int days, boolean includeToday) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        Calendar cursor = Calendar.getInstance();
        if (!includeToday) cursor.add(Calendar.DAY_OF_MONTH, -1);
        for (int i = 0; i < days; i++) {
            result.add(collectDay(context, settings, cursor));
            cursor.add(Calendar.DAY_OF_MONTH, -1);
        }
        return result;
    }

    private static String formatDate(Calendar calendar) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
    }

    private static String formatIsoWithOffset(Calendar calendar) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(calendar.getTime());
    }
}
