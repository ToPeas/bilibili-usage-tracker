import { abilityAccessCtrl, common, Permissions, PermissionRequestResult } from '@kit.AbilityKit';
import { promptAction, router } from '@kit.ArkUI';
import { SettingsStore, Settings } from '../common/SettingsStore';
import { UsageCollector, DayPayload } from '../services/UsageCollector';
import { D1Client, DayRow } from '../services/D1Client';

const PINK = '#FB7299';
const PINK_DEEP = '#E45378';
const PINK_SOFT = '#FFE6EE';
const PINK_FILL = 'rgba(251,114,153,0.30)';
const PINK_FILL2 = 'rgba(251,114,153,0.02)';
const INK = '#222222';
const INK_SOFT = '#6B6470';
const GRID = '#F0E8EE';

// --- 通用工具 ---
function niceCeilMs(ms: number): number {
  if (ms <= 0) return 60_000;
  const steps: number[] = [
    60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000,
    60 * 60_000, 2 * 60 * 60_000, 3 * 60 * 60_000, 4 * 60 * 60_000,
    6 * 60 * 60_000, 8 * 60 * 60_000, 12 * 60 * 60_000, 24 * 60 * 60_000
  ];
  for (const s of steps) if (ms <= s) return s;
  return Math.ceil(ms / 3600_000) * 3600_000;
}

function axisLabelMs(ms: number): string {
  if (ms <= 0) return '0';
  const s = Math.round(ms / 1000);
  if (s < 60) return s + 's';
  const m = Math.round(s / 60);
  if (m < 60) return m + 'm';
  const hh = Math.floor(m / 60);
  const rem = m % 60;
  if (rem === 0) return hh + 'h';
  return hh + 'h' + rem + 'm';
}

