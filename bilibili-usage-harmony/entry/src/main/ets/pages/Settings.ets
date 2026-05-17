import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { SettingsStore, Settings } from '../common/SettingsStore';
import { D1Client } from '../services/D1Client';

const PINK = '#FB7299';
const INK = '#222';
const INK_SOFT = '#6B6470';

@Entry
@Component
struct SettingsPage {
  @State accountId: string = '';
  @State databaseId: string = '';
  @State apiToken: string = '';
  @State deviceAlias: string = '鸿蒙设备';
  @State deviceId: string = '';
  @State testing: boolean = false;
  @State testResult: string = '';

  async aboutToAppear(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    const s = await SettingsStore.get(ctx);
    this.accountId = s.accountId;
    this.databaseId = s.databaseId;
    this.apiToken = s.apiToken;
    this.deviceAlias = s.deviceAlias;
    this.deviceId = s.deviceId;
  }

  build() {
    Scroll() {
      Column() {
        Row() {
          Text('‹').fontSize(28).fontColor('#FFFFFF').padding(4)
            .onClick(() => router.back())
          Text('设置').fontSize(16).fontWeight(700).fontColor('#FFFFFF')
            .margin({ left: 8 }).layoutWeight(1)
          Button('保存')
            .fontSize(12).height(28).type(ButtonType.Capsule)
            .backgroundColor('#FFFFFF').fontColor(PINK)
            .onClick(() => this.save())
        }
        .width('100%').padding(12).backgroundColor(PINK)

        Column() {
          Text('Cloudflare D1').fontSize(13).fontWeight(700).fontColor(INK).margin({ bottom: 8 })

          this.field('Account ID', this.accountId, v => { this.accountId = v; })
          this.field('Database ID', this.databaseId, v => { this.databaseId = v; })
          this.field('API Token', this.apiToken, v => { this.apiToken = v; }, true)

          Divider().margin({ top: 16, bottom: 16 })

          Text('本机标识').fontSize(13).fontWeight(700).fontColor(INK).margin({ bottom: 8 })
          this.field('设备别名', this.deviceAlias, v => { this.deviceAlias = v; })
          Text('Device ID: ' + this.deviceId).fontSize(11).fontColor(INK_SOFT).margin({ top: 4 })

          Divider().margin({ top: 16, bottom: 16 })

          Row() {
            Button(this.testing ? '测试中…' : '测试连接')
              .fontSize(12).height(32).type(ButtonType.Capsule)
              .backgroundColor(this.testing ? '#CCC' : PINK)
              .fontColor('#FFFFFF')
              .layoutWeight(1)
              .onClick(() => this.test())
          }
          if (this.testResult) {
            Text(this.testResult).fontSize(11).fontColor(INK_SOFT).margin({ top: 8 })
          }
        }
        .padding(16)
        .alignItems(HorizontalAlign.Start)
        .width('100%')
      }
    }
    .width('100%').height('100%').backgroundColor('#FFFFFF')
  }

  @Builder field(label: string, value: string, onChange: (v: string) => void, password: boolean = false) {
    Column() {
      Text(label).fontSize(11).fontColor(INK_SOFT)
      TextInput({ text: value, placeholder: '请输入 ' + label })
        .type(password ? InputType.Password : InputType.Normal)
        .fontSize(13)
        .height(40)
        .backgroundColor('#F7F7F7')
        .borderRadius(8)
        .padding({ left: 10, right: 10 })
        .margin({ top: 4, bottom: 12 })
        .onChange((v: string) => onChange(v))
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
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
    if (this.testing) return;
    this.testing = true;
    this.testResult = '';
    try {
      const ctx = getContext(this) as common.UIAbilityContext;
      await this.save();
      const s = await SettingsStore.get(ctx);
      const client = new D1Client(s);
      const r = await client.ensureSchema();
      this.testResult = r.ok ? '✅ 连接成功，表结构已就绪' : '❌ ' + r.error;
    } catch (e) {
      this.testResult = '❌ ' + String((e as Error).message || e);
    } finally {
      this.testing = false;
    }
  }
}
