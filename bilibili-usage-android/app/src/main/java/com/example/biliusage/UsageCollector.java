package com.example.biliusage;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;
import android.util.Log;

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
    static final String APP_VERSION = "1.3.8";
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

    /**
     * 读取指定本地自然日窗口的前台时长。
     *
     * <p>统计口径：Activity 在前台可见的时间（MOVE_TO_FOREGROUND → MOVE_TO_BACKGROUND）。
     * 全部走 queryEvents 事件流，同源同精度同时算：
     * - byBundle（按 App 的总时长）
     * - byHour（按小时分布）
     *
     * <p>不用 queryAndAggregateUsageStats / queryUsageStats(INTERVAL_DAILY)——
     * 这两个接口在当天 daily bucket 未 flush 时都会 fallback 到 weekly/monthly，
     * 导致今日数字被历史累计"污染"。
     */
    static DayBuckets queryDayBuckets(Context context, long startMs, long endMs) {
        DayBuckets buckets = new DayBuckets();
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return buckets;

        long effectiveEndMs = Math.min(endMs, System.currentTimeMillis());
        if (effectiveEndMs <= startMs) return buckets;

        addEventStats(manager, buckets, startMs, effectiveEndMs);
        return buckets;
    }

    /**
     * 从事件流中同时计算 byBundle + byHour。
     *
     * <p>小米 B站HD 不产生 MOVE_TO_FOREGROUND，只产生 ACTIVITY_RESUMED。
     * 因此同时监听两种「进入前台」事件：
     * <ul>
     *   <li>{@link UsageEvents.Event#MOVE_TO_FOREGROUND}（标准 API）</li>
     *   <li>{@link UsageEvents.Event#ACTIVITY_RESUMED}（小米/部分厂商实际产生的）</li>
     * </ul>
     * 退出用 ACTIVITY_PAUSED / ACTIVITY_STOPPED / MOVE_TO_BACKGROUND。
     * 用「每个 pkg 最早未结束的进入时刻」追踪，多 Activity 不重复计时。
     */
    /**
     * 从事件流中同时计算 byBundle + byHour。
     *
     * 小米 B站HD 不产生 MOVE_TO_FOREGROUND，只产生 ACTIVITY_RESUMED / ACTIVITY_PAUSED。
     * 策略：按「class（Activity 类名）」独立跟踪 RESUMED->PAUSED 时间段，
     * 最终把同 pkg 的所有 class 时段合并（去重叠）得到 pkg 在前台的总时长。
     */
    private static void addEventStats(UsageStatsManager manager, DayBuckets buckets, long startMs, long endMs) {
        try {
            UsageEvents events = manager.queryEvents(startMs, endMs);
            if (events == null) return;
            UsageEvents.Event event = new UsageEvents.Event();

            // key = "pkg/class"，value = RESUMED 时间戳
            Map<String, Long> classStart = new LinkedHashMap<>();
            // pkg -> 该 pkg 的所有前台时段 [s, e]
            Map<String, List<long[]>> pkgSegments = new LinkedHashMap<>();

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                String pkg = event.getPackageName();
                if (pkg == null || !isTargetPackage(pkg)) continue;

                int type = event.getEventType();
                long ts = event.getTimeStamp();
                if (ts < startMs || ts >= endMs) continue;

                String cls = event.getClassName();
                if (cls == null || cls.isEmpty()) cls = pkg;
                String key = pkg + "/" + cls;

                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    classStart.put(key, ts);
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND
                        || type == UsageEvents.Event.ACTIVITY_PAUSED
                        || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                    Long s = classStart.remove(key);
                    if (s != null && ts > s) {
                        pkgSegments.computeIfAbsent(pkg, k -> new ArrayList<>())
                                .add(new long[]{s, ts});
                    }
                }
            }

            // 还在前台的 class：计到查询右边界
            long rightBound = Math.min(endMs, System.currentTimeMillis());
            for (Map.Entry<String, Long> e : classStart.entrySet()) {
                String pkg = e.getKey().split("/", 2)[0];
                long s = e.getValue();
                if (rightBound > s) {
                    pkgSegments.computeIfAbsent(pkg, k -> new ArrayList<>())
                            .add(new long[]{s, rightBound});
                }
            }

            // 对每个 pkg 的时段做合并去重叠，再累加
            for (Map.Entry<String, List<long[]>> entry : pkgSegments.entrySet()) {
                String pkg = entry.getKey();
                List<long[]> segs = entry.getValue();
                segs.sort((a, b) -> Long.compare(a[0], b[0]));
                long segStart = -1, segEnd = -1;
                for (long[] seg : segs) {
                    if (segStart < 0) {
                        segStart = seg[0]; segEnd = seg[1];
                    } else if (seg[0] <= segEnd) {
                        segEnd = Math.max(segEnd, seg[1]);
                    } else {
                        long delta = segEnd - segStart;
                        if (delta > 0) {
                            buckets.addBundle(pkg, delta);
                            distributeIntoHourBuckets(buckets, segStart, segEnd);
                        }
                        segStart = seg[0]; segEnd = seg[1];
                    }
                }
                if (segStart >= 0 && segEnd > segStart) {
                    long delta = segEnd - segStart;
                    buckets.addBundle(pkg, delta);
                    distributeIntoHourBuckets(buckets, segStart, segEnd);
                }
            }

            Log.d("BiliUsage", "addEventStats sum=" + buckets.totalBundleMs() + "ms("
                    + buckets.totalBundleMs() / 60000 + "m)");
        } catch (Exception e) {
            Log.d("BiliUsage", "addEventStats failed: " + e.getMessage());
        }
    }

    /** 将 [s, e) 区间按本地时区的小时窗口拆分累加到 byHour。 */
    private static void distributeIntoHourBuckets(DayBuckets buckets, long s, long e) {
        Calendar cur = Calendar.getInstance();
        long cursor = s;
        while (cursor < e) {
            cur.setTimeInMillis(cursor);
            int hour = cur.get(Calendar.HOUR_OF_DAY);
            cur.set(Calendar.MILLISECOND, 0);
            cur.set(Calendar.SECOND, 0);
            cur.set(Calendar.MINUTE, 0);
            cur.add(Calendar.HOUR_OF_DAY, 1);
            long boundary = cur.getTimeInMillis();
            long segEnd = Math.min(boundary, e);
            long delta = segEnd - cursor;
            if (delta > 0) {
                buckets.addHour(hour, delta);
            }
            cursor = segEnd;
        }
    }

    private static boolean isTargetPackage(String pkg) {
        for (String t : TARGET_PACKAGES) if (t.equals(pkg)) return true;
        return false;
    }

    private static JSONObject toPayload(SettingsStore settings, Calendar dayStart, DayBuckets buckets) throws Exception {
        JSONArray items = new JSONArray();
        for (Map.Entry<String, Long> entry : buckets.byBundle.entrySet()) {
            if (entry.getValue() <= 0L) continue;
            JSONObject detail = new JSONObject();
            detail.put("bundle", entry.getKey());
            detail.put("durationMs", entry.getValue());
            items.put(detail);
        }
        // totalMs = hours 之和（与 byBundle 之和同源，保证一致）
        long totalMs = buckets.totalMs();
        Log.d("BiliUsage", "toPayload date=" + formatDate(dayStart)
                + " totalMs=" + totalMs + "ms(" + totalMs / 60000 + "m)");

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

        long totalBundleMs() {
            long sum = 0L;
            for (long v : byBundle.values()) sum += v;
            return sum;
        }
    }
}
