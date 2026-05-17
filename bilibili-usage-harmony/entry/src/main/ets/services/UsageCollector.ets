import usageStatistics from '@ohos.resourceschedule.usageStatistics';
import { BusinessError } from '@ohos.base';

const BILI_BUNDLES: string[] = [
  'com.huawei.hmsapp.bilibili',   // 鸿蒙版 B 站
  'com.bilibili.app.in',          // 国际版兜底
  'tv.danmaku.bili',              // 原 Android 包名兜底
  'com.bilibili.app.blue'         // B站概念版
];

export class DayBuckets {
  totalMs: number = 0;
  byBundle: Map<string, number> = new Map();
  byHour: number[] = new Array<number>(24).fill(0);
}

export class UsageCollector {
  /**
   * 拉取指定时间段内 B 站系列 App 的前台使用时长。
   * 用 HarmonyOS 提供的「应用使用记录」服务，需要 ohos.permission.BUNDLE_ACTIVE_INFO。
   */
  static async queryDayBuckets(startMs: number, endMs: number): Promise<DayBuckets> {
    const buckets = new DayBuckets();

    try {
      // 1) 拉每日聚合（与系统数字健康一致）
      const stats: usageStatistics.BundleStatsInfo[] =
        await usageStatistics.queryBundleStatsInfoByInterval(
          usageStatistics.IntervalType.BY_DAILY, startMs, endMs);

      for (const item of stats) {
        if (!UsageCollector.isTargetBundle(item.bundleName)) continue;
        const fts = item.fromTime || 0;
        const lts = item.lastTime || 0;
        if (lts < startMs || fts >= endMs) continue;
        const fg = item.abilityInFgTotalTime || 0;
        if (fg <= 0) continue;
        buckets.totalMs += fg;
        const prev = buckets.byBundle.get(item.bundleName) || 0;
        buckets.byBundle.set(item.bundleName, prev + fg);
      }

      // 2) 用 events 拿"小时分布相对比例"，归一化到 totalMs
      const hourShape = await UsageCollector.queryHourShape(startMs, endMs);
      let shapeSum = 0;
      for (const v of hourShape) shapeSum += v;
      if (shapeSum > 0 && buckets.totalMs > 0) {
        const scale = buckets.totalMs / shapeSum;
        let written = 0; let lastNonZero = -1;
        for (let h = 0; h < 24; h++) {
          if (hourShape[h] <= 0) continue;
          const v = Math.round(hourShape[h] * scale);
          buckets.byHour[h] = v; written += v; lastNonZero = h;
        }
        const diff = buckets.totalMs - written;
        if (lastNonZero >= 0 && diff !== 0) {
          buckets.byHour[lastNonZero] = Math.max(0, buckets.byHour[lastNonZero] + diff);
        }
      } else if (buckets.totalMs > 0) {
        // 没有 events 信号兜底：全部放到"当下小时"或最后小时
        const refTs = Math.min(endMs - 1, Date.now());
        const hour = new Date(refTs).getHours();
        buckets.byHour[Math.max(0, Math.min(23, hour))] += buckets.totalMs;
      }
    } catch (e) {
      const err = e as BusinessError;
      console.error(`[UsageCollector] queryDayBuckets err: ${err.code} ${err.message}`);
    }
    return buckets;
  }

  private static async queryHourShape(startMs: number, endMs: number): Promise<number[]> {
    const hours = new Array<number>(24).fill(0);
    try {
      const events: usageStatistics.BundleEvents[] =
        await usageStatistics.queryBundleEvents(startMs, endMs);
      // 用前台 enter/exit 配对
      const enterMap: Map<string, number> = new Map();
      // 事件已按时间顺序返回
      for (const ev of events) {
        if (!UsageCollector.isTargetBundle(ev.bundleName)) continue;
        let ts = ev.eventOccurredTime || 0;
        if (ts < startMs) ts = startMs;
        if (ts > endMs) ts = endMs;
        const t = ev.eventId;
        // HarmonyOS BundleEvents.eventId: 1 = 前台(FOREGROUND), 2 = 后台(BACKGROUND)
        if (t === 1) {
          enterMap.set(ev.bundleName, ts);
        } else if (t === 2) {
          const enter = enterMap.get(ev.bundleName);
          if (enter !== undefined && ts > enter) {
            UsageCollector.addToHourShape(hours, enter, ts);
            enterMap.delete(ev.bundleName);
          }
        }
      }
      // 没等到 background 的，按 endMs 收口
      for (const [, enter] of enterMap) {
        UsageCollector.addToHourShape(hours, enter, endMs);
      }
    } catch (e) {
      const err = e as BusinessError;
      console.warn(`[UsageCollector] queryHourShape err: ${err.code} ${err.message}`);
    }
    return hours;
  }

  private static addToHourShape(hours: number[], startMs: number, endMs: number): void {
    if (endMs <= startMs) return;
    let cursor = startMs;
    while (cursor < endMs) {
      const d = new Date(cursor);
      d.setMinutes(59); d.setSeconds(59); d.setMilliseconds(999);
      const hourEnd = Math.min(d.getTime() + 1, endMs);
      const h = new Date(cursor).getHours();
      hours[h] += (hourEnd - cursor);
      cursor = hourEnd;
    }
  }

  private static isTargetBundle(bundleName: string): boolean {
    if (!bundleName) return false;
    if (BILI_BUNDLES.indexOf(bundleName) >= 0) return true;
    const lower = bundleName.toLowerCase();
    return lower.indexOf('bilibili') >= 0;
  }

  /** 收集最近 N 天的逐日统计（含今日） */
  static async collectRecent(days: number): Promise<DayPayload[]> {
    const out: DayPayload[] = [];
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    for (let i = 0; i < days; i++) {
      const dayStart = new Date(today.getTime() - i * 24 * 3600 * 1000);
      const dayEnd = new Date(dayStart.getTime() + 24 * 3600 * 1000);
      const b = await UsageCollector.queryDayBuckets(dayStart.getTime(), dayEnd.getTime());
      out.push({
        date: UsageCollector.fmtDate(dayStart),
        startMs: dayStart.getTime(),
        endMs: dayEnd.getTime(),
        totalMs: b.totalMs,
        byBundle: Array.from(b.byBundle.entries()).map(([k, v]) => new BundleItem(k, v)),
        byHour: b.byHour
      });
    }
    return out;
  }

  static fmtDate(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${dd}`;
  }
}

export class BundleItem {
  bundle: string;
  durationMs: number;
  constructor(bundle: string, durationMs: number) {
    this.bundle = bundle;
    this.durationMs = durationMs;
  }
}

export class DayPayload {
  date: string = '';
  startMs: number = 0;
  endMs: number = 0;
  totalMs: number = 0;
  byBundle: BundleItem[] = [];
  byHour: number[] = [];
}
