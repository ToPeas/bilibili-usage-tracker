import WorkSchedulerExtensionAbility from "@ohos:WorkSchedulerExtensionAbility";
import workScheduler from "@ohos:resourceschedule.workScheduler";
import hilog from "@ohos:hilog";
import type common from "@ohos:app.ability.common";
import { SettingsStore } from "@bundle:com.example.biliusage/entry/ets/common/SettingsStore";
import { UsageCollector } from "@bundle:com.example.biliusage/entry/ets/services/UsageCollector";
import { D1Client } from "@bundle:com.example.biliusage/entry/ets/services/D1Client";
const DOMAIN = 0x0000;
const TAG = 'BiliUploadWorker';
/**
 * 每日定时后台上传任务（对应安卓端 DailyUploadReceiver）。
 *
 * WorkScheduler 约束：
 *   - workId = 1001，isPersisted = true（重启后仍生效，类比 BootReceiver + AlarmManager）
 *   - repeatCycleTime = 24h，由系统在满足条件时调度执行
 *   - 补传范围：最近 180 天，跳过 totalMs == 0 的日期
 */
export default class UploadWorkerAbility extends WorkSchedulerExtensionAbility {
    static readonly WORK_ID = 1001;
    static readonly BACKFILL_DAYS = 180;
    onWorkStart(workInfo: workScheduler.WorkInfo): void {
        hilog.info(DOMAIN, TAG, 'onWorkStart workId=%{public}d', workInfo.workId);
        this.doUpload();
    }
    onWorkStop(workInfo: workScheduler.WorkInfo): void {
        hilog.info(DOMAIN, TAG, 'onWorkStop workId=%{public}d', workInfo.workId);
    }
    private async doUpload(): Promise<void> {
        try {
            const ctx = this.context as common.ExtensionContext;
            const settings = await SettingsStore.get(ctx);
            if (!settings.accountId || !settings.databaseId || !settings.apiToken) {
                hilog.warn(DOMAIN, TAG, 'D1 settings not configured, skip upload');
                return;
            }
            const client = new D1Client(settings);
            await client.ensureSchema();
            // 补传最近 180 天（空日自动跳过）
            const payloads = await UsageCollector.collectRecentForUpload(UploadWorkerAbility.BACKFILL_DAYS, false);
            let uploaded = 0;
            for (const day of payloads) {
                const r = await client.uploadDay(day);
                if (r.ok) {
                    uploaded++;
                }
                else {
                    hilog.warn(DOMAIN, TAG, 'upload failed date=%{public}s err=%{public}s', day.date, r.error);
                }
            }
            hilog.info(DOMAIN, TAG, 'doUpload done, uploaded=%{public}d days', uploaded);
        }
        catch (e) {
            const msg = (e as Error).message || String(e);
            hilog.error(DOMAIN, TAG, 'doUpload exception: %{public}s', msg);
        }
    }
    /**
     * 注册每日定时 WorkScheduler（在 EntryAbility.onCreate 调用）。
     * 幂等：先 stop 旧任务再重新注册。
     *
     * NetworkType 枚举值：
     *   NETWORK_TYPE_ANY = 0
     */
    static registerDailyWork(): void {
        try {
            workScheduler.stopWork({
                workId: UploadWorkerAbility.WORK_ID,
                bundleName: 'com.example.biliusage',
                abilityName: 'UploadWorkerAbility'
            }, false);
        }
        catch (_e) {
            // 可能未注册，忽略
        }
        try {
            const workInfo: workScheduler.WorkInfo = {
                workId: UploadWorkerAbility.WORK_ID,
                bundleName: 'com.example.biliusage',
                abilityName: 'UploadWorkerAbility',
                isPersisted: true,
                isRepeat: true,
                repeatCycleTime: 24 * 60 * 60 * 1000,
                networkType: 0 // NetworkType.NETWORK_TYPE_ANY = 0
            };
            workScheduler.startWork(workInfo);
            hilog.info(DOMAIN, TAG, 'daily WorkScheduler registered');
        }
        catch (e) {
            hilog.warn(DOMAIN, TAG, 'registerDailyWork failed: %{public}s', (e as Error).message || String(e));
        }
    }
}
