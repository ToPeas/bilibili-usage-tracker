package com.example.biliusage;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
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

/**
 * 通过 {@link UsageStatsManager#queryEvents} 把目标 B 站客户端的「前后台事件」流回放，
 * 再按本地时区切到「日 + 小时」两级桶里。
 *
 * <p>结果 payload 字段：</p>
 * <pre>
 *   {
 *     date: yyyy-MM-dd,
 *     source: "android",
 *     deviceId, deviceAlias, timezone, appVersion, schemaVersion,
 *     totalMs, reportedAt,
 *     items: [{bundle, durationMs}],  // 按包名分布
 *     hours: [{hour, durationMs}]     // 0..23，未出现的小时不附带
 *   }
 * </pre>
 */
final class UsageCollector {
    private static final String[] TARGET_PACKAGES = {
            "tv.danmaku.bili",
            "tv.danmaku.bilibilihd",
            "com.bilibili.app.in"
    };
    /** 一次 queryEvents 拉取的最大窗口（30 天）。超过则分段调用避免 binder 超时。 */
    private static final long QUERY_WINDOW_MS = 30L * 24 * 3600 * 1000;
    static final String APP_VERSION = "1.2.0";
    static final int SCHEMA_VERSION = 2;

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

        DayBuckets buckets = queryDayBuckets(context, start.getTimeInMillis(), end.getTimeInMillis());
        return toPayload(settings, start, buckets);
    }

    static List<JSONObject> collectRecent(Context context, SettingsStore settings, int days, boolean includeToday) throws Exception {
        days = Math.max(1, days);
        List<JSONObject> result = new ArrayList<>();

        // 一次性划定范围，减少 binder 来回
        Calendar today = Calendar.getInstance();
        Calendar endDay = (Calendar) today.clone();
        if (!includeToday) endDay.add(Calendar.DAY_OF_MONTH, -1);
        Calendar startDay = (Calendar) endDay.clone();
        startDay.add(Calendar.DAY_OF_MONTH, -(days - 1));

        Calendar startOfRange = (Calendar) startDay.clone();
        startOfRange.set(Calendar.HOUR_OF_DAY, 0);
        startOfRange.set(Calendar.MINUTE, 0);
        startOfRange.set(Calendar.SECOND, 0);
        startOfRange.set(Calendar.MILLISECOND, 0);
        Calendar endOfRange = (Calendar) endDay.clone();
        endOfRange.set(Calendar.HOUR_OF_DAY, 0);
        endOfRange.set(Calendar.MINUTE, 0);
        endOfRange.set(Calendar.SECOND, 0);
        endOfRange.set(Calendar.MILLISECOND, 0);
        endOfRange.add(Calendar.DAY_OF_MONTH, 1);

        DayBuckets[] perDay = new DayBuckets[days];
        for (int i = 0; i < days; i++) perDay[i] = new DayBuckets();

        // 一次拉 events，按所属日划到每天桍。
        long rangeStartMs = startOfRange.getTimeInMillis();
        long rangeEndMs = endOfRange.getTimeInMillis();
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager != null) {
            long queryStart = rangeStartMs - 2L * 3600 * 1000;
            long queryEnd = rangeEndMs;
            java.util.Map<String, Long> foregroundEnter = new LinkedHashMap<>();
            long cursor = queryStart;
            while (cursor < queryEnd) {
                long next = Math.min(cursor + QUERY_WINDOW_MS, queryEnd);
                android.app.usage.UsageEvents events = manager.queryEvents(cursor, next);
                android.app.usage.UsageEvents.Event ev = new android.app.usage.UsageEvents.Event();
                while (events != null && events.hasNextEvent()) {
                    events.getNextEvent(ev);
                    String pkg = ev.getPackageName();
                    if (pkg == null || !isTargetPackage(pkg)) continue;
                    int type = ev.getEventType();
                    long ts = ev.getTimeStamp();
                    if (type == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        foregroundEnter.put(pkg, ts);
                    } else if (type == android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND
                            || type == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
                            || type == android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED) {
                        Long enter = foregroundEnter.remove(pkg);
                        if (enter != null && ts > enter) {
                            distributeToDays(perDay, startOfRange, days, pkg, enter, ts);
                        }
                    }
                }
                cursor = next;
            }
            for (Map.Entry<String, Long> entry : foregroundEnter.entrySet()) {
                distributeToDays(perDay, startOfRange, days, entry.getKey(), entry.getValue(), rangeEndMs);
            }
        }

        // 输出：从最近一天往老（跟原有顺序保持一致）
        for (int i = days - 1; i >= 0; i--) {
            Calendar dayCal = (Calendar) startOfRange.clone();
            dayCal.add(Calendar.DAY_OF_MONTH, i);
            result.add(toPayload(settings, dayCal, perDay[i]));
        }
        return result;
    }

    /** 把 [enter, leave) 划到 startOfRange + i 天那个桍。 */
    private static void distributeToDays(DayBuckets[] perDay, Calendar startOfRange, int days, String pkg, long enter, long leave) {
        long rangeStart = startOfRange.getTimeInMillis();
        Calendar rangeEndCal = (Calendar) startOfRange.clone();
        rangeEndCal.add(Calendar.DAY_OF_MONTH, days);
        long rangeEnd = rangeEndCal.getTimeInMillis();

        long segStart = Math.max(enter, rangeStart);
        long segEnd = Math.min(leave, rangeEnd);
        if (segEnd <= segStart) return;
        long cursor = segStart;
        while (cursor < segEnd) {
            int dayIdx = dayIndex(startOfRange, cursor);
            if (dayIdx < 0 || dayIdx >= days) break;
            Calendar dayStart = (Calendar) startOfRange.clone();
            dayStart.add(Calendar.DAY_OF_MONTH, dayIdx);
            Calendar dayEnd = (Calendar) dayStart.clone();
            dayEnd.add(Calendar.DAY_OF_MONTH, 1);

            long hourEnd = nextHourStart(cursor);
            long next = Math.min(Math.min(dayEnd.getTimeInMillis(), hourEnd), segEnd);
            long delta = next - cursor;
            if (delta > 0) {
                int hour = hourOfLocal(cursor);
                perDay[dayIdx].addHour(hour, delta);
                perDay[dayIdx].addBundle(pkg, delta);
            }
            cursor = next;
        }
    }

    private static int dayIndex(Calendar startOfRange, long ms) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(ms);
        Calendar localStart = (Calendar) local.clone();
        localStart.set(Calendar.HOUR_OF_DAY, 0);
        localStart.set(Calendar.MINUTE, 0);
        localStart.set(Calendar.SECOND, 0);
        localStart.set(Calendar.MILLISECOND, 0);
        long startMs = startOfRange.getTimeInMillis();
        long delta = localStart.getTimeInMillis() - startMs;
        return (int) Math.floor(delta / (double) (24L * 3600 * 1000));
    }

    /** 把 [startMs, endMs) 区间内的前台事件回放，按小时和包名分桶。 */
    static DayBuckets queryDayBuckets(Context context, long startMs, long endMs) {
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        DayBuckets buckets = new DayBuckets();
        if (manager == null) return buckets;

        // 为了在某些设备上确保事件完整，往左多取 2 小时，再过滤
        long queryStart = startMs - 2L * 3600 * 1000;
        long queryEnd = endMs;

        // 维护各包名当前是否在前台 & 进入前台的时间戳
        Map<String, Long> foregroundEnter = new LinkedHashMap<>();
        long cursor = queryStart;
        while (cursor < queryEnd) {
            long next = Math.min(cursor + QUERY_WINDOW_MS, queryEnd);
            UsageEvents events = manager.queryEvents(cursor, next);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                String pkg = event.getPackageName();
                if (pkg == null || !isTargetPackage(pkg)) continue;
                int type = event.getEventType();
                long ts = event.getTimeStamp();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foregroundEnter.put(pkg, ts);
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND
                        || type == UsageEvents.Event.ACTIVITY_PAUSED
                        || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                    Long enter = foregroundEnter.remove(pkg);
                    if (enter != null && ts > enter) {
                        accumulate(buckets, pkg, enter, ts, startMs, endMs);
                    }
                }
            }
            cursor = next;
        }
        // 若到 endMs 仍未结束（比如用户当前正在使用），按 endMs 截断
        for (Map.Entry<String, Long> entry : foregroundEnter.entrySet()) {
            accumulate(buckets, entry.getKey(), entry.getValue(), endMs, startMs, endMs);
        }
        return buckets;
    }

    /** 把一段 [enter, leave) 切到 [startMs, endMs) 之内，再按小时拆。 */
    private static void accumulate(DayBuckets buckets, String pkg, long enter, long leave, long startMs, long endMs) {
        long segStart = Math.max(enter, startMs);
        long segEnd = Math.min(leave, endMs);
        if (segEnd <= segStart) return;
        long cursor = segStart;
        while (cursor < segEnd) {
            long hourEnd = nextHourStart(cursor);
            long next = Math.min(hourEnd, segEnd);
            long delta = next - cursor;
            if (delta > 0) {
                int hour = hourOfLocal(cursor);
                buckets.addHour(hour, delta);
                buckets.addBundle(pkg, delta);
            }
            cursor = next;
        }
    }

    private static long nextHourStart(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MINUTE, 0);
        c.add(Calendar.HOUR_OF_DAY, 1);
        return c.getTimeInMillis();
    }

    private static int hourOfLocal(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    private static boolean isTargetPackage(String pkg) {
        for (String t : TARGET_PACKAGES) if (t.equals(pkg)) return true;
        return false;
    }

    private static JSONObject toPayload(SettingsStore settings, Calendar dayStart, DayBuckets buckets) throws Exception {
        JSONArray items = new JSONArray();
        long totalMs = 0L;
        for (Map.Entry<String, Long> entry : buckets.byBundle.entrySet()) {
            if (entry.getValue() <= 0L) continue;
            JSONObject detail = new JSONObject();
            detail.put("bundle", entry.getKey());
            detail.put("durationMs", entry.getValue());
            items.put(detail);
            totalMs += entry.getValue();
        }
        JSONArray hours = new JSONArray();
        for (int h = 0; h < 24; h++) {
            long ms = buckets.byHour[h];
            if (ms <= 0L) continue;
            JSONObject hourJson = new JSONObject();
            hourJson.put("hour", h);
            hourJson.put("durationMs", ms);
            hours.put(hourJson);
        }

        JSONObject payload = new JSONObject();
        payload.put("date", formatDate(dayStart));
        payload.put("source", "android");
        payload.put("deviceId", settings.deviceId);
        payload.put("deviceAlias", settings.deviceAlias.isEmpty() ? settings.deviceId : settings.deviceAlias);
        payload.put("timezone", TimeZone.getDefault().getID());
        payload.put("items", items);
        payload.put("hours", hours);
        payload.put("totalMs", totalMs);
        payload.put("reportedAt", formatIsoWithOffset(Calendar.getInstance()));
        payload.put("appVersion", APP_VERSION);
        payload.put("schemaVersion", SCHEMA_VERSION);
        return payload;
    }

    private static String formatDate(Calendar calendar) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
    }

    private static String formatIsoWithOffset(Calendar calendar) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(calendar.getTime());
    }

    /** 单日累积桶。byHour 长度恒为 24。 */
    static final class DayBuckets {
        final long[] byHour = new long[24];
        final Map<String, Long> byBundle = new LinkedHashMap<>();

        void addHour(int hour, long ms) {
            if (hour < 0 || hour >= 24 || ms <= 0) return;
            byHour[hour] += ms;
        }

        void addBundle(String pkg, long ms) {
            if (pkg == null || ms <= 0) return;
            byBundle.merge(pkg, ms, Long::sum);
        }
    }
}
