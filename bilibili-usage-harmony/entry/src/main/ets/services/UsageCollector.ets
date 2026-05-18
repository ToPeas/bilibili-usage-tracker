export class DayBuckets {
  totalMs: number = 0;
  byBundle: Map<string, number> = new Map<string, number>();
  byHour: number[] = new Array<number>(24).fill(0);
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

export class UsageCollector {
  static canReadDetailedAppUsage(): boolean {
    return false;
  }

  static unsupportedReason(): string {
    return '当前使用 OpenHarmony SDK 20，系统没有向三方应用开放读取其它 App 使用时长的 API。';
  }

  /**
   * 当前项目使用的是 OpenHarmony SDK 20。这个 SDK 不提供 HarmonyOS 的
   * resourceschedule.usageStatistics 应用使用记录 API，所以这里先返回空桶，
   * 保持项目可编译、D1 设置和查询 UI 可用。
   */
  static async queryDayBuckets(_startMs: number, _endMs: number): Promise<DayBuckets> {
    return new DayBuckets();
  }

  /** 收集最近 N 天的逐日统计（含今日）。 */
  static async collectRecent(days: number): Promise<DayPayload[]> {
    const out: DayPayload[] = [];
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    for (let i = 0; i < days; i++) {
      const dayStart = new Date(today.getTime() - i * 24 * 3600 * 1000);
      const dayEnd = new Date(dayStart.getTime() + 24 * 3600 * 1000);
      const buckets = await UsageCollector.queryDayBuckets(dayStart.getTime(), dayEnd.getTime());
      const payload = new DayPayload();
      payload.date = UsageCollector.fmtDate(dayStart);
      payload.startMs = dayStart.getTime();
      payload.endMs = dayEnd.getTime();
      payload.totalMs = buckets.totalMs;
      payload.byBundle = UsageCollector.bundleItemsFromMap(buckets.byBundle);
      payload.byHour = buckets.byHour;
      out.push(payload);
    }

    return out;
  }

  static fmtDate(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${dd}`;
  }

  private static bundleItemsFromMap(source: Map<string, number>): BundleItem[] {
    const result: BundleItem[] = [];
    source.forEach((durationMs: number, bundle: string) => {
      result.push(new BundleItem(bundle, durationMs));
    });
    return result;
  }
}
