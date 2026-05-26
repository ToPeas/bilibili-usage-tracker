if (!("finalizeConstruction" in ViewPU.prototype)) {
    Reflect.set(ViewPU.prototype, "finalizeConstruction", () => { });
}
interface HourChart_Params {
    hours?: number[];
    settings?: RenderingContextSettings;
    ctx?: CanvasRenderingContext2D;
}
interface TrendChart_Params {
    days?: DayPayload[];
    selectedDate?: string;
    onSelect?: (date: string) => void;
    settings?: RenderingContextSettings;
    ctx?: CanvasRenderingContext2D;
    clickXs?: number[];
}
interface Index_Params {
    todayLocal?: string;
    todayAll?: string;
    recentDays?: DayPayload[];
    cloudDays?: DayRow[];
    selectedDate?: string;
    refreshing?: boolean;
    message?: string;
    permGranted?: boolean;
    usageSupported?: boolean;
    backfilling?: boolean;
    backfillResult?: string;
}
import abilityAccessCtrl from "@ohos:abilityAccessCtrl";
import type common from "@ohos:app.ability.common";
import type { Permissions } from "@ohos:abilityAccessCtrl";
import type { PermissionRequestResult } from "@ohos:abilityAccessCtrl";
import router from "@ohos:router";
import { SettingsStore } from "@bundle:com.example.biliusage/entry/ets/common/SettingsStore";
import { UsageCollector } from "@bundle:com.example.biliusage/entry/ets/services/UsageCollector";
import type { DayPayload } from "@bundle:com.example.biliusage/entry/ets/services/UsageCollector";
import { D1Client } from "@bundle:com.example.biliusage/entry/ets/services/D1Client";
import type { DayRow } from "@bundle:com.example.biliusage/entry/ets/services/D1Client";
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
    if (ms <= 0)
        return 60000;
    const steps: number[] = [
        60000, 5 * 60000, 10 * 60000, 15 * 60000, 30 * 60000,
        60 * 60000, 2 * 60 * 60000, 3 * 60 * 60000, 4 * 60 * 60000,
        6 * 60 * 60000, 8 * 60 * 60000, 12 * 60 * 60000, 24 * 60 * 60000
    ];
    for (const s of steps)
        if (ms <= s)
            return s;
    return Math.ceil(ms / 3600000) * 3600000;
}
function axisLabelMs(ms: number): string {
    if (ms <= 0)
        return '0';
    const s = Math.round(ms / 1000);
    if (s < 60)
        return s + 's';
    const m = Math.round(s / 60);
    if (m < 60)
        return m + 'm';
    const hh = Math.floor(m / 60);
    const rem = m % 60;
    if (rem === 0)
        return hh + 'h';
    return hh + 'h' + rem + 'm';
}
function fmtDur(ms: number): string {
    ms = Math.max(0, Math.round(ms));
    if (ms < 1000)
        return ms + ' 毫秒';
    const s = Math.floor(ms / 1000);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (h > 0)
        return `${h}小时${m}分${sec}秒`;
    if (m > 0)
        return `${m}分${sec}秒`;
    return sec + '秒';
}
function shortLabel(date: string): string {
    const parts = date.split('-');
    if (parts.length !== 3)
        return date;
    return `${Number(parts[1])}/${Number(parts[2])}`;
}
class Index extends ViewPU {
    constructor(parent, params, __localStorage, elmtId = -1, paramsLambda = undefined, extraInfo) {
        super(parent, __localStorage, elmtId, extraInfo);
        if (typeof paramsLambda === "function") {
            this.paramsGenerator_ = paramsLambda;
        }
        this.__todayLocal = new ObservedPropertySimplePU('--', this, "todayLocal");
        this.__todayAll = new ObservedPropertySimplePU('--', this, "todayAll");
        this.__recentDays = new ObservedPropertyObjectPU([], this, "recentDays");
        this.__cloudDays = new ObservedPropertyObjectPU([], this, "cloudDays");
        this.__selectedDate = new ObservedPropertySimplePU('', this, "selectedDate");
        this.__refreshing = new ObservedPropertySimplePU(false, this, "refreshing");
        this.__message = new ObservedPropertySimplePU('', this, "message");
        this.__permGranted = new ObservedPropertySimplePU(false, this, "permGranted");
        this.__usageSupported = new ObservedPropertySimplePU(false, this, "usageSupported");
        this.__backfilling = new ObservedPropertySimplePU(false, this, "backfilling");
        this.__backfillResult = new ObservedPropertySimplePU('', this, "backfillResult");
        this.setInitiallyProvidedValue(params);
        this.finalizeConstruction();
    }
    setInitiallyProvidedValue(params: Index_Params) {
        if (params.todayLocal !== undefined) {
            this.todayLocal = params.todayLocal;
        }
        if (params.todayAll !== undefined) {
            this.todayAll = params.todayAll;
        }
        if (params.recentDays !== undefined) {
            this.recentDays = params.recentDays;
        }
        if (params.cloudDays !== undefined) {
            this.cloudDays = params.cloudDays;
        }
        if (params.selectedDate !== undefined) {
            this.selectedDate = params.selectedDate;
        }
        if (params.refreshing !== undefined) {
            this.refreshing = params.refreshing;
        }
        if (params.message !== undefined) {
            this.message = params.message;
        }
        if (params.permGranted !== undefined) {
            this.permGranted = params.permGranted;
        }
        if (params.usageSupported !== undefined) {
            this.usageSupported = params.usageSupported;
        }
        if (params.backfilling !== undefined) {
            this.backfilling = params.backfilling;
        }
        if (params.backfillResult !== undefined) {
            this.backfillResult = params.backfillResult;
        }
    }
    updateStateVars(params: Index_Params) {
    }
    purgeVariableDependenciesOnElmtId(rmElmtId) {
        this.__todayLocal.purgeDependencyOnElmtId(rmElmtId);
        this.__todayAll.purgeDependencyOnElmtId(rmElmtId);
        this.__recentDays.purgeDependencyOnElmtId(rmElmtId);
        this.__cloudDays.purgeDependencyOnElmtId(rmElmtId);
        this.__selectedDate.purgeDependencyOnElmtId(rmElmtId);
        this.__refreshing.purgeDependencyOnElmtId(rmElmtId);
        this.__message.purgeDependencyOnElmtId(rmElmtId);
        this.__permGranted.purgeDependencyOnElmtId(rmElmtId);
        this.__usageSupported.purgeDependencyOnElmtId(rmElmtId);
        this.__backfilling.purgeDependencyOnElmtId(rmElmtId);
        this.__backfillResult.purgeDependencyOnElmtId(rmElmtId);
    }
    aboutToBeDeleted() {
        this.__todayLocal.aboutToBeDeleted();
        this.__todayAll.aboutToBeDeleted();
        this.__recentDays.aboutToBeDeleted();
        this.__cloudDays.aboutToBeDeleted();
        this.__selectedDate.aboutToBeDeleted();
        this.__refreshing.aboutToBeDeleted();
        this.__message.aboutToBeDeleted();
        this.__permGranted.aboutToBeDeleted();
        this.__usageSupported.aboutToBeDeleted();
        this.__backfilling.aboutToBeDeleted();
        this.__backfillResult.aboutToBeDeleted();
        SubscriberManager.Get().delete(this.id__());
        this.aboutToBeDeletedInternal();
    }
    private __todayLocal: ObservedPropertySimplePU<string>;
    get todayLocal() {
        return this.__todayLocal.get();
    }
    set todayLocal(newValue: string) {
        this.__todayLocal.set(newValue);
    }
    private __todayAll: ObservedPropertySimplePU<string>;
    get todayAll() {
        return this.__todayAll.get();
    }
    set todayAll(newValue: string) {
        this.__todayAll.set(newValue);
    }
    private __recentDays: ObservedPropertyObjectPU<DayPayload[]>;
    get recentDays() {
        return this.__recentDays.get();
    }
    set recentDays(newValue: DayPayload[]) {
        this.__recentDays.set(newValue);
    }
    private __cloudDays: ObservedPropertyObjectPU<DayRow[]>;
    get cloudDays() {
        return this.__cloudDays.get();
    }
    set cloudDays(newValue: DayRow[]) {
        this.__cloudDays.set(newValue);
    }
    private __selectedDate: ObservedPropertySimplePU<string>;
    get selectedDate() {
        return this.__selectedDate.get();
    }
    set selectedDate(newValue: string) {
        this.__selectedDate.set(newValue);
    }
    private __refreshing: ObservedPropertySimplePU<boolean>;
    get refreshing() {
        return this.__refreshing.get();
    }
    set refreshing(newValue: boolean) {
        this.__refreshing.set(newValue);
    }
    private __message: ObservedPropertySimplePU<string>;
    get message() {
        return this.__message.get();
    }
    set message(newValue: string) {
        this.__message.set(newValue);
    }
    private __permGranted: ObservedPropertySimplePU<boolean>;
    get permGranted() {
        return this.__permGranted.get();
    }
    set permGranted(newValue: boolean) {
        this.__permGranted.set(newValue);
    }
    private __usageSupported: ObservedPropertySimplePU<boolean>;
    get usageSupported() {
        return this.__usageSupported.get();
    }
    set usageSupported(newValue: boolean) {
        this.__usageSupported.set(newValue);
    }
    private __backfilling: ObservedPropertySimplePU<boolean>;
    get backfilling() {
        return this.__backfilling.get();
    }
    set backfilling(newValue: boolean) {
        this.__backfilling.set(newValue);
    }
    private __backfillResult: ObservedPropertySimplePU<string>;
    get backfillResult() {
        return this.__backfillResult.get();
    }
    set backfillResult(newValue: string) {
        this.__backfillResult.set(newValue);
    }
    async aboutToAppear(): Promise<void> {
        await this.refresh();
    }
    private async refresh(): Promise<void> {
        if (this.refreshing)
            return;
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
            }
            else {
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
                        if (!up.ok)
                            this.message = '上传失败：' + up.error;
                    }
                }
                else {
                    this.message = '建表失败：' + schemaResp.error;
                }
                this.cloudDays = await client.queryRecent(7);
                const todayCloud = this.cloudDays.filter(r => r.date === todayKey)
                    .reduce((sum, r) => sum + r.totalMs, 0);
                this.todayAll = fmtDur(todayCloud);
            }
            else {
                this.todayAll = '未配置 D1';
            }
        }
        catch (e) {
            this.message = '错误：' + String((e as Error).message || e);
        }
        finally {
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
        }
        catch (_e) {
            return false;
        }
    }
    /** 手动补传最近 N 天历史数据（对应安卓端 DailyUploadReceiver.upload 手动入口） */
    private async backfill(days: number): Promise<void> {
        if (this.backfilling)
            return;
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
                if (r.ok)
                    uploaded++;
            }
            this.backfillResult = `补传完成：已上传 ${uploaded} 天`;
            // 刷新云端列表
            this.cloudDays = await client.queryRecent(7);
        }
        catch (e) {
            this.backfillResult = '补传失败：' + String((e as Error).message || e);
        }
        finally {
            this.backfilling = false;
        }
    }
    initialRender() {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Scroll.create();
            Scroll.width('100%');
            Scroll.height('100%');
            Scroll.backgroundColor('#FFFFFF');
        }, Scroll);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.width('100%');
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            // Hero
            Column.create();
            // Hero
            Column.width('100%');
            // Hero
            Column.padding(16);
            // Hero
            Column.backgroundColor(PINK);
            // Hero
            Column.alignItems(HorizontalAlign.Start);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('B');
            Text.fontSize(18);
            Text.fontWeight(700);
            Text.fontColor(PINK);
            Text.width(34);
            Text.height(34);
            Text.backgroundColor('#FFFFFF');
            Text.borderRadius(10);
            Text.textAlign(TextAlign.Center);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.margin({ left: 10 });
            Column.layoutWeight(1);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('鸿蒙原生 · 今日');
            Text.fontSize(11);
            Text.fontColor('#FFD4DF');
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.todayLocal);
            Text.fontSize(26);
            Text.fontWeight(700);
            Text.fontColor('#FFFFFF');
            Text.margin({ top: 2 });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.padding({ left: 8, right: 8, top: 2, bottom: 2 });
            Row.backgroundColor('rgba(255,255,255,0.18)');
            Row.borderRadius(8);
            Row.margin({ top: 6 });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('全设备总计 · 今日');
            Text.fontSize(11);
            Text.fontColor('#FFFFFF');
            Text.opacity(0.85);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.todayAll);
            Text.fontSize(13);
            Text.fontWeight(700);
            Text.fontColor('#FFFFFF');
            Text.margin({ left: 6 });
        }, Text);
        Text.pop();
        Row.pop();
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('⚙');
            Text.fontSize(20);
            Text.fontColor('#FFFFFF');
            Text.padding(6);
            Text.onClick(() => router.pushUrl({ url: 'pages/Settings' }));
        }, Text);
        Text.pop();
        Row.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.margin({ top: 12 });
        }, Row);
        this.chip.bind(this)(this.usageSupported ? (this.permGranted ? '权限已授予' : '权限未授予') : '系统不开放统计', this.usageSupported ? (this.permGranted ? '#34C759' : '#FF3B30') : '#FF9500');
        this.chip.bind(this)(this.todayAll === '未配置 D1' ? 'D1 未配置' : 'D1 已连接', this.todayAll === '未配置 D1' ? '#FF9500' : '#34C759');
        Row.pop();
        // Hero
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.message) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        Text.create(this.message);
                        Text.fontSize(12);
                        Text.fontColor('#E45378');
                        Text.padding(10);
                        Text.width('100%');
                        Text.backgroundColor(PINK_SOFT);
                    }, Text);
                    Text.pop();
                });
            }
            // 最近 7 天 折线图
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                });
            }
        }, If);
        If.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            // 最近 7 天 折线图
            Column.create();
            // 最近 7 天 折线图
            Column.padding(16);
            // 最近 7 天 折线图
            Column.width('100%');
            // 最近 7 天 折线图
            Column.alignItems(HorizontalAlign.Start);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.margin({ bottom: 8 });
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('最近 7 天');
            Text.fontSize(14);
            Text.fontWeight(700);
            Text.fontColor(INK);
            Text.layoutWeight(1);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel(this.refreshing ? '刷新中…' : '刷新');
            Button.fontSize(12);
            Button.height(28);
            Button.type(ButtonType.Capsule);
            Button.backgroundColor(this.refreshing ? '#CCC' : PINK);
            Button.onClick(() => this.refresh());
        }, Button);
        Button.pop();
        Row.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            __Common__.create();
            __Common__.width('100%');
            __Common__.height(200);
        }, __Common__);
        {
            this.observeComponentCreation2((elmtId, isInitialRender) => {
                if (isInitialRender) {
                    let componentCall = new TrendChart(this, {
                        days: this.recentDays,
                        selectedDate: this.selectedDate,
                        onSelect: (date: string): void => { this.selectedDate = date; }
                    }, undefined, elmtId, () => { }, { page: "entry/src/main/ets/pages/Index.ets", line: 235, col: 11 });
                    ViewPU.create(componentCall);
                    let paramsLambda = () => {
                        return {
                            days: this.recentDays,
                            selectedDate: this.selectedDate,
                            onSelect: (date: string): void => { this.selectedDate = date; }
                        };
                    };
                    componentCall.paramsGenerator_ = paramsLambda;
                }
                else {
                    this.updateStateVarsOfChildByElmtId(elmtId, {
                        days: this.recentDays,
                        selectedDate: this.selectedDate
                    });
                }
            }, { name: "TrendChart" });
        }
        __Common__.pop();
        // 最近 7 天 折线图
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            // 24 小时折线
            Column.create();
            // 24 小时折线
            Column.padding(16);
            // 24 小时折线
            Column.width('100%');
            // 24 小时折线
            Column.alignItems(HorizontalAlign.Start);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.selectedDateLabel());
            Text.fontSize(13);
            Text.fontWeight(700);
            Text.fontColor(INK);
            Text.margin({ bottom: 4 });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(this.selectedDateSub());
            Text.fontSize(11);
            Text.fontColor(INK_SOFT);
            Text.margin({ bottom: 8 });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            __Common__.create();
            __Common__.width('100%');
            __Common__.height(180);
        }, __Common__);
        {
            this.observeComponentCreation2((elmtId, isInitialRender) => {
                if (isInitialRender) {
                    let componentCall = new HourChart(this, { hours: this.selectedHourArr() }, undefined, elmtId, () => { }, { page: "entry/src/main/ets/pages/Index.ets", line: 252, col: 11 });
                    ViewPU.create(componentCall);
                    let paramsLambda = () => {
                        return {
                            hours: this.selectedHourArr()
                        };
                    };
                    componentCall.paramsGenerator_ = paramsLambda;
                }
                else {
                    this.updateStateVarsOfChildByElmtId(elmtId, {
                        hours: this.selectedHourArr()
                    });
                }
            }, { name: "HourChart" });
        }
        __Common__.pop();
        // 24 小时折线
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            // 云端设备列表
            Column.create();
            // 云端设备列表
            Column.padding(16);
            // 云端设备列表
            Column.width('100%');
            // 云端设备列表
            Column.alignItems(HorizontalAlign.Start);
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('云端设备 · ' + this.selectedDateLabel());
            Text.fontSize(13);
            Text.fontWeight(700);
            Text.fontColor(INK);
            Text.margin({ bottom: 8 });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.cloudDevicesForSelected().length === 0) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        Text.create('暂无云端数据');
                        Text.fontSize(12);
                        Text.fontColor(INK_SOFT);
                    }, Text);
                    Text.pop();
                });
            }
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        ForEach.create();
                        const forEachItemGenFunction = _item => {
                            const row = _item;
                            this.observeComponentCreation2((elmtId, isInitialRender) => {
                                Row.create();
                                Row.width('100%');
                                Row.padding({ top: 8, bottom: 8 });
                            }, Row);
                            this.observeComponentCreation2((elmtId, isInitialRender) => {
                                Text.create(this.srcTag(row.source));
                                Text.fontSize(10);
                                Text.fontColor('#FFFFFF');
                                Text.padding({ left: 6, right: 6, top: 2, bottom: 2 });
                                Text.backgroundColor(this.srcColor(row.source));
                                Text.borderRadius(6);
                            }, Text);
                            Text.pop();
                            this.observeComponentCreation2((elmtId, isInitialRender) => {
                                Text.create(row.deviceAlias || row.deviceId);
                                Text.fontSize(13);
                                Text.fontColor(INK);
                                Text.margin({ left: 6 });
                                Text.layoutWeight(1);
                            }, Text);
                            Text.pop();
                            this.observeComponentCreation2((elmtId, isInitialRender) => {
                                Text.create(fmtDur(row.totalMs));
                                Text.fontSize(13);
                                Text.fontColor(PINK_DEEP);
                                Text.fontWeight(600);
                            }, Text);
                            Text.pop();
                            Row.pop();
                        };
                        this.forEachUpdateFunction(elmtId, this.cloudDevicesForSelected(), forEachItemGenFunction, (row: DayRow) => row.deviceId + '|' + row.source + '|' + row.date, false, false);
                    }, ForEach);
                    ForEach.pop();
                });
            }
        }, If);
        If.pop();
        // 云端设备列表
        Column.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            // 历史补传区（对应安卓 DailyUploadReceiver 手动入口）
            Column.create();
            // 历史补传区（对应安卓 DailyUploadReceiver 手动入口）
            Column.padding(16);
            // 历史补传区（对应安卓 DailyUploadReceiver 手动入口）
            Column.width('100%');
            // 历史补传区（对应安卓 DailyUploadReceiver 手动入口）
            Column.alignItems(HorizontalAlign.Start);
            // 历史补传区（对应安卓 DailyUploadReceiver 手动入口）
            Column.backgroundColor('#FAFAFA');
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('历史数据补传');
            Text.fontSize(13);
            Text.fontWeight(700);
            Text.fontColor(INK);
            Text.margin({ bottom: 8 });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('手动补传最近 N 天的使用记录到 D1（仅上传有数据的日期，不会覆盖其它设备记录）');
            Text.fontSize(11);
            Text.fontColor(INK_SOFT);
            Text.margin({ bottom: 10 });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel(this.backfilling ? '补传中…' : '补传 7 天');
            Button.fontSize(12);
            Button.height(32);
            Button.type(ButtonType.Capsule);
            Button.backgroundColor(this.backfilling ? '#CCC' : PINK);
            Button.margin({ right: 8 });
            Button.onClick(() => this.backfill(7));
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel(this.backfilling ? '补传中…' : '补传 30 天');
            Button.fontSize(12);
            Button.height(32);
            Button.type(ButtonType.Capsule);
            Button.backgroundColor(this.backfilling ? '#CCC' : PINK_DEEP);
            Button.margin({ right: 8 });
            Button.onClick(() => this.backfill(30));
        }, Button);
        Button.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel(this.backfilling ? '补传中…' : '补传 180 天');
            Button.fontSize(12);
            Button.height(32);
            Button.type(ButtonType.Capsule);
            Button.backgroundColor(this.backfilling ? '#CCC' : '#555');
            Button.onClick(() => this.backfill(180));
        }, Button);
        Button.pop();
        Row.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.backfillResult) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        Text.create(this.backfillResult);
                        Text.fontSize(12);
                        Text.fontColor(this.backfillResult.indexOf('失败') >= 0 ? '#E45378' : '#34C759');
                        Text.margin({ top: 8 });
                    }, Text);
                    Text.pop();
                });
            }
            else {
                this.ifElseBranchUpdateFunction(1, () => {
                });
            }
        }, If);
        If.pop();
        // 历史补传区（对应安卓 DailyUploadReceiver 手动入口）
        Column.pop();
        Column.pop();
        Scroll.pop();
    }
    chip(text: string, color: string, parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(text);
            Text.fontSize(11);
            Text.fontColor('#FFFFFF');
            Text.padding({ left: 8, right: 8, top: 3, bottom: 3 });
            Text.backgroundColor(color);
            Text.borderRadius(10);
            Text.margin({ right: 6 });
        }, Text);
        Text.pop();
    }
    selectedHourArr(): number[] {
        if (!this.selectedDate || !this.recentDays.length)
            return new Array<number>(24).fill(0);
        const found = this.recentDays.find(d => d.date === this.selectedDate);
        return found ? found.byHour : new Array<number>(24).fill(0);
    }
    selectedDateLabel(): string {
        if (!this.selectedDate)
            return '今日';
        const today = UsageCollector.fmtDate(new Date());
        if (this.selectedDate === today)
            return '今日 · ' + this.selectedDate;
        return this.selectedDate;
    }
    selectedDateSub(): string {
        if (!this.usageSupported) {
            return '本机：当前 SDK 不支持读取其它 App 使用时长';
        }
        const arr = this.selectedHourArr();
        let sum = 0;
        for (const v of arr)
            sum += v;
        return '本机：' + fmtDur(sum) + ' ｜ 24 小时分布';
    }
    cloudDevicesForSelected(): DayRow[] {
        if (!this.selectedDate)
            return [];
        return this.cloudDays.filter(r => r.date === this.selectedDate);
    }
    srcTag(s: string): string {
        const k = (s || '').toLowerCase();
        if (k === 'app' || k === 'android')
            return 'Android';
        if (k === 'web' || k === 'browser')
            return '浏览器';
        if (k === 'harmony')
            return '鸿蒙';
        return k || '未知';
    }
    srcColor(s: string): string {
        const k = (s || '').toLowerCase();
        if (k === 'app' || k === 'android')
            return '#2F7BFF';
        if (k === 'web' || k === 'browser')
            return '#FB7299';
        if (k === 'harmony')
            return '#34C759';
        return '#999999';
    }
    rerender() {
        this.updateDirtyElements();
    }
    static getEntryName(): string {
        return "Index";
    }
}
class TrendChart extends ViewPU {
    constructor(parent, params, __localStorage, elmtId = -1, paramsLambda = undefined, extraInfo) {
        super(parent, __localStorage, elmtId, extraInfo);
        if (typeof paramsLambda === "function") {
            this.paramsGenerator_ = paramsLambda;
        }
        this.__days = new SynchedPropertyObjectOneWayPU(params.days, this, "days");
        this.__selectedDate = new SynchedPropertySimpleOneWayPU(params.selectedDate, this, "selectedDate");
        this.onSelect = (): void => { };
        this.settings = new RenderingContextSettings(true);
        this.ctx = new CanvasRenderingContext2D(this.settings);
        this.clickXs = [];
        this.setInitiallyProvidedValue(params);
        this.finalizeConstruction();
    }
    setInitiallyProvidedValue(params: TrendChart_Params) {
        if (params.onSelect !== undefined) {
            this.onSelect = params.onSelect;
        }
        if (params.settings !== undefined) {
            this.settings = params.settings;
        }
        if (params.ctx !== undefined) {
            this.ctx = params.ctx;
        }
        if (params.clickXs !== undefined) {
            this.clickXs = params.clickXs;
        }
    }
    updateStateVars(params: TrendChart_Params) {
        this.__days.reset(params.days);
        this.__selectedDate.reset(params.selectedDate);
    }
    purgeVariableDependenciesOnElmtId(rmElmtId) {
        this.__days.purgeDependencyOnElmtId(rmElmtId);
        this.__selectedDate.purgeDependencyOnElmtId(rmElmtId);
    }
    aboutToBeDeleted() {
        this.__days.aboutToBeDeleted();
        this.__selectedDate.aboutToBeDeleted();
        SubscriberManager.Get().delete(this.id__());
        this.aboutToBeDeletedInternal();
    }
    private __days: SynchedPropertySimpleOneWayPU<DayPayload[]>;
    get days() {
        return this.__days.get();
    }
    set days(newValue: DayPayload[]) {
        this.__days.set(newValue);
    }
    private __selectedDate: SynchedPropertySimpleOneWayPU<string>;
    get selectedDate() {
        return this.__selectedDate.get();
    }
    set selectedDate(newValue: string) {
        this.__selectedDate.set(newValue);
    }
    private onSelect: (date: string) => void;
    private settings: RenderingContextSettings;
    private ctx: CanvasRenderingContext2D;
    private clickXs: number[];
    initialRender() {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Canvas.create(this.ctx);
            Canvas.width('100%');
            Canvas.height('100%');
            Canvas.backgroundColor('#FFFFFF');
            Canvas.onReady((): void => { this.draw(); });
            Canvas.onClick((event?: ClickEvent): void => { if (event)
                this.onCanvasClick(event); });
        }, Canvas);
        Canvas.pop();
    }
    // 触发重绘
    aboutToAppear(): void { }
    // 当 selectedDate / days 改变时 ArkUI 会自动调 onReady? 兼容写法：在 build 里通过 stack 也可
    // 这里通过 @Watch
    // 注：ArkTS 不支持 watch 同时用 @Prop，得手动在父组件 setState 时重建 — 我们用 key 让组件刷新
    // 简化：直接每次 build → reset ctx + 在 draw 里读取最新数据
    private onCanvasClick(event: ClickEvent): void {
        if (!this.days || this.days.length === 0 || this.clickXs.length === 0)
            return;
        const x = event.x;
        // 最近邻：找 clickXs 中距离 x 最近的那个
        let bestIdx = 0;
        let bestDist = Number.POSITIVE_INFINITY;
        for (let i = 0; i < this.clickXs.length; i++) {
            const d = Math.abs(this.clickXs[i] - x);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
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
        const padLeft = 44;
        const padRight = 12;
        const padTop = 24;
        const padBot = 26;
        const chartLeft = padLeft;
        const chartRight = w - padRight;
        const chartTop = padTop;
        const chartBot = h - padBot;
        const innerW = chartRight - chartLeft;
        const innerH = chartBot - chartTop;
        const rawMax = sorted.reduce<number>((m: number, d: DayPayload) => Math.max(m, d.totalMs || 0), 0);
        const niceMax = niceCeilMs(Math.max(60000, rawMax));
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
            if (!d.totalMs)
                y = chartBot;
            else
                y = Math.min(y, chartBot - 2);
            xs.push(cx);
            ys.push(y);
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
            if (tx < chartLeft)
                tx = chartLeft;
            if (tx + tw > chartRight)
                tx = chartRight - tw;
            let ty = ys[selIdx] - th - 8;
            if (ty < 2)
                ty = 2;
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
    rerender() {
        this.updateDirtyElements();
    }
}
class HourChart extends ViewPU {
    constructor(parent, params, __localStorage, elmtId = -1, paramsLambda = undefined, extraInfo) {
        super(parent, __localStorage, elmtId, extraInfo);
        if (typeof paramsLambda === "function") {
            this.paramsGenerator_ = paramsLambda;
        }
        this.__hours = new SynchedPropertyObjectOneWayPU(params.hours, this, "hours");
        this.settings = new RenderingContextSettings(true);
        this.ctx = new CanvasRenderingContext2D(this.settings);
        this.setInitiallyProvidedValue(params);
        this.finalizeConstruction();
    }
    setInitiallyProvidedValue(params: HourChart_Params) {
        if (params.settings !== undefined) {
            this.settings = params.settings;
        }
        if (params.ctx !== undefined) {
            this.ctx = params.ctx;
        }
    }
    updateStateVars(params: HourChart_Params) {
        this.__hours.reset(params.hours);
    }
    purgeVariableDependenciesOnElmtId(rmElmtId) {
        this.__hours.purgeDependencyOnElmtId(rmElmtId);
    }
    aboutToBeDeleted() {
        this.__hours.aboutToBeDeleted();
        SubscriberManager.Get().delete(this.id__());
        this.aboutToBeDeletedInternal();
    }
    private __hours: SynchedPropertySimpleOneWayPU<number[]>;
    get hours() {
        return this.__hours.get();
    }
    set hours(newValue: number[]) {
        this.__hours.set(newValue);
    }
    private settings: RenderingContextSettings;
    private ctx: CanvasRenderingContext2D;
    initialRender() {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Canvas.create(this.ctx);
            Canvas.width('100%');
            Canvas.height('100%');
            Canvas.backgroundColor('#FFFFFF');
            Canvas.onReady((): void => { this.draw(); });
        }, Canvas);
        Canvas.pop();
    }
    private draw(): void {
        const ctx = this.ctx;
        const w = ctx.width;
        const h = ctx.height;
        ctx.clearRect(0, 0, w, h);
        ctx.fillStyle = '#FFFFFF';
        ctx.fillRect(0, 0, w, h);
        const padLeft = 44;
        const padRight = 12;
        const padTop = 16;
        const padBot = 24;
        const chartLeft = padLeft;
        const chartRight = w - padRight;
        const chartTop = padTop;
        const chartBot = h - padBot;
        const innerW = chartRight - chartLeft;
        const innerH = chartBot - chartTop;
        const arr = this.hours || new Array<number>(24).fill(0);
        let rawMax = 0;
        for (const v of arr)
            if (v > rawMax)
                rawMax = v;
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
            if (v <= 0)
                y = chartBot;
            else
                y = Math.min(y, chartBot - 2);
            xs.push(cx);
            ys.push(y);
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
            if ((arr[i] || 0) <= 0)
                continue;
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
    rerender() {
        this.updateDirtyElements();
    }
}
registerNamedRoute(() => new Index(undefined, {}), "", { bundleName: "com.example.biliusage", moduleName: "entry", pagePath: "pages/Index", pageFullPath: "entry/src/main/ets/pages/Index", integratedHsp: "false", moduleType: "followWithHap" });
