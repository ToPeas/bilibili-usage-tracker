if (!("finalizeConstruction" in ViewPU.prototype)) {
    Reflect.set(ViewPU.prototype, "finalizeConstruction", () => { });
}
interface SettingsPage_Params {
    accountId?: string;
    databaseId?: string;
    apiToken?: string;
    deviceAlias?: string;
    deviceId?: string;
    testing?: boolean;
    testResult?: string;
}
import router from "@ohos:router";
import promptAction from "@ohos:promptAction";
import type common from "@ohos:app.ability.common";
import { SettingsStore, Settings } from "@bundle:com.example.biliusage/entry/ets/common/SettingsStore";
import { D1Client } from "@bundle:com.example.biliusage/entry/ets/services/D1Client";
const PINK = '#FB7299';
const INK = '#222';
const INK_SOFT = '#6B6470';
class SettingsPage extends ViewPU {
    constructor(parent, params, __localStorage, elmtId = -1, paramsLambda = undefined, extraInfo) {
        super(parent, __localStorage, elmtId, extraInfo);
        if (typeof paramsLambda === "function") {
            this.paramsGenerator_ = paramsLambda;
        }
        this.__accountId = new ObservedPropertySimplePU('', this, "accountId");
        this.__databaseId = new ObservedPropertySimplePU('', this, "databaseId");
        this.__apiToken = new ObservedPropertySimplePU('', this, "apiToken");
        this.__deviceAlias = new ObservedPropertySimplePU('鸿蒙设备', this, "deviceAlias");
        this.__deviceId = new ObservedPropertySimplePU('', this, "deviceId");
        this.__testing = new ObservedPropertySimplePU(false, this, "testing");
        this.__testResult = new ObservedPropertySimplePU('', this, "testResult");
        this.setInitiallyProvidedValue(params);
        this.finalizeConstruction();
    }
    setInitiallyProvidedValue(params: SettingsPage_Params) {
        if (params.accountId !== undefined) {
            this.accountId = params.accountId;
        }
        if (params.databaseId !== undefined) {
            this.databaseId = params.databaseId;
        }
        if (params.apiToken !== undefined) {
            this.apiToken = params.apiToken;
        }
        if (params.deviceAlias !== undefined) {
            this.deviceAlias = params.deviceAlias;
        }
        if (params.deviceId !== undefined) {
            this.deviceId = params.deviceId;
        }
        if (params.testing !== undefined) {
            this.testing = params.testing;
        }
        if (params.testResult !== undefined) {
            this.testResult = params.testResult;
        }
    }
    updateStateVars(params: SettingsPage_Params) {
    }
    purgeVariableDependenciesOnElmtId(rmElmtId) {
        this.__accountId.purgeDependencyOnElmtId(rmElmtId);
        this.__databaseId.purgeDependencyOnElmtId(rmElmtId);
        this.__apiToken.purgeDependencyOnElmtId(rmElmtId);
        this.__deviceAlias.purgeDependencyOnElmtId(rmElmtId);
        this.__deviceId.purgeDependencyOnElmtId(rmElmtId);
        this.__testing.purgeDependencyOnElmtId(rmElmtId);
        this.__testResult.purgeDependencyOnElmtId(rmElmtId);
    }
    aboutToBeDeleted() {
        this.__accountId.aboutToBeDeleted();
        this.__databaseId.aboutToBeDeleted();
        this.__apiToken.aboutToBeDeleted();
        this.__deviceAlias.aboutToBeDeleted();
        this.__deviceId.aboutToBeDeleted();
        this.__testing.aboutToBeDeleted();
        this.__testResult.aboutToBeDeleted();
        SubscriberManager.Get().delete(this.id__());
        this.aboutToBeDeletedInternal();
    }
    private __accountId: ObservedPropertySimplePU<string>;
    get accountId() {
        return this.__accountId.get();
    }
    set accountId(newValue: string) {
        this.__accountId.set(newValue);
    }
    private __databaseId: ObservedPropertySimplePU<string>;
    get databaseId() {
        return this.__databaseId.get();
    }
    set databaseId(newValue: string) {
        this.__databaseId.set(newValue);
    }
    private __apiToken: ObservedPropertySimplePU<string>;
    get apiToken() {
        return this.__apiToken.get();
    }
    set apiToken(newValue: string) {
        this.__apiToken.set(newValue);
    }
    private __deviceAlias: ObservedPropertySimplePU<string>;
    get deviceAlias() {
        return this.__deviceAlias.get();
    }
    set deviceAlias(newValue: string) {
        this.__deviceAlias.set(newValue);
    }
    private __deviceId: ObservedPropertySimplePU<string>;
    get deviceId() {
        return this.__deviceId.get();
    }
    set deviceId(newValue: string) {
        this.__deviceId.set(newValue);
    }
    private __testing: ObservedPropertySimplePU<boolean>;
    get testing() {
        return this.__testing.get();
    }
    set testing(newValue: boolean) {
        this.__testing.set(newValue);
    }
    private __testResult: ObservedPropertySimplePU<string>;
    get testResult() {
        return this.__testResult.get();
    }
    set testResult(newValue: string) {
        this.__testResult.set(newValue);
    }
    async aboutToAppear(): Promise<void> {
        const ctx = getContext(this) as common.UIAbilityContext;
        const s = await SettingsStore.get(ctx);
        this.accountId = s.accountId;
        this.databaseId = s.databaseId;
        this.apiToken = s.apiToken;
        this.deviceAlias = s.deviceAlias;
        this.deviceId = s.deviceId;
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
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
            Row.width('100%');
            Row.padding(12);
            Row.backgroundColor(PINK);
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('‹');
            Text.fontSize(28);
            Text.fontColor('#FFFFFF');
            Text.padding(4);
            Text.onClick(() => router.back());
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('设置');
            Text.fontSize(16);
            Text.fontWeight(700);
            Text.fontColor('#FFFFFF');
            Text.margin({ left: 8 });
            Text.layoutWeight(1);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel('保存');
            Button.fontSize(12);
            Button.height(28);
            Button.type(ButtonType.Capsule);
            Button.backgroundColor('#FFFFFF');
            Button.fontColor(PINK);
            Button.onClick(() => this.save());
        }, Button);
        Button.pop();
        Row.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.padding(16);
            Column.alignItems(HorizontalAlign.Start);
            Column.width('100%');
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('Cloudflare D1');
            Text.fontSize(13);
            Text.fontWeight(700);
            Text.fontColor(INK);
            Text.margin({ bottom: 8 });
        }, Text);
        Text.pop();
        this.field.bind(this)('Account ID', this.accountId, v => { this.accountId = v; });
        this.field.bind(this)('Database ID', this.databaseId, v => { this.databaseId = v; });
        this.field.bind(this)('API Token', this.apiToken, v => { this.apiToken = v; }, true);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Divider.create();
            Divider.margin({ top: 16, bottom: 16 });
        }, Divider);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('本机标识');
            Text.fontSize(13);
            Text.fontWeight(700);
            Text.fontColor(INK);
            Text.margin({ bottom: 8 });
        }, Text);
        Text.pop();
        this.field.bind(this)('设备别名', this.deviceAlias, v => { this.deviceAlias = v; });
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create('Device ID: ' + this.deviceId);
            Text.fontSize(11);
            Text.fontColor(INK_SOFT);
            Text.margin({ top: 4 });
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Divider.create();
            Divider.margin({ top: 16, bottom: 16 });
        }, Divider);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
        }, Row);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Button.createWithLabel(this.testing ? '测试中…' : '测试连接');
            Button.fontSize(12);
            Button.height(32);
            Button.type(ButtonType.Capsule);
            Button.backgroundColor(this.testing ? '#CCC' : PINK);
            Button.fontColor('#FFFFFF');
            Button.layoutWeight(1);
            Button.onClick(() => this.test());
        }, Button);
        Button.pop();
        Row.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            If.create();
            if (this.testResult) {
                this.ifElseBranchUpdateFunction(0, () => {
                    this.observeComponentCreation2((elmtId, isInitialRender) => {
                        Text.create(this.testResult);
                        Text.fontSize(11);
                        Text.fontColor(INK_SOFT);
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
        Column.pop();
        Column.pop();
        Scroll.pop();
    }
    field(label: string, value: string, onChange: (v: string) => void, password: boolean = false, parent = null) {
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Column.create();
            Column.alignItems(HorizontalAlign.Start);
            Column.width('100%');
        }, Column);
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Text.create(label);
            Text.fontSize(11);
            Text.fontColor(INK_SOFT);
        }, Text);
        Text.pop();
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            TextInput.create({ text: value, placeholder: '请输入 ' + label });
            TextInput.type(password ? InputType.Password : InputType.Normal);
            TextInput.fontSize(13);
            TextInput.height(40);
            TextInput.backgroundColor('#F7F7F7');
            TextInput.borderRadius(8);
            TextInput.padding({ left: 10, right: 10 });
            TextInput.margin({ top: 4, bottom: 12 });
            TextInput.onChange((v: string) => onChange(v));
        }, TextInput);
        Column.pop();
    }
    async save(): Promise<void> {
        const ctx = getContext(this) as common.UIAbilityContext;
        const s = new Settings();
        s.accountId = this.accountId.trim();
        s.databaseId = this.databaseId.trim();
        s.apiToken = this.apiToken.trim();
        s.deviceAlias = this.deviceAlias.trim() || '鸿蒙设备';
        s.deviceId = this.deviceId || ('harmony-' + Math.random().toString(36).slice(2, 10));
        await SettingsStore.save(ctx, s);
        promptAction.showToast({ message: '已保存', duration: 1500 });
    }
    async test(): Promise<void> {
        if (this.testing)
            return;
        this.testing = true;
        this.testResult = '';
        try {
            const ctx = getContext(this) as common.UIAbilityContext;
            await this.save();
            const s = await SettingsStore.get(ctx);
            const client = new D1Client(s);
            const r = await client.ensureSchema();
            this.testResult = r.ok ? '✅ 连接成功，表结构已就绪' : '❌ ' + r.error;
        }
        catch (e) {
            this.testResult = '❌ ' + String((e as Error).message || e);
        }
        finally {
            this.testing = false;
        }
    }
    rerender() {
        this.updateDirtyElements();
    }
    static getEntryName(): string {
        return "SettingsPage";
    }
}
registerNamedRoute(() => new SettingsPage(undefined, {}), "", { bundleName: "com.example.biliusage", moduleName: "entry", pagePath: "pages/Settings", pageFullPath: "entry/src/main/ets/pages/Settings", integratedHsp: "false", moduleType: "followWithHap" });
