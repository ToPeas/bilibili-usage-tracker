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
    return '当前版本先恢复可安装状态。系统使用时长权限接入需要单独验证 HarmonyOS profile。';
  }

  static async queryDayBuckets(_startMs: number, _endMs: number): Promise<DayBuckets> {
    return new DayBuckets();
  }

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

  static async collectRecentForUpload(days: number, includeToday: boolean): Promise<DayPayload[]> {
    const out: DayPayload[] = [];
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const totalDays = Math.max(1, days);
    const startOffset = includeToday ? 0 : 1;

    for (let i = startOffset; i < startOffset + totalDays; i++) {
      const dayStart = new Date(today.getTime() - i * 24 * 3600 * 1000);
      const dayEnd = new Date(dayStart.getTime() + 24 * 3600 * 1000);
      const buckets = await UsageCollector.queryDayBuckets(dayStart.getTime(), dayEnd.getTime());
      if (buckets.totalMs <= 0) {
        continue;
      }
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
      if (durationMs > 0) {
        result.push(new BundleItem(bundle, durationMs));
      }
    });
    return result;
  }
}
