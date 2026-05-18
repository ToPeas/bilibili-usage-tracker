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
          this.message = '请在系统设置中授予「应用使用记录」权限后再回来';
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
        this.cloudDays = await client.queryRecent(7);
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
    const perm: Permissions = 'ohos.permission.BUNDLE_ACTIVE_INFO';
    try {
      const result: PermissionRequestResult = await am.requestPermissionsFromUser(ctx, [perm]);
      const code = result.authResults && result.authResults.length > 0 ? result.authResults[0] : -1;
      return code === 0;
    } catch (_e) {
      return false;
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

        // 24 小时折线
        Column() {
          Text(this.selectedDateLabel())
            .fontSize(13).fontWeight(700).fontColor(INK).margin({ bottom: 4 })
          Text(this.selectedDateSub())
            .fontSize(11).fontColor(INK_SOFT).margin({ bottom: 8 })
          HourChart({ hours: this.selectedHourArr() })
            .width('100%').height(180)
        }
        .padding(16)
        .width('100%')
        .alignItems(HorizontalAlign.Start)

        // 云端设备列表
        Column() {
          Text('云端设备 · ' + this.selectedDateLabel())
            .fontSize(13).fontWeight(700).fontColor(INK).margin({ bottom: 8 })
          if (this.cloudDevicesForSelected().length === 0) {
            Text('暂无云端数据').fontSize(12).fontColor(INK_SOFT)
          } else {
            ForEach(this.cloudDevicesForSelected(), (row: DayRow) => {
              Row() {
                Text(this.srcTag(row.source))
                  .fontSize(10).fontColor('#FFFFFF')
                  .padding({ left: 6, right: 6, top: 2, bottom: 2 })
                  .backgroundColor(this.srcColor(row.source))
                  .borderRadius(6)
                Text(row.deviceAlias || row.deviceId)
                  .fontSize(13).fontColor(INK).margin({ left: 6 }).layoutWeight(1)
                Text(fmtDur(row.totalMs))
                  .fontSize(13).fontColor(PINK_DEEP).fontWeight(600)
              }
              .width('100%')
              .padding({ top: 8, bottom: 8 })
            }, (row: DayRow) => row.deviceId + '|' + row.source + '|' + row.date)
          }
        }
        .padding(16)
        .width('100%')
        .alignItems(HorizontalAlign.Start)
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

  cloudDevicesForSelected(): DayRow[] {
    if (!this.selectedDate) return [];
    return this.cloudDays.filter(r => r.date === this.selectedDate);
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

    const padLeft = 44; const padRight = 12; const padTop = 24; const padBot = 26;
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

/* ===================== 折线图组件（24 小时） ===================== */
@Component
struct HourChart {
  @Prop hours: number[];
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

    const padLeft = 44; const padRight = 12; const padTop = 16; const padBot = 24;
    const chartLeft = padLeft;
    const chartRight = w - padRight;
    const chartTop = padTop;
    const chartBot = h - padBot;
    const innerW = chartRight - chartLeft;
    const innerH = chartBot - chartTop;

    const arr = this.hours || new Array<number>(24).fill(0);
    let rawMax = 0;
    for (const v of arr) if (v > rawMax) rawMax = v;
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

    // 24 个点
    const n = 24;
    const stepX = innerW / (n - 1);
    const xs: number[] = [];
    const ys: number[] = [];
    for (let i = 0; i < n; i++) {
      const v = arr[i] || 0;
      const cx = chartLeft + stepX * i;
      let y = chartBot - (v / niceMax) * innerH;
      if (v <= 0) y = chartBot;
      else y = Math.min(y, chartBot - 2);
      xs.push(cx); ys.push(y);
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

    // 非零点
    for (let i = 0; i < n; i++) {
      if ((arr[i] || 0) <= 0) continue;
      ctx.fillStyle = '#FFFFFF';
      ctx.beginPath();
      ctx.arc(xs[i], ys[i], 3, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = PINK;
      ctx.beginPath();
      ctx.arc(xs[i], ys[i], 1.9, 0, Math.PI * 2);
      ctx.fill();
    }

    // X 轴标签
    ctx.fillStyle = INK_SOFT;
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    const labels: number[] = [0, 6, 12, 18, 23];
    for (const i of labels) {
      ctx.fillText(`${i}:00`, xs[i], chartBot + 4);
    }
  }
}
