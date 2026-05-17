import preferences from '@ohos.data.preferences';
import { common } from '@kit.AbilityKit';

const STORE_NAME = 'bili_usage_settings';

export class Settings {
  accountId: string = '';
  databaseId: string = '';
  apiToken: string = '';
  deviceAlias: string = '鸿蒙设备';
  deviceId: string = '';
  appVersion: string = '1.0.0';
}

export class SettingsStore {
  private static pref: preferences.Preferences | null = null;

  static async get(ctx: common.Context): Promise<Settings> {
    if (!SettingsStore.pref) {
      SettingsStore.pref = await preferences.getPreferences(ctx, STORE_NAME);
    }
    const s = new Settings();
    s.accountId = (await SettingsStore.pref.get('accountId', '')) as string;
    s.databaseId = (await SettingsStore.pref.get('databaseId', '')) as string;
    s.apiToken = (await SettingsStore.pref.get('apiToken', '')) as string;
    s.deviceAlias = (await SettingsStore.pref.get('deviceAlias', '鸿蒙设备')) as string;
    s.deviceId = (await SettingsStore.pref.get('deviceId', '')) as string;
    s.appVersion = '1.0.0';
    if (!s.deviceId) {
      s.deviceId = 'harmony-' + Math.random().toString(36).slice(2, 10);
      await SettingsStore.pref.put('deviceId', s.deviceId);
      await SettingsStore.pref.flush();
    }
    return s;
  }

  static async save(ctx: common.Context, s: Settings): Promise<void> {
    if (!SettingsStore.pref) {
      SettingsStore.pref = await preferences.getPreferences(ctx, STORE_NAME);
    }
    await SettingsStore.pref.put('accountId', s.accountId || '');
    await SettingsStore.pref.put('databaseId', s.databaseId || '');
    await SettingsStore.pref.put('apiToken', s.apiToken || '');
    await SettingsStore.pref.put('deviceAlias', s.deviceAlias || '鸿蒙设备');
    await SettingsStore.pref.put('deviceId', s.deviceId || ('harmony-' + Math.random().toString(36).slice(2, 10)));
    await SettingsStore.pref.flush();
  }
}
