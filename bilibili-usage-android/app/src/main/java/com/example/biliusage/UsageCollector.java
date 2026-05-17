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
    static final String APP_VERSION = "1.2.6";
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

        Calendar today = Calendar.getInstance();
        Calendar endDay = (Calendar) today.clone();
        if (!includeToday) endDay.add(Calendar.DAY_OF_MONTH, -1);

        // 从最近一天往老（跟原有顺序保持一致），每天单独调 queryDayBuckets（=纯 stats）
        for (int i = 0; i < days; i++) {
            Calendar dayCal = (Calendar) endDay.clone();
            dayCal.add(Calendar.DAY_OF_MONTH, -i);
            Calendar dayStart = (Calendar) dayCal.clone();
            dayStart.set(Calendar.HOUR_OF_DAY, 0);
            dayStart.set(Calendar.MINUTE, 0);
            dayStart.set(Calendar.SECOND, 0);
            dayStart.set(Calendar.MILLISECOND, 0);
            Calendar dayEnd = (Calendar) dayStart.clone();
            dayEnd.add(Calendar.DAY_OF_MONTH, 1);
            DayBuckets buckets = queryDayBuckets(context, dayStart.getTimeInMillis(), dayEnd.getTimeInMillis());
            result.add(toPayload(settings, dayStart, buckets));
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

    /** 直接读取系统「数字健康」级别的统计：跟系统设置完全一致。
     *  不用 events 自己回放（不同 ROM 事件丢失严重），只用 queryUsageStats(INTERVAL_DAILY)。
     *  小时分布无法精准，故近似处理：仅当 byHour 全 0 时把总量贴到当前小时；否则保留事件分布形状。
     */
    static DayBuckets queryDayBuckets(Context context, long startMs, long endMs) {
        DayBuckets buckets = new DayBuckets();
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return buckets;

        // 1) 主数据：queryUsageStats — 跟系统设置的"数字健康/屏幕使用时间"一致
        long statsTotal = 0L;
        Map<String, Long> statsPerBundle = new LinkedHashMap<>();
        try {
            List<android.app.usage.UsageStats> list = manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startMs, endMs);
            if (list != null) {
                for (android.app.usage.UsageStats us : list) {
                    if (us == null) continue;
                    String pkg = us.getPackageName();
                    if (pkg == null || !isTargetPackage(pkg)) continue;
                    // 严格过滤：UsageStats 的 firstTimeStamp/lastTimeStamp 必须落在本日内，
                    // 防止有些 ROM 把跨天会话整段计到某一天。
                    long fts = us.getFirstTimeStamp();
                    long lts = us.getLastTimeStamp();
                    // 与查询区间无交集就丢
                    if (lts < startMs || fts >= endMs) continue;
                    long fg = us.getTotalTimeInForeground();
                    if (fg <= 0L) continue;
                    statsTotal += fg;
                    statsPerBundle.merge(pkg, fg, Long::sum);
                }
            }
        } catch (Exception ignore) {
            // 拿不到就显示 0
        }

        // 2) 写入 byBundle = stats
        for (Map.Entry<String, Long> entry : statsPerBundle.entrySet()) {
            buckets.byBundle.put(entry.getKey(), entry.getValue());
        }

        if (statsTotal <= 0L) return buckets;

        // 3) 小时分布：尝试用 events 仅获取「形状」（相对占比），再按 stats 总量归一化。
        //    events 失败/无数据时，把总量全部贴到当前小时（用户看到 0 也能定位）。
        long[] hourShape = tryGetHourShape(manager, startMs, endMs);
        long shapeSum = 0L;
        for (long v : hourShape) shapeSum += v;
        if (shapeSum > 0L) {
            double scale = (double) statsTotal / (double) shapeSum;
            long sumWritten = 0L;
            int lastNonZero = -1;
            for (int h = 0; h < 24; h++) {
                if (hourShape[h] <= 0L) continue;
                long v = Math.round(hourShape[h] * scale);
                buckets.byHour[h] = v;
                sumWritten += v;
                lastNonZero = h;
            }
            // 修正取整误差
            long diff = statsTotal - sumWritten;
            if (lastNonZero >= 0 && diff != 0L) {
                buckets.byHour[lastNonZero] = Math.max(0L, buckets.byHour[lastNonZero] + diff);
            }
        } else {
            // 完全没有事件形状：贴到当前小时（若是今天）或最后一小时（若是历史天）
            long bucketTs = Math.min(endMs - 1, System.currentTimeMillis());
            if (bucketTs < startMs || bucketTs >= endMs) bucketTs = endMs - 1;
            int curHour = hourOfLocal(bucketTs);
            if (curHour >= 0 && curHour < 24) buckets.byHour[curHour] += statsTotal;
        }
        return buckets;
    }

    /** 用 events 获取本日的小时占比形状（不返回精确时长，只返回相对比例）。 */
    private static long[] tryGetHourShape(UsageStatsManager manager, long startMs, long endMs) {
        long[] hours = new long[24];
        try {
            // 仅查询本日内的事件，避免被前/后两小时跨天事件污染
            UsageEvents events = manager.queryEvents(startMs, endMs);
            if (events == null) return hours;
            Map<String, Long> foregroundEnter = new LinkedHashMap<>();
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                String pkg = event.getPackageName();
                if (pkg == null || !isTargetPackage(pkg)) continue;
                int type = event.getEventType();
                long ts = event.getTimeStamp();
                if (ts < startMs) ts = startMs;
                if (ts > endMs) ts = endMs;
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foregroundEnter.put(pkg, ts);
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND
                        || type == UsageEvents.Event.ACTIVITY_PAUSED
                        || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                    Long enter = foregroundEnter.remove(pkg);
                    if (enter != null && ts > enter) addToHourShape(hours, enter, ts);
                }
            }
            // 至 endMs 仍在前台 → 贴到 endMs
            for (Map.Entry<String, Long> e : foregroundEnter.entrySet()) {
                addToHourShape(hours, e.getValue(), endMs);
            }
        } catch (Exception ignore) {
            // 不影响主流程
        }
        return hours;
    }

    /** 把一段时间按小时拆分加到 hours[] 上（不再除以总量，只用相对值描形状）。 */
    private static void addToHourShape(long[] hours, long enter, long leave) {
        if (leave <= enter) return;
        long cursor = enter;
        while (cursor < leave) {
            long hourEnd = nextHourStart(cursor);
            long next = Math.min(hourEnd, leave);
            int h = hourOfLocal(cursor);
            if (h >= 0 && h < 24) hours[h] += (next - cursor);
            cursor = next;
        }
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

        long totalMs() {
            long sum = 0L;
            for (long v : byHour) sum += v;
            return sum;
        }
    }
}