function fmtDur(ms: number): string {
  ms = Math.max(0, Math.round(ms));
  if (ms < 1000) return ms + ' 毫秒';
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}小时${m}分${sec}秒`;
  if (m > 0) return `${m}分${sec}秒`;
  return sec + '秒';
}

function shortLabel(date: string): string {
  const parts = date.split('-');
  if (parts.length !== 3) return date;
  return `${Number(parts[1])}/${Number(parts[2])}`;
}

@Entry
@Component
struct Index {
  @State todayLocal: string = '--';
  @State todayAll: string = '--';
  @State recentDays: DayPayload[] = [];
  @State cloudDays: DayRow[] = [];
  @State selectedDate: string = '';
  @State refreshing: boolean = false;
  @State message: string = '';
  @State permGranted: boolean = false;
  @State usageSupported: boolean = false;
  @State backfilling: boolean = false;
  @State backfillResult: string = '';

  async aboutToAppear(): Promise<void> {
    await this.refresh();
  }

  private async refresh(): Promise<void> {
    if (this.refreshing) return;
    this.refreshing = true;
    this.message = '';
    try {
      const ctx = getContext(this) as common.UIAbilityContext;
      this.usageSupported = UsageCollector.canReadDetailedAppUsage();
      if (this.usageSupported) {
        const ok = await this.ensurePermission(ctx);
        this.permGranted = ok;
        if (!ok) {
          this.message = '请在系统设置 → 隐私 → 应用使用记录 中授予本应用权限后再回来';
          return;
        }
      } else {
        this.permGranted = false;
        this.message = UsageCollector.unsupportedReason();
      }

      this.recentDays = await UsageCollector.collectRecent(7);
      const todayKey = UsageCollector.fmtDate(new Date());
      const today = this.recentDays.find(d => d.date === todayKey);
      this.todayLocal = fmtDur(today ? today.totalMs : 0);
      // 始终默认选中"今天"
      this.selectedDate = todayKey;

      const s = await SettingsStore.get(ctx);
      if (s.accountId && s.databaseId && s.apiToken) {
        const client = new D1Client(s);
        const schemaResp = await client.ensureSchema();
        if (schemaResp.ok) {
          if (this.usageSupported && today && today.totalMs > 0) {
            const up = await client.uploadDay(today);
            if (!up.ok) this.message = '上传失败：' + up.error;
          }
        } else {
          this.message = '建表失败：' + schemaResp.error;
        }
        this.cloudDays = await client.queryRecentWithHours(7);
        const todayCloud = this.cloudDays.filter(r => r.date === todayKey)
          .reduce((sum, r) => sum + r.totalMs, 0);
        this.todayAll = fmtDur(todayCloud);
      } else {
        this.todayAll = '未配置 D1';
      }
    } catch (e) {
      this.message = '错误：' + String((e as Error).message || e);
    } finally {
      this.refreshing = false;
    }
  }

  private async ensurePermission(ctx: common.UIAbilityContext): Promise<boolean> {
    const am = abilityAccessCtrl.createAtManager();
    const perms: Permissions[] = [
      'ohos.permission.BUNDLE_ACTIVE_INFO',
      'ohos.permission.KEEP_BACKGROUND_RUNNING'
    ];
    try {
      const result: PermissionRequestResult = await am.requestPermissionsFromUser(ctx, perms);
      const code = result.authResults && result.authResults.length > 0 ? result.authResults[0] : -1;
      return code === 0;
    } catch (_e) {
      return false;
    }
  }

  /** 手动补传最近 N 天历史数据（对应安卓端 DailyUploadReceiver.upload 手动入口） */
  private async backfill(days: number): Promise<void> {
    if (this.backfilling) return;
    this.backfilling = true;
    this.backfillResult = '补传中…';
    try {
      const ctx = getContext(this) as common.UIAbilityContext;
      const s = await SettingsStore.get(ctx);
      if (!s.accountId || !s.databaseId || !s.apiToken) {
        this.backfillResult = 'D1 未配置，请先填写设置';
        return;
      }
      const client = new D1Client(s);
      await client.ensureSchema();
      const payloads = await UsageCollector.collectRecentForUpload(days, false);
      let uploaded = 0;
      for (const day of payloads) {
        const r = await client.uploadDay(day);
        if (r.ok) uploaded++;
      }
      this.backfillResult = `补传完成：已上传 ${uploaded} 天`;
      // 刷新云端列表
      this.cloudDays = await client.queryRecentWithHours(7);
    } catch (e) {
      this.backfillResult = '补传失败：' + String((e as Error).message || e);
    } finally {
      this.backfilling = false;
    }
  }

  build() {
    Scroll() {
      Column() {
        // Hero
        Column() {
          Row() {
            Text('B').fontSize(18).fontWeight(700).fontColor(PINK)
              .width(34).height(34).backgroundColor('#FFFFFF').borderRadius(10)
              .textAlign(TextAlign.Center)
            Column() {
              Text('鸿蒙原生 · 今日').fontSize(11).fontColor('#FFD4DF')
              Text(this.todayLocal).fontSize(26).fontWeight(700).fontColor('#FFFFFF')
                .margin({ top: 2 })
              Row() {
                Text('全设备总计 · 今日').fontSize(11).fontColor('#FFFFFF').opacity(0.85)
                Text(this.todayAll).fontSize(13).fontWeight(700).fontColor('#FFFFFF')
                  .margin({ left: 6 })
              }
              .padding({ left: 8, right: 8, top: 2, bottom: 2 })
              .backgroundColor('rgba(255,255,255,0.18)')
              .borderRadius(8)
              .margin({ top: 6 })
            }
            .alignItems(HorizontalAlign.Start)
            .margin({ left: 10 })
            .layoutWeight(1)

            Text('⚙').fontSize(20).fontColor('#FFFFFF')
              .padding(6)
              .onClick(() => router.pushUrl({ url: 'pages/Settings' }))
          }
          .width('100%')

          Row() {
            this.chip(this.usageSupported ? (this.permGranted ? '权限已授予' : '权限未授予') : '系统不开放统计',
              this.usageSupported ? (this.permGranted ? '#34C759' : '#FF3B30') : '#FF9500')
            this.chip(this.todayAll === '未配置 D1' ? 'D1 未配置' : 'D1 已连接',
              this.todayAll === '未配置 D1' ? '#FF9500' : '#34C759')
          }.margin({ top: 12 })
        }
        .width('100%')
        .padding(16)
        .backgroundColor(PINK)
        .alignItems(HorizontalAlign.Start)

        if (this.message) {
          Text(this.message).fontSize(12).fontColor('#E45378')
            .padding(10).width('100%')
            .backgroundColor(PINK_SOFT)
        }

        // 最近 7 天 折线图
        Column() {
          Row() {
            Text('最近 7 天').fontSize(14).fontWeight(700).fontColor(INK).layoutWeight(1)
            Button(this.refreshing ? '刷新中…' : '刷新')
              .fontSize(12).height(28).type(ButtonType.Capsule)
              .backgroundColor(this.refreshing ? '#CCC' : PINK)
              .onClick(() => this.refresh())
          }.width('100%').margin({ bottom: 8 })

          TrendChart({
            days: this.recentDays,
            selectedDate: this.selectedDate,
            onSelect: (date: string): void => { this.selectedDate = date; }
          })
            .width('100%').height(200)
        }
        .padding(16)
        .width('100%')
        .alignItems(HorizontalAlign.Start)

        // 选中日期详情
        Column() {
          Text(this.selectedDateTitle())
            .fontSize(20).fontWeight(700).fontColor('#111827').margin({ bottom: 4 })
          Text(this.selectedDateSummary())
            .fontSize(13).fontColor(INK_SOFT).margin({ bottom: 10 })
          HourChart({
            rows: this.cloudDevicesForSelected(),
            fallbackHours: this.selectedHourArr(),
            colorOf: (row: DayRow): string => this.deviceColor(row)
          })
            .width('100%').height(188)
            .margin({ bottom: 10 })
          if (this.cloudDevicesForSelected().length === 0) {
            Text('暂无云端数据').fontSize(12).fontColor(INK_SOFT)
          } else {
            ForEach(this.cloudDevicesForSelected(), (row: DayRow) => {
              Column() {
                Row() {
                  Circle()
                    .width(10).height(10)
                    .fill(this.deviceColor(row))
                    .margin({ right: 10 })
                  Text(row.deviceAlias || row.deviceId)
                    .fontSize(16).fontColor('#111827').fontWeight(700).layoutWeight(1)
                  Text(fmtDur(row.totalMs))
                    .fontSize(16).fontColor('#111827').fontWeight(700)
                }
                .width('100%')

                Text('上传 ' + this.uploadLabel(row.uploadedAt))
                  .fontSize(13).fontColor('#9AA3AF')
                  .margin({ left: 20, top: 8 })
              }
              .width('100%')
              .padding({ top: 14, bottom: 14 })
            }, (row: DayRow) => row.deviceId + '|' + row.source + '|' + row.date)
          }
        }
        .padding(16)
        .width('100%')
        .alignItems(HorizontalAlign.Start)

        // 历史补传区（对应安卓 DailyUploadReceiver 手动入口）
        Column() {
          Text('历史数据补传').fontSize(13).fontWeight(700).fontColor(INK).margin({ bottom: 8 })
          Text('手动补传最近 N 天的使用记录到 D1（仅上传有数据的日期，不会覆盖其它设备记录）')
            .fontSize(11).fontColor(INK_SOFT).margin({ bottom: 10 })
          Row() {
            Button(this.backfilling ? '补传中…' : '补传 7 天')
              .fontSize(12).height(32).type(ButtonType.Capsule)
              .backgroundColor(this.backfilling ? '#CCC' : PINK)
              .margin({ right: 8 })
              .onClick(() => this.backfill(7))
            Button(this.backfilling ? '补传中…' : '补传 30 天')
              .fontSize(12).height(32).type(ButtonType.Capsule)
              .backgroundColor(this.backfilling ? '#CCC' : PINK_DEEP)
              .margin({ right: 8 })
              .onClick(() => this.backfill(30))
            Button(this.backfilling ? '补传中…' : '补传 180 天')
              .fontSize(12).height(32).type(ButtonType.Capsule)
              .backgroundColor(this.backfilling ? '#CCC' : '#555')
              .onClick(() => this.backfill(180))
          }.width('100%')
          if (this.backfillResult) {
            Text(this.backfillResult)
              .fontSize(12).fontColor(this.backfillResult.indexOf('失败') >= 0 ? '#E45378' : '#34C759')
              .margin({ top: 8 })
          }
        }
        .padding(16)
        .width('100%')
        .alignItems(HorizontalAlign.Start)
        .backgroundColor('#FAFAFA')
      }
      .width('100%')
    }
    .width('100%').height('100%')
    .backgroundColor('#FFFFFF')
  }

  @Builder chip(text: string, color: string) {
    Text(text).fontSize(11).fontColor('#FFFFFF')
      .padding({ left: 8, right: 8, top: 3, bottom: 3 })
      .backgroundColor(color)
      .borderRadius(10)
      .margin({ right: 6 })
  }

  selectedHourArr(): number[] {
    if (!this.selectedDate || !this.recentDays.length) return new Array<number>(24).fill(0);
    const found = this.recentDays.find(d => d.date === this.selectedDate);
    return found ? found.byHour : new Array<number>(24).fill(0);
  }

  selectedDateLabel(): string {
    if (!this.selectedDate) return '今日';
    const today = UsageCollector.fmtDate(new Date());
    if (this.selectedDate === today) return '今日 · ' + this.selectedDate;
    return this.selectedDate;
  }

  selectedDateSub(): string {
    if (!this.usageSupported) {
      return '本机：当前 SDK 不支持读取其它 App 使用时长';
    }
    const arr = this.selectedHourArr();
    let sum = 0; for (const v of arr) sum += v;
    return '本机：' + fmtDur(sum) + ' ｜ 24 小时分布';
  }

  selectedDateTitle(): string {
    return this.selectedDate || UsageCollector.fmtDate(new Date());
  }

  selectedDateSummary(): string {
    const rows = this.cloudDevicesForSelected();
    let total = 0;
    for (const row of rows) {
      total += row.totalMs || 0;
    }
    if (rows.length > 0) {
      return '总计 ' + fmtDur(total) + ' · ' + rows.length + ' 台设备';
    }
    const arr = this.selectedHourArr();
    for (const v of arr) {
      total += v || 0;
    }
    return '总计 ' + fmtDur(total) + ' · 本机';
  }

  cloudDevicesForSelected(): DayRow[] {
    if (!this.selectedDate) return [];
    return this.cloudDays.filter(r => r.date === this.selectedDate);
  }

  deviceColor(row: DayRow): string {
    const alias = row.deviceAlias || row.deviceId || '';
    if (alias.indexOf('平板') >= 0 || row.source === 'harmony') return '#43D11D';
    if (alias.indexOf('公司') >= 0) return '#FF9500';
    if (alias.indexOf('家里') >= 0 && row.source.toLowerCase() !== 'web' && row.source.toLowerCase() !== 'browser') return '#29B6E6';
    const k = row.source.toLowerCase();
    if (k === 'app' || k === 'android') return '#43D11D';
    if (k === 'web' || k === 'browser') return '#FB7299';
    const colors: string[] = ['#29B6E6', '#7A35D6', '#FF9500', '#43D11D', '#FB7299'];
    let hash = 0;
    for (let i = 0; i < alias.length; i++) {
      hash = (hash + alias.charCodeAt(i)) % colors.length;
    }
    return colors[hash];
  }

  uploadLabel(uploadedAt: string): string {
    if (!uploadedAt) return '--';
    const m = uploadedAt.match(/(\d{2}):(\d{2})/);
    if (m && this.selectedDate) {
      return shortLabel(this.selectedDate).replace('/', '-') + ' ' + m[1] + ':' + m[2];
    }
    return uploadedAt;
  }

  srcTag(s: string): string {
    const k = (s || '').toLowerCase();
    if (k === 'app' || k === 'android') return 'Android';
    if (k === 'web' || k === 'browser') return '浏览器';
    if (k === 'harmony') return '鸿蒙';
    return k || '未知';
  }
  srcColor(s: string): string {
    const k = (s || '').toLowerCase();
    if (k === 'app' || k === 'android') return '#2F7BFF';
    if (k === 'web' || k === 'browser') return '#FB7299';
    if (k === 'harmony') return '#34C759';
    return '#999999';
  }
}

/* ===================== 折线图组件（最近 N 天） ===================== */
@Component
struct TrendChart {
  @Prop days: DayPayload[];
  @Prop selectedDate: string;
  onSelect: (date: string) => void = (): void => {};

  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private clickXs: number[] = [];

  build() {
    Canvas(this.ctx)
      .width('100%').height('100%')
      .backgroundColor('#FFFFFF')
      .onReady((): void => { this.draw(); })
      .onClick((event?: ClickEvent): void => { if (event) this.onCanvasClick(event); })
  }

  // 触发重绘
  aboutToAppear(): void {}

  // 当 selectedDate / days 改变时 ArkUI 会自动调 onReady? 兼容写法：在 build 里通过 stack 也可
  // 这里通过 @Watch
  // 注：ArkTS 不支持 watch 同时用 @Prop，得手动在父组件 setState 时重建 — 我们用 key 让组件刷新
  // 简化：直接每次 build → reset ctx + 在 draw 里读取最新数据

  private onCanvasClick(event: ClickEvent): void {
    if (!this.days || this.days.length === 0 || this.clickXs.length === 0) return;
    const x = event.x;
    // 最近邻：找 clickXs 中距离 x 最近的那个
    let bestIdx = 0;
    let bestDist = Number.POSITIVE_INFINITY;
    for (let i = 0; i < this.clickXs.length; i++) {
      const d = Math.abs(this.clickXs[i] - x);
      if (d < bestDist) { bestDist = d; bestIdx = i; }
    }
    const sorted = this.sortedDays();
    if (bestIdx >= 0 && bestIdx < sorted.length) {
      this.onSelect(sorted[bestIdx].date);
      this.draw();
    }
  }

  private sortedDays(): DayPayload[] {
    return [...(this.days || [])].sort((a, b) => a.date < b.date ? -1 : 1);
  }

  private draw(): void {
    const ctx = this.ctx;
    const w = ctx.width;
    const h = ctx.height;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#FFFFFF';
    ctx.fillRect(0, 0, w, h);

    const sorted = this.sortedDays();
    if (sorted.length === 0) {
      ctx.fillStyle = INK_SOFT;
      ctx.font = '12px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('暂无数据', w / 2, h / 2);
      return;
    }

    const padLeft = 30; const padRight = 2; const padTop = 24; const padBot = 26;
    const chartLeft = padLeft;
    const chartRight = w - padRight;
    const chartTop = padTop;
    const chartBot = h - padBot;
    const innerW = chartRight - chartLeft;
    const innerH = chartBot - chartTop;

    const rawMax = sorted.reduce<number>((m: number, d: DayPayload) => Math.max(m, d.totalMs || 0), 0);
    const niceMax = niceCeilMs(Math.max(60_000, rawMax));

    // Y 轴 4 段 + 数值
    ctx.strokeStyle = GRID;
    ctx.lineWidth = 1;
    ctx.fillStyle = INK_SOFT;
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    for (let i = 0; i <= 4; i++) {
      const y = chartTop + innerH * i / 4;
      ctx.beginPath();
      ctx.moveTo(chartLeft, y);
      ctx.lineTo(chartRight, y);
      ctx.stroke();
      const value = niceMax * (1 - i / 4);
      ctx.fillText(axisLabelMs(value), chartLeft - 4, y);
    }

    const n = sorted.length;
    const slot = innerW / n;
    const xs: number[] = [];
    const ys: number[] = [];
    for (let i = 0; i < n; i++) {
      const d = sorted[i];
      const cx = chartLeft + slot * (i + 0.5);
      const ratio = (d.totalMs || 0) / niceMax;
      let y = chartBot - innerH * ratio;
      if (!d.totalMs) y = chartBot;
      else y = Math.min(y, chartBot - 2);
      xs.push(cx); ys.push(y);
    }
    this.clickXs = xs;

    // 选中背景高亮
    const selIdx = sorted.findIndex(d => d.date === this.selectedDate);
    if (selIdx >= 0) {
      ctx.fillStyle = 'rgba(251,114,153,0.10)';
      ctx.fillRect(xs[selIdx] - slot / 2 + 2, chartTop - 4, slot - 4, innerH + 4);
    }

    // 填充
    const grad = ctx.createLinearGradient(0, chartTop, 0, chartBot);
    grad.addColorStop(0, PINK_FILL);
    grad.addColorStop(1, PINK_FILL2);
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.moveTo(xs[0], chartBot);
    ctx.lineTo(xs[0], ys[0]);
    for (let i = 1; i < n; i++) {
      const midX = (xs[i - 1] + xs[i]) / 2;
      ctx.bezierCurveTo(midX, ys[i - 1], midX, ys[i], xs[i], ys[i]);
    }
    ctx.lineTo(xs[n - 1], chartBot);
    ctx.closePath();
    ctx.fill();

    // 折线
    ctx.strokeStyle = PINK;
    ctx.lineWidth = 2;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(xs[0], ys[0]);
    for (let i = 1; i < n; i++) {
      const midX = (xs[i - 1] + xs[i]) / 2;
      ctx.bezierCurveTo(midX, ys[i - 1], midX, ys[i], xs[i], ys[i]);
    }
    ctx.stroke();

    // 点
    for (let i = 0; i < n; i++) {
      const isSel = i === selIdx;
      ctx.fillStyle = '#FFFFFF';
      ctx.beginPath();
      ctx.arc(xs[i], ys[i], isSel ? 5 : (sorted[i].totalMs > 0 ? 3.5 : 2), 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = isSel ? PINK_DEEP : (sorted[i].totalMs > 0 ? PINK : '#E9CCD7');
      ctx.beginPath();
      ctx.arc(xs[i], ys[i], isSel ? 3.5 : (sorted[i].totalMs > 0 ? 2.2 : 1.5), 0, Math.PI * 2);
      ctx.fill();
      if (isSel) {
        ctx.strokeStyle = PINK_DEEP;
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.arc(xs[i], ys[i], 6, 0, Math.PI * 2);
        ctx.stroke();
      }
    }

    // 选中 tooltip
    if (selIdx >= 0) {
      const d = sorted[selIdx];
      const text = `${shortLabel(d.date)} · ${fmtDur(d.totalMs)}`;
      ctx.font = '11px sans-serif';
      const tw = ctx.measureText(text).width + 16;
      const th = 20;
      let tx = xs[selIdx] - tw / 2;
      if (tx < chartLeft) tx = chartLeft;
      if (tx + tw > chartRight) tx = chartRight - tw;
      let ty = ys[selIdx] - th - 8;
      if (ty < 2) ty = 2;
      ctx.fillStyle = '#18181B';
      ctx.beginPath();
      ctx.moveTo(tx + 6, ty);
      ctx.lineTo(tx + tw - 6, ty);
      ctx.quadraticCurveTo(tx + tw, ty, tx + tw, ty + 6);
      ctx.lineTo(tx + tw, ty + th - 6);
      ctx.quadraticCurveTo(tx + tw, ty + th, tx + tw - 6, ty + th);
      ctx.lineTo(tx + 6, ty + th);
      ctx.quadraticCurveTo(tx, ty + th, tx, ty + th - 6);
      ctx.lineTo(tx, ty + 6);
      ctx.quadraticCurveTo(tx, ty, tx + 6, ty);
      ctx.closePath();
      ctx.fill();
      ctx.fillStyle = '#FFFFFF';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(text, tx + tw / 2, ty + th / 2);
    }

    // X 轴标签
    ctx.fillStyle = INK_SOFT;
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    const step = Math.max(1, Math.ceil(n / 6));
    for (let i = 0; i < n; i += step) {
      ctx.fillText(shortLabel(sorted[i].date), xs[i], chartBot + 4);
    }
    if (n > 0 && (n - 1) % step !== 0) {
      ctx.fillText(shortLabel(sorted[n - 1].date), xs[n - 1], chartBot + 4);
    }
  }
}

/* ===================== 柱状图组件（24 小时） ===================== */
@Component
struct HourChart {
  @Prop rows: DayRow[];
  @Prop fallbackHours: number[];
  colorOf: (row: DayRow) => string = (_row: DayRow): string => PINK;

  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);

  build() {
    Canvas(this.ctx)
      .width('100%').height('100%')
      .backgroundColor('#FFFFFF')
      .onReady((): void => { this.draw(); })
  }

  private draw(): void {
    const ctx = this.ctx;
    const w = ctx.width;
    const h = ctx.height;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#FFFFFF';
    ctx.fillRect(0, 0, w, h);

    const padLeft = 30; const padRight = 2; const padTop = 14; const padBot = 24;
    const chartLeft = padLeft;
    const chartRight = w - padRight;
    const chartTop = padTop;
    const chartBot = h - padBot;
    const innerW = chartRight - chartLeft;
    const innerH = chartBot - chartTop;

    const rows = this.rows || [];
    const fallback = this.fallbackHours || new Array<number>(24).fill(0);
    const totals = new Array<number>(24).fill(0);
    if (rows.length > 0) {
      for (const row of rows) {
        const arr = row.byHour || new Array<number>(24).fill(0);
        for (let h = 0; h < 24; h++) {
          totals[h] += arr[h] || 0;
        }
      }
    } else {
      for (let h = 0; h < 24; h++) {
        totals[h] = fallback[h] || 0;
      }
    }

    let rawMax = 0;
    for (const v of totals) if (v > rawMax) rawMax = v;
    if (rawMax <= 0) {
      ctx.fillStyle = INK_SOFT;
      ctx.font = '12px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('尚无时段数据', w / 2, h / 2);
      return;
    }
    const niceMax = niceCeilMs(rawMax);

    // Y 轴
    ctx.strokeStyle = GRID;
    ctx.lineWidth = 1;
    ctx.fillStyle = INK_SOFT;
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    for (let i = 0; i <= 4; i++) {
      const y = chartTop + innerH * i / 4;
      ctx.beginPath();
      ctx.moveTo(chartLeft, y);
      ctx.lineTo(chartRight, y);
      ctx.stroke();
      const value = niceMax * (1 - i / 4);
      ctx.fillText(axisLabelMs(value), chartLeft - 4, y);
    }

    // 24 个堆叠柱
    const n = 24;
    const slot = innerW / n;
    const barW = Math.max(4, Math.min(14, slot * 0.68));
    const radius = Math.min(4, barW / 2);
    for (let hIdx = 0; hIdx < n; hIdx++) {
      let used = 0;
      const x = chartLeft + slot * hIdx + (slot - barW) / 2;
      if (rows.length > 0) {
        for (const row of rows) {
          const value = (row.byHour && row.byHour[hIdx]) ? row.byHour[hIdx] : 0;
          if (value <= 0) continue;
          const height = Math.max(2, innerH * value / niceMax);
          const y = chartBot - used - height;
          ctx.fillStyle = this.colorOf(row);
          this.roundRect(ctx, x, y, barW, height, radius);
          used += height;
        }
      } else {
        const value = fallback[hIdx] || 0;
        if (value > 0) {
          const height = Math.max(2, innerH * value / niceMax);
          ctx.fillStyle = PINK;
          this.roundRect(ctx, x, chartBot - height, barW, height, radius);
        }
      }
    }

    // X 轴标签
    ctx.fillStyle = INK_SOFT;
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    const labels: number[] = [0, 6, 12, 18, 23];
    for (const i of labels) {
      ctx.fillText(String(i), chartLeft + slot * (i + 0.5), chartBot + 4);
    }
  }

  private roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
    const radius = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + radius, y);
    ctx.lineTo(x + w - radius, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + radius);
    ctx.lineTo(x + w, y + h - radius);
    ctx.quadraticCurveTo(x + w, y + h, x + w - radius, y + h);
    ctx.lineTo(x + radius, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - radius);
    ctx.lineTo(x, y + radius);
    ctx.quadraticCurveTo(x, y, x + radius, y);
    ctx.closePath();
    ctx.fill();
  }
}
